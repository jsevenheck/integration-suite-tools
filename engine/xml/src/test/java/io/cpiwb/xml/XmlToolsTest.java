package io.cpiwb.xml;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class XmlToolsTest {

    private static final String XSD = "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
            + "<xs:element name='Order'><xs:complexType><xs:sequence>"
            + "<xs:element name='Qty' type='xs:int'/></xs:sequence></xs:complexType></xs:element></xs:schema>";

    private static Map<String, Object> validate(String xml) {
        return new XsdStep().run(Json.o().with("schema", Json.o().with("content", XSD))
                .with("input", Json.o().with("body", xml)));
    }

    @Test
    void validDocument() {
        Map<String, Object> r = validate("<Order><Qty>5</Qty></Order>");
        assertTrue(Json.bool(r, "valid", false), () -> Json.write(r));
    }

    @Test
    void invalidDocumentHasLocation() {
        Map<String, Object> r = validate("<Order>\n<Qty>five</Qty></Order>");
        assertFalse(Json.bool(r, "valid", true));
        Map<String, Object> d = (Map<String, Object>) Json.arr(r, "diagnostics").get(0);
        assertEquals(2L, ((Number) d.get("line")).longValue());
    }

    @Test
    void brokenSchemaIsReported() {
        Map<String, Object> r = new XsdStep().run(Json.o().with("schema", Json.o().with("content", "<xs:schema"))
                .with("input", Json.o().with("body", "<a/>")));
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("schema", r.get("failedPhase"));
    }

    @Test
    void schemaIncludeFromProject(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("types.xsd"), "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
                + "<xs:simpleType name='Code'><xs:restriction base='xs:string'><xs:length value='2'/></xs:restriction></xs:simpleType></xs:schema>");
        String main = "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'><xs:include schemaLocation='types.xsd'/>"
                + "<xs:element name='C' type='Code'/></xs:schema>";
        Map<String, Object> ok = new XsdStep().run(Json.o().with("schema", Json.o().with("content", main).with("path", "main.xsd"))
                .with("baseDir", dir.toString()).with("input", Json.o().with("body", "<C>DE</C>")));
        assertTrue(Json.bool(ok, "valid", false), () -> Json.write(ok));
        Map<String, Object> bad = new XsdStep().run(Json.o().with("schema", Json.o().with("content", main).with("path", "main.xsd"))
                .with("baseDir", dir.toString()).with("input", Json.o().with("body", "<C>DEU</C>")));
        assertFalse(Json.bool(bad, "valid", true));
    }

    @Test
    void xpath10And31() {
        XPathStep x = new XPathStep();
        Map<String, Object> in = Json.o().with("body", "<p:a xmlns:p='urn:p'><p:b>1</p:b><p:b>2</p:b></p:a>");
        Map<String, Object> r1 = x.run(Json.o().with("expression", "count(//p:b)").with("version", "1.0")
                .with("namespaces", Json.o().with("p", "urn:p")).with("input", in));
        assertEquals("2", ((Map<String, Object>) Json.arr(r1, "items").get(0)).get("value"), () -> Json.write(r1));
        Map<String, Object> nodes = x.run(Json.o().with("expression", "//p:b").with("version", "1.0")
                .with("namespaces", Json.o().with("p", "urn:p")).with("input", in));
        assertEquals(2L, ((Number) nodes.get("count")).longValue());
        Map<String, Object> r3 = x.run(Json.o().with("expression", "string-join(//p:b, '-')").with("version", "3.1")
                .with("namespaces", Json.o().with("p", "urn:p")).with("input", in));
        assertEquals("1-2", ((Map<String, Object>) Json.arr(r3, "items").get(0)).get("value"));
        Map<String, Object> err = x.run(Json.o().with("expression", "//(").with("version", "3.1").with("input", in));
        assertFalse(Json.bool(err, "ok", true));
    }

    @Test
    void xpathValuesForFlowSteps() {
        XPathStep x = new XPathStep();
        Map<String, Object> in = Json.o().with("body", "<ns:ORDERS05 xmlns:ns='urn:i'><IDOC><DOCNUM>42</DOCNUM><E>a</E><E>b</E></IDOC></ns:ORDERS05>");
        java.util.function.BiFunction<String, String, Object> eval = (expr, as) -> x.run(Json.o().with("expression", expr)
                .with("version", "3.1").with("as", as).with("input", in)).get("value");
        // Router conditions as written in real iFlows (XPath 2.0+ syntax).
        assertEquals("true", eval.apply("boolean(/node()/*:IDOC[1][1=last()])", "boolean"));
        assertEquals("true", eval.apply("//DOCNUM", "boolean"));
        assertEquals("false", eval.apply("count(//E) = 0", "boolean"));
        assertEquals("42", eval.apply("//DOCNUM/text()", "string"));
        assertEquals("a", eval.apply("//E", "string"));
        assertEquals("<E xmlns:ns=\"urn:i\">a</E><E xmlns:ns=\"urn:i\">b</E>", eval.apply("//E", "nodes"));
    }

    @Test
    void format() {
        Map<String, Object> r = new XPathStep().format(Json.o().with("text", "<a><b>1</b></a>"));
        assertTrue(Json.str(r, "text", "").contains("\n   <b>1</b>"), () -> Json.write(r));
    }

    @Test
    void testRunnerRunsCaseFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("cpi-workbench.json"), "{}");
        Path mappings = Files.createDirectories(dir.resolve("mappings"));
        Files.writeString(mappings.resolve("m.xsl"), "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'"
                + " xmlns:hci='http://sap.com/it/'><xsl:param name='exchange'/><xsl:param name='p'/>"
                + "<xsl:template match='/'><xsl:value-of select=\"hci:setHeader($exchange,'h','v')\"/><out><xsl:value-of select='$p'/></out></xsl:template></xsl:stylesheet>");
        Path c = Files.createDirectories(dir.resolve("tests/case1"));
        Files.writeString(c.resolve("input.xml"), "<in/>");
        Files.writeString(c.resolve("expected.xml"), "<out>\n  X\n</out>".replace("\n  X\n", "X"));
        Files.writeString(c.resolve("case.json"), "{\"name\":\"first\",\"step\":\"xslt\",\"xslt\":\"../../mappings/m.xsl\","
                + "\"input\":{\"body\":{\"file\":\"input.xml\"},\"properties\":{\"p\":\"X\"}},"
                + "\"expect\":{\"body\":{\"file\":\"expected.xml\"},\"headers\":{\"h\":\"v\"}}}");
        Path c2 = Files.createDirectories(dir.resolve("tests/case2"));
        Files.writeString(c2.resolve("case.json"), "{\"step\":\"xslt\",\"xslt\":\"../../mappings/m.xsl\","
                + "\"input\":{\"body\":\"<in/>\"},\"expect\":{\"headers\":{\"h\":\"wrong\"}}}");
        Map<String, Object> r = new XmlEngine().runTests(dir.resolve("tests"));
        assertEquals(1L, ((Number) r.get("passed")).longValue(), () -> Json.write(r));
        assertEquals(1L, ((Number) r.get("failed")).longValue());
        List<Object> results = Json.arr(r, "results");
        assertTrue(Json.write(results.get(1)).contains("expected 'wrong' but was 'v'"));
    }
}
