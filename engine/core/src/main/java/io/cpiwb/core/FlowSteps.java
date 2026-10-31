package io.cpiwb.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content Modifier, Router and Splitter settings from an iFlow model. The keys are the ones seen in the
 * exported iFlows of SAP's public sample repository (SAP-samples/cloud-integration-flow, 2026-10-28):
 * Content Modifier {@code activityType=Enricher} with {@code headerTable}, {@code propertyTable} (rows of
 * {@code <cell id='Action|Type|Value|Default|Name|Datatype'>}), {@code bodyType} and {@code wrapContent}
 * (versions up to 1.3 have no {@code bodyType}: their body is an expression, as SAP's sample "Externalizing
 * Additional Parameter" shows with {@code ${header.x}} in a 1.2 body);
 * Splitter {@code splitType}, {@code exprType}, {@code splitExprValue} or {@code tokenValue},
 * {@code grouping}, {@code Streaming}; Router {@code exclusiveGateway} with {@code throwException}, its
 * outgoing sequence flows ({@code expressionType} XML or NonXML, {@code conditionExpression}) and the
 * {@code default} flow; Filter {@code xpathType} (String, Integer, Boolean, Node, Nodelist) and the XPath in
 * {@code wrapContent}; Gather {@code messageType} (SameXMLFormat, DiffXMLFormat, PlainText),
 * {@code aggregationAlgorithm} (sap-identical-multi-mapping, sap-pi-multi-mapping, xpath-merge-strategy,
 * concatenate-strategy), {@code sourceXPath} and {@code targetXPath}. The format is not documented.
 * {@code {{name}}} placeholders take the value from parameters.prop.
 */
final class FlowSteps {

    private static final Pattern PARAM = Pattern.compile("\\{\\{([^}]+)}}");

    private FlowSteps() {
    }

    /** A Content Modifier, Filter, Gather or General / Iterating Splitter; null for other activities. */
    static Json.Obj of(Element act, Map<String, String> props, Properties params) throws Exception {
        String activity = props.getOrDefault("activityType", "");
        if ("Enricher".equals(activity)) {
            return step(act, "contentModifier", props).with("config", Json.o()
                    .with("headers", rows(props.get("headerTable"), params))
                    .with("properties", rows(props.get("propertyTable"), params))
                    .with("bodyType", param(props.getOrDefault("bodyType", "expression"), params).toLowerCase())
                    .with("body", param(props.getOrDefault("wrapContent", ""), params)));
        }
        if ("Filter".equals(activity)) {
            return step(act, "filter", props).with("config", Json.o()
                    .with("expression", param(props.getOrDefault("wrapContent", ""), params))
                    .with("valueType", param(props.getOrDefault("xpathType", "Nodelist"), params).toLowerCase()));
        }
        if ("Gather".equals(activity)) {
            String format = param(props.getOrDefault("messageType", "SameXMLFormat"), params);
            String algorithm = param(props.getOrDefault("aggregationAlgorithm", ""), params);
            return step(act, "gather", props).with("config", Json.o()
                    .with("messageType", format.equals("PlainText") ? "plainText" : format.equals("DiffXMLFormat") ? "differentXml"
                            : format.equals("SameXMLFormat") ? "sameXml" : format)
                    .with("algorithm", algorithm.equals("concatenate-strategy") ? "concatenate"
                            : algorithm.equals("xpath-merge-strategy") ? "combineAtXPath"
                            : algorithm.endsWith("multi-mapping") ? "combine" : algorithm)
                    .with("sourceXPath", param(props.getOrDefault("sourceXPath", ""), params))
                    .with("targetXPath", param(props.getOrDefault("targetXPath", ""), params)));
        }
        String split = props.getOrDefault("splitType", "");
        if ("Splitter".equals(activity) && (split.equals("GeneralSplitter") || split.equals("IteratingSplitter"))) {
            String expr = props.getOrDefault("exprType", "XPath");
            String type = expr.equals("Token") ? "token" : expr.equals("Line Break") ? "lineBreak" : "xpath";
            return step(act, "splitter", props).with("config", Json.o()
                    .with("splitter", split.equals("GeneralSplitter") ? "general" : "iterating")
                    .with("expressionType", type)
                    .with("expression", param(props.getOrDefault("token".equals(type) ? "tokenValue" : "splitExprValue", ""), params))
                    .with("grouping", param(props.getOrDefault("grouping", ""), params))
                    .with("streaming", !"false".equals(param(props.getOrDefault("Streaming", "true"), params))));
        }
        return null;
    }

    /** A Router: its routes in the order of the gateway's outgoing flows (an assumption for CPI's order). */
    static Json.Obj router(Document doc, Element gw, Map<String, String> props, Properties params) {
        List<Object> routes = new ArrayList<>();
        String dflt = gw.getAttribute("default");
        NodeList flows = doc.getElementsByTagNameNS("*", "sequenceFlow");
        for (Node c = gw.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element) || !"outgoing".equals(c.getLocalName())) {
                continue;
            }
            String id = c.getTextContent().trim();
            for (int i = 0; i < flows.getLength(); i++) {
                Element f = (Element) flows.item(i);
                if (!id.equals(f.getAttribute("id"))) {
                    continue;
                }
                NodeList cond = f.getElementsByTagNameNS("*", "conditionExpression");
                Map<String, String> fp = IflowImport.properties(f);
                routes.add(Json.o().with("name", f.getAttribute("name"))
                        .with("expressionType", "NonXML".equals(fp.get("expressionType")) ? "nonxml" : "xml")
                        .with("condition", cond.getLength() == 0 ? "" : param(cond.item(0).getTextContent().trim(), params))
                        .with("default", id.equals(dflt)));
            }
        }
        return step(gw, "router", props).with("config", Json.o().with("routes", routes)
                .with("throwException", "true".equals(props.get("throwException"))));
    }

    private static Json.Obj step(Element el, String type, Map<String, String> props) {
        return Json.o().with("id", el.getAttribute("id")).with("name", el.getAttribute("name")).with("type", type)
                .with("componentVersion", props.get("componentVersion"));
    }

    /** Table rows: {@code <row><cell id='Name'>..</cell>..</row>}, kept as the iFlow writes them. */
    private static List<Object> rows(String table, Properties params) throws Exception {
        List<Object> out = new ArrayList<>();
        if (table == null || table.isBlank()) {
            return out;
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document d = f.newDocumentBuilder().parse(new InputSource(new StringReader("<rows>" + table + "</rows>")));
        NodeList rows = d.getElementsByTagName("row");
        for (int i = 0; i < rows.getLength(); i++) {
            Json.Obj row = Json.o();
            NodeList cells = ((Element) rows.item(i)).getElementsByTagName("cell");
            for (int j = 0; j < cells.getLength(); j++) {
                Element cell = (Element) cells.item(j);
                String key = cell.getAttribute("id").toLowerCase();
                row.with("datatype".equals(key) ? "datatype" : key, param(cell.getTextContent(), params));
            }
            out.add(row);
        }
        return out;
    }

    private static String param(String value, Properties params) {
        Matcher m = PARAM.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = params.getProperty(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        return m.appendTail(sb).toString();
    }
}
