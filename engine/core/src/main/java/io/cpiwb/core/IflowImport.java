package io.cpiwb.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports an exported iFlow (zip) into the project and lists its Groovy Script and XSLT Mapping
 * steps. The .iflw format is not publicly specified; this reads the BPMN call activities and their
 * key/value properties generically and resolves referenced files by name, so it degrades gracefully
 * when SAP changes property names.
 */
public final class IflowImport {

    private static final long MAX_UNZIPPED = 200L * 1024 * 1024;

    private IflowImport() {
    }

    /** Extracts {@code zip} into {@code projectRoot/iflows/<name>/} and returns the steps found. */
    public static Map<String, Object> importZip(Path zip, Path projectRoot, String name) throws IOException {
        if (!Files.isRegularFile(zip)) {
            throw new IllegalArgumentException("Not a file: " + zip);
        }
        String n = name == null || name.isBlank() ? zip.getFileName().toString().replaceFirst("\\.zip$", "") : name;
        n = n.replaceAll("[^A-Za-z0-9._-]+", "_");
        Path iflows = projectRoot.resolve("iflows").normalize();
        Path target = iflows.resolve(n).normalize();
        if (!target.startsWith(iflows) || target.equals(iflows)) {
            throw new IllegalArgumentException("Invalid iFlow name: " + n);
        }
        Files.createDirectories(target);
        long total = 0;
        int files = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = target.resolve(e.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("Zip entry outside the target folder: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                total += Files.copy(limited(in, MAX_UNZIPPED - total), out, StandardCopyOption.REPLACE_EXISTING);
                files++;
            }
        }
        Map<String, Object> r = steps(projectRoot);
        r.put("imported", projectRoot.relativize(target).toString().replace('\\', '/'));
        r.put("files", files);
        return r;
    }

    /** The current entry, failing as soon as the archive's unpacked size passes the limit. */
    private static InputStream limited(ZipInputStream in, long budget) {
        return new java.io.FilterInputStream(in) {
            private long left = budget;

            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b >= 0) {
                    count(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n = in.read(buf, off, len);
                if (n > 0) {
                    count(n);
                }
                return n;
            }

            private void count(int n) throws IOException {
                left -= n;
                if (left < 0) {
                    throw new IOException("iFlow archive is larger than " + (MAX_UNZIPPED >> 20) + " MB unpacked");
                }
            }

            @Override
            public void close() {
                // keep the zip stream open
            }
        };
    }

    /** Scans {@code iflows/} for .iflw files and lists script and XSLT steps. */
    public static Map<String, Object> steps(Path projectRoot) throws IOException {
        List<Object> steps = new ArrayList<>();
        Path iflows = projectRoot.resolve("iflows");
        List<Path> models = new ArrayList<>();
        if (Files.isDirectory(iflows)) {
            try (Stream<Path> s = Files.walk(iflows, 12)) {
                s.filter(p -> p.toString().endsWith(".iflw")).sorted().forEach(models::add);
            }
        }
        for (Path model : models) {
            Path flowRoot = flowRoot(iflows, model);
            try {
                steps.addAll(parse(model, flowRoot, projectRoot));
            } catch (Exception ex) {
                steps.add(Json.o().with("iflow", projectRoot.relativize(model).toString()).with("type", "error")
                        .with("name", "Cannot read iFlow model: " + ex.getMessage()));
            }
        }
        return Json.o().with("steps", steps).with("models", models.size());
    }

    private static Path flowRoot(Path iflows, Path model) {
        Path rel = iflows.relativize(model);
        return rel.getNameCount() > 0 ? iflows.resolve(rel.getName(0)) : iflows;
    }

