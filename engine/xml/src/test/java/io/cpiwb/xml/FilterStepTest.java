package io.cpiwb.xml;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The expected bodies are SAP's ("Define Filter" and the Filter's "Examples"). */
class FilterStepTest {

    static final String STARS = "<starCatalogue>\n"
            + "\t<star>\n\t\t<class>A</class>\n\t\t<name>Vega</name>\n\t\t<distance>25.04</distance>\n\t</star>\n"
            + "\t<star>\n\t\t<class>A</class>\n\t\t<name>Deneb</name>\n\t\t<distance>1550</distance>\n\t</star>\n"
            + "\t<star>\n\t\t<class>A</class>\n\t\t<name>Sirius</name>\n\t\t<distance>8.6</distance>\n\t</star>\n"
            + "\t<star>\n\t\t<class>G</class>\n\t\t<name>Sun</name>\n\t\t<distance>0</distance>\n\t</star>\n"
            + "</starCatalogue>";
    static final String ORDERS = "<Message>\n<orders>\n\t\t<order>\n\t\t\t<clientId>I0001</clientId>\n\t\t\t<count>100</count>\n"
            + "\t\t</order>\n\t\t<order>\n\t\t\t<clientId>I0002</clientId>\n\t\t     \t <count>10</count>\n\t\t</order>\n</orders>\n</Message>";

    static Map<String, Object> filter(String expr, String type, String body) {
        return new FilterStep().run(Json.o().with("expression", expr).with("valueType", type)
                .with("input", Json.o().with("body", body)));
    }

    static String body(Map<String, Object> r) {
        assertTrue(Json.bool(r, "ok", false), () -> String.valueOf(r.get("diagnostics")));
        return (String) r.get("body");
    }

    @Test
    void stringJoinsTheValues() {
        assertEquals("10010", body(filter("/Message/orders/order/count/text()", "string", ORDERS)));
    }

    @Test
    void nodeAndNodelist() {
        assertEquals("<star>\n\t\t<class>A</class>\n\t\t<name>Deneb</name>\n\t\t<distance>1550</distance>\n\t</star>",
                body(filter("/starCatalogue/star[distance = max(/starCatalogue/star/distance)]", "node", STARS)));
        // SAP's example with function:simple('${property.class}') resolved to 'A'.
        String a = body(filter("/starCatalogue/star[(class le 'A') and (class ge 'A')]", "nodelist", STARS));
        assertEquals(3, a.split("<star>", -1).length - 1);
        assertTrue(a.startsWith("<star>") && a.contains("Sirius") && !a.contains("Sun"));
    }

    @Test
    void integerAndBoolean() {
        assertEquals("4", body(filter("count(//star)", "integer", STARS)));
        assertEquals("110", body(filter("sum(//count)", "integer", ORDERS)));
        assertEquals("true", body(filter("//star[class = 'G']", "boolean", STARS)));
        assertEquals("false", body(filter("//star[class = 'B']", "boolean", STARS)));
        assertFalse(Json.bool(filter("//distance[1]", "integer", STARS), "ok", true));
    }

    @Test
    void headersAndPropertiesAreVariables() {
        // As in real iFlows (Student[Age > $AgeLimit_Header]); Camel: the header first, then the property.
        Map<String, Object> in = Json.o().with("body", STARS)
                .with("headers", java.util.List.of(Json.o().with("name", "cls").with("value", "G")))
                .with("properties", java.util.List.of(Json.o().with("name", "cls").with("value", "A"),
                        Json.o().with("name", "max").with("value", "100").with("type", "Integer")));
        Map<String, Object> r = new FilterStep().run(Json.o().with("expression", "count(//star[class = $cls])")
                .with("valueType", "integer").with("input", in));
        assertEquals("1", body(r));
        r = new FilterStep().run(Json.o().with("expression", "count(//star[number(distance) < $max])")
                .with("valueType", "integer").with("input", in));
        assertEquals("3", body(r));
    }

    @Test
    void errors() {
        assertFalse(Json.bool(filter("count(//star)", "node", STARS), "ok", true));
        Map<String, Object> simple = filter("//star[class = function:simple('${property.class}')]", "nodelist", STARS);
        assertTrue(Json.arr(simple, "diagnostics").toString().contains("UnsupportedInSimulator"));
        assertEquals("", body(filter("//planet", "nodelist", STARS)));
    }
}
