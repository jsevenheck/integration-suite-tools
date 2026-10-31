# User guide

## Desktop app

Download the installer for your platform from the
[latest release](https://github.com/jsevenheck/cpi-workbench/releases/latest) (see
[Installing](install.md)), install it and open a project folder. Any folder works; **Init** adds `mappings/`, `schemas/`,
`scripts/`, `tests/` and example files in `platform/`.

* Open a `.xsl` or `.groovy` file in the tree and enter the input body, headers and properties on the left.
  Opening a Groovy script picks the runtime from its Script API import; for a pasted script, choose
  it under **Runtime**.
* **Format** above a mapping or script re-indents it. It only moves whitespace that cannot reach the
  result: text in XSLT, `xsl:text`, `xml:space="preserve"`, CDATA, literal attribute values and
  multi-line Groovy strings keep theirs.
* **Save** writes the open mapping, script or schema; **Save as…** writes it under a new name
  (`<name>-copy` is suggested) and leaves the open file as it is. Saving or creating a file under a
  name that exists asks before it overwrites the file, and opening another file asks before it
  drops unsaved changes (the dot after the file name).
* Right-click a file in the tree to open, rename, copy or delete it, or to copy its path.
* Drag the bars between input, mapping or script and output to resize them; double-click a bar to reset.
* **Save…** above the input body stores it under `inputs/`. Clicking a file there, or **Use as input**
  in a file's context menu, loads it as the input body of the current tool. A JSON file always goes
  to the Groovy tab, because only a script takes a JSON body; **Format** there pretty-prints JSON
  too.
* Other files, such as `platform/*.json` or `cpi-workbench.json`, open in a **File** tab with their own
  editor. They no longer replace the input body.
* The output updates live, or press **Ctrl+Enter**. Compile and runtime errors are marked in the editor.
* **Parameters** (XSLT) shows how each `xsl:param` was bound: header, property or default.
* **MPL**, **Console** and **API calls** (Groovy) show message processing log entries, `println`
  output and the calls to SAP APIs with their arguments.
* **Compare** also runs the input on the other runtime of the same kind (Saxon-HE 9.9 ↔ 12,
  Groovy Script 1.x ↔ 2.x) and lists what differs: status, headers, properties and a body diff.
  This helps before you move a script from 1.x to 2.x.
* Test cases (see [Test cases](test-cases.md)) are written by hand and run from the command line;
  the app has no test runner. Opening a `case.json` loads the case into its editor view. Inputs only
  a case file can hold (attachments, SOAP headers, platform overrides) are kept while the case is
  open and are shown below the input.
* **XSD** validates a document against a schema; **XPath** evaluates an expression (3.1 by default, or 1.0).
* **Flow Steps** runs a **Content Modifier**, **Filter**, **Router**, **General / Iterating Splitter** or
  **Gather** on an input message (body, headers, properties, namespace mapping):
  * Content Modifier: header and property rows with their source types and data types, and the
    message body; the result shows each row's value and the output message.
  * Router: routes in order, XML (XPath) or Non-XML (Camel Simple, with CPI's `=`, `and`, `or` and
    `${property.x}`) conditions, default route; the result shows which route the message takes.
  * Filter: an XPath and its Value Type (String, Integer, Boolean, Node, Nodelist); the result is the
    new body. `function:simple('${property.x}')` takes the value from Camel Simple.
  * Splitter: XPath, Line Break or Token, grouping; the result lists the messages. *Gather* passes them on.
  * Gather: Combine (SAP's multi-mapping format), Combine at XPath or Concatenate of the messages from
    the splitter or added by hand.

  In XPath, `$name` is the header of that name, else the exchange property, as in CPI.

  These steps have no Groovy version: *Camel* in the top bar picks the tenant's Camel line (3.14
  for tenant software 7.x / 8.x, the default; 2.24 for 5.x / 6.x). XPath runs on Saxon-HE 9.9. *Use as input* passes a result on to the next step. See
  [Fidelity](fidelity.md#flow-steps-content-modifier-filter-router-splitter-gather).
* **Import iFlow** unpacks an iFlow exported from Integration Suite (zip) into `iflows/` and lists
  its Groovy Script, XSLT Mapping, XML Validator, Content Modifier, Filter, Router, Splitter and Gather steps.
  Clicking a script or mapping step opens its file with the step's function name and script
  version; a Content Modifier, Filter, Router, Splitter or Gather opens in Flow Steps with its settings, with
  `{{parameters}}` filled in from the iFlow's `parameters.prop`. Steps that choose their mapping from a header at runtime
  are marked. The `.iflw` format is not publicly documented, so this is read generically (best effort).
* **Lint** shows hints about what breaks when a script moves from 1.x to 2.x:
  * `XmlSlurper` or `XmlParser` used without a `groovy.xml` import,
  * Script API v1 imports or v1-only methods in a 2.x step,
  * wildcard imports.

**✕** closes the project in the engines too, so runs no longer see its files or `platform/` data.

### What a run can access

* XSLT, XSD and XPath runs read files inside the open project only: includes, `doc()`,
  `document()`, `unparsed-text()`, external entities and DTDs. The network is blocked.
  `xsl:result-document` and `collection()` are not supported. Reflexive `java:` calls are the
  exception: they run JDK code and are not restricted.
* Groovy scripts run as ordinary Java code with your user's rights; there is no sandbox. Run only
  scripts you trust.

The installers are not code-signed yet; [Installing](install.md) shows how to start them the
first time. The Windows `setup.exe` installs per user and needs no admin rights; the `.msi` installs
per machine and needs admin rights.

The Groovy runtime starts only when the Groovy or Flow Steps tab is used and stops after 10 idle minutes.

## Command line (CI-friendly)

```sh
java -jar cpiwb-groovy-v1.jar run --script scripts/enrich.groovy --in input.xml -H SenderSystem=ERP
java -jar cpiwb-groovy-v2.jar test testdata/regression-project/tests
cpiwb-xml xslt --xsl mappings/order.xsl --in input.xml -H SenderSystem=ERP -P Count=3:Integer
cpiwb-xml validate --xsd schemas/order.xsd --in output.xml
cpiwb-xml xpath --expr "//Item/@sku" --in input.xml --version 3.1
cpiwb-xml test testdata/regression-project/tests        # exit code 1 if a case fails
```

`-H` sets a header and `-P` an exchange property, as `name=value[:Type]`. The types are listed in
[Test cases](test-cases.md). Each engine runs only the cases of its own kind and skips the others.

## Browser mode (no desktop shell needed)

```sh
cpiwb-xml-he99 serve --http 7777 --ui app/dist    # then open http://127.0.0.1:7777
```

Web mode listens on localhost only. The engine that serves the page also handles the project's
files. To add more engines, pass them in a URL parameter:

```sh
java -jar cpiwb-groovy-v1.jar serve --http 7778 &
java -jar cpiwb-groovy-v2.jar serve --http 7779 &
# open http://127.0.0.1:7777/?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779
```
