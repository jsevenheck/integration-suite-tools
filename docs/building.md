# Building

## Requirements

* JDK 17+ and Maven.
* GraalVM 21+ for the native engines.
* A JDK 17 with jmods for the Groovy runtime (`engine/groovy/build-jre.sh`).
* Node 22 and Rust (stable).
* On Linux, the WebKitGTK development packages (see `.github/workflows/build.yml`).

## Commands

```sh
mvn -f engine/pom.xml verify                         # engines + tests for both Saxon and both Groovy versions
GRAALVM_HOME=/path/to/graalvm engine/native/build-native.sh   # native engines → engine/native/out
python3 engine/native/workload.py engine/native/out/cpiwb-xml-he99 serve --stdio   # smoke test

JAVA_HOME=/path/to/jdk17 engine/groovy/build-jre.sh /tmp/jre    # trimmed Java 17 for Groovy

cd app
npm ci
npm run check                                        # Svelte/TypeScript checks
npm test                                             # UI unit tests (formatters; Node 22.18+)
CPIWB_JRE_DIR=/tmp/jre node scripts/prepare-sidecars.mjs   # stage engines + JRE for bundling
npx tauri build                                      # installer for the current OS
npx tauri dev                                        # development (UI hot reload)
```

## Shell tests

`cargo test` in `app/src-tauri` checks the desktop shell's path to the engines: each engine is
started over stdio and runs the whole demo project. It needs `npm run build` (the shell embeds the
UI bundle) and the engine builds; it looks for the native engines in `engine/native/out` and for
the jars in the engine `target/` folders. `JAVA_HOME` selects the Java runtime, e.g. the trimmed
one from `build-jre.sh`. Without `CPIWB_REQUIRE_ENGINES=1` the test is skipped.

```sh
npm --prefix app run build
CPIWB_REQUIRE_ENGINES=1 JAVA_HOME=/tmp/jre cargo test --manifest-path app/src-tauri/Cargo.toml
```

### Without GraalVM

Tauri's build script refuses to build while the sidecars in `src-tauri/binaries` are missing. Without
native engines, drop the bundle entries with a config override. The shell then runs the XML engine
jars with `JAVA_HOME`:

```sh
TAURI_CONFIG='{"bundle":{"externalBin":null,"resources":null}}' CPIWB_REQUIRE_ENGINES=1 cargo test --manifest-path app/src-tauri/Cargo.toml
cd app && npx tauri dev --config '{"bundle":{"externalBin":null,"resources":null}}'
```

Installers still need the staged sidecars.

## End-to-end tests (browser mode)

```sh
npm --prefix app run build
java -jar engine/xml-he99/target/cpiwb-xml-he99.jar serve --http 7777 --ui app/dist &
java -jar engine/groovy-v1/target/cpiwb-groovy-v1.jar serve --http 7778 &
java -jar engine/groovy-v2/target/cpiwb-groovy-v2.jar serve --http 7779 &
java -jar engine/xml-he12/target/cpiwb-xml-he12.jar serve --http 7780 &
cp -r testdata/regression-project /tmp/demo-project   # the tests write into the project
node app/e2e/smoke.mjs /tmp/demo-project [screenshotDir]
node app/e2e/groovy.mjs /tmp/demo-project [screenshotDir]
```

With `CPIWB_IFLOW_ZIP` set to a zip of `engine/core/src/test/resources/iflow`, `groovy.mjs` also imports
that iFlow and opens its Groovy and Content Modifier steps. CI does this; locally:
`jar --create --no-manifest --file /tmp/iflow.zip -C engine/core/src/test/resources/iflow .`

## CI and installers

`.github/workflows/build.yml`:

* runs the engine tests and the regression cases in `testdata/regression-project`,
* runs the browser e2e tests,
* runs the shell tests against the native engines and the trimmed JRE, then builds the bundles.

Pushes to `main`, tags `v*`, pull requests to `main` and manual runs (*Run workflow*,
`workflow_dispatch`) run everything on Linux, Windows (e2e, .msi/.exe) and macOS (Apple Silicon, .dmg).
The macOS runner is `macos-26`; `MACOSX_DEPLOYMENT_TARGET=11.0` (for native-image as the linker option
`-mmacosx-version-min`) keeps the app and the native engines starting on
macOS 11 (the app's `minimumSystemVersion`), and a CI step checks that. The Linux bundles are built on
`ubuntu-22.04` on purpose, so they need only that release's glibc.

The workflow artifacts (engine jars, installers) expire after 3 days; a tag's installers stay 90 days,
and the release keeps its own copies.

Native images must be built on each target OS; there is no cross-compilation.
The installers are not code-signed yet. On macOS the app is signed ad hoc (`signingIdentity: "-"`,
without hardened runtime, so the bundled JRE loads its libraries); it is not notarized.

## Releasing

A tag `v<version>` builds, tests and publishes a GitHub release with the installers, the engine
jars and `SHA256SUMS.txt` (job `release` in `build.yml`). It runs only when every other job passes.

1. In [`CHANGELOG.md`](../CHANGELOG.md), rename *Unreleased* to `## [<version>] - <date>`, add a new
   empty *Unreleased* section and update the links at the bottom. The release notes are this section.
2. Set the version: `node app/scripts/set-version.mjs <version>` (SemVer). It updates the app
   files, the Maven poms and the `VERSION` that the engines report.
3. Commit, then tag and push:

   ```sh
   git tag v<version>
   git push origin main v<version>
   ```

The job fails if the tag does not match the version in `tauri.conf.json` and in the engines, or if
the changelog has no section for it. A version with a suffix (`0.2.0-rc.1`) becomes a pre-release.

## Native image metadata

When a Saxon feature fails only in the native build, re-record the reachability metadata:

1. Run `workload.py` with `-agentlib:native-image-agent=config-merge-dir=engine/xml-heXX/src/main/resources/META-INF/native-image/io.cpiwb/cpiwb-xml-heXX`.
2. Regenerate `saxon-generated/reflect-config.json` with `engine/native/GenReflect.java`.
