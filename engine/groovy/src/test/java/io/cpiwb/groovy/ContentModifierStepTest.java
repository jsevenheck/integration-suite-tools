package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rows use the iFlow encoding (types and Delete names as in exported .iflw files). Runs on Camel 2 and 3. */
@SuppressWarnings("unchecked")
class ContentModifierStepTest {

    static Map<String, Object> row(String action, String name, String type, String value, String datatype) {
        return Json.o().with("action", action).with("name", name).with("type", type).with("value", value).with("datatype", datatype);
    }

    static Map<String, Object> run(List<Object> headers, List<Object> properties, String bodyType, String body) throws Exception {
        Map<String, Object> input = ScriptRunnerTest.in("<Product><ID>HT-1051</ID></Product>",
                List.of(ScriptRunnerTest.hv("SenderId", "ERP", "String"), ScriptRunnerTest.hv("X-Trace", "1", "String"),
                        ScriptRunnerTest.hv("x-other", "2", "String")),
                List.of(ScriptRunnerTest.hv("Partner", "ACME", "String")));
        Map<String, Object> r = (Map<String, Object>) ScriptRunnerTest.ENGINE.call("contentModifier.run", Json.o()
                .with("headers", headers).with("properties", properties).with("bodyType", bodyType).with("body", body)
                .with("input", input));
        return r;
    }

    static Map<String, String> values(Map<String, Object> r, String list) {
        assertTrue(Json.bool(r, "ok", false), () -> String.valueOf(r.get("diagnostics")));
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Object o : Json.arr(Json.obj(r, "output"), list)) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.put((String) m.get("name"), m.get("value") + ":" + m.get("type"));
        }
        return out;
    }

    @Test
    void rowsRunTopToBottomThenTheBody() throws Exception {
        Map<String, Object> r = run(
                List.of(row("Create", "Order", "constant", "4711", ""),
                        row("Create", "Key", "expression", "${header.SenderId}-${header.Order}", "java.lang.String"),
                        row("Create", "Copy", "property", "Partner", "")),
                List.of(row("Create", "timestamp", "expression", "${date:now:yyyy}", ""),
                        row("Create", "Count", "expression", "${header.Order}", "Integer"),
                        row("Create", "FromHeader", "header", "Key", "")),
                "expression", "${property.Partner}:${in.body}");
        Map<String, String> h = values(r, "headers");
        assertEquals("ERP-4711:String", h.get("Key"));
        assertEquals("ACME:String", h.get("Copy"));
        Map<String, String> p = values(r, "properties");
        assertEquals("4711:Integer", p.get("Count"));
        assertEquals("ERP-4711:String", p.get("FromHeader"));
        assertEquals("ACME:<Product><ID>HT-1051</ID></Product>", Json.obj(r, "output").get("body"));
        assertTrue(((List<Object>) Json.obj(Json.obj(r, "changes"), "headers").get("added")).contains("Order"));
    }

    @Test
    void xpathRowsArriveEvaluated() throws Exception {
        Map<String, Object> xp = row("Create", "Id", "xpath", "//ID/text()", "java.lang.Integer");
        Map<String, Object> r = run(List.of(), List.of(xp), "constant", "");
        assertFalse(Json.bool(r, "ok", true));
        xp.put("resolved", "1051");
        r = run(List.of(), List.of(xp), "constant", "");
        assertEquals("1051:Integer", values(r, "properties").get("Id"));
        // An empty body leaves the message body as it is.
        assertEquals("<Product><ID>HT-1051</ID></Product>", Json.obj(r, "output").get("body"));
    }

    @Test
    void deleteByNameAndPattern() throws Exception {
        Map<String, Object> r = run(List.of(row("Delete", "$include=X-*", "constant", "", ""),
                        row("Delete", "$name=SenderId", "constant", "", "")),
                List.of(row("Delete", "$include=*,$exclude=Part*", "constant", "", "")), "constant", "");
        Map<String, String> h = values(r, "headers");
        assertFalse(h.containsKey("X-Trace"));
        assertFalse(h.containsKey("SenderId"));
        assertTrue(values(r, "properties").containsKey("Partner"));
    }

    @Test
    void variablesAndUnsupportedTypes() throws Exception {
        Map<String, Object> var = row("Create", "Last", "persisted variables", "lastRun", "");
        var.put("default", "never");
        Map<String, Object> r = run(List.of(var), List.of(), "constant", "");
        assertEquals("never:String", values(r, "headers").get("Last"));
        r = run(List.of(row("Create", "No", "numberRange", "ORDERS", "")), List.of(), "constant", "");
        assertFalse(Json.bool(r, "ok", true));
        assertTrue(Json.arr(r, "diagnostics").toString().contains("UnsupportedInSimulator"));
        r = run(List.of(), List.of(row("Create", "Nothing", "expression", "${null}", "")), "constant", "");
        assertNull(((Map<String, Object>) Json.arr(r, "trace").get(0)).get("value"));
    }
}
