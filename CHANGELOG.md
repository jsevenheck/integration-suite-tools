# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). A release takes its notes from the section
of its version.

## [Unreleased]

### Added

- **Flow Steps** tab: runs a Content Modifier, Filter, Router, General / Iterating Splitter or Gather on
  an input message. These steps have no Groovy version: the tab picks the tenant's Camel line (3.14 by
  default, or 2.24). XPath uses Saxon (XPath 3.1) and sees headers and properties as variables (`$name`),
  as CPI's Camel does. CPI's `=`, `and`, `or` and `${property.x}` work on both Camel lines, and so do OGNL
  calls such as `${in.header.name.split(',')[0]}` (the Groovy 2.x engine now bundles `camel-bean`).
  Gather writes SAP's multi-mapping format and takes a splitter's result with one click.
- iFlow import lists Content Modifier, Filter, Router, Splitter and Gather steps and opens them in Flow Steps with
  their settings and the iFlow's externalized parameters.

### Changed

- The XPath tab starts with XPath 3.1 (Saxon), as CPI's Content Modifier and Router use it. Its
  1.0 option no longer claims to be what simple iFlow steps use; that had no source.

## [0.1.1] - 2026-10-26

### Fixed

- Windows: the installed app could not start the Groovy engines ("exited during start"), because
  the jar path reached Java with a `\\?\` prefix.
- Windows: the Groovy engines now also start when the install folder has characters Java cannot
  handle, such as a Polish or Chinese user name (JDK-8205991). The app reaches the folder through a
  junction in `C:\ProgramData\CPI Workbench <user SID>`. If that fails, the start error names the
  cause and the workaround.

## [0.1.0] - 2026-10-25

First public release.

### Added

- Desktop app for Windows, macOS (Apple Silicon) and Linux; browser mode served by the engines.
- **XSLT Mapping** on Saxon-HE 9.9 (CPI's release line) and Saxon-HE 12: CPI parameter binding from
  headers and properties, `$exchange` with `hci:setHeader` / `hci:setProperty`, includes from the
  project, reflexive `java:` calls to JDK classes, error lines, runtime comparison.
- **Groovy Script** 1.x (Groovy 2.4.21, Camel 2.24.3) and 2.x (Groovy 4.0.29, Camel 3.14.7) with
  clean-room Script API v1 and v2, message processing log, and mocks for Secure Store, Value
  Mapping, Partner Directory, Keystore and number ranges from the project's `platform/` folder.
- **XSD validation** and **XPath** 1.0 / 3.1.
- iFlow import (zip): lists the Groovy, XSLT and XML Validator steps and opens them with the step's
  settings.
- Project files: `inputs/` for input bodies, JSON inputs for Groovy, file tree context menu,
  Save as…, Format, resizable panes.
- Test cases (`case.json`) for the command line and CI, with the engine jars.
- XML runs read files inside the project only; `xsl:result-document` and `collection()` are not
  supported.
- The installers and engine jars include the licence texts and notices of the bundled components.

[Unreleased]: https://github.com/jsevenheck/cpi-workbench/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/jsevenheck/cpi-workbench/releases/tag/v0.1.0
