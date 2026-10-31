# Fidelity: what matches CPI and what does not (yet)

"To verify" means the behaviour follows SAP's documentation or Camel's code but has not been
observed on a tenant. The probes in [`probes/`](../probes/README.md) turn these items into facts.
Finding numbers (F…) and questions (Q…) refer to the [plan](plan.md).

## XSLT Mapping and XML tools

| Area | Status |
|---|---|
| Saxon release line (9.9) | Same line as CPI. CPI runs the **EE** edition, the simulator **HE** (F8). |
| Higher-order functions, `xsl:evaluate` | Not in HE 9.9; use the *Modern HE (Saxon 12)* profile. The error message says so. |
| Schema-aware XSLT, streaming | EE only; not available. The error message says that CPI supports it. |
| `format-date` with languages other than English | HE prints English names (`[Language: en]…`); CPI's EE probably localises them (F20). |
| Header vs. property with the same name | Header wins (Camel XsltBuilder order). **To verify** (Q-X2). |
| Declared parameter without header or property | The XSLT default is used; can be switched to "error". **To verify** (Q-X4). |
| Typed parameter values | Passed as Java objects, so Saxon converts them (F21). **To verify** (Q-X3). |
| Reflexive Java calls (`java:` namespaces) | Supported for JDK classes, since SAP's own samples use them (F32). Classes from the iFlow's jars are not available to XSLT. Argument conversion follows simple rules, not all of Saxon-EE's. |
| XSD validation | JDK validator, XML Schema 1.0. CPI's validator is **to verify** (Q-V1). |
| XPath tab | 3.1 (Saxon) by default, as SAP documents for the Content Modifier and Filter and as Router conditions in real iFlows need (F37). 1.0 (JDK) stays available; no CPI step is known to use it. |
| File access (includes, `doc()`, `unparsed-text()`, external entities, also in `parse-xml()`) | Inside the project folder only, no network: a simulator rule, standing in for the iFlow's resources. Whether CPI resolves external entities and DTDs is **to verify**. |
| `unparsed-text()` encoding | The encoding argument is used; without it, files are read as UTF-8 (the spec also allows guessing from the content). |
| `xsl:result-document`, `collection()` | Not supported (`UnsupportedInSimulator`); the simulator has no place for secondary results. What CPI does with them is **to verify**. |

## Flow Steps: Content Modifier, Filter, Router, Splitter, Gather

Camel Simple (Non-XML conditions and *Expression* values):

