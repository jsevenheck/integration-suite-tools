package io.cpiwb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class IflowImportTest {

    private static Path zipOf(Path dir, Path zip) throws Exception {
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os);
             Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                z.putNextEntry(new ZipEntry(dir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void importsZipAndListsSteps(@TempDir Path tmp) throws Exception {
        Path fixture = Path.of(getClass().getResource("/iflow").toURI());
        Path zip = zipOf(fixture, tmp.resolve("Order_Replication.zip"));
        Path project = Files.createDirectories(tmp.resolve("project"));
        Map<String, Object> r = IflowImport.importZip(zip, project, null);
        assertEquals("iflows/Order_Replication", r.get("imported"));
        List<Object> steps = (List<Object>) r.get("steps");
        assertEquals(10, steps.size(), () -> Json.write(r));
        Map<String, Object> g = (Map<String, Object>) steps.get(0);
        assertEquals("groovy", g.get("type"));
        assertEquals("enrich", g.get("function"));
        assertEquals("groovy-2.x", g.get("profile"));
        assertEquals("iflows/Order_Replication/src/main/resources/script/enrich.groovy", g.get("file"));
        Map<String, Object> x = (Map<String, Object>) steps.get(1);
        assertEquals("xslt", x.get("type"));
        assertEquals("iflows/Order_Replication/src/main/resources/mapping/order.xsl", x.get("file"));
        assertTrue((Boolean) x.get("fileFound"));

        Map<String, Object> blankFn = (Map<String, Object>) steps.get(2);
        assertEquals("processData", blankFn.get("function"));
        assertEquals("groovy-1.x", blankFn.get("profile"));
        Map<String, Object> dynamic = (Map<String, Object>) steps.get(3);
        assertEquals("xslt", dynamic.get("type"));
        assertEquals(false, dynamic.get("fileFound"));
        assertTrue(dynamic.get("note").toString().contains("xsltmappingname"));
        Map<String, Object> xsd = (Map<String, Object>) steps.get(4);
        assertEquals("xsd", xsd.get("type"));
        assertEquals("iflows/Order_Replication/src/main/resources/xsd/order.xsd", xsd.get("file"));

        // Content Modifier, Splitter, Gather, Filter and Router, with {{parameters}} from parameters.prop.
        Map<String, Object> cm = (Map<String, Object>) steps.get(5);
        assertEquals("contentModifier", cm.get("type"));
        Map<String, Object> cmConfig = Json.obj(cm, "config");
        assertEquals("expression", cmConfig.get("bodyType"));
        assertEquals("<Wrapped>${in.body}</Wrapped>", cmConfig.get("body"));
        Map<String, Object> h1 = (Map<String, Object>) Json.arr(cmConfig, "headers").get(0);
        assertEquals("xpath", h1.get("type"));
        assertEquals("//Order/@id", h1.get("value"));
        assertEquals("java.lang.String", h1.get("datatype"));
        assertEquals("$include=X-*", ((Map<String, Object>) Json.arr(cmConfig, "headers").get(1)).get("name"));
        assertEquals("INFO", ((Map<String, Object>) Json.arr(cmConfig, "properties").get(0)).get("value"));
        Map<String, Object> split = Json.obj((Map<String, Object>) steps.get(6), "config");
        assertEquals("general", split.get("splitter"));
        assertEquals("/Order/Item/", split.get("expression"));
        assertEquals("2", split.get("grouping"));
        Map<String, Object> gather = (Map<String, Object>) steps.get(7);
        assertEquals("gather", gather.get("type"));
        assertEquals("combineAtXPath", Json.obj(gather, "config").get("algorithm"));
        assertEquals("sameXml", Json.obj(gather, "config").get("messageType"));
        assertEquals("/Order/Item", Json.obj(gather, "config").get("sourceXPath"));
        Map<String, Object> filter = (Map<String, Object>) steps.get(8);
        assertEquals("filter", filter.get("type"));
        assertEquals("nodelist", Json.obj(filter, "config").get("valueType"));
        assertEquals("/Order/Item", Json.obj(filter, "config").get("expression"));
        Map<String, Object> router = (Map<String, Object>) steps.get(9);
        assertEquals("router", router.get("type"));
        List<Object> routes = Json.arr(Json.obj(router, "config"), "routes");
        assertEquals("ERP", ((Map<String, Object>) routes.get(0)).get("name"));
        assertEquals("nonxml", ((Map<String, Object>) routes.get(0)).get("expressionType"));
        assertEquals("${header.SenderId} = 'ERP'", ((Map<String, Object>) routes.get(0)).get("condition"));
        assertEquals(true, ((Map<String, Object>) routes.get(1)).get("default"));
    }

    @Test
    void contentModifierBodyType() throws Exception {
        org.w3c.dom.Element act = javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
                .newDocument().createElement("callActivity");
        java.util.Properties params = new java.util.Properties();
        params.setProperty("type", "Constant");
        // Versions up to 1.3 have no bodyType; their body is an expression (SAP's sample iFlows rely on it).
        Map<String, Object> old = FlowSteps.of(act, Map.of("activityType", "Enricher", "componentVersion", "1.2",
                "wrapContent", "Header = ${header.x}"), params);
        assertEquals("expression", Json.obj(old, "config").get("bodyType"));
        Map<String, Object> param = FlowSteps.of(act, Map.of("activityType", "Enricher", "bodyType", "{{type}}"), params);
        assertEquals("constant", Json.obj(param, "config").get("bodyType"));
    }

    @Test
    void rejectsZipSlip(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("../../outside.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        Path project = Files.createDirectories(tmp.resolve("project"));
        assertThrows(java.io.IOException.class, () -> IflowImport.importZip(zip, project, "evil"));
        assertTrue(Files.notExists(tmp.resolve("outside.txt")));
    }

    @Test
    void rejectsNamesThatLeaveTheIflowsFolder(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("ok.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        Path project = Files.createDirectories(tmp.resolve("project"));
        for (String name : List.of("..", ".")) {
            assertThrows(IllegalArgumentException.class, () -> IflowImport.importZip(zip, project, name), name);
        }
        assertTrue(Files.notExists(project.resolve("a.txt")));
        assertTrue(Files.notExists(project.resolve("iflows/a.txt")));
    }
}