    private static List<Object> parse(Path model, Path flowRoot, Path projectRoot) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(model.toFile());
        List<Object> out = new ArrayList<>();
        Properties params = parameters(flowRoot);
        NodeList all = doc.getElementsByTagNameNS("*", "callActivity");
        for (int i = 0; i < all.getLength(); i++) {
            Element act = (Element) all.item(i);
            Map<String, String> props = properties(act);
            Json.Obj flowStep = FlowSteps.of(act, props, params);
            if (flowStep != null) {
                out.add(flowStep.with("iflow", flowRoot.getFileName().toString()));
                continue;
            }
            String type = classify(props);
            if (type == null) {
                continue;
            }
            String file = null;
            for (String v : props.values()) {
                if (hasExtension(type, v.toLowerCase(Locale.ROOT))) {
                    file = v;
                    break;
                }
            }
            Path resolved = file == null ? null : resolve(flowRoot, file);
            Json.Obj step = Json.o()
                    .with("iflow", flowRoot.getFileName().toString())
                    .with("id", act.getAttribute("id"))
                    .with("name", act.getAttribute("name"))
                    .with("type", type)
                    .with("componentVersion", props.get("componentVersion"))
                    .with("file", resolved == null ? file : projectRoot.relativize(resolved).toString().replace('\\', '/'))
                    .with("fileFound", resolved != null);
            String note = note(type, props, file);
            if (note != null) {
                step.with("note", note);
            }
            if ("groovy".equals(type)) {
                String fn = props.get("scriptFunction");
                step.with("function", fn == null || fn.isBlank() ? "processData" : fn.trim());
                step.with("profile", majorVersion(props.get("componentVersion")) >= 2 ? "groovy-2.x" : "groovy-1.x");
            }
            out.add(step);
        }
        NodeList gateways = doc.getElementsByTagNameNS("*", "exclusiveGateway");
        for (int i = 0; i < gateways.getLength(); i++) {
            Element gw = (Element) gateways.item(i);
            out.add(FlowSteps.router(doc, gw, properties(gw), params).with("iflow", flowRoot.getFileName().toString()));
        }
        return out;
    }

    /** The iFlow's externalized parameters (src/main/resources/parameters.prop), if it has them. */
    private static Properties parameters(Path flowRoot) throws IOException {
        Properties p = new Properties();
        Path file = flowRoot.resolve("src/main/resources/parameters.prop");
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        return p;
    }

    /** ifl:property children: {@code <key>..</key><value>..</value>}. */
    static Map<String, String> properties(Element act) {
        Map<String, String> props = new LinkedHashMap<>();
        NodeList ps = act.getElementsByTagNameNS("*", "property");
        for (int j = 0; j < ps.getLength(); j++) {
            String key = null;
            String value = null;
            for (Node c = ps.item(j).getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                String ln = c.getLocalName() == null ? c.getNodeName() : c.getLocalName();
                if ("key".equals(ln)) {
                    key = c.getTextContent().trim();
                } else if ("value".equals(ln)) {
                    value = c.getTextContent().trim();
                }
            }
            if (key != null) {
                props.put(key, value == null ? "" : value);
            }
        }
        return props;
    }

    /**
     * Step type from the activity properties (as seen in SAP's public sample iFlows:
     * {@code activityType} Script/Mapping/XmlValidator, {@code subActivityType} GroovyScript/XSLTMapping,
     * {@code cmdVariantUri} ...cname::GroovyScript...), else from referenced file extensions.
     */
    private static String classify(Map<String, String> props) {
        String all = (props.getOrDefault("activityType", "") + " " + props.getOrDefault("subActivityType", "")
                + " " + props.getOrDefault("cmdVariantUri", "")).toLowerCase(Locale.ROOT);
        if (all.contains("javascript")) {
            return null;
        }
        if (all.contains("groovy") || all.contains("script")) {
            return "groovy";
        }
        if (all.contains("xslt")) {
            return "xslt";
        }
        if (all.contains("xmlvalidator")) {
            return "xsd";
        }
        for (String type : List.of("groovy", "xslt")) {
            for (String v : props.values()) {
                if (hasExtension(type, v.toLowerCase(Locale.ROOT))) {
                    return type;
                }
            }
        }
        return null;
    }

    private static boolean hasExtension(String type, String lowerValue) {
        switch (type) {
            case "groovy":
                return lowerValue.endsWith(".groovy") || lowerValue.endsWith(".gsh");
            case "xslt":
                return lowerValue.endsWith(".xsl") || lowerValue.endsWith(".xslt");
            case "xsd":
                return lowerValue.endsWith(".xsd");
            default:
                return false;
        }
    }

    /** Explains a step whose file cannot be opened directly. */
    private static String note(String type, Map<String, String> props, String file) {
        String source = props.getOrDefault("mappingSource", "");
        if ("xslt".equals(type) && "mappingSrcHeader".equals(source)) {
            return "The mapping is chosen at runtime from the header "
                    + props.getOrDefault("mappingHeaderNameKey", "(unknown)") + "; open the stylesheet yourself.";
        }
        if (file == null) {
            return "xsd".equals(type) ? "No schema file referenced in the step."
                    : "No file referenced in the step (not configured, or from another artifact).";
        }
        return null;
    }

    /** Resolves a referenced file: as a path inside the flow, else by file name below src/main/resources. */
    private static Path resolve(Path flowRoot, String ref) throws IOException {
        String clean = ref.replaceFirst("^[a-z]+://", "").replace('\\', '/').replaceFirst("^/+", "");
        for (Path base : List.of(flowRoot, flowRoot.resolve("src/main/resources"))) {
            Path p = base.resolve(clean).normalize();
            if (p.startsWith(flowRoot) && Files.isRegularFile(p)) {
                return p;
            }
        }
        String fileName = clean.substring(clean.lastIndexOf('/') + 1);
        try (Stream<Path> s = Files.walk(flowRoot, 12)) {
            return s.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        }
    }

    private static int majorVersion(String v) {
        if (v == null || v.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(v.trim().split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