| Area | Status |
|---|---|
| Where CPI uses it | Router and Looping Process Call conditions with *Expression Type* Non-XML, and Content Modifier / Write Variables values of type *Expression* (SAP: [Using Camel Simple Expression Language](https://help.sap.com/docs/cloud-integration/sap-cloud-integration/using-camel-simple-expression-language)). The Content Filter also takes it; the simulator has no Content Filter. |
| Engine | The Groovy engine's own Camel evaluates the expression: 3.14.7 or 2.24.3, chosen under *Camel* on the Flow Steps tab (default 3.14.7) and independent of the Groovy tab. These steps have no Groovy version; the Camel line belongs to the tenant, not to a script version: SAP numbers tenant software 8.x / 7.x for Camel 3 and 6.x / 5.x for Camel 2, and has been migrating tenants to Camel 3.14.7 (F34). OGNL calls such as `${in.header.fname.split(',')[0]}` (in SAP's table) work on both; the 2.x engine bundles `camel-bean` for them. |
| CPI spellings plain Camel rejects | SAP's Router table ([Define Router](https://help.sap.com/docs/r/368c481cd6954bdfa5d0435479fd4eaf/Cloud/en-US/d7fddbd52e3944d3a6d4e5b228c7e63b.html)) uses `=`, `and`, `or` and `${property.x}`. Camel has no `=` operator, and Camel 3 dropped `and`, `or` and `property`. The simulator rewrites them to `==`, `&&`, `\|\|` and `${exchangeProperty.x}` and the Router result shows the rewritten expression. CPI converts `property` to `exchangeProperty` itself (SAP patch notes, August 2024, 7.28.14 / 6.55.13). That it treats `=`, `and` and `or` exactly like `==`, `&&` and `\|\|` is an **assumption** (F35). |
| `${exchangeProperty.x}` | SAP's documentation uses `${property.x}` (180 times) and never `${exchangeProperty.x}` (SAP-docs repository, 2026-10-22). It works in the simulator. A [SAP Community post](https://community.sap.com/t5/technology-blog-posts-by-members/get-to-know-camel-s-simple-expression-language-in-sap-cloud-integration/ba-p/13292822) reportedly says it fails in a CPI Router condition (seen only in a search summary; the page could not be opened from the build environment). **To verify.** |
| Content Modifier expressions | SAP: it does not process equality or comparison expressions; operators stay text (Camel's expression parser). SAP lists a subset of Camel's functions for it; the simulator does not reject the others. |
| `${exception.…}`, `${exchangeId}`, `${date:now}` | There is no exception in the tester, so `${exception.message}` is empty. IDs and dates come from the simulator's run. |

The steps (SAP: *Define Content Modifier*, *Define Filter*, *Define Router*, *Define General Splitter*, *Define Iterating Splitter*, *Define Gather and Join*):

| Area | Status |
|---|---|
| XPath | Saxon-HE 9.9 (CPI's release line; Saxon 12 if 9.9 is not installed), XPath 3.1, in the XML engine: SAP documents XPath 3.1 for the Content Modifier, and Router conditions in real iFlows use XPath 2.0 syntax such as `/node()/*:IDOC` (F37). Namespace prefixes come from the namespace mapping (the iFlow's *Runtime Configuration*), shared with the XPath tab. The Router's XPath version is not documented; 3.1 is an assumption. |
| XPath variables | `$name` is the header of that name, else the exchange property: Camel's XPath variable resolver (`MessageVariableResolver`, the same in Camel 2.24.3 and 3.14.7) does this, and real iFlows rely on it (`Student[Age > $AgeLimit_Header]`, `$NodeId="0"`; F39). Typed values (Integer, Boolean, …) become typed XPath values. Applies to Content Modifier XPath rows, Router XML conditions and the Filter. |
| Content Modifier rows | Run top to bottom (SAP); the header table before the property table, both before the body (SAP Community blog, seen only in a search summary: **to verify**). An XPath row reads the incoming body. Constant values are not evaluated. *Data Type* is resolved by Camel, so `String` works as well as `java.lang.String`; a DOM type (`org.w3c.dom.NodeList`) keeps the XML as text. An XPath that returns several items gives the first one's string value: **to verify**. An empty body field leaves the body as it is. The body is Constant or Expression; Content Modifiers up to version 1.3 have no type and their body is an expression (F38). |
| Content Modifier sources the simulator lacks | Local and global variables: no variable store, so the *Default Value* is used, as CPI does when the variable is not found. Number Range and External Parameter: `UnsupportedInSimulator`. |
| Delete rows | The iFlow stores `$name=X` or `$include=pattern,$exclude=pattern` (F36); Camel's `removeHeaders` / `removeProperties` apply the patterns. That CPI uses these Camel methods is an assumption. |
| Filter | XPath 3.1 (SAP: Filter 1.1 uses XPath 3.1 EE); the result becomes the body, headers and properties stay. String joins the string values of all items (SAP's example: `10010`); Integer is that text as a whole number; Boolean is the effective boolean value; Node the first node; Nodelist all nodes one after another without a wrapper (SAP's examples). Integer, Boolean and Node follow Camel's XPath result types and the join has no separator (Camel's converter): **assumptions**. `function:simple('${…}')` (SAP's example) is replaced by the value Camel Simple gives, as an XPath string; that CPI treats it as a string is an assumption. |
| Router | Conditions run in the order of the gateway's outgoing flows in the iFlow (an assumption; the model has no order field), and the first true one wins. Without a match the default route is taken, or, with *Throw Exception*, an exception (SAP). A condition that fails to evaluate is shown as an error. |
| General Splitter | Each message keeps the envelope of the **first** split point: its ancestors and, on every level, the siblings before it. Later siblings are dropped, and every split point goes under the first one's parent. This reproduces all of SAP's examples (`SplitStepTest`); other cases are inferred from them. |
| Iterating Splitter | Each message holds only the split point(s); with grouping above 1 they are concatenated (an assumption). *Token* splits at elements with that local name (CPI may use Camel's text-based `tokenizeXML`: **to verify**). |
| Line Break | General puts the first line in front of every message (SAP); Iterating does not. Like SAP's example, the text `\n` also counts as a line break. Messages have no trailing line break (**to verify**). |
| Splitter XPath | Paths only, as SAP documents. A prefix may be declared after the path (`/ns1:root/shop xmlns:ns1="…"`, SAP); a default namespace (`xmlns="…"`) is rejected, as CPI does. Steps without a prefix take the prefix of the step before them, which reproduces SAP's example (matching any namespace would too: **to verify**); `\| + * > = <= [ ] @` are rejected. A trailing `/` is accepted (seen in real iFlows). `${header.x}` and `${property.x}` in the XPath or grouping are resolved first. No match gives no message (SAP: the flow ends as *Completed*). |
| Split messages | General Splitter messages start with `<?xml version='1.0' encoding='UTF-8'?>`, as in SAP's example; Iterating ones have no declaration (SAP's examples show none). Attribute order can differ from the input (the JDK's DOM). `CamelSplitIndex`, `CamelSplitComplete` and `CamelSplitSize` (only on the last message with streaming, SAP) are shown, not set. Parallel processing, timeouts and *Stop On Exception* are not simulated. |
| Gather | Combine writes SAP's multi-mapping format exactly as SAP's result example: `<?xml version='1.0' encoding='UTF-8'?><multimap:Messages xmlns:multimap="http://sap.com/xi/XI/SplitAndMerge">`, all bodies in `Message1` for XML (Same Format), one `MessageN` each for XML (Different Format); each body without its XML declaration. Concatenate joins plain text bodies. Combine at XPath has no example in SAP's docs: the nodes the source XPath selects in every message replace those of the first message, under the target node (by default the source's parent); with a General Splitter's messages this rebuilds the document, as SAP's Composed Message Processor pattern describes. This is an **assumption**. Headers and properties are not aggregated (SAP). Zip and Tar are not simulated. The algorithms each Incoming Format allows follow SAP's table. |

## Groovy Script

| Area | Status |
|---|---|
| Groovy versions | Groovy 2.4.21 for 1.x and 4.0.29 for 2.x, as SAP documents (F1, F2). |
| Java version | Java 17, as reported for 2.x (F3). The Java version behind 1.x is **to verify** (F4). |
| Camel version | 3.14.7 for 2.x (reported). 1.x runs on 2.24.3, but SAP's v1 API jar targets Camel 3.14 since late 2025, so 1.x may run on 3.14 now: **to verify** (F30, Q-G3). |
| Script API v1 | The methods of SAP's published interface (`script.api` on Maven Central): body, headers, properties, attachments (`AttachmentWrapper`), SOAP headers, sizes (F29). Not offered: the overloads that take Camel's own `Attachment` type. |
| Script API v2 | The methods of SAP's published interface (`script.v2.api` 4.2.2): attachments, `getHeader(name, default)`, `resetBodyStream`, exchange timestamps. ProcessDirect calls raise `UnsupportedInSimulator`. CPI's runtime class may offer more than the interface; `fingerprint-v2.groovy` lists it (Q-G4). |
| Message log | `messageLogFactory` and `MessageLog` follow SAP's published interface; MPL disabled gives `null`. |
| Platform APIs | Follow SAP's published `generic.api` (F33): Secure Store (user credentials, OAuth2 tokens from a fixed value), Value Mapping (`getMappedValue`), Partner Directory (parameters, certificate parameters, partner IDs, authorized users, deprecated pattern lookups), Keystore (keys, certificates, chains, key pairs, key and trust managers), number ranges. Other services raise `InvalidContextException`. The runtime's edge cases are **to verify** (Q-P1). |
| Data Store from scripts | Not simulated: CPI has no public script API for it (F28). |
| Extra libraries on the class path | Unknown (F31). The simulator adds only `lib/` jars. |
| Groovy modules on 2.x | xml, json, dateutil, datetime, templates and nio are bundled. CPI's set is **to verify**. Scripts on 2.x also see Camel's `camel-bean` (bundled for Camel Simple's OGNL calls; Camel 2's core has it built in). |
| Pretty-printing (`XmlUtil.serialize`) | Depends on the JDK version (F25), so byte-exact comparisons depend on Java 17 being right. |
