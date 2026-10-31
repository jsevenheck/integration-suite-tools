# CPI Workbench — Project Plan

Status: **v3, implementation in progress** — see §0 for what is built and [status.md](status.md) for the per-area completeness audit. Open decisions are in §13.

Working name: **CPI Workbench** (placeholder).

What changed in v3: the plan is now built around **our own CPI simulation core**. Third-party components are limited to the foundation CPI itself runs on (JDK, Groovy, Apache Camel, Saxon). Small community projects such as go-xslt are only references for features and UX, not components (§3). XSLT now runs on the **same Saxon release line as CPI (9.9)**, compiled to a native binary, so XSLT needs no JVM at runtime (§5, §7).

---

## 0. Implementation status (2026-10-25)

Decisions taken: build-vs-reuse principle accepted (§3), XSLT first, tenant probes later (§13 Q1–Q6).

| Phase | Status |
|---|---|
| 0 — Spikes | **Done.** (a) Saxon-HE 9.9.1-8 and 12.9 compile to GraalVM native executables (Linux measured; Windows/macOS built and smoke-tested in CI, not measured). (b) CPI parameter binding and `hci:setHeader`/`setProperty` work as Saxon integrated extension functions in both versions — no `xsl:message` workaround needed. (d) Tauri ⇄ engine JSON-RPC over stdio works. (e) Budgets measured, see below. (c) Groovy spike: skipped, Groovy was built directly in Phase 3. |
| 1 — Probe kit | **Written** (`probes/`), simulator baseline recorded; waiting for a tenant run. |
| 2 — XSLT MVP | **Done**: XSLT simulator with live mode, both Saxon profiles, parameter binding view, XSD validation, XPath 1.0/3.1, test cases + runner + CLI, desktop app and browser mode. |
| 3 — Groovy MVP | **Done**: engines for Groovy Script 1.x (Groovy 2.4.21, Camel 2.24.3) and 2.x (Groovy 4.0.29, Camel 3.14.7), clean-room Script API, MPL, Secure Store + Value Mapping mocks, Groovy view in the app, test cases, bundled Java 17 runtime with AppCDS and idle shutdown. Message methods of v1 and v2 follow SAP's published interfaces (F29). |
| 4 — Productivity | **Done**: CLI runner; platform API mocks following SAP's published generic API (Secure Store incl. OAuth2 tokens, Value Mapping, Partner Directory, Keystore incl. key/trust managers, number ranges; F33; Data Store has no public script API, F28); runtime comparison (Saxon 9.9 ↔ 12, Groovy 1.x ↔ 2.x); lint (v1 imports in 2.x, wildcard imports, 1.x scripts that will break on 2.x through the `groovy.util` → `groovy.xml` move, v1-only methods in 2.x); iFlow-zip import (Groovy, XSLT and XML Validator steps, header-driven mappings flagged; checked against 24 SAP sample iFlows); reflexive Java extension functions in XSLT (F32); SOAP headers and `lib/` jars for scripts. |
| 5 — Distribution | Partly: CI builds unsigned Windows/macOS (Apple Silicon)/Linux installers (green, not yet run on real Windows/macOS hardware). Open: code signing, auto-update. |

Measured (Linux x86-64, native engines): engine start ≈ 10 ms (JVM: ≈ 200 ms); first XSLT run ≈ 10 ms, cached
runs < 1 ms (JVM: ≈ 420 ms / 5 ms); 270 KB input ≈ 70 ms (9.9) / ≈ 200 ms (12) with ≈ 60 MB peak RSS;
executables 48 MB (9.9) and 54 MB (12), ≈ 16 and 18 MB gzip-compressed. Both engines fit the ≤ 60 MB base-installer
budget only together with installer compression; the measured compressed size suggests they do (to confirm with real installers).

New findings from the implementation:

