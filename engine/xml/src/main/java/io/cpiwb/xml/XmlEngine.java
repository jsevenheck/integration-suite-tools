package io.cpiwb.xml;

import io.cpiwb.core.Json;
import io.cpiwb.core.ProjectFiles;
import io.cpiwb.core.RpcHandler;
import io.cpiwb.core.TestRunner;
import io.cpiwb.core.TypedValue;
import io.cpiwb.xml.compat.SaxonCompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RPC dispatcher of the native XML engine (XSLT, XSD, XPath, project files, test runner). */
public final class XmlEngine implements RpcHandler {

    public static final String VERSION = "0.1.1";

    private final XsltStep xslt = new XsltStep();
    private final XsdStep xsd = new XsdStep();
    private final XPathStep xpath = new XPathStep();
    private final SplitStep split = new SplitStep();
    private final FilterStep filter = new FilterStep();
    private final GatherStep gather = new GatherStep();
    private final ProjectFiles files = new ProjectFiles();

    @Override
    public Map<String, Object> describe() {
        return Json.o()
                .with("name", "cpiwb-xml")
                .with("version", VERSION)
                .with("profile", SaxonCompat.PROFILE)
                .with("profileLabel", SaxonCompat.PROFILE_LABEL)
                .with("saxon", XsltStep.engineVersion())
                .with("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"))
                .with("nativeImage", System.getProperty("org.graalvm.nativeimage.imagecode") != null)
                .with("types", TypedValue.TYPES)
                .with("methods", List.of("engine.info", "xslt.run", "xsd.validate", "xpath.eval", "split.run", "filter.run", "gather.run", "xml.format",
                        "project.open", "project.close", "project.list", "file.read", "file.write", "file.delete", "iflow.import", "iflow.steps", "tests.run"));
    }

    @Override
    public Object call(String method, Map<String, Object> params) throws Exception {
        switch (method) {
            case "engine.info":
            case "ping":
                return describe();
            case "xslt.run":
                return xslt.run(withProjectRoot(params));
            case "xsd.validate":
                return xsd.run(withProjectRoot(params));
            case "xpath.eval":
                return xpath.run(withProjectRoot(params));
            case "split.run":
                return split.run(withProjectRoot(params));
            case "filter.run":
                return filter.run(withProjectRoot(params));
            case "gather.run":
                return gather.run(withProjectRoot(params));
            case "xml.format":
                return xpath.format(params);
            case "project.open":
                return files.open(params);
            case "project.close":
                return files.close();
            case "project.list":
                return files.listing();
            case "file.read":
                return files.read(params);
            case "file.write":
                return files.write(params);
            case "file.delete":
                return files.delete(params);
            case "iflow.import":
                return files.iflowImport(params);
            case "iflow.steps":
                return files.iflowSteps();
            case "tests.run":
                return runTests(params);
            default:
                throw new IllegalArgumentException("Unknown method '" + method + "'");
        }
    }

    /** Uses the open project as baseDir when the caller did not pass one. */
    private Map<String, Object> withProjectRoot(Map<String, Object> params) {
        if (params.get("baseDir") == null && files.root() != null) {
            Map<String, Object> copy = new LinkedHashMap<>(params);
            copy.put("baseDir", files.root().toString());
            return copy;
        }
        return params;
    }

    private Map<String, Object> runTests(Map<String, Object> params) throws IOException {
        String p = Json.str(params, "path", null);
        Path target = p == null ? files.root().resolve("tests")
                : (files.root() != null && !Path.of(p).isAbsolute() ? files.resolve(p) : Path.of(p));
        return runTests(target);
    }

    public Map<String, Object> runTests(Path target) throws IOException {
        TestRunner runner = testRunner();
        List<Object> results = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        for (Path c : TestRunner.findCases(target)) {
            TestRunner.CaseResult r = runner.run(c);
            results.add(r.toJson());
            switch (r.status) {
                case "passed": passed++; break;
                case "skipped": skipped++; break;
                default: failed++;
            }
        }
        return Json.o().with("results", results).with("passed", passed).with("failed", failed).with("skipped", skipped);
    }

    public TestRunner testRunner() {
        return new TestRunner(this, SaxonCompat.PROFILE)
                .register("xslt", (tc, dir, root, params) -> {
                    params.put("xslt", ref(tc.get("xslt"), dir, root));
                    commonParams(tc, dir, root, params);
                    return "xslt.run";
                })
                .register("xsd", (tc, dir, root, params) -> {
                    params.put("schema", ref(tc.get("schema"), dir, root));
                    commonParams(tc, dir, root, params);
                    return "xsd.validate";
                });
    }

    private static void commonParams(Map<String, Object> tc, Path dir, Path root, Map<String, Object> params)
            throws IOException {
        params.put("baseDir", root.toString());
        Map<String, Object> in = Json.obj(tc, "input");
        Map<String, Object> input = new LinkedHashMap<>();
        if (in != null) {
            input.put("body", TestRunner.readText(in.get("body"), dir));
            input.put("headers", in.get("headers"));
            input.put("properties", in.get("properties"));
        }
        params.put("input", input);
        if (tc.get("options") != null) {
            params.put("options", tc.get("options"));
        }
    }

    /** A mapping/schema reference in a case file: path relative to the case dir → project path. */
    private static Map<String, Object> ref(Object value, Path dir, Path root) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Case file does not name the mapping/schema");
        }
        Path file = dir.resolve(value.toString()).normalize();
        String rel = root.relativize(file).toString().replace('\\', '/');
        return Json.o().with("path", rel).with("content", Files.readString(file, StandardCharsets.UTF_8));
    }
}
