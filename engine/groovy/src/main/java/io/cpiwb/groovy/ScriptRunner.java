package io.cpiwb.groovy;

import com.sap.gateway.ip.core.customdev.util.SoapHeader;
import com.sap.it.api.msglog.MessageLogFactory;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.GroovySystem;
import groovy.lang.Script;
import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import io.cpiwb.core.TypedValue;
import io.cpiwb.groovy.camel.Exchanges;
import org.apache.camel.Exchange;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.syntax.SyntaxException;

import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates the Cloud Integration <em>Groovy Script</em> step: compiles the script with the
 * profile's Groovy version, calls the configured function with a CPI {@code Message} backed by a
 * Camel exchange and reports body, headers, properties, MPL entries, console output and errors.
 */
public final class ScriptRunner {

    /** Script API generation of this engine: "v1" (Groovy 2.4) or "v2" (Groovy 4). */
    private final String apiVersion;
    private final Class<? extends MessageSupport> defaultMessageClass;
    private final Map<String, Class<?>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
            return size() > 32;
        }
    };

    public ScriptRunner(String apiVersion, Class<? extends MessageSupport> defaultMessageClass) {
        this.apiVersion = apiVersion;
        this.defaultMessageClass = defaultMessageClass;
    }

    public synchronized Map<String, Object> run(Map<String, Object> req) throws Exception {
        long t0 = System.nanoTime();
        List<Diagnostic> diags = new ArrayList<>();
        Json.Obj result = Json.o();
        Map<String, Object> scriptRef = Json.obj(req, "script");
        if (scriptRef == null) {
            throw new IllegalArgumentException("Missing 'script'");
        }
        String baseDirStr = Json.str(req, "baseDir", null);
        Path baseDir = baseDirStr == null || baseDirStr.isBlank() ? null : Path.of(baseDirStr).toAbsolutePath().normalize();
        String path = Json.str(scriptRef, "path", "script.groovy");
        String source = scriptRef.get("content") != null ? Json.str(scriptRef, "content", "")
                : Files.readString(project(baseDir, path), StandardCharsets.UTF_8);
        String function = Json.str(req, "function", "processData");
        Map<String, Object> options = Json.obj(req, "options");

        Platform platform = Platform.fromJson(platformData(req, baseDir), baseDir);
        Platform.setCurrent(platform);
        lint(source, apiVersion, diags);

        // ---- compile (cached by source, name and classpath)
        List<String> classpath = new ArrayList<>();
        for (Object o : Json.arr(req, "classpath")) {
            classpath.add(o.toString());
        }
        classpath.addAll(libraryJars(baseDir, path));
        String className = className(path);
        Class<?> scriptClass;
        boolean cached;
        String key = hash(className + "\0" + source + "\0" + jarStamps(baseDir, classpath) + "\0" + baseDir);
        long tc = System.nanoTime();
        scriptClass = cache.get(key);
        cached = scriptClass != null;
        if (scriptClass == null) {
            try {
                scriptClass = compile(source, className, classpath, baseDir);
                cache.put(key, scriptClass);
            } catch (MultipleCompilationErrorsException e) {
                compileDiagnostics(e, apiVersion, diags);
                return finish(result, false, "compile", diags, null, null, platform, "", t0, 0, false, null);
            } catch (CompilationFailedException e) {
                diags.add(Diagnostic.error(e.getMessage()));
                return finish(result, false, "compile", diags, null, null, platform, "", t0, 0, false, null);
            }
        }
        double compileMs = (System.nanoTime() - tc) / 1_000_000.0;

        Method fn = findFunction(scriptClass, function);
        if (fn == null) {
            diags.add(Diagnostic.error("Function '" + function + "' with one parameter was not found in the script. "
                    + "The Groovy Script step calls " + function + "(message)."));
            return finish(result, false, "compile", diags, null, null, platform, "", t0, compileMs, cached, null);
        }

        // ---- build the message
        SimExchange in = SimExchange.fromJson(Json.obj(req, "input"));
        Exchange exchange = Exchanges.create();
        String bodyType = Json.str(options, "bodyType", "String");
        exchange.getIn().setBody(inputBody(in, bodyType));
        exchange.getIn().getHeaders().putAll(in.getHeaders());
        exchange.getProperties().putAll(in.getProperties());
        MessageSupport<?> message = newMessage(fn.getParameterTypes()[0], exchange, diags);
        message.setAttachments(attachments(Json.obj(req, "input")));
        List<SoapHeader> inSoap = soapHeaders(Json.obj(req, "input"));
        if (message instanceof com.sap.gateway.ip.core.customdev.util.Message) {
            ((com.sap.gateway.ip.core.customdev.util.Message) message).setSoapHeaders(inSoap);
        } else if (!inSoap.isEmpty()) {
            diags.add(Diagnostic.info("The input SOAP headers are not passed to the script: the Script API v2 Message "
                    + "has no getSoapHeaders()."));
        }

        // ---- run
        StringWriter console = new StringWriter();
        PrintWriter out = new PrintWriter(console, true);
        Binding binding = new Binding();
        binding.setVariable("messageLogFactory", new MessageLogFactory());
        binding.setVariable("out", out);
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream sysOut = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(sysOut, true, StandardCharsets.UTF_8);
        Object returned = null;
        boolean ok = true;
        long tr = System.nanoTime();
        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try {
            System.setOut(capture);
            System.setErr(capture);
            Thread.currentThread().setContextClassLoader(scriptClass.getClassLoader());
            Script script = InvokerHelper.createScript(scriptClass, binding);
            returned = script.invokeMethod(function, new Object[] {message});
        } catch (Throwable e) {
            ok = false;
            diags.add(runtimeDiagnostic(e, className));
            result.with("stackTrace", stackTrace(e, className));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            Thread.currentThread().setContextClassLoader(oldCl);
        }
        double runMs = (System.nanoTime() - tr) / 1_000_000.0;
        out.flush();
        String consoleText = console + sysOut.toString(StandardCharsets.UTF_8);
        if (returned != null && !(returned instanceof MessageSupport)) {
            diags.add(Diagnostic.warning("The function returned " + returned.getClass().getName()
                    + " instead of the message; the simulator continues with the message object that was passed in."));
        }
        result.with("runMs", runMs);
        Json.Obj output = ok ? describeOutput(exchange, in.getCharset(), diags) : null;
        Json.Obj changes = null;
        if (ok) {
            changes = Json.o()
                    .with("headers", SimExchange.diff(in.getHeaders(), exchange.getIn().getHeaders()))
                    .with("properties", SimExchange.diff(in.getProperties(), exchange.getProperties()));
        }
        return finish(result, ok, ok ? null : "runtime", diags, output, changes, platform, consoleText, t0,
                compileMs, cached, message);
    }

    /** Input SOAP headers: [{name: "{namespace}local", value: "<header element/>", mustUnderstand, actor}]. */
    @SuppressWarnings("unchecked")
    private static List<SoapHeader> soapHeaders(Map<String, Object> input) {
        List<SoapHeader> out = new ArrayList<>();
        for (Object o : Json.arr(input, "soapHeaders")) {
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                String name = Json.str(m, "name", "header");
                try {
                    out.add(new SoapHeader(QName.valueOf(name), Json.str(m, "value", null),
                            Json.bool(m, "mustUnderstand", false), Json.str(m, "actor", null)));
                } catch (XMLStreamException e) {
                    throw new IllegalArgumentException("Input SOAP header " + name + ": " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    /** Input attachments: [{name, contentType, text | base64}]. */
    @SuppressWarnings("unchecked")
    private static Map<String, javax.activation.DataHandler> attachments(Map<String, Object> input) {
        Map<String, javax.activation.DataHandler> out = new LinkedHashMap<>();
        for (Object o : Json.arr(input, "attachments")) {
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                String name = Json.str(m, "name", "attachment" + (out.size() + 1));
                String type = Json.str(m, "contentType", "application/octet-stream");
                byte[] data = m.get("base64") != null
                        ? java.util.Base64.getMimeDecoder().decode(Json.str(m, "base64", ""))
                        : Json.str(m, "text", "").getBytes(StandardCharsets.UTF_8);
                out.put(name, new javax.activation.DataHandler(new BytesDataSource(name, type, data)));
            }
        }
        return out;
    }

    /** In-memory attachment content (javax.activation has no byte-array data source of its own). */
    private record BytesDataSource(String name, String contentType, byte[] data) implements javax.activation.DataSource {
        public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        public java.io.OutputStream getOutputStream() throws java.io.IOException {
            throw new java.io.IOException("Read-only attachment");
        }
        public String getContentType() { return contentType; }
        public String getName() { return name; }
    }

    private static String xml(Element element) {
        if (element == null) {
            return null;
        }
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(element), new StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            return element.toString();
        }
    }

    // ---------------------------------------------------------------- compile

    /**
     * Libraries a script sees besides the runtime: jars in the project's {@code lib/} folder and, for
     * a script of an imported iFlow, the iFlow's own {@code src/main/resources/lib/} (where CPI keeps
     * archives uploaded as iFlow resources).
     */
    static List<String> libraryJars(Path baseDir, String scriptPath) throws java.io.IOException {
        List<String> out = new ArrayList<>();
        if (baseDir == null) {
            return out;
        }
        List<Path> dirs = new ArrayList<>();
        dirs.add(baseDir.resolve("lib"));
        String p = scriptPath.replace('\\', '/');
        if (p.startsWith("iflows/") && p.indexOf('/', 7) > 0) {
            dirs.add(baseDir.resolve(p.substring(0, p.indexOf('/', 7))).resolve("src/main/resources/lib"));
        }
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                    s.filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                            .sorted()
                            .forEach(f -> out.add(baseDir.relativize(f).toString().replace('\\', '/')));
                }
            }
        }
        return out;
    }

    /** Class path plus modification times, so a replaced jar is picked up. */
    private static String jarStamps(Path baseDir, List<String> classpath) {
        StringBuilder sb = new StringBuilder();
        for (String jar : classpath) {
            sb.append(jar).append('@');
            try {
                sb.append(Files.getLastModifiedTime(project(baseDir, jar)).toMillis());
            } catch (Exception e) {
                sb.append('?');
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private Class<?> compile(String source, String className, List<String> classpath, Path baseDir) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String jar : classpath) {
            urls.add(shadowCopy(project(baseDir, jar)).toUri().toURL());
        }
        ClassLoader parent = getClass().getClassLoader();
        if (!urls.isEmpty()) {
            parent = new URLClassLoader(urls.toArray(new URL[0]), parent);
        }
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.setSourceEncoding("UTF-8");
        GroovyClassLoader gcl = new GroovyClassLoader(parent, cfg);
        GroovyCodeSource code = new GroovyCodeSource(source, className + ".groovy", "/groovy/script");
        code.setCachable(false);
        return gcl.parseClass(code);
    }

    /**
     * The class loader reads a copy of each jar, never the project's file: an open jar is locked on
     * Windows, so the user could not replace or delete it while the engine runs. Copies live in one
     * temp folder per engine process (named with its PID); folders of processes that have ended are
     * removed.
     */
    private static Path shadowCopy(Path jar) throws java.io.IOException {
        synchronized (ScriptRunner.class) {
            if (shadowDir == null) {
                Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
                try (java.util.stream.Stream<Path> old = Files.list(tmp)) {
                    old.filter(ScriptRunner::abandoned).forEach(ScriptRunner::deleteQuietly);
                } catch (java.io.IOException ignored) {
                    // best effort
                }
                shadowDir = Files.createTempDirectory(SHADOW_PREFIX + ProcessHandle.current().pid() + "-");
            }
            Files.createDirectories(shadowDir);
        }
        Path copy = shadowDir.resolve(hash(jar + "\0" + Files.getLastModifiedTime(jar).toMillis() + "\0" + Files.size(jar))
                .substring(0, 24) + ".jar");
        if (!Files.exists(copy)) {
            Path part = Files.createTempFile(shadowDir, "copy", ".part");
            Files.copy(jar, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return copy;
    }

    private static final String SHADOW_PREFIX = "cpiwb-lib-";
    private static Path shadowDir;

    /** A shadow folder whose engine process is no longer running. */
    private static boolean abandoned(Path dir) {
        String name = dir.getFileName().toString();
        if (!name.startsWith(SHADOW_PREFIX)) {
            return false;
        }
        String[] parts = name.substring(SHADOW_PREFIX.length()).split("-", 2);
        try {
            return ProcessHandle.of(Long.parseLong(parts[0])).map(h -> !h.isAlive()).orElse(true);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Deletes a folder tree; files that are still locked stay. */
    private static void deleteQuietly(Path dir) {
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (java.io.IOException ignored) {
                    // in use
                }
            });
        } catch (java.io.IOException ignored) {
            // gone already
        }
    }

    private static Path project(Path baseDir, String relative) {
        if (baseDir == null) {
            throw new IllegalArgumentException("No project folder: cannot read " + relative);
        }
        Path p = baseDir.resolve(relative).normalize();
        if (!p.startsWith(baseDir)) {
            throw new IllegalArgumentException("Path is outside the project: " + relative);
        }
        return p;
    }

    static String className(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("\\.(groovy|gsh|gy)$", "");
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (sb.length() == 0 || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, "Script_");
        }
        return sb.toString();
    }

    private static Method findFunction(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    private MessageSupport<?> newMessage(Class<?> declared, Exchange exchange, List<Diagnostic> diags) throws Exception {
        Class<? extends MessageSupport> cls = defaultMessageClass;
        if (MessageSupport.class.isAssignableFrom(declared) && declared != MessageSupport.class) {
            cls = declared.asSubclass(MessageSupport.class);
        }
        if ("v2".equals(apiVersion) && cls.getName().equals("com.sap.gateway.ip.core.customdev.util.Message")) {
            diags.add(Diagnostic.warning("Script API v1 Message used in a Groovy Script 2.x profile. Whether CPI's 2.x step "
                    + "still accepts v1 imports is not verified yet (probe fingerprint-v2.groovy)."));
        }
        return cls.getConstructor(Exchange.class).newInstance(exchange);
    }

    private static Object inputBody(SimExchange in, String type) {
        switch (type) {
            case "byte[]":
                return in.getBody();
            case "InputStream":
                return new ByteArrayInputStream(in.getBody());
            case "String":
            default:
                return in.getBodyAsString();
        }
    }

    // ---------------------------------------------------------------- output

    static Json.Obj describeOutput(Exchange exchange, Charset charset, List<Diagnostic> diags) {
        Object body = exchange.getIn().getBody();
        String text;
        String bodyClass = body == null ? "null" : body.getClass().getName();
        try {
            if (body == null) {
                text = "";
            } else if (body instanceof String) {
                text = (String) body;
            } else if (body instanceof byte[]) {
                text = new String((byte[]) body, charset);
            } else if (body instanceof InputStream || body instanceof Reader) {
                text = exchange.getIn().getBody(String.class);
            } else {
                text = exchange.getIn().getBody(String.class);
                if (text == null) {
                    text = body.toString();
                }
            }
        } catch (RuntimeException e) {
            text = String.valueOf(body);
            diags.add(Diagnostic.warning("Body of type " + bodyClass + " could not be converted to text: " + e.getMessage()));
        }
        Map<String, Object> headers = new LinkedHashMap<>(exchange.getIn().getHeaders());
        Map<String, Object> props = new LinkedHashMap<>(exchange.getProperties());
        return Json.o()
                .with("body", text == null ? "" : text)
                .with("bodyType", bodyClass)
                .with("encoding", charset.name())
                .with("bodySize", text == null ? 0 : text.getBytes(charset).length)
                .with("headers", TypedValue.writeList(headers))
                .with("properties", TypedValue.writeList(props));
    }

    private Json.Obj finish(Json.Obj result, boolean ok, String failedPhase, List<Diagnostic> diags, Json.Obj output,
                            Json.Obj changes, Platform platform, String console, long t0, double compileMs,
                            boolean cached, MessageSupport<?> message) {
        result.with("ok", ok);
        if (failedPhase != null) {
            result.with("failedPhase", failedPhase);
        }
        List<Object> d = new ArrayList<>();
        diags.forEach(x -> d.add(x.toJson()));
        result.with("diagnostics", d);
        if (output != null) {
            result.with("output", output);
            result.with("changes", changes);
        }
        if (message != null) {
            List<Object> att = new ArrayList<>();
            message.getAttachments().forEach((k, v) -> att.add(Json.o().with("name", k)
                    .with("contentType", v == null ? null : v.getContentType())
                    .with("size", AttachmentHeaders.size(v))));
            result.with("attachments", att);
            if (message instanceof com.sap.gateway.ip.core.customdev.util.Message) {
                List<Object> soap = new ArrayList<>();
                for (SoapHeader h : ((com.sap.gateway.ip.core.customdev.util.Message) message).getSoapHeaders()) {
                    soap.add(Json.o().with("name", h.getQname() == null ? null : h.getQname().toString())
                            .with("value", xml(h.getElement())).with("mustUnderstand", h.isMustUnderstand())
                            .with("actor", h.getActor()));
                }
                result.with("soapHeaders", soap);
            }
        }
        result.with("mpl", platform.mplJson());
        result.with("apiCalls", platform.apiCallsJson());
        result.with("console", console);
        result.with("timing", Json.o().with("compileMs", compileMs).with("cached", cached)
                .with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
        result.with("engine", engineInfo());
        return result;
    }

    public Map<String, Object> engineInfo() {
        return Json.o().with("profile", "v1".equals(apiVersion) ? "groovy-1.x" : "groovy-2.x")
                .with("groovy", GroovySystem.getVersion())
                .with("camel", Exchanges.camelVersion())
                .with("java", System.getProperty("java.version"))
                .with("scriptApi", apiVersion);
    }

    // ---------------------------------------------------------------- diagnostics

    private static void compileDiagnostics(MultipleCompilationErrorsException e, String apiVersion, List<Diagnostic> diags) {
        for (Object m : e.getErrorCollector().getErrors()) {
            if (m instanceof SyntaxErrorMessage) {
                SyntaxException se = ((SyntaxErrorMessage) m).getCause();
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, se.getOriginalMessage() + movedClassHint(se.getOriginalMessage(), apiVersion),
                        null, null, se.getLine(), se.getStartColumn()));
            } else if (m instanceof ExceptionMessage) {
                Exception cause = ((ExceptionMessage) m).getCause();
                diags.add(Diagnostic.error(cause == null ? m.toString() : cause.toString()));
            } else {
                diags.add(Diagnostic.error(String.valueOf(m)));
            }
        }
        if (diags.isEmpty()) {
            diags.add(Diagnostic.error(e.getMessage()));
        }
    }

    private static Diagnostic runtimeDiagnostic(Throwable e, String className) {
        Throwable t = e;
        int line = -1;
        while (t != null && line < 0) {
            line = scriptLine(t, className);
            if (line < 0) {
                t = t.getCause();
            }
        }
        Throwable shown = unwrap(e);
        String msg = shown.getClass().getName() + (shown.getMessage() == null ? "" : ": " + shown.getMessage());
        msg += v1OnlyMethodHint(shown);
        return new Diagnostic(Diagnostic.Severity.ERROR, msg, null, null, line, -1);
    }

    /** Removes Groovy/Java reflection wrappers so the script's own exception is reported. */
    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t
                && (t.getClass().getName().equals("org.codehaus.groovy.runtime.InvokerInvocationException")
                    || t instanceof java.lang.reflect.InvocationTargetException)) {
            t = t.getCause();
        }
        return t;
    }

    private static int scriptLine(Throwable t, String className) {
        for (StackTraceElement el : t.getStackTrace()) {
            if ((className + ".groovy").equals(el.getFileName()) && el.getLineNumber() > 0) {
                return el.getLineNumber();
            }
        }
        return -1;
    }

    private static String stackTrace(Throwable e, String className) {
        StringBuilder sb = new StringBuilder();
        Throwable first = unwrap(e);
        for (Throwable t = first; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t != first) {
                sb.append("Caused by: ");
            }
            sb.append(t).append('\n');
            int shown = 0;
            for (StackTraceElement el : t.getStackTrace()) {
                if (el.getClassName().startsWith("io.cpiwb.groovy.ScriptRunner")) {
                    break; // everything below is the simulator itself
                }
                boolean script = (className + ".groovy").equals(el.getFileName());
                boolean internal = el.getClassName().startsWith("org.codehaus.groovy.") || el.getClassName().startsWith("groovy.")
                        || el.getClassName().startsWith("java.lang.reflect.") || el.getClassName().startsWith("jdk.internal.")
                        || el.getClassName().startsWith("io.cpiwb.") || el.getClassName().startsWith("sun.reflect.");
                if (script || (!internal && shown < 6)) {
                    sb.append("    at ").append(el).append('\n');
                    shown++;
                }
            }
        }
        return sb.toString();
    }

    /** XML classes that Groovy 4 (Script 2.x) only has in groovy.xml, no longer in the default-imported groovy.util. */
    private static final java.util.regex.Pattern MOVED_XML_CLASS =
            java.util.regex.Pattern.compile("\\b(XmlSlurper|XmlParser|XmlNodePrinter|GPathResult)\\b");

    /** Methods of SAP's v1 Message interface that its published v2 interface lacks. */
    private static final java.util.regex.Pattern V1_ONLY_METHOD = java.util.regex.Pattern.compile(
            "\\.(getSoapHeaders|setSoapHeaders|getAttachmentWrapperObjects|setAttachmentWrapperObjects)\\s*\\(");

    private static void lint(String source, String apiVersion, List<Diagnostic> diags) {
        String[] lines = source.split("\n", -1);
        boolean xmlImport = source.contains("import groovy.xml.");
        boolean movedClassReported = false;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("import ") && l.replace(";", "").endsWith(".*")) {
                diags.add(new Diagnostic(Diagnostic.Severity.INFO, "Wildcard import: SAP recommends explicit imports "
                        + "for Groovy Script 2.x compatibility.", "lint", null, i + 1, 1));
            }
            if (l.startsWith("//") || l.startsWith("*") || l.startsWith("import ")) {
                continue;
            }
            java.util.regex.Matcher m = MOVED_XML_CLASS.matcher(l);
            if ("v1".equals(apiVersion) && !xmlImport && !movedClassReported && m.find()) {
                String cls = m.group(1);
                diags.add(new Diagnostic(Diagnostic.Severity.INFO, cls + " comes from groovy.util here. Groovy Script 2.x "
                        + "(Groovy 4) only has it in groovy.xml: add 'import groovy.xml." + ("GPathResult".equals(cls)
                        ? "slurpersupport." : "") + cls + "' before upgrading the step.", "lint", null, i + 1, 1));
                movedClassReported = true;
            }
            java.util.regex.Matcher v = V1_ONLY_METHOD.matcher(l);
            if ("v2".equals(apiVersion) && v.find()) {
                diags.add(new Diagnostic(Diagnostic.Severity.WARNING, v.group(1) + "() is part of the Script API v1 "
                        + "Message but not of SAP's published v2 interface (script.v2.api 4.2.2).", "lint", null, i + 1, 1));
            }
        }
    }

    private static String movedClassHint(String message, String apiVersion) {
        String msg = message == null ? "" : message;
        if ("v1".equals(apiVersion) && msg.contains("unable to resolve class com.sap.it.script.v2.")) {
            return " (Script API v2 needs a Groovy Script 2.x step; this runtime is Groovy Script 1.x)";
        }
        if ("v1".equals(apiVersion) && java.util.regex.Pattern.compile("unable to resolve class groovy\\.xml\\.(slurpersupport\\.)?"
                + "(XmlSlurper|XmlParser|XmlNodePrinter|GPathResult)\\b").matcher(msg).find()) {
            return " (Groovy 2.4 has it in groovy.util; groovy.xml has it from Groovy 3 on, so in Groovy Script 2.x)";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("unable to resolve class (XmlSlurper|XmlParser|XmlNodePrinter|GPathResult)\\b")
                .matcher(msg);
        if (!m.find()) {
            return "";
        }
        String pkg = "GPathResult".equals(m.group(1)) ? "groovy.xml.slurpersupport." : "groovy.xml.";
        return " (Groovy 4 moved it out of groovy.util: add 'import " + pkg + m.group(1) + "')";
    }

    private static String v1OnlyMethodHint(Throwable t) {
        String msg = t.getMessage();
        if (t instanceof groovy.lang.MissingMethodException && msg != null
                && msg.contains("com.sap.it.script.v2.api.Message") && V1_ONLY_METHOD.matcher("." + ((groovy.lang.MissingMethodException) t).getMethod() + "(").find()) {
            return " (this method is part of the Script API v1 Message only)";
        }
        return "";
    }

    // ---------------------------------------------------------------- platform data

    /** Platform data from the project's platform/ folder; keys given in the request replace the folder's. */
    private static Map<String, Object> platformData(Map<String, Object> req, Path baseDir) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        if (baseDir != null) {
            for (String[] f : PLATFORM_FILES) {
                Path file = baseDir.resolve("platform").resolve(f[1]);
                if (Files.isRegularFile(file)) {
                    try {
                        out.put(f[0], Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("platform/" + f[1] + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        Map<String, Object> p = Json.obj(req, "platform");
        if (p != null) {
            out.putAll(p);
        }
        return out;
    }

    private static final String[][] PLATFORM_FILES = {
            {"credentials", "credentials.json"},
            {"valueMappings", "value-mappings.json"},
            {"partnerDirectory", "partner-directory.json"},
            {"keystore", "keystore.json"},
            {"numberRanges", "number-ranges.json"},
    };

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
