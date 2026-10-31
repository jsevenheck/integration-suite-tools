#!/usr/bin/env python3
"""Representative engine workload, used to record GraalVM reachability metadata
(native-image-agent) and to smoke-test native binaries.

Usage: workload.py <engine command...>   e.g.  workload.py java -jar cpiwb-xml-he99.jar
Exits non-zero if a response does not look as expected."""
import json, os, subprocess, sys, tempfile, time

XSL = """<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:hci="http://sap.com/it/" xmlns:xs="http://www.w3.org/2001/XMLSchema" exclude-result-prefixes="hci xs">
  <xsl:include href="lib.xsl"/>
  <xsl:param name="exchange"/><xsl:param name="P"/><xsl:param name="N"/>
  <xsl:output indent="yes" encoding="UTF-8"/>
  <xsl:template match="/">
    <xsl:value-of select="hci:setHeader($exchange, 'H', 'v')"/>
    <xsl:value-of select="hci:setProperty($exchange, 'Q', string($N + 1))"/>
    <xsl:message>msg <xsl:value-of select="format-dateTime(xs:dateTime('2026-01-01T10:00:00Z'), '[Y]-[M01]-[D01]')"/></xsl:message>
    <r p="{$P}" n="{$N}"><xsl:call-template name="lib"/>
      <xsl:for-each-group select="//i" group-by="@g"><g k="{current-grouping-key()}" c="{count(current-group())}"/></xsl:for-each-group>
      <xsl:value-of select="replace(upper-case('abc'), 'B', '-'), tokenize('a,b', ','), sum(//i/@v), format-number(1234.5, '#,##0.00')"/>
    </r>
  </xsl:template>
</xsl:stylesheet>"""
LIB = """<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"><xsl:template name="lib"><lib/></xsl:template></xsl:stylesheet>"""
XSD = """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="x"><xs:complexType><xs:sequence>
<xs:element name="i" maxOccurs="unbounded"><xs:complexType><xs:attribute name="g"/><xs:attribute name="v" type="xs:int"/></xs:complexType></xs:element>
</xs:sequence></xs:complexType></xs:element></xs:schema>"""
COVER = r"""<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:map="http://www.w3.org/2005/xpath-functions/map"
  xmlns:err="http://www.w3.org/2005/xqt-errors" exclude-result-prefixes="#all">
  <xsl:output method="xml" indent="no"/>
  <xsl:template match="/">
    <out>
      <j><xsl:value-of select="xml-to-json(json-to-xml('{&quot;a&quot;:[1,2,{&quot;b&quot;:&quot;c&quot;}]}'))"/></j>
      <m><xsl:value-of select="map:get(map{'k':'v'}, 'k')"/></m>
      <s><xsl:value-of select="serialize(parse-xml('&lt;a x=&quot;1&quot;/&gt;'))"/></s>
      <d><xsl:value-of select="format-date(xs:date('2026-03-05'), '[D1] [MNn] [Y0001]', 'en', (), ())"/></d>
      <de><xsl:value-of select="format-date(xs:date('2026-03-05'), '[D1]. [MNn] [Y0001]', 'de', (), ())"/></de>
      <n><xsl:value-of select="format-number(-1234567.891, '#,##0.00')"/></n>
      <r><xsl:analyze-string select="'a1b22c333'" regex="[0-9]+"><xsl:matching-substring>[<xsl:value-of select="."/>]</xsl:matching-substring></xsl:analyze-string></r>
      <i><xsl:iterate select="1 to 5"><xsl:param name="acc" select="0"/><xsl:on-completion><xsl:value-of select="$acc"/></xsl:on-completion><xsl:next-iteration><xsl:with-param name="acc" select="$acc + ."/></xsl:next-iteration></xsl:iterate></i>
      <c><xsl:value-of select="codepoints-to-string(string-to-codepoints('Grüße')), normalize-unicode('é','NFD') ne 'é', encode-for-uri('a b'), escape-html-uri('ä')"/></c>
      <t><xsl:value-of select="current-dateTime() instance of xs:dateTime, adjust-dateTime-to-timezone(xs:dateTime('2026-01-01T00:00:00Z'), xs:dayTimeDuration('PT1H'))"/></t>
      <x><xsl:value-of select="translate('abc','abc','ABC'), matches('abc', '^A', 'i'), tokenize('a  b', '\s+'), compare('a','b'), distinct-values(('b','a','b')), upper-case('straße'), string-join(reverse(1 to 3), '')"/></x>
      <u><xsl:value-of select="unparsed-text-available('nope.txt'), doc-available('nope.xml')"/></u>
      <e><xsl:try><xsl:sequence select="xs:integer('x')"/><xsl:catch><xsl:value-of select="$err:code"/></xsl:catch></xsl:try></e>
      <g><xsl:for-each-group select="(3,1,2,1)" group-by="."><xsl:sort select="."/><xsl:value-of select="current-grouping-key()"/></xsl:for-each-group></g>
    </out>
  </xsl:template>
</xsl:stylesheet>"""

