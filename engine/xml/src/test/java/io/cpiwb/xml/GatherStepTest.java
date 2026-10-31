package io.cpiwb.xml;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Combine reproduces SAP's result example ("Define Gather and Join"). */
@SuppressWarnings("unchecked")
class GatherStepTest {

    static final String[] NOTES = {"Jack", "Chris", "Tove"};

    static String note(String to) {
        return "<note>\n<to>" + to + "</to>\n<from>Jill</from>\n</note>";
    }

    static Map<String, Object> gather(String format, String algorithm, String source, String target, List<String> messages) {
        return new GatherStep().run(Json.o().with("messageType", format).with("algorithm", algorithm)
                .with("sourceXPath", source).with("targetXPath", target).with("messages", messages));
    }

    static String body(Map<String, Object> r) {
        assertTrue(Json.bool(r, "ok", false), () -> String.valueOf(r.get("diagnostics")));
        return (String) r.get("body");
    }

    @Test
    void combine() {
        List<String> notes = List.of(note("Jack"), note("Chris"), "<?xml version=\"1.0\"?>\n" + note("Tove"));
        String head = "<?xml version='1.0' encoding='UTF-8'?><multimap:Messages xmlns:multimap=\"http://sap.com/xi/XI/SplitAndMerge\">";
        assertEquals(head + "<multimap:Message1>" + note("Jack") + note("Chris") + note("Tove") + "</multimap:Message1></multimap:Messages>",
                body(gather("sameXml", "combine", "", "", notes)));
        assertEquals(head + "<multimap:Message1>" + note("Jack") + "</multimap:Message1><multimap:Message2>" + note("Chris")
                + "</multimap:Message2><multimap:Message3>" + note("Tove") + "</multimap:Message3></multimap:Messages>",
                body(gather("differentXml", "combine", "", "", notes)));
    }

    @Test
    void concatenate() {
        assertEquals("a\nb\n", body(gather("plainText", "concatenate", "", "", List.of("a\n", "b\n"))));
        assertFalse(Json.bool(gather("sameXml", "concatenate", "", "", List.of("<a/>")), "ok", true));
        assertFalse(Json.bool(gather("plainText", "combine", "", "", List.of("a")), "ok", true));
    }

    @Test
    void combineAtXPathRebuildsASplitDocument() {
        String order = "<PurchaseOrder><Id>1</Id><Items><Item>a</Item><Item>b</Item><Item>c</Item></Items></PurchaseOrder>";
        Map<String, Object> split = new SplitStep().run(Json.o().with("splitter", "general").with("expressionType", "xpath")
                .with("expression", "/PurchaseOrder/Items/Item").with("input", Json.o().with("body", order)));
        List<String> parts = new ArrayList<>();
        for (Object m : Json.arr(split, "messages")) {
            parts.add((String) ((Map<String, Object>) m).get("body"));
        }
        assertEquals(3, parts.size());
        // The target defaults to the source's parent (/PurchaseOrder/Items).
        assertEquals("<?xml version='1.0' encoding='UTF-8'?>" + order,
                body(gather("sameXml", "combineAtXPath", "/PurchaseOrder/Items/Item", "", parts)));
        assertFalse(Json.bool(gather("sameXml", "combineAtXPath", "/PurchaseOrder/X", "", parts), "ok", true));
        assertFalse(Json.bool(gather("sameXml", "combineAtXPath", "/PurchaseOrder/Items/Item", "/Nope", parts), "ok", true));
    }
}
