# Architecture

## Repository layout

| Path | What |
|---|---|
| `engine/core` | Shared Java 17 code: JSON, JSON-RPC server (stdio and HTTP), project files, iFlow import, test runner. |
| `engine/xml`, `engine/xml-he99`, `engine/xml-he12` | CPI XSLT step, XSD validation, XPath. The same sources are built against Saxon-HE 9.9.1-8 and 12.9. |
| `engine/native` | GraalVM native-image build (`build-native.sh`), reflection metadata generator, workload smoke test. |
| `engine/groovy`, `engine/groovy-v1`, `engine/groovy-v2` | Groovy engines: shared runner, clean-room `com.sap.*` API (`src/api-common`, `src/api-v1`, `src/api-v2`), and the two builds (Groovy 2.4.21 with Camel 2.24.3, Groovy 4.0.29 with Camel 3.14.7). `build-jre.sh` trims the Java 17 runtime. |
| `app/` | Svelte 5 + Monaco UI (`src/`), Tauri 2 shell (`src-tauri/`), browser e2e tests (`e2e/`). |
| `examples/demo-project/` | Sample project for the app: mappings, scripts, a schema, inputs and platform data. |
| `testdata/regression-project/` | Project with test cases (`case.json`) that CI, the browser e2e and the shell tests run. |
| `probes/` | Fingerprint and behaviour probes for a real tenant, plus the simulator's baseline output. |

## Engines and protocol

The desktop shell starts each engine as a sidecar process on demand and talks JSON-RPC to it
over stdio. Every engine can also serve HTTP on localhost (`serve --http <port>`) for the
browser mode. The main methods are:

* `xslt.run`, `xsd.validate`, `xpath.eval`, `split.run`, `filter.run`, `gather.run` (XML engines),
* `groovy.run`, `simple.eval`, `contentModifier.run` (Groovy engines); the UI evaluates a Router
  route by route with `xpath.eval` and `simple.eval`,
* `tests.run`, and `project.*`, `file.*`, `iflow.*` for files (all engines).

File methods and XML runs stay inside the open project (`ProjectFiles`, `ProjectResolver`). Over
HTTP, the engines answer pages from `localhost`, `127.0.0.1` and the desktop shell only: requests
need an `X-Cpiwb` header, which forces a CORS preflight, and the origin's host is compared exactly.

* **XML engines** are GraalVM native executables, so XSLT needs no JVM at runtime. The same code
  also runs as a jar (`java -jar engine/xml-he99/target/cpiwb-xml-he99.jar`).
* **Groovy engines** run on a trimmed Java 17 runtime with an AppCDS archive. Groovy 2 and 4 run in
  separate processes, so they never mix and a runaway script only stops its own engine. The
  `Message` object is backed by a real Camel exchange, so type conversion and header handling come
  from Camel itself. On Windows, Java cannot start from a folder whose path has characters outside
  the ANSI code page ([JDK-8205991](https://bugs.openjdk.org/browse/JDK-8205991)). If the install
  folder has non-ASCII characters, the shell reaches it through a directory junction in
  `%ProgramData%\CPI Workbench <user SID>`, a folder only that user, SYSTEM and Administrators can
  write (`app/src-tauri/src/ascii_alias.rs`); the AppCDS archives go there too.
* **Caches**: compiled stylesheets and scripts are cached by content hash and profile, so changing
  only the input skips compilation.

The design reasons and the build-vs-reuse principle are in the [plan](plan.md) §3 and §5.

## Measured performance

Measured on a Linux x86-64 build machine; Windows and macOS have not been measured.

| | XSLT (native) | Groovy 1.x (JVM) | Groovy 2.x (JVM) |
|---|---|---|---|
| Engine start to ready | ≈ 10 ms | ≈ 220 ms (with AppCDS, 440 ms without) | ≈ 230 ms (410 ms without) |
| First run | ≈ 10 ms | ≈ 170 ms | ≈ 420 ms |
| Later runs (cached) | < 1 ms | ≈ 2 ms | ≈ 2 ms |
| Peak memory | ≈ 60 MB (270 KB input) | ≈ 75 MB | ≈ 150 MB |
| Size | 48 / 54 MB executables (≈ 16 / 18 MB compressed) | jars 14 + 12 MB, Java 17 runtime 75 MB (≈ 47 MB compressed) | |

A 7.4 MB message took 1.3 s through XSLT and 0.45 s through Groovy over HTTP.