BODY = '<x><i g="a" v="1"/><i g="b" v="2"/><i g="a" v="3"/></x>'

def main():
    cmd = sys.argv[1:]
    proj = tempfile.mkdtemp(prefix="cpiwb-workload-")
    os.makedirs(os.path.join(proj, "mappings"))
    open(os.path.join(proj, "mappings", "lib.xsl"), "w").write(LIB)
    inp = {"body": BODY, "headers": [{"name": "P", "value": "hp", "type": "String"}],
           "properties": [{"name": "N", "value": "41", "type": "Integer"}]}
    # A file next to the project: runs must not read it (entities, doc(), unparsed-text()).
    secret = os.path.join(os.path.dirname(proj), os.path.basename(proj) + "-secret.txt")
    open(secret, "w").write("TOP-SECRET-42")
    secret_uri = "file:///" + secret.replace(os.sep, "/").lstrip("/")
    entity_doc = f"<!DOCTYPE r [<!ENTITY x SYSTEM '{secret_uri}'>]><r>&x;</r>"
    no_leak = lambda r: not r["ok"] and "TOP-SECRET" not in json.dumps(r) and "outside the project" in json.dumps(r)
    reqs = [
        ("engine.info", {}, lambda r: r["profile"]),
        ("project.open", {"dir": proj, "init": True}, lambda r: r["isProject"]),
        ("file.write", {"path": "mappings/main.xsl", "content": XSL}, lambda r: r["saved"]),
        ("file.read", {"path": "mappings/main.xsl"}, lambda r: "hci:setHeader" in r["content"]),
        ("project.list", {}, lambda r: len(r["files"]) > 0),
        ("xslt.run", {"xslt": {"path": "mappings/main.xsl"}, "input": inp},
            lambda r: r["ok"] and 'p="hp"' in r["output"]["body"] and '<g k="a" c="2"/>' in r["output"]["body"]),
        ("xslt.run", {"xslt": {"path": "mappings/main.xsl"}, "input": inp}, lambda r: r["ok"] and r["timing"]["cached"]),
        ("xslt.run", {"xslt": {"content": "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:template match='/'><xsl:value-of select='1 +'/></xsl:template></xsl:stylesheet>"},
                      "input": {"body": "<a/>"}}, lambda r: not r["ok"] and r["failedPhase"] == "compile"),
        ("xslt.run", {"xslt": {"content": "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:template match='/'><xsl:value-of select='error()'/></xsl:template></xsl:stylesheet>"},
                      "input": {"body": "<a/>"}}, lambda r: not r["ok"] and r["failedPhase"] == "runtime"),
        ("xslt.run", {"xslt": {"content": "<xsl:stylesheet version='1.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:output method='text'/><xsl:template match='/'><xsl:value-of select='count(//i)'/></xsl:template></xsl:stylesheet>"},
                      "input": {"body": BODY}}, lambda r: r["ok"] and r["output"]["body"] == "3"),
        ("xslt.run", {"xslt": {"content": COVER}, "input": {"body": "<a/>"}},
            lambda r: r["ok"] and "<m>v</m>" in r["output"]["body"] and "<i>15</i>" in r["output"]["body"] and "FORG0001" in r["output"]["body"]),
        ("xsd.validate", {"schema": {"content": XSD}, "input": {"body": BODY}}, lambda r: r["valid"]),
        ("xsd.validate", {"schema": {"content": XSD}, "input": {"body": '<x><i v="no"/></x>'}}, lambda r: not r["valid"]),
        ("xpath.eval", {"expression": "//i[@g='a']/@v", "version": "1.0", "input": {"body": BODY}}, lambda r: r["count"] == 2),
        ("xpath.eval", {"expression": "sum(//i/@v)", "version": "1.0", "input": {"body": BODY}}, lambda r: r["items"][0]["value"] == "6"),
        ("xpath.eval", {"expression": "string-join(//i/@g, ','), //i[1]", "version": "3.1", "input": {"body": BODY}}, lambda r: r["items"][0]["value"] == "a,b,a"),
        ("xpath.eval", {"expression": "concat(string(count(//i)), '-', name(/*), substring('abc', 2), normalize-space(' a '), string-length('ab'), boolean(//i), number('1'), translate('a','a','b'), not(false()), local-name(/*), contains('ab','b'), starts-with('ab','a'), floor(1.5), ceiling(1.5), round(1.5), last())",
                        "version": "1.0", "input": {"body": BODY}}, lambda r: r["ok"] and r["items"][0]["value"].startswith("3-x")),
        ("xpath.eval", {"expression": "/x", "version": "1.0", "input": {"body": BODY}}, lambda r: r["ok"] and r["items"][0]["kind"] == "element"),
        ("xpath.eval", {"expression": "//i[position() = last()]/@g | //i[1]/@g", "version": "1.0", "input": {"body": BODY}}, lambda r: r["ok"] and r["count"] == 2),
        ("xml.format", {"text": BODY}, lambda r: r["ok"]),
        # Flow Steps: XPath values for Router and Content Modifier, General and Iterating Splitter.
        ("xpath.eval", {"expression": "//i[@g='b']", "version": "3.1", "as": "boolean", "input": {"body": BODY}}, lambda r: r["value"] == "true"),
        ("xpath.eval", {"expression": "//i/@v", "version": "3.1", "as": "string", "input": {"body": BODY}}, lambda r: r["value"] == "1"),
        ("split.run", {"splitter": "general", "expressionType": "xpath", "expression": "/x/i", "grouping": 2, "input": {"body": BODY}},
         lambda r: r["count"] == 2 and "<x>" in r["messages"][1]["body"]),
        ("split.run", {"splitter": "iterating", "expressionType": "token", "expression": "i", "input": {"body": BODY}}, lambda r: r["count"] == 3),
        ("split.run", {"splitter": "general", "expressionType": "xpath", "expression": "/r", "input": {"body": entity_doc}}, no_leak),
        # Filter (XPath 3.1, headers as variables) and Gather.
        ("filter.run", {"expression": "count(/x/i[@g = $g])", "valueType": "integer",
                        "input": {"body": BODY, "headers": [{"name": "g", "value": "b"}]}}, lambda r: r["body"] == "1"),
        ("filter.run", {"expression": "/x/i", "valueType": "nodelist", "input": {"body": BODY}}, lambda r: r["count"] == 3),
        ("filter.run", {"expression": "string(/r)", "valueType": "string", "input": {"body": entity_doc}}, no_leak),
        ("gather.run", {"messageType": "sameXml", "algorithm": "combine", "messages": ["<a/>", "<b/>"]},
         lambda r: "<multimap:Message1><a/><b/></multimap:Message1>" in r["body"]),
        ("gather.run", {"messageType": "sameXml", "algorithm": "combineAtXPath", "sourceXPath": "/x/i", "messages": [BODY, BODY]},
         lambda r: r["body"].count("<i ") == 6),
        ("gather.run", {"messageType": "sameXml", "algorithm": "combineAtXPath", "sourceXPath": "/r", "targetXPath": "/r",
                        "messages": [entity_doc]}, no_leak),
        ("xpath.eval", {"expression": "string(/r)", "version": "1.0", "input": {"body": entity_doc}}, no_leak),
        ("xpath.eval", {"expression": "string(/r)", "version": "3.1", "input": {"body": entity_doc}}, no_leak),
        ("xpath.eval", {"expression": f"unparsed-text('{secret_uri}')", "version": "3.1", "input": {"body": "<a/>"}}, no_leak),
        ("xpath.eval", {"expression": "string(parse-xml(concat('<!DOCTYPE r [<!ENTITY x SYSTEM \"', $u, '\">]><r>&x;</r>')))".replace("$u", f"'{secret_uri}'"),
                        "version": "3.1", "input": {"body": "<a/>"}}, no_leak),
        ("xslt.run", {"xslt": {"content": "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:template match='/'><xsl:copy-of select='.'/></xsl:template></xsl:stylesheet>"},
                      "input": {"body": entity_doc}}, no_leak),
        ("tests.run", {"path": "tests"}, lambda r: r["failed"] == 0),
        ("file.delete", {"path": "mappings/main.xsl"}, lambda r: r["deleted"]),
        ("project.close", {}, lambda r: r["closed"]),
        ("project.list", {}, "error"),
    ]
    t0 = time.time()
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    ready = json.loads(p.stdout.readline())
    startup = time.time() - t0
    failures = 0
    for i, (method, params, check) in enumerate(reqs):
        t = time.time()
        p.stdin.write(json.dumps({"id": i, "method": method, "params": params}) + "\n")
        p.stdin.flush()
        resp = json.loads(p.stdout.readline())
        ms = (time.time() - t) * 1000
        if check == "error":
            ok = "error" in resp
        else:
            ok = "result" in resp
            try:
                ok = ok and bool(check(resp["result"]))
            except Exception as e:  # noqa
                ok = False
        if not ok:
            failures += 1
        print(f"{'ok ' if ok else 'BAD'} {method:14s} {ms:8.1f} ms" + ("" if ok else "  " + json.dumps(resp)[:1500]))
        if os.environ.get("WORKLOAD_SHOW") and method == "xslt.run" and "result" in resp and "output" in resp["result"]:
            print("    " + resp["result"]["output"]["body"][:600].replace("\n", " "))
    p.stdin.close()
    p.wait(timeout=30)
    print(f"engine: {ready['engine']['saxon']} | native={ready['engine']['nativeImage']} | startup-to-ready {startup*1000:.0f} ms")
    sys.exit(1 if failures else 0)

if __name__ == "__main__":
    main()