| # | Finding | Label | Evidence |
|---|---|---|---|
| F20 | Saxon-HE (9.9 and 12) only has English names for `format-date` (`'de'` → `[Language: en]5. March 2026`). CPI's Saxon-EE is expected to localise. | Observed (simulator) / Inferred (CPI) | `probes/baseline-simulator/fingerprint.saxon-he-9.9.xml` |
| F21 | Passing headers/properties as Java objects through JAXP `setParameter` makes Saxon convert them itself: `Integer` → `xs:integer`, `Boolean` → `xs:boolean`, `Date` → `xs:dateTime`, `byte[]` → sequence of integers. | Observed (simulator) | `probes/baseline-simulator/probe-xslt-behaviour.saxon-he-9.9.xml` |
| F22 | Header-over-property precedence is modelled on Camel's XsltBuilder (properties set first, headers override). That CPI uses this code path is an inference. | Inferred → probe Q-X2 | `XsltStep.java` |
| F24 | Groovy 2.4.21 runs the Script API v1 corpus on Java 17 and 21 without changes (7 community scripts). | Observed (simulator) | `GroovyCorpusTest` |
| F25 | `XmlUtil.serialize` / XSLT pretty-printing differs between JDK versions (indentation), so the Java version behind each CPI step matters for byte-exact comparisons. | Observed (corpus expected files vs. JDK 17/21 output) | `GroovyCorpusTest` |
| F26 | A classic 1.x → 2.x break is reproduced: scripts using `XmlParser`/`XmlSlurper` without import (default `groovy.util` package) fail on Groovy 4. | Observed (simulator) | corpus `edi-segment-counter` |
| F27 | Partner Directory, Keystore, Secure Store and number ranges: SAP's Javadoc (`generic.api`, F33) documents null for a missing Partner Directory parameter, partner or certificate parameter; `IllegalArgumentException` for null/empty IDs and for types other than `String`/`BinaryData`; null for an unknown keystore alias or the wrong entry kind (`IllegalStateException` when a key cannot be recovered); `SecureStoreException` for an unknown credential. The simulator follows this. Whether the deployed runtime matches its Javadoc, and the exact messages, are not verified. | Observed (Javadoc) / Unknown (runtime) → probe Q-P1 | S24 |
| F28 | CPI has no public script API for the Data Store; scripts that access it use internal classes looked up from the Camel registry. The simulator does not mock this. | Inferred (community examples) | — |
| F29 | SAP publishes its Script API interfaces on Maven Central (`com.sap.cloud.script:script.api` 2.14.0–2.16.4 for v1, `script.v2.api` 4.0.0–4.2.2 for v2; SAP Developer License, not bundled). The simulator's `Message`, `AttachmentWrapper`, `SoapHeader` and the v2 `Attachment`, extension-function and exception types follow these signatures. The v2 `Message` has no `getSoapHeaders`/`setSoapHeaders` and no `AttachmentWrapper` methods, but adds `getHeader(String)`, `getHeader(String, Object)`, `clearAttachments`, `resetBodyStream` and `getExtensionFunctions` (exchange timestamps, ProcessDirect). The interface is a lower bound: CPI's implementation class may offer more, which the fingerprint probes list (`messageMethods`). | Observed (published jars) / Unknown (runtime class) → Q-G4 | Maven Central `com.sap.cloud.script` |
| F30 | The v1 API jar moved from Camel 2.24 (`script.api` 2.15.0, May 2024: imports `org.apache.camel` [2.24,3), `AttachmentWrapper` extends `org.apache.camel.impl.DefaultAttachment`) to Camel 3.14 (2.16.2, Dec 2025, and 2.16.4: [3.14,4), `org.apache.camel.attachment.DefaultAttachment`). So 1.x steps may now run on Camel 3.14; the simulator's 1.x engine still uses Camel 2.24.3 until the fingerprint (`camelPom`) confirms. The simulator's v1 Message leaves out the overloads that take Camel's `Attachment` type. | Observed (jar manifests) / Inferred (tenant) → Q-G3 | Maven Central `com.sap.cloud.script:script.api` |
| F31 | Which libraries CPI puts on a script's class path besides Groovy and Camel (e.g. commons-codec, IAIK used by SAP's own sample scripts) is not known; the simulator offers none and loads jars from `lib/` and the iFlow's `src/main/resources/lib/`. | Unknown → fingerprint `classesAvailable` | SAP sample iFlows |
| F32 | SAP's sample XSLTs call Java static methods through `java:` namespaces (e.g. `java:java.util.Date`), so CPI's Saxon-EE allows reflexive extension functions. The simulator registers them as integrated functions for JDK classes (native engines: a fixed list of JDK classes). Classes from the iFlow's or project's jars are not available to XSLT (the native engines cannot load classes at runtime). Exact argument conversion rules of Saxon-EE are not reproduced in all cases. | Observed (SAP samples) / Inferred (conversion) | SAP sample iFlows, `JavaExtensions.java` |
| F33 | SAP also publishes the platform API interfaces as `com.sap.cloud.adk:generic.api` (4.10.2, with Javadoc; SAP Developer License, not bundled): `ITApiFactory`, `ITApiException`/`InvalidContextException`, Keystore (incl. key and trust managers), message log, number ranges (`NumberRangeConfigurationService.getNextValuefromNumberRange(name, correlationId)`), Partner Directory (incl. `getCertificateParameter(parameterId, partnerId)`, authorized users, deprecated LIKE-pattern lookups) and Secure Store (incl. OAuth2 tokens: `getAccesTokenForOauth2ClientCredential`, SAP's spelling). `ValueMappingApi` is not in it. The simulator's classes follow these signatures. | Observed (published jar + Javadoc) | S24 |
| F34 | The Camel line is a property of the tenant: SAP numbers Cloud Integration software 8.x (Cloud Foundry) and 7.x (Neo) for runtimes on Camel 3, 6.x and 5.x for Camel 2. Patch notes from 2024 to 2026 describe the migration of tenants to Camel 3 and back-ports to the Camel 3.14.7 branch. Whether any tenant still runs Camel 2 is not stated. | Observed (SAP docs) | S25 |
| F35 | SAP's Router table uses `=`, `and`, `or` and `${property.x}`, and its docs never use `${exchangeProperty.x}`. Plain Camel has no `=` operator ("Unexpected token =" on 2.24 and 3.14), and Camel 3.14 rejects `and`, `or` (only `&&`, `\|\|`) and `property`. SAP's patch notes (August 2024) confirm that CPI converts `property` to `exchangeProperty`; how it handles `=`, `and` and `or` is not documented. | Observed (SAP docs, simulator) / Inferred (`=`, `and`, `or`) | S25, `SimpleStepTest` |
| F36 | In exported iFlows a Content Modifier is `activityType=Enricher` with `headerTable` / `propertyTable` (rows of `<cell id='Action\|Type\|Value\|Default\|Name\|Datatype'>`), `bodyType` and `wrapContent`. Source types: constant, header, property, xpath, expression, persisted variables, global persisted variables, numberRange, external parameter. Delete rows name `$name=X` or `$include=…,$exclude=…`. A Router is an `exclusiveGateway` (`throwException`, `default` flow) whose outgoing flows carry `expressionType` XML or NonXML and a `conditionExpression`. A splitter has `splitType` GeneralSplitter or IteratingSplitter, `exprType` XPath, Line Break or Token, `splitExprValue` or `tokenValue`, `grouping`, `Streaming`. Values may hold `{{parameters}}` from `parameters.prop`. | Observed (1,570 Content Modifiers, 629 Routers, 77 splitters in 389 exported iFlows) | S26 |
| F37 | Router XML conditions in real iFlows use XPath 2.0+ syntax (`/node()/*:IDOC[1][1=last()]`, `/a/count(b) = 0`), so CPI's Router does not evaluate them as XPath 1.0. | Observed | S26 |
| F38 | Content Modifier versions up to 1.3 have no `bodyType`, and their bodies hold Camel Simple (`${header.x}`, `${exception.message}`; 39 such steps, 19 with `${…}`), as in SAP's sample "Externalizing Additional Parameter" (1.2), which shows header values in the body: these bodies are expressions. From 1.4 the type is stored; 1.6 mostly stores `constant` for an empty body. In the same samples, 1,565 of 1,568 Content Modifiers and 623 of 625 Non-XML Router conditions run on the simulator's Camel; the rest fail on input values, a misspelt class (`java.Lang.Integer`) or three-level nested `${…}` that Camel's parser rejects. | Observed | S26 |
| F39 | Filter steps store `xpathType` (String, Integer, Boolean, Node, Nodelist) and the XPath in `wrapContent`; Gather steps store `messageType` (SameXMLFormat, DiffXMLFormat, PlainText), `aggregationAlgorithm` (sap-identical-multi-mapping, sap-pi-multi-mapping, xpath-merge-strategy, concatenate-strategy), `sourceXPath` and `targetXPath` (64 Filters, 55 Gathers in the samples). Of the 64 Filter XPaths, 60 compile on Saxon without variables; the other 4, and one Router XML condition, use `$name` for a header or property. Camel's XPath variable resolver (`MessageVariableResolver`, Camel 2.24.3 and 3.14.7, read from the Apache jars) resolves an unprefixed `$name` to the header, else the exchange property. | Observed | S26 |
| F23 | Saxon instantiates built-in functions reflectively; native builds need reflection metadata for all of them (generated by `engine/native/GenReflect.java`), plus the JDK XPath 1.0 function classes. | Observed | native build failures fixed in this phase |

---

## 1. The core idea

Testing a Groovy script or an XSLT mapping in SAP Integration Suite today means: change → deploy → wait → trigger → enable trace → open the monitor → read the payload → repeat. Each round takes minutes.

CPI Workbench removes that loop. It is a lean desktop app (Windows, macOS; Linux comes almost for free) where you:

1. enter an **input message** — body, headers, exchange properties, attachments,
2. pick a **Groovy script** (v1 or v2) or an **XSLT mapping**,
3. press **Run** (or let it re-run on every change),
4. see the **output message** — body, headers, properties, MPL entries, logs and errors — immediately, with a diff against the input.

The test must behave **as close as possible to the real CPI step**: same Groovy version, same Saxon release line, same parameter binding, same API surface, same type conversions. That fidelity is the reason to build this instead of using a generic Groovy console or XSLT tester.

Everything runs **offline** on the local machine.

### In scope
- Groovy Script step, **Script API v1** (step 1.x, Groovy 2.4.21) and **Script API v2** (step 2.x, Groovy 4.0.29)
- XSLT Mapping step (XSLT 1.0 / 2.0 / 3.0)
- XML Validator-like **XSD validation**, an **XPath tester** (Content Modifier / Router style)
- Test cases saved as files, a runner and CLI for regression tests

### Out of scope (for now)
- Whole iFlows, adapters, routers, Message Mapping (graphical)
- Connecting to a tenant; the app stays offline
- Shipping SAP code or decompiling SAP runtime binaries

---

## 2. What CPI actually does (research ledger)

Labels follow the REA evidence rules (see §4): **Observed** = read in a source or measured; **Inferred** = concluded from observations; **Unknown** = not established.

| # | Finding | Label | Source |
|---|---|---|---|
| F1 | Groovy Script step 1.x → **Groovy 2.4.21** | Observed | S1 |
| F2 | Groovy Script step 2.x → **Groovy 4.0.29** | Observed | S1 |
| F3 | A diagnostic on a 2.x step reported **Java 17.0.18, Groovy 4.0.29, Camel 3.14.7** | Observed (one community report) | S2 |
| F4 | Java version behind **1.x** scripts today | **Unknown** (SAP Help still mentions Java 8) | S1 |
| F5 | v1 API `com.sap.gateway.ip.core.customdev.util.Message`, entry `def Message processData(Message message)` | Observed | S1, S5 |
| F6 | v2 API in package `com.sap.it.script.v2.api` (`Message`, `Exchange`); full method list | Interface **Observed** (F29); runtime class Unknown | S2, S3, S23 |
| F7 | The public SAP Script API jar is under an SAP licence and is "bare-bones" | Observed (jar: SAP Developer License, interfaces only) → not shippable (Inferred) | S5, S6, S23 |
| F8 | CPI's XSLT engine is **Saxon-EE**, most recently reported as **9.9.1.6** (older report: 9.8.0.12) | Observed (community) | S8, S9 |
| F9 | XSLT 3.0 supported since XSLT Mapping step 1.2 | Observed | S7 |
| F10 | XSLT parameters: **every `xsl:param` is bound automatically** to a header or exchange property of the same name, if one exists | Observed (SAP sample docs) | S10 |
| F11 | Setting values from XSLT: namespace `http://sap.com/it/` (prefix free, SAP uses `hci:`), functions `setHeader($exchange, name, value)` and `setProperty($exchange, name, value)`, all strings; the stylesheet must declare `<xsl:param name="exchange"/>` | Observed (SAP sample docs) | S10 |
| F12 | Error "Header/Property value not found for the parameter" exists as an SAP KBA — parameter binding has edge cases | Observed (KBA title only) | S10 |
| F13 | Saxon-HE **9.9.1-x** is on Maven Central under **MPL-2.0** | Observed | S11 |
| F14 | Saxon **9.9 HE does not support higher-order functions** (PE/EE only); HE gained them (and `xsl:evaluate`) in Saxon 10 | Observed | S12 |
| F15 | Saxon-HE supports **integrated extension functions** (`ExtensionFunctionDefinition`); reflexive Java calls need PE/EE | Observed (Saxon docs as cited by PTC; Saxon docs to re-check) | S13, S8 |
| F16 | Saxon-HE can be compiled to a **native executable with GraalVM** (reported for 12.4, ~42 MB, small reflection config); SaxonC is Saxonica's own native build | Observed (anecdotal for 12.4) → feasible for 9.9 (Inferred) | S14, S15 |
| F17 | Dynamic Groovy (runtime-compiled scripts) **cannot run in a GraalVM native image**; no Groovy implementation exists outside the JVM | Observed | S16, S17 |
| F18 | CPI traces show headers, exchange properties and payload per step; MPL with trace content can be exported | Observed (blogs; export format Unknown) | S18 |
| F19 | Existing references: GrooveBox (online, Groovy 2.4/Java 8 and 4/Java 17, body/headers/properties, value mappings, secure store, partner directory, MPL, console, list of SAP API calls), Contiva Groovy IDE (online), go-xslt (pure-Go XSLT 3.0/XSD engine + desktop tester, pre-1.0, single maintainer) | Observed (descriptions, not inspected in depth) | S19, S20, S21 |

---

## 3. Build vs. reuse — the guiding decision

### 3.1 The principle
> **We build everything that makes it "CPI". We reuse only what CPI itself runs on.**

Why: the goal is to behave like CPI. CPI executes scripts with Groovy, converts types with Apache Camel, and transforms XML with Saxon. Using the same libraries is the most direct way to get the same behaviour. Rebuilding them ourselves would make the simulator *less* like CPI, not more: every self-written engine adds its own bugs and its own edge-case behaviour, which is exactly what we want to avoid.

### 3.2 What we build ourselves (our product)

| Component | What it does |
|---|---|
| **CPI Exchange model** | Message, headers, exchange properties, attachments with Java types; one-shot stream semantics |
| **Script API v1 + v2 implementations** (clean-room) | Same package/class names as SAP, so scripts run unchanged |
| **Groovy step runner** | Loads the script with the right Groovy version, calls the configured function, captures results, maps errors to script lines |
| **MPL emulation** | `messageLogFactory`, string properties, custom header properties, log attachments |
| **Platform API mocks** | `ITApiFactory`: Secure Store, Value Mapping, Keystore, Partner Directory, Data Store — data from local files |
| **XSLT step runner** | CPI parameter binding (F10), `$exchange` + `setHeader`/`setProperty` as Saxon integrated extension functions (F11, F15), output handling, `xsl:include`/`xsl:import` resolution |
| **XSD validator step** and **XPath tester** | CPI-style configuration and error reporting |
| **Runtime profiles + calibration probes** | Version data per CPI step version; probe iFlows to verify against a real tenant (§8) |
| **Compatibility linter** | v1→v2 migration warnings, Groovy 2→4 breaking changes, EE-only XSLT features |
| **Test-case format, runner, CLI** | Regression tests without a tenant |
| **Desktop app and UI** | The editor, the panels, the diff, the "live run" loop |

### 3.3 What we reuse (the foundation CPI runs on)

| Component | Version | Licence | Why |
|---|---|---|---|
| Groovy | 2.4.21 and 4.0.29 | Apache-2.0 | Exactly what CPI uses (F1, F2) |
| Apache Camel core (type converters) | 3.14.x for v2; v1 to calibrate | Apache-2.0 | Same type conversion rules as CPI (F3) |
| Saxon-HE | **9.9.1-x** (CPI line) and 12.x (optional) | MPL-2.0 | Same engine family and release line as CPI (F8, F13) |
| JDK (Eclipse Temurin) | 17 (plus 8/11 if calibration requires) | GPLv2 + Classpath Exception | CPI runtime (F3) |
| Tauri, Monaco editor | current | MIT / Apache-2.0 | App shell and code editor |

All of them are long-lived projects backed by foundations or companies (Apache, Saxonica, Eclipse, the Tauri team, Microsoft) — not one-person projects.

### 3.4 What we deliberately don't do
- **Use go-xslt (or similar) as a component.** It is a reference for features and UX only (a desktop XSLT tester with live results, error markers, project folders). Fidelity-wise it would be a step away from CPI, because CPI runs Saxon, and it is a pre-1.0 one-person project.
- **Write our own XSLT 3.0 / XPath 3.1 / XSD engine.** This is years of work (the W3C test suites alone have ~127,000 cases) and would still behave differently from Saxon in implementation-defined details (serialization defaults, `format-number`/`format-dateTime` output, regex and collation edge cases, error codes). It would work against the core goal. If a reason appears later (e.g. a browser version), this can be re-evaluated.
- **Write our own Groovy implementation.** Not feasible (F17), and scripts call the Java class library directly.

---

## 4. Method: how REA is applied

*Reverse Engineer Anything* (REA, `morluto/rea`) is a workflow for studying the behaviour of a closed target and rebuilding it clean-room. Its tools (MCP server, disassemblers) are made for shipped binaries and are not installed here. Rebuilding an **open-source** project like go-xslt with it would make no sense: its source code is public, and copying it would not be our own work.

The right target for REA's method is **CPI itself**, observed as a black box:

1. **Question checklist** — what exactly does the step do? (parameter binding, type conversion, encoding, error handling, …) — Appendix A.
2. **Observe** — probe iFlows on a test tenant record inputs and outputs for each question (§8.3). No decompiling of SAP binaries.
3. **Ledger** — every finding labelled Observed / Inferred / Unknown with its evidence (§2).
4. **Rebuild** — our clean-room implementation of the observed behaviour.
5. **Differential test** — run the same probe cases locally; every difference is a bug or a documented limitation. Repeat.

Reference products (GrooveBox, Contiva, go-xslt's desktop app, CPI's own simulation mode) are studied for **features and UX** only — by using them, not by copying code or assets.

---

## 5. Architecture

### 5.1 Overview

```
┌────────────────────────────── Desktop app ──────────────────────────────┐
│  UI: TypeScript + Svelte, Monaco editor (webview)                        │
│  Shell: Rust (Tauri 2) — projects, settings, process supervisor,         │
│         test runner, CLI, auto-update                                    │
└───────────┬───────────────────────────────────────────┬─────────────────┘
            │ JSON-RPC over stdio                       │ JSON-RPC over stdio
            ▼                                           ▼
┌───────────────────────────────┐        ┌────────────────────────────────┐
│ cpisim-xml  (NATIVE binary)   │        │ cpisim-groovy  (JVM worker)    │
│  our CPI XSLT step runner     │        │  our Script API v1 / v2        │
│  our XSD / XPath tools        │        │  our MPL + platform API mocks  │
│  Saxon-HE 9.9.1 (CPI line)    │        │  Groovy 2.4.21 | Groovy 4.0.29 │
│  compiled with GraalVM        │        │  Camel type converters         │
│  → no JVM at runtime          │        │  trimmed bundled JRE           │
└───────────────────────────────┘        └────────────────────────────────┘
          shared code base: cpisim-core (Java 17) — exchange model, profiles,
          test-case format, result format
```

### 5.2 Technology choices and why
- **Engine core in Java 17** (`cpisim-core`, `cpisim-xml`, `cpisim-groovy`): it must sit directly on Groovy, Camel and Saxon, which are Java libraries. Plain Java (rather than Kotlin) keeps the runtime small and is the safest choice for GraalVM native compilation.
- **XSLT/XSD/XPath as a native binary** (GraalVM Native Image): Saxon is pure Java and has been compiled natively before (F16). Result: fast start, low memory, no JVM for XML work. If native compilation of 9.9 hits a blocker, the same code runs on the bundled JRE (fallback, slower start).
- **Groovy on a JVM**: unavoidable (F17). v1 and v2 run as separate worker processes so Groovy 2 and 4 never mix, and a runaway script only kills its own worker.
- **Shell in Rust (Tauri 2)**: small native binary, uses the operating system's webview (no bundled browser), good installer and update tooling. The UI needs a real code editor, which the webview + Monaco provides; a pure-Rust UI toolkit (egui, as in ArtCraft) has no comparable editor.
- Technology is not dogma: if the spikes show a better option (e.g. a JVM-only app being simpler), we switch.

### 5.3 Footprint and performance

**Lean by default**
- **Modular delivery**: the base app contains shell, UI and the native XML engine. The **Groovy pack** (JRE + Groovy + Camel + our API) is downloaded on first Groovy use, or comes with an **all-in-one offline installer** for machines without internet access.
- **Per-user installation without admin rights** (Windows NSIS current-user install; portable ZIP).
- **jlink-trimmed JRE**: only the JDK modules Groovy and Camel need (determined with `jdeps`), debug info stripped, compressed. One shared JRE for v1 and v2 if calibration shows both use the same Java version.

**Fast**
- XML engine starts in milliseconds (native); no JVM for XSLT-only users.
- JVM starts only on the first Groovy run, then stays **warm**; **idle shutdown** after 10 min (configurable) frees memory.
- **AppCDS** class-data archive for faster JVM starts.
- **Compiled-script and compiled-stylesheet cache** keyed by content hash and profile: changing only the input skips compilation.
- Low-footprint JVM flags (`-XX:+UseSerialGC`, 512 MB max heap by default, configurable per project).
- Large messages (above ~1 MB) are passed as temp files, not through JSON-RPC, and shown in a virtualized viewer.

**Target budgets** — goals to verify in the spikes, not measurements:

| Metric | Target |
|---|---|
| Base installer (incl. native XML engine) | ≤ 60 MB |
| Groovy pack download | ≤ 70 MB |
| App start to usable UI | ≤ 1 s |
| Memory, no Groovy worker running | ≤ 200 MB |
| XSLT run, 1 MB input, typical mapping, cached stylesheet | ≤ 100 ms |
| First Groovy run (cold JVM, with AppCDS) | ≤ 2 s |
| Groovy re-run (warm worker, cached script) | ≤ 100 ms |

---

## 6. Groovy Script Simulator

### 6.1 Input
- **Body**: XML / JSON / text / binary (from file); encoding selector; pretty-print.
- **Headers** and **exchange properties**: tables with name, value and **Java type** (`String`, `Integer`, `Long`, `Boolean`, `byte[]`, `Date`, `null`, …) — wrong types are a common cause of CPI failures.
- **Attachments**: name, content type, file.
- **Script** in Monaco; **function name** (default `processData`, as in the step configuration).
- **Script collection / libraries**: extra `.groovy` and `.jar` files on the classpath.
- **Profile**: `Groovy Script 1.x` or `2.x`.

### 6.2 Output
- Body with diff to input; headers and properties with added/changed/removed and type changes highlighted.
- **MPL view**: string properties, custom header properties, log attachments.
- **API call log**: which SAP APIs the script called (Secure Store, Value Mapping, …) — an idea taken from GrooveBox.
- Console (`println`, stdout/stderr, SLF4J), exception with stack trace mapped to script lines, timing, memory.

### 6.3 Our Script API implementation
**v1** `com.sap.gateway.ip.core.customdev.util.Message`: `getBody()`, `getBody(Class)`, `setBody`, `getHeaders`, `getHeader(String, Class)`, `setHeader(s)`, `getProperties`, `getProperty`, `setProperty`/`setProperties`, attachment methods, `getBodySize`, `getAttachmentsSize`; `messageLogFactory` binding with `MessageLog` (`setStringProperty`, `addCustomHeaderProperty`, `addAttachmentAsString`).

**v2** `com.sap.it.script.v2.api.*`: `Message` (incl. `getHeader(String)`, `getHeader(String, Object)`, `Attachment` methods, `clearAttachments`, `resetBodyStream`, `getExtensionFunctions` with `ExchangeExtensionFunctions`; `ProcessDirectService` fails with `UnsupportedInSimulator`), `attachment.DefaultAttachment`, `exceptions.*`. Not simulated: `Exchange`, `CamelContext` and the `language.Simple*` classes (F29).

**Type conversion**: our `Message` wraps a real Camel `DefaultExchange`, so `getBody(String)`, `getHeader(name, Type)` etc. use Camel's actual converters — the same rules as on CPI.

**Platform APIs** (`ITApiFactory`): mocks fed from `platform/` files in the project (credentials, value mapping CSV, keystore, partner directory). Unsupported APIs fail with a clear `UnsupportedInSimulator` error.

### 6.4 Linting
v1 imports in a v2 profile; Groovy 2→4 breaking changes (e.g. `groovy.util.XmlSlurper` → `groovy.xml.XmlSlurper`); wildcard imports (discouraged by SAP's upgrade guidance, S3); Java APIs not available on the profile's Java version. "Run under both profiles" compares v1 and v2 results side by side.

---

## 7. XSLT Mapping Simulator

### 7.1 The workflow (same as the Groovy UI)
Three panes: **Input** (body, headers, properties) → **XSLT** (Monaco, with includes) → **Output** (body, headers/properties set by the mapping, `xsl:message` console, errors with line/column). **Live mode** re-runs on every change (debounced), so editing the mapping shows the result immediately.

### 7.2 CPI step behaviour we implement
- **Parameter binding** (F10): every `xsl:param` gets the value of the header or exchange property with the same name. The precedence when both exist, the type passed (string vs. typed value), and behaviour for missing values (F12) are measured with probe iFlows before implementation (Appendix A, Q-X2…X4).
- **`$exchange` and `setHeader` / `setProperty`** (F11): registered as **Saxon integrated extension functions** in namespace `http://sap.com/it/`, exactly where CPI registers them. Calls are recorded and applied to the output message.
- **Output**: result becomes the new body; output encoding and serialization as configured in the stylesheet (Saxon decides, as in CPI).
- **Includes/imports** resolved from the project's mapping folder, like iFlow resources.
- Step options such as source/target from header or property (newer step versions) — to verify, then implement.

### 7.3 Saxon profiles

| Profile | Engine | Purpose |
|---|---|---|
| **CPI 9.9 (default)** | Saxon-HE 9.9.1-x, native | Same release line as CPI (F8) — closest behaviour |
| **Modern HE** | Saxon-HE 12.x, native | Supports higher-order functions and `xsl:evaluate`, which CPI's EE 9.9 has but HE 9.9 lacks (F14) |
| **Bring your own EE** (optional) | your licensed Saxon-EE 9.9 jar, on the JRE | Exact CPI engine, if you own a licence |

The linter detects EE-only features (higher-order functions, schema-aware XSLT, streaming, reflexive Java calls) and suggests the right profile. ~~Reflexive calls to your own Java classes do not work in CPI either (S8), so they are flagged as errors.~~ Superseded by F32: SAP's own samples use reflexive calls, so the simulator supports them; features that need Saxon-PE/EE get an explaining diagnostic instead.

---

## 8. Fidelity: profiles, calibration, limits

### 8.1 Runtime profiles
Versioned JSON files with Java, Groovy, Camel and Saxon versions per CPI step version, each value linked to its evidence (finding IDs) and a `verifiedOn` date.

### 8.2 Isolation and safety
Fresh exchange per run, timeout (default 10 s), memory cap, captured output. XML engine: no network, file access limited to the project folder. Groovy: network allowed by default (CPI scripts may call HTTP), "offline sandbox" toggle.
*Update 2026-10: no Groovy sandbox is planned; scripts run with the user's rights, and the user guide says to run trusted scripts only.*

### 8.3 Calibration and probe iFlows (the REA "observe" step)
- **Fingerprint iFlow**: reports Java, Groovy (`GroovySystem.getVersion()`), Camel and Saxon (`system-property('xsl:product-version')`) versions, default charset, timezone, locale.
- **Probe iFlows**: one per open behaviour question (Appendix A) — e.g. header vs. property precedence for `xsl:param`, type conversions of `getBody(String)` for various body types, serialization defaults. Results are stored as expected values.
- The app imports the results, updates the profiles and turns probe cases into regression tests. Every release must pass them.

### 8.4 Limits we accept
- Adapter-specific headers exist only if set in the input (templates help).
- Tenant-specific settings (memory limits, custom libraries, real platform data) are not reproduced.
- Saxon-EE-only features without your own EE licence (§7.3).
- SAP-internal classes outside the public script API.

---

## 9. Project and test-case format

```
my-project/
  cpi-workbench.json      # marks the folder as a project (name)
  scripts/                # .groovy
  mappings/               # .xsl / .xslt
  schemas/                # .xsd
  lib/                    # extra .jar
  platform/               # mocked secure store, value mappings, partner directory
  tests/
    order-to-idoc/
      case.json           # step type, profile, function name, typed headers/properties
      input.xml
      expected-output.xml # optional → regression assertion
```
Plain, git-friendly files. **CLI** (`cpiwb run tests/`) runs all cases and exits non-zero on failure (CI-ready).

Later convenience: open an **iFlow export (zip)** and list its script and XSLT steps with their configuration; create a test case from an **exported MPL trace** (export format to be checked, F18).

---

## 10. Roadmap

| Phase | Scope | Exit criterion |
|---|---|---|
| **0 — Spikes** | (a) Saxon-HE 9.9.1 as GraalVM native image on Windows + macOS; (b) CPI parameter binding + `setHeader`/`setProperty` as integrated extension functions; (c) Groovy 2.4.21 and 4.0.29 workers with Camel-backed `Message`; (d) Tauri ⇄ engine JSON-RPC; (e) measure all budgets in §5.3 | Measured numbers; go/no-go for native XML engine |
| **1 — Probe kit** | Fingerprint + probe iFlows for the questions in Appendix A | You run them once on a test tenant; results stored |
| **2 — XSLT MVP** | XSLT simulator with live mode, CPI binding, profiles, XSD validation, XPath tester, test cases | Probe cases for XSLT pass |
| **3 — Groovy MVP** | Groovy v1 + v2, typed headers/properties, MPL, API call log, console, diff | Probe cases for Groovy pass |
| **4 — Productivity** | Platform API mocks, linting, both-profiles comparison, CLI, iFlow-zip import | Usable for regression tests |
| **5 — Distribution** | Signed installers (`.msi`, notarized `.dmg`), offline installer, auto-update | Shareable with colleagues |

---

## 11. Testing strategy
- Unit and golden-file tests for every API method and step behaviour we implement.
- **Differential tests** against tenant probe results (§8.3) — the main fidelity measure.
- A corpus of real CPI scripts and XSLTs (yours, anonymised).
- Conformance of XSLT/Groovy themselves is not our job — Saxon and Groovy are the reference.

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| Saxon 9.9 does not compile cleanly to a native image | Fallback: run `cpisim-xml` on the bundled JRE (slower start, same results) |
| v2 API surface unknown (F6) | Get SAP's reference (Q3); probe iFlow lists methods via reflection at runtime |
| CPI updates Groovy/Saxon/Java | Profiles are data, not code; re-run fingerprint iFlow |
| Groovy 2.4.21 on Java 17 behaves differently from CPI's v1 runtime | Calibrate (F4); ship a Java 8/11 JRE for v1 if needed |
| Saxon-HE vs. EE differences | Profiles + linter + optional bring-your-own EE |

---

## 13. Open decisions for you

1. **Build-vs-reuse principle (§3)**: OK that we build the whole CPI simulation layer ourselves but reuse Groovy, Camel and Saxon-HE, because CPI runs exactly these?
2. **Tenant access**: can you run the fingerprint and probe iFlows (§8.3) on a test tenant? This is what makes "as close to CPI as possible" verifiable.
3. **Script API v2**: can you provide SAP's API documentation or the v2 Script API jar? (help.sap.com was not reachable from this environment.)
4. **Saxon-EE**: does your company have a Saxon-EE licence, or are the HE profiles enough?
5. **Platform APIs**: which do your scripts use most (Secure Store, Value Mapping, Keystore, Partner Directory, Data Store)?
6. **Priorities**: XSLT first (no JVM, fewest unknowns), then Groovy — or the other way round?
7. **Name / licence**: app name; open source or private?

---

## Appendix A — Question checklist

| ID | Question | Status | How to close it |
|---|---|---|---|
| Q-G1 | Groovy versions per step | Answered (F1, F2) | — |
| Q-G2 | Java version per step | Partial (F3, F4) | Fingerprint iFlow |
| Q-G3 | Camel version per step | Partial (F3, F30) | Fingerprint iFlow |
| Q-G4 | v2 API method list | Interface answered (F29); runtime class open | Fingerprint (`messageMethods`) |
| Q-G5 | `getBody(Class)` conversions for String/byte[]/InputStream/Document bodies | Inferred (Camel) | Probe iFlow |
| Q-G6 | Which bindings are in the script context (`messageLogFactory`, others?) | Partial | Probe iFlow listing the binding |
| Q-X1 | Saxon edition/version | Partial (F8) | Fingerprint iFlow |
| Q-X2 | `xsl:param` precedence when header and property share a name | Open | Probe iFlow |
| Q-X3 | Type of bound parameter values (string vs. typed) | Open | Probe iFlow |
| Q-X4 | Behaviour for a declared `xsl:param` without matching header/property (F12) | Open | Probe iFlow |
| Q-X5 | `setHeader`/`setProperty` signatures and return value | Partial (F11) | Probe iFlow |
| Q-X6 | Source/target from header/property in newer step versions | Open | SAP docs + probe |
| Q-V1 | XSD engine and version behind the XML Validator step | Open | Probe iFlow (error message format) |
| Q-T1 | Format of exported MPL trace content | Open (F18) | Export one sample |
| Q-P1 | Partner Directory / Keystore / Secure Store edge cases: missing parameter, unknown partner, unknown keystore alias (null or exception, which message) | Documented (F27, F33); runtime and messages open | `probe-platform-v1.groovy` |

## Sources

- S1 SAP Help — Define a Local Script Step: https://help.sap.com/docs/r/368c481cd6954bdfa5d0435479fd4eaf/Cloud/en-US/03b32eb2c5c249f0a59bcd27c44d1e4e.html
- S2 SAP Community — Testing Cloud Integration Groovy 4: https://community.sap.com/t5/integration-forum/testing-cloud-integration-groovy-4/m-p/14317619 ; Equalise IT — Upgrade to Groovy 4.0.x: https://equaliseit.com/upgrade-to-groovy-4-0-x-in-sap-cloud-integration/
- S3 SAP Community — Upgrade to script version 2.x: https://community.sap.com/t5/technology-blog-posts-by-members/how-to-fix-groovy-script-warnings-and-upgrade-to-script-version-2-x-in-sap/ba-p/14351691
- S4 SAP Community — Testing Groovy scripts locally (IntelliJ): https://blogs.sap.com/2022/03/03/testing-groovy-script-locally-using-intellij-idea/
- S5 SAP Community — Groovy script debugging in Eclipse (2024): https://answers.sap.com/t5/technology-blogs-by-members/groovy-script-debugging-in-eclipse-2024/ba-p/13582941
- S6 SAP Community — How do you test your Groovy scripts: https://community.sap.com/t5/technology-blogs-by-members/how-do-you-test-your-groovy-scripts/bc-p/13327543
- S7 SAP Community — XSLT Mapping enriched with XSLT 3.0: https://community.sap.com/t5/technology-blog-posts-by-sap/cloud-integration-xslt-mapping-is-enriched-with-xslt-3-0-specification/ba-p/13415753
- S8 SAP Community — Peeking under the hood of CPI's XSLT processor: https://community.sap.com/t5/technology-blog-posts-by-members/peeking-under-the-hood-of-cpi-s-xslt-processor/ba-p/13322917
- S9 SAP Community — Migrating XSLT mappings from PO to Integration Suite: https://community.sap.com/t5/integration-blog-posts/migrating-xslt-mappings-from-sap-process-orchestration-to-sap-integration/ba-p/14284726
- S10 SAP sample documentation (XSLT headers/properties): https://api.sap.com/odata/1.0/catalog.svc/Files('6e6e727f472741ce9a172adc6df81a68')/$value ; SAP Q&A: https://answers.sap.com/questions/12688623/cpi-xslt-using-of-header-variable-in-xslt.html ; SAP KBA 3095160: https://userapps.support.sap.com/sap/support/knowledge/en/3095160
- S11 Saxon-HE on Maven Central: https://central.sonatype.com/artifact/net.sf.saxon/Saxon-HE ; Saxonica licence conditions: https://www.saxonica.com/html/documentation9.8/conditions
- S12 Saxon 10 changes (HOF and xsl:evaluate in HE): https://saxonica.com/html/documentation12/changes/v10/xpath31.html ; BaseX list, Saxon EE 9.9 HOF error: https://mailman.uni-konstanz.de/mailman3/hyperkitty/list/basex-talk@mailman.uni-konstanz.de/message/V6MYV3KUN6LGC5ZPZVKGN7HAKUJC5WHI/
- S13 PTC Windchill — Saxon-HE full-interface extension functions: https://support.ptc.com/help/windchill/r13.1.2.0/en/Windchill_Help_Center/customization/WCCG_BusLogicCust_XSLT_SaxonJar.html
- S14 Saxonica forum — Native compilation with GraalVM (HE 12.4): https://saxonica.plan.io/boards/2/topics/9627
- S15 SaxonC product information: https://www.saxonica.com/html/saxon-c/index.html
- S16 Camel Quarkus Groovy extension: https://camel.apache.org/camel-quarkus/reference/extensions/groovy.html
- S17 Spring Boot with GraalVM (Groovy not supported): https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-with-GraalVM/Spring-Boot-Config-Data-Migration-Guide
- S18 SAP blog — Tracing of exchange properties: https://blogs.sap.com/2018/08/23/cloud-integration-enabling-tracing-of-exchange-properties-in-the-message-processing-log-viewer/
- S19 GrooveBox: https://grooveboxide.com/guides ; LinkedIn description: https://it.linkedin.com/in/gabrielbenolli
- S20 Contiva Groovy IDE: https://ide.contiva.com/
- S21 go-xslt: https://github.com/tim-riep/go-xslt
- S22 REA workflow skill: https://github.com/morluto/rea/blob/main/.agents/skills/reverse-engineer-anything/SKILL.md
- S23 SAP Script API jars on Maven Central: https://repo1.maven.org/maven2/com/sap/cloud/script/
- S24 SAP generic API (platform services) on Maven Central, with Javadoc jar: https://repo1.maven.org/maven2/com/sap/cloud/adk/generic.api/
- S25 SAP-docs Integration Suite documentation (GitHub mirror of help.sap.com): *Using Camel Simple Expression Language*, *Define Router*, *Define Looping Process Call*, *Define Content Modifier*, *Understanding Software Version Numbers*, *Patch Releases for Cloud Integration*: https://github.com/SAP-docs/btp-integration-suite/tree/main/docs/ci
- S26 SAP's sample iFlows (Apache-2.0), read for the model format only, not copied: https://github.com/SAP-samples/cloud-integration-flow
