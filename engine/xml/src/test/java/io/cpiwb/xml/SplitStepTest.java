package io.cpiwb.xml;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The expected messages are SAP's examples ("General and Iterating Splitter (Examples)" and related pages). */
@SuppressWarnings("unchecked")
class SplitStepTest {

    static final String CUSTOMER = "<customer xmlns=\"http://myCustomer.com\">\n"
            + "    <customerNumber>0001</customerNumber>\n    <customerName>Paul Smith</customerName>\n"
            + "    <order id=\"100\">\n        <items>\n            <item>Shirt</item>\n            <item>Shoe</item>\n"
            + "        </items>\n    </order>\n    <order id=\"101\">\n        <items>\n            <item>Shoe</item>\n"
            + "            <item>Car</item>\n            <item>Watch</item>\n        </items>\n    </order>\n</customer>";
    static final String SHOP = "<root><shop><product><id>1</id><id>2</id></product>"
            + "<customerReview><id>3</id><id>4</id></customerReview></shop></root>";
    static final String CSV = "\"Customer Number\", \"No of Ordered Articles\", \"Price\"\n\"0001\", \"100\", \"1000 EUR\"\n"
            + "\"0009\", \"1\", \"10000 EUR\"\n\"0011\", \"20\", \"999 EUR\"\n";

    static Map<String, Object> split(String splitter, String type, String expr, Object grouping, String body,
                                     Map<String, Object> ns) {
        return new SplitStep().run(Json.o().with("splitter", splitter).with("expressionType", type)
                .with("expression", expr).with("grouping", grouping).with("namespaces", ns)
                .with("input", Json.o().with("body", body)));
    }

    static List<String> bodies(Map<String, Object> r) {
        assertTrue(Json.bool(r, "ok", false), () -> String.valueOf(r.get("diagnostics")));
        List<String> out = new ArrayList<>();
        for (Object m : Json.arr(r, "messages")) {
            out.add(((Map<String, Object>) m).get("body").toString().replaceAll(">\\s+<", "><").trim());
        }
        return out;
    }

    /** The bodies without the XML declaration the General Splitter writes. */
    static List<String> content(Map<String, Object> r) {
        List<String> out = new ArrayList<>();
        for (String b : bodies(r)) {
            out.add(b.replaceFirst("^<\\?xml[^>]*\\?>\\s*", ""));
        }
        return out;
    }

    @Test
    void generalKeepsTheEnvelope() {
        List<String> b = content(split("general", "xpath", "/n1:customer/n1:order", null, CUSTOMER,
                Json.o().with("n1", "http://myCustomer.com")));
        assertEquals(2, b.size());
        assertEquals("<customer xmlns=\"http://myCustomer.com\"><customerNumber>0001</customerNumber>"
                + "<customerName>Paul Smith</customerName><order id=\"101\"><items><item>Shoe</item><item>Car</item>"
                + "<item>Watch</item></items></order></customer>", b.get(1));
    }

    @Test
    void generalWithRelativePaths() {
        assertEquals(List.of("<root><shop><product><id>1</id><id>2</id></product><customerReview><id>3</id></customerReview></shop></root>",
                "<root><shop><product><id>1</id><id>2</id></product><customerReview><id>4</id></customerReview></shop></root>"),
                content(split("general", "xpath", "//customerReview/id", null, SHOP, null)));
        // SAP: with //id all four ids end up under the first parent, product.
        List<String> all = content(split("general", "xpath", "//id", null, SHOP, null));
        assertEquals(4, all.size());
        assertEquals("<root><shop><product><id>3</id></product></shop></root>", all.get(2));
    }

    @Test
    void generalDropsElementsAfterTheSplitPoint() {
        assertEquals(List.of("<A><B><C>1</C></B></A>", "<A><B><C>2</C></B></A>"),
                content(split("general", "xpath", "/A/B/C", "", "<A><B><C>1</C><C>2</C><E>e</E></B></A>", null)));
    }

    @Test
    void namespaceDeclaredAfterThePath() {
        // SAP: "Using XPath Expressions in the Splitter Step", Default XML Namespaces.
        String doc = "<root xmlns=\"demo.sap.com\"><shop><customerReview><id>001</id></customerReview>"
                + "<customerReview><id>002</id></customerReview></shop></root>";
        Map<String, Object> r = split("general", "xpath", "/ns1:root/shop/customerReview xmlns:ns1=\"demo.sap.com\"", null, doc, null);
        List<String> b = bodies(r);
        assertEquals(2, b.size());
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><root xmlns=\"demo.sap.com\"><shop><customerReview><id>002</id>"
                + "</customerReview></shop></root>", b.get(1));
        assertFalse(Json.bool(split("general", "xpath", "/root/shop/customerReview xmlns=\"demo.sap.com\"", null, doc, null), "ok", true));
        assertEquals("/a:x//a:y/a:z", SplitStep.inheritPrefixes("/a:x//y/z"));
        assertEquals("//id", SplitStep.inheritPrefixes("//id"));
    }

    @Test
    void iteratingWithXPathAndToken() {
        String list = "<customerList xmlns=\"http://myCustomer.com\"><customers><customerNumber>0001</customerNumber>"
                + "</customers><customers><customerNumber>0002</customerNumber></customers></customerList>";
        assertEquals("<customers xmlns=\"http://myCustomer.com\"><customerNumber>0002</customerNumber></customers>",
                bodies(split("iterating", "xpath", "/n2:customerList/n2:customers", 1, list,
                        Json.o().with("n2", "http://myCustomer.com"))).get(1));
        assertEquals(List.of("<customerName>Paul Smith</customerName>", "<customerName>Seena Kumar</customerName>"),
                bodies(split("iterating", "token", "customerName", 1, "<customerList><customers><customerName>Paul Smith"
                        + "</customerName></customers><customers><customerName>Seena Kumar</customerName></customers></customerList>", null)));
    }

    @Test
    void lineBreak() {
        List<String> general = bodies(split("general", "lineBreak", "", null, CSV, null));
        assertEquals(3, general.size());
        assertEquals("\"Customer Number\", \"No of Ordered Articles\", \"Price\"\n\"0009\", \"1\", \"10000 EUR\"", general.get(1));
        assertEquals(4, bodies(split("iterating", "lineBreak", "", null, CSV, null)).size());
        // SAP: the text \n also separates lines.
        assertEquals(general, bodies(split("general", "lineBreak", "", null, CSV.replace("\n", "\\n"), null)));
    }

    @Test
    void groupingAndSplitHeaders() {
        Map<String, Object> r = split("iterating", "xpath", "/r/i/", 2, "<r><i>1</i><i>2</i><i>3</i></r>", null);
        assertEquals(List.of("<i>1</i><i>2</i>", "<i>3</i>"), bodies(r));
        Map<String, Object> first = (Map<String, Object>) Json.arr(r, "messages").get(0);
        Map<String, Object> last = (Map<String, Object>) Json.arr(r, "messages").get(1);
        assertEquals(false, first.get("complete"));
        assertFalse(first.containsKey("size"));
        assertEquals(2, last.get("size"));
    }

    @Test
    void noMatchAndUnsupportedXPath() {
        Map<String, Object> none = split("general", "xpath", "/r/x", null, "<r><i/></r>", null);
        assertEquals(0, bodies(none).size());
        assertTrue(Json.arr(none, "diagnostics").toString().contains("Completed"));
        assertFalse(Json.bool(split("general", "xpath", "/r/i[1]", null, "<r><i/></r>", null), "ok", true));
        assertFalse(Json.bool(split("general", "xpath", "/r/i", "0", "<r><i/></r>", null), "ok", true));
    }
}
