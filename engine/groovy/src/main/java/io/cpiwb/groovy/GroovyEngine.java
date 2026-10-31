package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import io.cpiwb.core.ProjectFiles;
import io.cpiwb.core.RpcHandler;
import io.cpiwb.core.TestRunner;
import io.cpiwb.core.TypedValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RPC dispatcher of a Groovy engine (one per Script API generation). */
public final class GroovyEngine implements RpcHandler {

    public static final String VERSION = "0.1.1";

    private final ScriptRunner runner;
    private final SimpleStep simple = new SimpleStep();
    private final ContentModifierStep contentModifier = new ContentModifierStep();
    private final String profile;
    private final ProjectFiles files = new ProjectFiles();

    public GroovyEngine(String apiVersion, Class<? extends MessageSupport> messageClass) {
        this.runner = new ScriptRunner(apiVersion, messageClass);
        this.profile = "v1".equals(apiVersion) ? "groovy-1.x" : "groovy-2.x";
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> info = runner.engineInfo();
        return Json.o()
                .with("name", "cpiwb-groovy")
                .with("version", VERSION)
                .with("profile", profile)
                .with("profileLabel", "groovy-1.x".equals(profile) ? "Groovy Script 1.x (Groovy " + info.get("groovy") + ")"
                        : "Groovy Script 2.x (Groovy " + info.get("groovy") + ")")
                .with("groovy", info.get("groovy"))
                .with("camel", info.get("camel"))
                .with("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"))
                .with("nativeImage", false)
                .with("types", TypedValue.TYPES)
                .with("methods", List.of("engine.info", "groovy.run", "simple.eval", "contentModifier.run", "project.open", "project.close", "project.list", "file.read",
                        "file.write", "file.delete", "iflow.import", "iflow.steps", "tests.run"));
    }

    @Override
    public Object call(String method, Map<String, Object> params) throws Exception {
        switch (method) {
            case "engine.info":
            case "ping":
                return describe();
            case "groovy.run":
                return runner.run(withProjectRoot(params));
            case "simple.eval":
                return simple.run(params);
            case "contentModifier.run":
                return contentModifier.run(params);
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
                String p = Json.str(params, "path", null);
                Path target = p == null ? files.root().resolve("tests")
                        : (files.root() != null && !Path.of(p).isAbsolute() ? files.resolve(p) : Path.of(p));
                return runTests(target);
            default:
                throw new IllegalArgumentException("Unknown method '" + method + "'");
        }
    }

    /**
     * Adds the open project as base directory. A test case opened in the app sends its attachments as
     * written in case.json; {"file"} entries are read here, relative to {@code caseDir}.
     */
    private Map<String, Object> withProjectRoot(Map<String, Object> params) throws IOException {
        if (files.root() == null) {
            return params;
        }
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.putIfAbsent("baseDir", files.root().toString());
        Map<String, Object> in = Json.obj(params, "input");
        if (in != null && in.get("attachments") != null) {
            Map<String, Object> input = new LinkedHashMap<>(in);
            Path dir = files.root().resolve(Json.str(params, "caseDir", "")).normalize();
            input.put("attachments", attachments(Json.arr(in, "attachments"), dir, files.root()));
            copy.put("input", input);
        }
        return copy;
    }

    /** Test-case attachments: {"file": "..."} entries are read relative to the case and sent as Base64. */
    @SuppressWarnings("unchecked")
    private static List<Object> attachments(List<Object> list, Path dir, Path root) throws IOException {
        List<Object> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
            Object file = m.remove("file");
            if (file != null) {
                Path p = dir.resolve(file.toString()).normalize();
                if (!p.startsWith(root)) {
                    throw new IllegalArgumentException("Attachment file is outside the project: " + file);
                }
                m.put("base64", java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
                m.putIfAbsent("name", p.getFileName().toString());
            }
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> runTests(Path target) throws IOException {
        TestRunner tr = new TestRunner(this, profile).register("groovy", (tc, dir, root, params) -> {
            Object ref = tc.get("script");
            if (ref == null) {
                throw new IllegalArgumentException("Case file does not name the script");
            }
            Path file = dir.resolve(ref.toString()).normalize();
            params.put("script", Json.o().with("path", root.relativize(file).toString().replace('\\', '/'))
                    .with("content", Files.readString(file, StandardCharsets.UTF_8)));
            params.put("baseDir", root.toString());
            if (tc.get("function") != null) {
                params.put("function", tc.get("function"));
            }
            Map<String, Object> in = Json.obj(tc, "input");
            Map<String, Object> input = new LinkedHashMap<>();
            if (in != null) {
                input.put("body", TestRunner.readText(in.get("body"), dir));
                input.put("headers", in.get("headers"));
                input.put("properties", in.get("properties"));
                if (in.get("soapHeaders") != null) {
                    input.put("soapHeaders", in.get("soapHeaders"));
                }
                if (in.get("attachments") != null) {
                    input.put("attachments", attachments(Json.arr(in, "attachments"), dir, root));
                }
            }
            params.put("input", input);
            for (String k : List.of("options", "platform", "classpath")) {
                if (tc.get(k) != null) {
                    params.put(k, tc.get(k));
                }
            }
            return "groovy.run";
        });
        List<Object> results = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        for (Path c : TestRunner.findCases(target)) {
            TestRunner.CaseResult r = tr.run(c);
            results.add(r.toJson());
            switch (r.status) {
                case "passed": passed++; break;
                case "skipped": skipped++; break;
                default: failed++;
            }
        }
        return Json.o().with("results", results).with("passed", passed).with("failed", failed).with("skipped", skipped);
    }
}
