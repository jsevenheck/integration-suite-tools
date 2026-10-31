package io.cpiwb.groovy;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import io.cpiwb.groovy.camel.Exchanges;
import org.apache.camel.Exchange;
import org.apache.camel.spi.Language;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Camel Simple as CPI uses it (SAP: "Using Camel Simple Expression Language"): a Router or Looping Process
 * Call condition (Expression Type Non-XML) or a Content Modifier / Write Variables value of type Expression. The
 * engine's own Camel evaluates it against the input message. CPI spellings from SAP's docs that plain
 * Camel rejects are rewritten first, and the result says so:
 * <ul>
 * <li>{@code =} as an operator (SAP's Router operator table) becomes Camel's {@code ==}.</li>
 * <li>{@code and} / {@code or} (same table) become {@code &&} / {@code ||} on Camel 3, which dropped them.</li>
 * <li>{@code ${property.x}} (SAP's Router example) becomes {@code ${exchangeProperty.x}} on Camel 3,
 * which dropped {@code property}. Camel 2 still has it. CPI does the same (SAP patch notes, August 2024:
 * "The conversion from property to exchangeProperty ...").</li>
 * </ul>
 * That CPI evaluates {@code =}, {@code and} and {@code or} exactly like the Camel spellings is an assumption.
 */
public final class SimpleStep {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{property\\.");

    /**
     * Request: {@code {"expression": "${header.SenderId} = '1'", "mode": "condition"|"expression",
     * "input": {"body": "...", "headers": [...], "properties": [...]}}}.
     */
    public Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        String expr = Json.str(req, "expression", "");
        boolean condition = !"expression".equals(Json.str(req, "mode", "condition"));
        List<Object> diags = new ArrayList<>();
        Json.Obj result = Json.o();
        String camel = toCamel(expr, condition);
        if (!camel.equals(expr)) {
            result.with("evaluated", camel);
        }
        boolean ok = true;
        try {
            if (expr.isBlank()) {
                throw new IllegalArgumentException("Enter an expression.");
            }
            Exchange exchange = exchange(SimExchange.fromJson(Json.obj(req, "input")));
            Language simple = exchange.getContext().resolveLanguage("simple");
            if (condition) {
                result.with("value", String.valueOf(simple.createPredicate(camel).matches(exchange))).with("type", "Boolean");
            } else {
                Object v = simple.createExpression(camel).evaluate(exchange, Object.class);
                result.with("value", v == null ? null : exchange.getContext().getTypeConverter().convertTo(String.class, exchange, v))
                        .with("type", v == null ? "null" : v.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            ok = false;
            diags.add(Diagnostic.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).toJson());
        }
        return result.with("ok", ok)
                .with("diagnostics", diags)
                .with("engine", "Camel " + Exchanges.camelVersion())
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    /** CPI's spelling of a condition or expression in Camel's spelling for the engine's Camel line. */
    static String toCamel(String expr, boolean condition) {
        boolean camel3 = !Exchanges.camelVersion().startsWith("2.");
        String camel = condition ? operatorsToCamel(expr, camel3) : expr;
        return camel3 ? PROPERTY.matcher(camel).replaceAll("\\${exchangeProperty.") : camel;
    }

    /** A Camel exchange with the input message (body as text). */
    static Exchange exchange(SimExchange in) {
        Exchange exchange = Exchanges.create();
        exchange.getIn().setBody(in.getBodyAsString());
        exchange.getIn().getHeaders().putAll(in.getHeaders());
        exchange.getProperties().putAll(in.getProperties());
        return exchange;
    }

    /**
     * Turns each {@code =} operator into {@code ==}, and with {@code camel3} {@code and} / {@code or} into
     * {@code &&} / {@code ||}. Text in quotes and inside {@code ${…}} stays as it is.
     */
    static String operatorsToCamel(String expr, boolean camel3) {
        StringBuilder out = new StringBuilder(expr.length() + 4);
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = depth == 0 ? c : 0;
            } else if (c == '$' && i + 1 < expr.length() && expr.charAt(i + 1) == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (camel3 && depth == 0 && (word(expr, i, "and") || word(expr, i, "or"))) {
                boolean and = expr.charAt(i) == 'a';
                out.append(and ? "&&" : "||");
                i += and ? 2 : 1;
                continue;
            } else if (c == '=' && depth == 0) {
                char prev = i > 0 ? expr.charAt(i - 1) : ' ';
                char next = i + 1 < expr.length() ? expr.charAt(i + 1) : ' ';
                if ("=!<>".indexOf(prev) < 0 && next != '=' && next != '~') {
                    out.append("==");
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean word(String s, int i, String w) {
        return s.startsWith(w, i) && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))
                && (i + w.length() == s.length() || !Character.isLetterOrDigit(s.charAt(i + w.length())));
    }
}
