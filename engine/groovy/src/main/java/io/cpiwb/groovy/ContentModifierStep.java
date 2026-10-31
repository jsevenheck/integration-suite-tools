package io.cpiwb.groovy;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import io.cpiwb.core.TypedValue;
import io.cpiwb.groovy.camel.Exchanges;
import org.apache.camel.Exchange;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Content Modifier step (SAP: "Define Content Modifier"), on the engine's Camel exchange. The rows use
 * the iFlow's own encoding (as in exported {@code .iflw} files, {@code headerTable} / {@code propertyTable}):
 * <ul>
 * <li>{@code action} Create or Delete, {@code type} constant, header, property, xpath, expression,
 * persisted variables, global persisted variables, numberRange or external parameter.</li>
 * <li>A Delete row names {@code $name=X}, or {@code $include=pattern,$exclude=pattern}; Camel's
 * {@code removeHeaders} / {@code removeProperties} apply the patterns. That CPI uses them is an assumption.</li>
 * <li>XPath rows arrive evaluated ({@code resolved}): CPI evaluates them with XPath 3.1 (SAP), which runs in
 * the XML engine.</li>
 * </ul>
 * Rows run top to bottom (SAP), the header table before the property table and both before the body (an
 * assumption from a SAP Community blog).
 */
public final class ContentModifierStep {

    /**
     * Request: {@code {"headers": [rows], "properties": [rows], "bodyType": "constant"|"expression",
     * "body": "...", "input": {...}}}; a row is {@code {action, name, type, value, datatype, default, resolved}}.
     */
    public Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        SimExchange in = SimExchange.fromJson(Json.obj(req, "input"));
        Exchange exchange = SimpleStep.exchange(in);
        List<Diagnostic> diags = new ArrayList<>();
        List<Object> trace = new ArrayList<>();
        boolean ok = true;
        try {
            for (String table : List.of("headers", "properties")) {
                for (Object o : Json.arr(req, table)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) o;
                    trace.add(apply(exchange, "headers".equals(table), row, diags));
                }
            }
            String body = Json.str(req, "body", "");
            if (!body.isEmpty()) {
                Object value = "expression".equalsIgnoreCase(Json.str(req, "bodyType", "constant"))
                        ? simple(exchange, body, String.class) : body;
                exchange.getIn().setBody(value);
                trace.add(Json.o().with("table", "body").with("name", "Message Body").with("value", TypedValue.display(value)));
            }
        } catch (RuntimeException e) {
            ok = false;
            diags.add(Diagnostic.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        Json.Obj result = Json.o().with("ok", ok).with("trace", trace);
        if (ok) {
            result.with("output", ScriptRunner.describeOutput(exchange, StandardCharsets.UTF_8, diags))
                    .with("changes", Json.o()
                            .with("headers", SimExchange.diff(in.getHeaders(), exchange.getIn().getHeaders()))
                            .with("properties", SimExchange.diff(in.getProperties(), exchange.getProperties())));
        }
        List<Object> d = new ArrayList<>();
        diags.forEach(x -> d.add(x.toJson()));
        return result.with("diagnostics", d)
                .with("engine", "Camel " + Exchanges.camelVersion())
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    private static Map<String, Object> apply(Exchange exchange, boolean header, Map<String, Object> row, List<Diagnostic> diags) {
        String name = Json.str(row, "name", "").trim();
        String type = Json.str(row, "type", "constant").trim().toLowerCase();
        String value = Json.str(row, "value", "");
        String where = (header ? "Header " : "Property ") + name;
        Json.Obj step = Json.o().with("table", header ? "header" : "property").with("name", name);
        if ("delete".equalsIgnoreCase(Json.str(row, "action", "Create"))) {
            delete(exchange, header, name);
            return step.with("deleted", true);
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException((header ? "A header" : "A property") + " row has no name.");
        }
        String datatype = Json.str(row, "datatype", "").trim();
        Object v;
        switch (type) {
            case "constant":
                v = value;
                if (value.contains("{{")) {
                    diags.add(Diagnostic.warning(where + ": the externalized parameter in '" + value + "' is not resolved "
                            + "(no value in the iFlow's parameters.prop)."));
                }
                break;
            case "header":
                v = exchange.getIn().getHeader(value);
                break;
            case "property":
                v = exchange.getProperty(value);
                break;
            case "expression":
                v = simple(exchange, value, datatype.isEmpty() ? Object.class : type(exchange, datatype, where));
                break;
            case "xpath":
                if (!row.containsKey("resolved")) {
                    throw new IllegalArgumentException(where + ": the XPath was not evaluated (it needs the XML engine).");
                }
                Object resolved = row.get("resolved");
                if (datatype.startsWith("org.w3c.dom.")) {
                    v = resolved;
                    diags.add(Diagnostic.info(where + ": " + datatype + " is kept as XML text in the simulator."));
                } else {
                    v = resolved == null ? null : convert(exchange, datatype.isEmpty() ? String.class : type(exchange, datatype, where), resolved);
                }
                break;
            case "persisted variables":
            case "global persisted variables":
                v = Json.str(row, "default", "");
                diags.add(Diagnostic.info(where + ": the simulator has no " + ("global persisted variables".equals(type) ? "global" : "local")
                        + " variables, so the default value is used (CPI does so when the variable is not found)."));
                break;
            default:
                throw new IllegalArgumentException("UnsupportedInSimulator: " + where + " uses the source type '" + type
                        + "', which the simulator does not provide.");
        }
        if (header) {
            exchange.getIn().setHeader(name, v);
        } else {
            exchange.setProperty(name, v);
        }
        return step.with("value", TypedValue.display(v)).with("type", TypedValue.typeOf(v));
    }

    /** {@code $name=X}, or {@code $include=a*,$exclude=b*}; a plain text is a name or a Camel pattern. */
    private static void delete(Exchange exchange, boolean header, String spec) {
        String include = null;
        List<String> exclude = new ArrayList<>();
        for (String part : spec.split(",(?=\\$)")) {
            String p = part.trim();
            if (p.startsWith("$name=")) {
                String n = p.substring(6);
                if (header) {
                    exchange.getIn().removeHeader(n);
                } else {
                    exchange.removeProperty(n);
                }
            } else if (p.startsWith("$include=")) {
                include = p.substring(9);
            } else if (p.startsWith("$exclude=")) {
                exclude.add(p.substring(9));
            } else if (!p.isEmpty()) {
                include = p;
            }
        }
        if (include != null) {
            String[] ex = exclude.toArray(new String[0]);
            if (header) {
                exchange.getIn().removeHeaders(include, ex);
            } else {
                exchange.removeProperties(include, ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object simple(Exchange exchange, String expr, Class<?> type) {
        return exchange.getContext().resolveLanguage("simple").createExpression(SimpleStep.toCamel(expr, false))
                .evaluate(exchange, (Class<Object>) type);
    }

    @SuppressWarnings("unchecked")
    private static Object convert(Exchange exchange, Class<?> type, Object value) {
        try {
            return exchange.getContext().getTypeConverter().mandatoryConvertTo((Class<Object>) type, exchange, value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot convert '" + value + "' to " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    /** The Data Type column, resolved by Camel (so {@code String} and {@code Integer} also work). */
    private static Class<?> type(Exchange exchange, String name, String where) {
        Class<?> c = exchange.getContext().getClassResolver().resolveClass(name);
        if (c == null) {
            throw new IllegalArgumentException(where + ": data type '" + name + "' is not a class Camel can load.");
        }
        return c;
    }
}
