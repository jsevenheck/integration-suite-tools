package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gather step (SAP: "Define Gather and Join"): merges messages, for example a splitter's, into one body.
 * Headers and properties are not aggregated (SAP).
 * <ul>
 * <li>Combine (XML): the multi-mapping format of SAP's result example. Messages of the same format all go
 * into {@code multimap:Message1}; messages of different formats each into their own {@code MessageN}. Each
 * body keeps its text; only its XML declaration is dropped.</li>
 * <li>Concatenate (plain text): the bodies one after another.</li>
 * <li>Combine at XPath (XML, same format): SAP shows no result. Here the nodes that the source XPath selects in
 * every message, in order, replace those of the first message, under the node the target XPath selects in it
 * (by default the source's parent). With a splitter's messages this rebuilds the split document, as in SAP's
 * Composed Message Processor pattern. This is an assumption.</li>
 * </ul>
 * Zip and Tar are not simulated.
 */
public final class GatherStep {

    private static final String MULTIMAP = "http://sap.com/xi/XI/SplitAndMerge";
    private static final Pattern DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]*\\?>\\s*");
    private static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /**
     * Request: {@code {"messageType": "sameXml"|"differentXml"|"plainText", "algorithm":
     * "combine"|"combineAtXPath"|"concatenate", "sourceXPath": "...", "targetXPath": "...",
     * "namespaces": {...}, "messages": ["body", ...]}}. Result: {@code body} and {@code count}.
     */
    public Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        String format = Json.str(req, "messageType", "sameXml");
        String algorithm = Json.str(req, "algorithm", "combine");
        List<String> messages = new ArrayList<>();
        for (Object o : Json.arr(req, "messages")) {
            messages.add(o == null ? "" : o.toString());
        }
        String base = Json.str(req, "baseDir", null);
        ProjectResolver resolver = new ProjectResolver(base == null || base.isBlank() ? null : Path.of(base), null);
        List<Object> diags = new ArrayList<>();
        Json.Obj result = Json.o().with("count", messages.size());
        boolean ok = true;
        try {
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("There are no messages to gather; run a splitter or add messages.");
            }
            result.with("body", gather(format, algorithm, messages, Json.str(req, "sourceXPath", "").trim(),
                    Json.str(req, "targetXPath", "").trim(), XsltStep.stringMap(Json.obj(req, "namespaces")), resolver));
        } catch (Exception e) {
            ok = false;
            diags.add(Diagnostic.error(e.getMessage() == null ? e.toString() : e.getMessage()).toJson());
        }
        return result.with("ok", ok).with("diagnostics", diags)
                .with("engine", "Gather")
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    private static String gather(String format, String algorithm, List<String> messages, String source, String target,
                                 Map<String, String> ns, ProjectResolver resolver) throws Exception {
        boolean xml = !"plainText".equals(format);
        switch (algorithm) {
            case "concatenate":
                if (xml) {
                    throw new IllegalArgumentException("Concatenate is for Incoming Format Plain Text (SAP).");
                }
                return String.join("", messages);
            case "combine":
                if (!xml) {
                    throw new IllegalArgumentException("Combine is for XML messages (SAP); plain text is concatenated.");
                }
                return combine(messages, "differentXml".equals(format));
            case "combineAtXPath":
                if (!"sameXml".equals(format)) {
                    throw new IllegalArgumentException("Combine at XPath is for Incoming Format XML (Same Format) (SAP).");
                }
                return combineAtXPath(messages, source, target, ns, resolver);
            default:
                throw new IllegalArgumentException("UnsupportedInSimulator: the Gather algorithm '" + algorithm
                        + "' (Zip and Tar are not simulated).");
        }
    }

    private static String combine(List<String> messages, boolean different) {
        StringBuilder sb = new StringBuilder(XML_DECLARATION).append("<multimap:Messages xmlns:multimap=\"")
                .append(MULTIMAP).append("\">");
        if (!different) {
            sb.append("<multimap:Message1>");
        }
        for (int i = 0; i < messages.size(); i++) {
            if (different) {
                sb.append("<multimap:Message").append(i + 1).append('>');
            }
            sb.append(DECLARATION.matcher(messages.get(i)).replaceFirst("").stripTrailing());
            if (different) {
                sb.append("</multimap:Message").append(i + 1).append('>');
            }
        }
        if (!different) {
            sb.append("</multimap:Message1>");
        }
        return sb.append("</multimap:Messages>").toString();
    }

    private static String combineAtXPath(List<String> messages, String source, String target, Map<String, String> ns,
                                         ProjectResolver resolver) throws Exception {
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Enter the source XPath (Combine from source).");
        }
        if (target.isEmpty()) {
            int slash = source.lastIndexOf('/');
            target = slash > 0 && source.charAt(slash - 1) != '/' ? source.substring(0, slash) : "";
            if (target.isEmpty()) {
                throw new IllegalArgumentException("Enter the target XPath (Combine at target).");
            }
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setEntityResolver(resolver);
        XPath xp = XPathFactory.newDefaultInstance().newXPath();
        xp.setNamespaceContext(XPathStep.namespaceContext(ns));
        Document result = null;
        Element at = null;
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            InputSource in = new InputSource(new ByteArrayInputStream(messages.get(i).getBytes(StandardCharsets.UTF_8)));
            in.setSystemId(ProjectResolver.MEMORY_BASE + "message" + (i + 1) + ".xml");
            Document doc;
            try {
                doc = builder.parse(in);
            } catch (Exception e) {
                throw new IllegalArgumentException("Message " + (i + 1) + " is not XML: " + e.getMessage(), e);
            }
            NodeList found = (NodeList) xp.evaluate(source, doc, XPathConstants.NODESET);
            if (i == 0) {
                result = doc;
                Node t = (Node) xp.evaluate(target, doc, XPathConstants.NODE);
                if (!(t instanceof Element)) {
                    throw new IllegalArgumentException("The target XPath " + target + " selects no element in the first message.");
                }
                at = (Element) t;
                for (int j = 0; j < found.getLength(); j++) {
                    found.item(j).getParentNode().removeChild(found.item(j));
                }
            }
            for (int j = 0; j < found.getLength(); j++) {
                nodes.add(found.item(j));
            }
        }
        if (nodes.isEmpty()) {
            // SAP: if the XPath points to no element in any branch, the scenario fails with an exception.
            throw new IllegalArgumentException("The source XPath " + source + " selects nothing in any message; CPI fails "
                    + "with an exception (SAP).");
        }
        for (Node n : nodes) {
            at.appendChild(result.importNode(n, true));
        }
        Transformer t = TransformerFactory.newDefaultInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(result), new StreamResult(w));
        return XML_DECLARATION + w;
    }
}
