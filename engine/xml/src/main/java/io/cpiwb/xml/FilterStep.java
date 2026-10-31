package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Filter step (SAP: "Define Filter" and its "Examples"). Filter 1.1 evaluates XPath 3.1 (SAP: Saxon EE; here
 * Saxon-HE) on the body, and the result becomes the new body; headers and properties stay. The value type:
 * <ul>
 * <li>String: the string values of all items one after another (SAP: {@code count/text()} of two orders
 * gives {@code 10010}).</li>
 * <li>Integer: that text as a whole number.</li>
 * <li>Boolean: the effective boolean value, as Camel's XPath result type BOOLEAN.</li>
 * <li>Node: the first node, as Camel's NODE. Nodelist: all nodes one after another, without a wrapper
 * element (SAP's examples show the selected elements as they are).</li>
 * </ul>
 * That CPI converts Integer, Boolean and Node like Camel's XPath result types, and joins a node list without a
 * separator (as Camel's converter does), are assumptions. Headers and properties are XPath variables
 * ({@code $name}, see {@link XPathStep#compile}); {@code function:simple(...)} (SAP) is resolved by the caller.
 */
public final class FilterStep {

    private final Processor processor = new Processor(false);

    public FilterStep() {
        ProjectResolver.install(processor.getUnderlyingConfiguration());
    }

    /**
     * Request: {@code {"expression": "/a/b", "valueType": "string"|"integer"|"boolean"|"node"|"nodelist",
     * "namespaces": {...}, "baseDir": "...", "input": {"body": "..."}}}. Result: {@code body} and {@code count}
     * (the number of items the XPath selected).
     */
    public synchronized Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        String expr = Json.str(req, "expression", "").trim();
        String type = Json.str(req, "valueType", "nodelist").toLowerCase();
        String base = Json.str(req, "baseDir", null);
        ProjectResolver resolver = new ProjectResolver(base == null || base.isBlank() ? null : Path.of(base), null);
        SimExchange input = SimExchange.fromJson(Json.obj(req, "input"));
        List<Object> diags = new ArrayList<>();
        Json.Obj result = Json.o();
        boolean ok = true;
        resolver.begin();
        try {
            if (expr.isEmpty()) {
                throw new IllegalArgumentException("Enter an XPath expression.");
            }
            if (expr.contains("function:simple")) {
                throw new IllegalArgumentException("UnsupportedInSimulator: function:simple(...) needs Camel; "
                        + "the Flow Steps tab resolves it before the XPath runs.");
            }
            XPathCompiler xc = processor.newXPathCompiler();
            xc.setBaseURI(URI.create(resolver.resolveAgainst(".", null)));
            XsltStep.stringMap(Json.obj(req, "namespaces")).forEach(xc::declareNamespace);
            XPathSelector sel = XPathStep.compile(xc, expr, input);
            sel.setURIResolver(resolver);
            sel.setContextItem(processor.newDocumentBuilder().build(resolver.source(
                    new InputSource(new ByteArrayInputStream(input.getBody())), ProjectResolver.MEMORY_BASE + "input.xml")));
            String body;
            if ("boolean".equals(type)) {
                body = String.valueOf(sel.effectiveBooleanValue());
            } else {
                XdmValue value = sel.evaluate();
                result.with("count", value.size());
                body = body(value, type, diags);
            }
            result.with("body", body);
        } catch (Exception e) {
            ok = false;
            diags.add(Diagnostic.error(e.getMessage() == null ? e.toString() : e.getMessage()).toJson());
        } finally {
            ProjectResolver.end();
        }
        return result.with("ok", ok).with("diagnostics", diags)
                .with("engine", XsltStep.engineVersion() + " (XPath 3.1)")
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    private String body(XdmValue value, String type, List<Object> diags) throws SaxonApiException {
        switch (type) {
            case "string":
                return text(value);
            case "integer":
                String s = text(value).trim();
                try {
                    return String.valueOf(new BigDecimal(s).intValueExact());
                } catch (ArithmeticException | NumberFormatException e) {
                    throw new IllegalArgumentException("The XPath result '" + s + "' is not a whole number (Value Type Integer).");
                }
            case "node":
            case "nodelist":
                StringBuilder out = new StringBuilder();
                for (XdmItem item : value) {
                    if (!(item instanceof XdmNode)) {
                        throw new IllegalArgumentException("The XPath returned the value '" + item.getStringValue()
                                + "', not a node; choose Value Type String, Integer or Boolean.");
                    }
                    XdmNode node = (XdmNode) item;
                    out.append(node.getNodeKind() == XdmNodeKind.ELEMENT || node.getNodeKind() == XdmNodeKind.DOCUMENT
                            ? serialize(node) : node.getStringValue());
                    if ("node".equals(type)) {
                        break;
                    }
                }
                if (value.size() == 0) {
                    diags.add(Diagnostic.info("The XPath selected nothing, so the body is empty.").toJson());
                }
                return out.toString();
            default:
                throw new IllegalArgumentException("Unknown Value Type '" + type + "' (String, Integer, Boolean, Node or Nodelist).");
        }
    }

    /** The node as it is in the body: not re-indented, no XML declaration. */
    private String serialize(XdmNode node) throws SaxonApiException {
        StringWriter w = new StringWriter();
        Serializer s = processor.newSerializer(w);
        s.setOutputProperty(Serializer.Property.METHOD, "xml");
        s.setOutputProperty(Serializer.Property.INDENT, "no");
        s.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
        s.serializeNode(node);
        return w.toString();
    }

    private static String text(XdmValue value) {
        StringBuilder sb = new StringBuilder();
        for (XdmItem item : value) {
            sb.append(item.getStringValue());
        }
        return sb.toString();
    }
}
