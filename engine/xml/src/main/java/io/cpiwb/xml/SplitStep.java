package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * General and Iterating Splitter (SAP: "Define General Splitter", "Define Iterating Splitter",
 * "General and Iterating Splitter (Examples)", "Using XPath Expressions in the Splitter Step"). The
 * rules below are read from SAP's examples, which the tests reproduce; CPI's implementation is not
 * public, so anything the examples do not show is an assumption:
 * <ul>
 * <li>General: each message keeps the envelope of the <em>first</em> split point: its ancestors, and on
 * every level the siblings before the path to it. Siblings after it are dropped, and every split point is
 * placed under the first one's parent (SAP's {@code //id} example).</li>
 * <li>Iterating: each message holds only the split points. Token splits at elements of that name.</li>
 * <li>Line Break: General repeats the first line in front of every message; Iterating does not. Like
 * SAP's example, the text {@code \n} or {@code \r} also counts as a line break.</li>
 * </ul>
 * Header or property references in the XPath or grouping are resolved by the caller.
 */
public final class SplitStep {

    /** SAP: these characters are not supported in a splitter XPath. */
    private static final String UNSUPPORTED = "|+*>=[]@";
    private static final Pattern DECLARATION = Pattern.compile("\\s+xmlns(?::([\\w.-]+))?\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern LINES = Pattern.compile("\\r\\n|\\r|\\n|\\\\r\\\\n|\\\\n|\\\\r");

    /**
     * Request: {@code {"splitter": "general"|"iterating", "expressionType": "xpath"|"token"|"lineBreak",
     * "expression": "/a/b", "grouping": 1, "streaming": true, "namespaces": {...}, "input": {"body": "..."}}}.
     * Result: {@code messages} with {@code body}, {@code index}, {@code complete} and {@code size}.
     */
    public Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        boolean general = !"iterating".equals(Json.str(req, "splitter", "general"));
        String type = Json.str(req, "expressionType", "xpath");
        String expr = Json.str(req, "expression", "").trim();
        boolean streaming = Json.bool(req, "streaming", true);
        String base = Json.str(req, "baseDir", null);
        ProjectResolver resolver = new ProjectResolver(base == null || base.isBlank() ? null : Path.of(base), null);
        SimExchange input = SimExchange.fromJson(Json.obj(req, "input"));
        List<Object> diags = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        boolean ok = true;
        try {
            int grouping = grouping(req.get("grouping"));
            if ("lineBreak".equals(type)) {
                bodies = splitLines(input.getBodyAsString(), general, grouping);
            } else {
                if (general && "token".equals(type)) {
                    throw new IllegalArgumentException("The General Splitter has no Token expression type (SAP); use the Iterating Splitter.");
                }
                bodies = splitXml(input.getBody(), general, type, expr, grouping,
                        XsltStep.stringMap(Json.obj(req, "namespaces")), resolver);
            }
            if (bodies.isEmpty()) {
                diags.add(Diagnostic.info("No split point found: CPI creates no message and ends the flow as Completed (SAP).").toJson());
            }
        } catch (Exception e) {
            ok = false;
            diags.add(Diagnostic.error(e.getMessage() == null ? e.toString() : e.getMessage()).toJson());
        }
        List<Object> messages = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            boolean last = i == bodies.size() - 1;
            Json.Obj m = Json.o().with("body", bodies.get(i)).with("index", i).with("complete", last);
            // SAP: with streaming, CamelSplitSize is set on the last message only.
            if (last || !streaming) {
                m.with("size", bodies.size());
            }
            messages.add(m);
        }
        return Json.o().with("ok", ok).with("messages", messages).with("count", messages.size())
                .with("diagnostics", diags)
                .with("engine", (general ? "General" : "Iterating") + " Splitter")
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    private static int grouping(Object o) {
        String s = o == null ? "" : o.toString().trim();
        if (s.isEmpty()) {
            return 1;
        }
        try {
            int n = Integer.parseInt(s);
            if (n >= 1) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException("Grouping must be a whole number of at least 1, not '" + s + "'.");
    }

    static List<String> splitLines(String text, boolean general, int grouping) {
        List<String> lines = new ArrayList<>(Arrays.asList(LINES.split(text, -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        String header = general && !lines.isEmpty() ? lines.remove(0) : null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += grouping) {
            String group = String.join("\n", lines.subList(i, Math.min(lines.size(), i + grouping)));
            out.add(header == null ? group : header + "\n" + group);
        }
        return out;
    }

    private static List<String> splitXml(byte[] body, boolean general, String type, String expr, int grouping,
                                         Map<String, String> ns, ProjectResolver resolver) throws Exception {
        if (expr.isEmpty()) {
            throw new IllegalArgumentException("token".equals(type) ? "Enter a token (an element name)." : "Enter an XPath expression.");
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setEntityResolver(resolver);
        InputSource in = new InputSource(new ByteArrayInputStream(body));
        in.setSystemId(ProjectResolver.MEMORY_BASE + "input.xml");
        Document doc = builder.parse(in);
        List<Element> points = "token".equals(type) ? byName(doc.getDocumentElement(), expr, new ArrayList<>())
                : select(doc, expr, ns);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i += grouping) {
            List<Element> group = points.subList(i, Math.min(points.size(), i + grouping));
            if (general) {
                Document msg = builder.newDocument();
                msg.appendChild(envelope(msg, points.get(0), group));
                // As in SAP's example of the General Splitter's result messages.
                out.add("<?xml version='1.0' encoding='UTF-8'?>\n" + serialize(msg));
            } else {
                StringBuilder sb = new StringBuilder();
                for (Element e : group) {
                    Document msg = builder.newDocument();
                    msg.appendChild(msg.importNode(e, true));
                    sb.append(serialize(msg));
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    private static List<Element> select(Document doc, String expr, Map<String, String> mapping) throws Exception {
        // SAP: a prefix can be declared after the path: /ns1:root/shop xmlns:ns1="demo.sap.com".
        Map<String, String> ns = new java.util.LinkedHashMap<>(mapping);
        java.util.regex.Matcher decl = DECLARATION.matcher(expr);
        String path = expr;
        while (decl.find()) {
            if (decl.group(1) == null) {
                throw new IllegalArgumentException("Declare a prefix (xmlns:ns1=\"...\"), not a default namespace: "
                        + "CPI rejects xmlns=\"...\" in a splitter XPath (SAP).");
            }
            ns.put(decl.group(1), decl.group(2));
            path = path.replace(decl.group(), "");
        }
        path = path.trim();
        // Real iFlows write paths with a trailing slash (/Participants/Participant/); CPI accepts them.
        path = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        path = inheritPrefixes(path);
        for (char c : path.toCharArray()) {
            if (UNSUPPORTED.indexOf(c) >= 0) {
                throw new IllegalArgumentException("'" + c + "' is not supported in a splitter XPath (SAP: only absolute "
                        + "or relative paths such as /A/B or //B; not | + * > = <= [ ] @).");
            }
        }
        javax.xml.xpath.XPath xp = XPathFactory.newDefaultInstance().newXPath();
        xp.setNamespaceContext(XPathStep.namespaceContext(ns));
        org.w3c.dom.NodeList nodes = (org.w3c.dom.NodeList) xp.evaluate(path, doc, XPathConstants.NODESET);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element)) {
                throw new IllegalArgumentException("The XPath must select elements; it selected a " + nodes.item(i).getNodeName() + " node.");
            }
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    /**
     * Steps without a prefix take the prefix of the step before them: in SAP's example
     * {@code /ns1:root/shop/customerReview} splits a document whose elements are all in ns1's namespace.
     * (Matching any namespace would explain the example too; which one CPI does is not documented.)
     */
    static String inheritPrefixes(String path) {
        String[] steps = path.split("/", -1);
        String prefix = null;
        for (int i = 0; i < steps.length; i++) {
            String step = steps[i];
            if (step.isEmpty() || step.startsWith(".") || step.contains("(")) {
                continue;
            }
            int colon = step.indexOf(':');
            if (colon > 0) {
                prefix = step.substring(0, colon);
            } else if (prefix != null) {
                steps[i] = prefix + ":" + step;
            }
        }
        return String.join("/", steps);
    }

    /** Elements with this local name in document order, not those inside an earlier match. */
    private static List<Element> byName(Element e, String name, List<Element> out) {
        String local = e.getLocalName() == null ? e.getNodeName() : e.getLocalName();
        if (local.equals(name)) {
            out.add(e);
            return out;
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element) {
                byName((Element) c, name, out);
            }
        }
        return out;
    }

    /** The first split point's ancestors with their preceding children, and the group under its parent. */
    private static Node envelope(Document msg, Element first, List<Element> group) {
        List<Element> chain = new ArrayList<>();
        for (Node p = first.getParentNode(); p instanceof Element; p = p.getParentNode()) {
            chain.add(0, (Element) p);
        }
        if (chain.isEmpty()) {
            return msg.importNode(first, true);
        }
        return level(msg, chain, 0, first, group);
    }

    private static Node level(Document msg, List<Element> chain, int depth, Element first, List<Element> group) {
        Element orig = chain.get(depth);
        Node onPath = depth + 1 < chain.size() ? chain.get(depth + 1) : first;
        Element copy = (Element) msg.importNode(orig, false);
        for (Node c = orig.getFirstChild(); c != onPath; c = c.getNextSibling()) {
            copy.appendChild(msg.importNode(c, true));
        }
        if (depth + 1 < chain.size()) {
            copy.appendChild(level(msg, chain, depth + 1, first, group));
        } else {
            for (int i = 0; i < group.size(); i++) {
                Node before = group.get(i).getPreviousSibling();
                if (i > 0 && isBlank(before)) {
                    copy.appendChild(msg.importNode(before, false));
                }
                copy.appendChild(msg.importNode(group.get(i), true));
            }
        }
        // Keep the indentation before the closing tag.
        if (isBlank(orig.getLastChild())) {
            copy.appendChild(msg.importNode(orig.getLastChild(), false));
        }
        return copy;
    }

    private static boolean isBlank(Node n) {
        return n != null && n.getNodeType() == Node.TEXT_NODE && n.getNodeValue().isBlank();
    }

    private static String serialize(Document doc) throws Exception {
        doc.normalizeDocument();
        Transformer t = TransformerFactory.newDefaultInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }
}
