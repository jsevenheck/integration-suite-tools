package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs on both engines; CPI's spellings must give the same result on Camel 2 and 3. */
@SuppressWarnings("unchecked")
class SimpleStepTest {

    static Map<String, Object> eval(String mode, String expr) throws Exception {
        Map<String, Object> input = ScriptRunnerTest.in("<Order id=\"4711\"/>",
                List.of(ScriptRunnerTest.hv("SenderId", "1", "String"), ScriptRunnerTest.hv("Count", "5", "Integer")),
                List.of(ScriptRunnerTest.hv("Partner", "ACME", "String")));
        return (Map<String, Object>) ScriptRunnerTest.ENGINE.call("simple.eval",
                Json.o().with("expression", expr).with("mode", mode).with("input", input));
    }

    static String value(Map<String, Object> r) {
        assertTrue(Json.bool(r, "ok", false), () -> String.valueOf(r.get("diagnostics")));
        return (String) r.get("value");
    }

    @Test
    void routerOperatorsFromSapsTable() throws Exception {
        assertEquals("true", value(eval("condition", "${header.SenderId} = '1'")));
        assertEquals("false", value(eval("condition", "${header.SenderId} != '1'")));
        assertEquals("true", value(eval("condition", "${header.SenderId}= '1' and ${header.Count} > 3")));
        assertEquals("true", value(eval("condition", "${header.SenderId} in '1,2'")));
        assertEquals("true", value(eval("condition", "${header.SenderId} regex '1.*'")));
        assertEquals("true", value(eval("condition", "${header.SenderId} not contains '2'")));
        assertEquals("true", value(eval("condition", "${property.Partner} = 'ACME'")));
        assertEquals("true", value(eval("condition", "${header.SenderId} = '2' or ${header.Count} <= 5")));
        assertEquals("${header.SenderId} == '1'", eval("condition", "${header.SenderId} = '1'").get("evaluated"));
        assertNull(eval("condition", "${header.SenderId} == '1'").get("evaluated"));
    }

    @Test
    void equalsInsideQuotesAndFunctionsStays() {
        assertEquals("${header.a} == 'x=y or z' || ${header.b} >= 2",
                SimpleStep.operatorsToCamel("${header.a} = 'x=y or z' or ${header.b} >= 2", true));
        assertEquals("${header.a} == 1 and ${header.order} != 2", SimpleStep.operatorsToCamel("${header.a} = 1 and ${header.order} != 2", false));
        assertEquals("${body} =~ 'abc' && ${header.android} == 1", SimpleStep.operatorsToCamel("${body} =~ 'abc' and ${header.android} = 1", true));
    }

    @Test
    void contentModifierExpression() throws Exception {
        assertEquals("1-ACME", value(eval("expression", "${header.SenderId}-${property.Partner}")));
        assertEquals("<Order id=\"4711\"/>", value(eval("expression", "${in.body}")));
        assertEquals(4, value(eval("expression", "${date:now:yyyy}")).length());
        // No operators in a Content Modifier: "=" stays text.
        assertEquals("1 = 1", value(eval("expression", "${header.SenderId} = 1")));
    }

    @Test
    void errorsAndWarnings() throws Exception {
        Map<String, Object> r = eval("condition", "${header.SenderId} = ");
        assertFalse(Json.bool(r, "ok", true));
        assertFalse(Json.bool(eval("condition", " "), "ok", true));
        assertEquals("true", value(eval("condition", "${exchangeProperty.Partner} == 'ACME'")));
        // OGNL calls, as in SAP's Content Modifier table (Camel 3 needs camel-bean for them).
        assertEquals("acme", value(eval("expression", "${property.Partner.toLowerCase()}")));
        assertEquals("true", value(eval("condition", "${in.header.SenderId.length} = 1")));
    }
}
