import { call, openProject, closeProject as closeEngineProject, availableProfiles, DEFAULT_PROFILE, type Profile, type ProfileInfo } from './engine';
import type { Diagnostic, FileEntry, GroovyResult, Row, XsltResult } from './types';
import { toRows } from './rows';
import { pathKey } from './paths';

export const SAMPLE_XSL = `<?xml version="1.0" encoding="UTF-8"?>
<!-- Headers and exchange properties arrive as xsl:param with the same name, like in Cloud Integration.
     $exchange + hci:setHeader / hci:setProperty change the message. Press Ctrl+Enter to run. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:hci="http://sap.com/it/"
    exclude-result-prefixes="hci">

  <xsl:param name="exchange"/>
  <xsl:param name="SenderSystem" select="'unknown'"/>
  <xsl:output method="xml" indent="yes"/>

  <xsl:template match="/Order">
    <xsl:value-of select="hci:setHeader($exchange, 'OrderNumber', @id)"/>
    <xsl:value-of select="hci:setProperty($exchange, 'ItemCount', string(count(Item)))"/>
    <xsl:message>Mapping order <xsl:value-of select="@id"/> from <xsl:value-of select="$SenderSystem"/></xsl:message>
    <SalesOrder sender="{$SenderSystem}">
      <xsl:for-each select="Item">
        <Line no="{position()}" material="{@sku}" qty="{@qty}"/>
      </xsl:for-each>
    </SalesOrder>
  </xsl:template>
</xsl:stylesheet>
`;

export const SAMPLE_INPUT = `<Order id="4711">
  <Item sku="M-100" qty="2"/>
  <Item sku="M-200" qty="1"/>
</Order>
`;

export type StepTool = 'xslt' | 'groovy' | 'xsd' | 'xpath' | 'flow';

export const app = $state({
  project: null as null | { root: string; files: FileEntry[] },
  profiles: [] as ProfileInfo[],
  engineInfo: null as any,
  profile: DEFAULT_PROFILE as Profile,
  groovyProfile: 'groovy-1.x' as Profile,
  tool: 'xslt' as StepTool | 'file',
  /** The step tool last shown; inputs opened from the tree go to its body. */
  stepTool: 'xslt' as StepTool,
  /** The inputs/ file last loaded or saved as each tool's input body. */
  inputPaths: {} as Partial<Record<StepTool, string>>,
  status: '',
  error: '',
  busy: false,
  iflowSteps: [] as IflowStep[],
});

export interface IflowStep {
  iflow: string;
  id: string;
  name: string;
  type: 'groovy' | 'xslt' | 'xsd' | FlowStep | 'error';
  note?: string;
  /** Flow step settings (Content Modifier, Filter, Router, Splitter, Gather) as the iFlow stores them. */
  config?: any;
  componentVersion?: string;
  file?: string;
  fileFound?: boolean;
  function?: string;
  profile?: Profile;
}

export const xslt = $state({
  mappingPath: null as string | null,
  mapping: SAMPLE_XSL,
  savedMapping: SAMPLE_XSL,
  body: SAMPLE_INPUT,
  headers: [{ name: 'SenderSystem', value: 'ERP_100', type: 'String' }] as Row[],
  properties: [] as Row[],
  paramPrecedence: 'header' as 'header' | 'property',
  missingParameter: 'default' as 'default' | 'error',
  result: null as XsltResult | null,
  running: false,
  live: true,
  casePath: null as string | null,
  compareOn: false,
  compare: null as CompareRun<XsltResult> | null,
});

/** The same run on the other runtime of the same kind (Saxon 9.9 vs 12, Groovy 1.x vs 2.x). */
export interface CompareRun<R> {
  profile: Profile;
  result: R | null;
  error: string;
}

export const SAMPLE_GROOVY_V1 = `import com.sap.gateway.ip.core.customdev.util.Message

// Groovy Script step 1.x (Script API v1). Ctrl+Enter runs the script with the input message.
def Message processData(Message message) {
    def body = message.getBody(String)
    def order = new XmlSlurper().parseText(body)

    message.setHeader('OrderNumber', order.@id.text())
    message.setProperty('ItemCount', order.Item.size())

    def log = messageLogFactory.getMessageLog(message)
    log?.setStringProperty('Order', order.@id.text())

    println "Processing order \${order.@id}"
    message.setBody(body.replace('<Order', '<ProcessedOrder').replace('</Order>', '</ProcessedOrder>'))
    return message
}
`;

export const SAMPLE_GROOVY_V2 = `import com.sap.it.script.v2.api.Message
import groovy.xml.XmlSlurper

// Groovy Script step 2.x (Groovy 4). Note: XmlSlurper now lives in groovy.xml.
def Message processData(Message message) {
    def body = message.getBody(String)
    def order = new XmlSlurper().parseText(body)

    message.setHeader('OrderNumber', order.@id.text())
    message.setProperty('ItemCount', order.Item.size())

    def log = messageLogFactory.getMessageLog(message)
    log?.setStringProperty('Order', order.@id.text())

    println "Processing order \${order.@id}"
    message.setBody(body.replace('<Order', '<ProcessedOrder').replace('</Order>', '</ProcessedOrder>'))
    return message
}
`;

export const groovy = $state({
  scriptPath: null as string | null,
  script: SAMPLE_GROOVY_V1,
  savedScript: SAMPLE_GROOVY_V1,
  functionName: 'processData',
  body: SAMPLE_INPUT,
  headers: [] as Row[],
  properties: [] as Row[],
  bodyType: 'String' as 'String' | 'byte[]' | 'InputStream',
  result: null as GroovyResult | null,
  running: false,
  live: true,
  casePath: null as string | null,
  /** Inputs only a test case can hold (attachments, SOAP headers, platform overrides); kept while it is open. */
  caseExtras: null as CaseExtras | null,
  compareOn: false,
  compare: null as CompareRun<GroovyResult> | null,
});

export interface CaseExtras {
  /** Project-relative folder of the case; attachment files are relative to it. */
  dir: string;
  attachments?: any[];
  soapHeaders?: any[];
  platform?: any;
}

export const xsd = $state({
  schemaPath: null as string | null,
  casePath: null as string | null,
  schema: '',
  savedSchema: '',
  body: '',
  result: null as null | { ok: boolean; valid: boolean; diagnostics: Diagnostic[]; timing: { totalMs: number } },
  running: false,
});

export const xpath = $state({
  expression: '//Item/@sku',
  version: '3.1' as '1.0' | '3.1',
  namespaces: [] as Row[],
  body: SAMPLE_INPUT,
  result: null as null | { ok: boolean; items: any[]; count: number; diagnostics: Diagnostic[]; engine: string },
  running: false,
});

export type FlowStep = 'contentModifier' | 'filter' | 'router' | 'splitter' | 'gather';

/** A Content Modifier row in the iFlow's encoding (type: constant, header, property, xpath, expression, ...). */
export interface CmRow {
  action: 'Create' | 'Delete';
  name: string;
  type: string;
  value: string;
  datatype: string;
  default: string;
}

export interface Route {
  name: string;
  expressionType: 'xml' | 'nonxml';
  condition: string;
  default: boolean;
}

export interface RouteResult {
  status: 'true' | 'false' | 'error' | 'not evaluated' | 'default';
  message?: string;
  /** The Camel spelling of a Non-XML condition, when CPI's had to be rewritten. */
  camel?: string;
}

export const flow = $state({
  step: 'contentModifier' as FlowStep,
  /**
   * The Camel line, from the Groovy engine that carries it. These steps have no Groovy version: the Camel
   * line belongs to the tenant, and SAP has moved tenants to Camel 3.14 (F34), hence the default.
   */
  camel: 'groovy-2.x' as Profile,
  /** The iFlow step each step's settings came from, if any. */
  sources: {} as Partial<Record<FlowStep, string>>,
  body: SAMPLE_INPUT,
  headers: [{ name: 'SenderId', value: 'ERP_100', type: 'String' }] as Row[],
  properties: [] as Row[],
  cm: {
    headers: [{ action: 'Create', name: 'OrderId', type: 'xpath', value: '/Order/@id', datatype: 'java.lang.String', default: '' }] as CmRow[],
    properties: [{ action: 'Create', name: 'Key', type: 'expression', value: '${header.SenderId}-${header.OrderId}', datatype: '', default: '' }] as CmRow[],
    bodyType: 'constant' as 'constant' | 'expression',
    body: '',
  },
  router: {
    routes: [
      { name: 'ERP', expressionType: 'nonxml', condition: "${header.SenderId} = 'ERP_100'", default: false },
      { name: 'Large order', expressionType: 'xml', condition: 'count(/Order/Item) > 5', default: false },
      { name: 'Default', expressionType: 'xml', condition: '', default: true },
    ] as Route[],
    throwException: false,
  },
  splitter: { splitter: 'general' as 'general' | 'iterating', expressionType: 'xpath' as 'xpath' | 'token' | 'lineBreak',
    expression: '/Order/Item', grouping: '', streaming: true },
  filter: { expression: "/Order/Item[@qty > 1]", valueType: 'nodelist' },
  /** The messages to gather: a splitter's result or bodies added by hand. */
  gather: { messageType: 'sameXml', algorithm: 'combine', sourceXPath: '', targetXPath: '', messages: [] as string[] },
  cmResult: null as any,
  routerResult: null as null | { routes: RouteResult[]; taken: string; diagnostics: string[] },
  splitResult: null as any,
  filterResult: null as any,
  gatherResult: null as any,
  running: false,
});

/** In-app prompt (window.prompt is not available in every desktop webview). */
export const dialog = $state({
  open: false,
  title: '',
  value: '',
  checkboxLabel: '' as string,
  checked: true,
  /** false: a yes/no question without a text field. */
  input: true,
  resolve: null as null | ((r: { value: string; checked: boolean } | null) => void),
});

export function ask(title: string, value: string, checkboxLabel = ''): Promise<{ value: string; checked: boolean } | null> {
  return new Promise((resolve) => {
    Object.assign(dialog, { open: true, title, value, checkboxLabel, checked: true, input: true, resolve });
  });
}

/** In-app confirmation (window.confirm is not available in every desktop webview). */
export function confirm(title: string): Promise<boolean> {
  return new Promise((resolve) => {
    Object.assign(dialog, { open: true, title, value: '', checkboxLabel: '', checked: true, input: false,
      resolve: (r: unknown) => resolve(r !== null) });
  });
}

export function closeDialog(ok: boolean) {
  const r = dialog.resolve;
  dialog.open = false;
  dialog.resolve = null;
  r?.(ok && (!dialog.input || dialog.value.trim()) ? { value: dialog.value.trim(), checked: dialog.checked } : null);
}

function fail(e: unknown) {
  app.error = e instanceof Error ? e.message : String(e);
}

export async function init() {
  try {
    const { profiles, info } = await availableProfiles();
    app.profiles = profiles;
    app.engineInfo = info;
    const firstXslt = profiles.find((p) => p.available && p.kind === 'xslt');
    const firstGroovy = profiles.find((p) => p.available && p.kind === 'groovy');
    if (firstXslt && !profiles.find((p) => p.id === app.profile)?.available) app.profile = firstXslt.id;
    if (firstGroovy && !profiles.find((p) => p.id === app.groovyProfile)?.available) app.groovyProfile = firstGroovy.id;
    if (!firstXslt && firstGroovy) app.tool = 'groovy';
    const saved = safeGet('cpiwb.project');
    if (saved) await open(saved).catch(() => undefined);
  } catch (e) {
    fail(e);
  }
}

function safeGet(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function safeSet(key: string, v: string | null) {
  try { v === null ? localStorage.removeItem(key) : localStorage.setItem(key, v); } catch { /* ignore */ }
}

// ------------------------------------------------------------------ project

export async function open(dir: string, initProject = false) {
  app.error = '';
  const r = await openProject(dir, initProject);
  app.project = { root: r.root, files: r.files };
  safeSet('cpiwb.project', r.root);
  await loadIflowSteps();
  if (!r.isProject && !initProject) app.status = 'Folder has no cpi-workbench.json – it still works; use "Init" to add the standard folders.';
  else app.status = 'Project opened: ' + r.root;
}

export async function closeProject() {
  // Mappings and scripts stay in their editors as unsaved text; the File tab closes with the project.
  if (!(await mayDiscard('file'))) return;
  await closeEngineProject();
  app.project = null;
  safeSet('cpiwb.project', null);
  xslt.mappingPath = null;
  xslt.casePath = null;
  groovy.scriptPath = null;
  groovy.casePath = null;
  groovy.caseExtras = null;
  xsd.schemaPath = null;
  xsd.casePath = null;
  closeFileView();
  app.inputPaths = {};
  app.iflowSteps = [];
}

export async function refreshFiles() {
  if (!app.project) return;
  const r = await call('project.list');
  app.project.files = r.files;
}

export async function loadIflowSteps() {
  if (!app.project) return;
  try {
    app.iflowSteps = (await call('iflow.steps')).steps;
  } catch {
    app.iflowSteps = [];
  }
}

export async function importIflow(zip: string) {
  const r = await call('iflow.import', { zip });
  if (app.project) app.project.files = r.fileList;
  app.iflowSteps = r.steps;
  app.status = `Imported ${r.imported} (${r.files} files, ${r.steps.length} steps found)`;
}

/** Opens the file of an imported iFlow step with the step's settings (function, script version). */
export async function openIflowStep(step: IflowStep) {
  if (step.config) return openFlowStep(step);
  if (!step.file || !step.fileFound) {
    app.error = step.note ? `Step "${step.name}": ${step.note}`
      : `The file of step "${step.name}" was not found in the export (${step.file ?? 'no reference'}).`;
    return;
  }
  // The step's function and runtime go in with the script, so a live run uses them from the start.
  await openFile(step.file, step.type === 'groovy' ? { functionName: step.function ?? 'processData', profile: step.profile } : {});
}

export async function readFile(path: string): Promise<string> {
  return (await call('file.read', { path })).content;
}

export async function writeFile(path: string, content: string) {
  await call('file.write', { path, content });
}

/** The editor a file opens in, or null for inputs (they replace the input body, not an editor). */
function editorFor(path: string): Editor | null {
  const lower = path.toLowerCase();
  if (lower.endsWith('.xsl') || lower.endsWith('.xslt')) return 'xslt';
  if (lower.endsWith('.groovy') || lower.endsWith('.gsh')) return 'groovy';
  if (lower.endsWith('.xsd')) return 'xsd';
  return path.startsWith('inputs/') ? null : 'file';
}

type Editor = 'xslt' | 'groovy' | 'xsd' | 'file';

/** Asks before an editor's unsaved changes are replaced; true when nothing would be lost. */
export async function mayDiscard(editor: Editor | null): Promise<boolean> {
  const [dirty, name] = editor === 'xslt' ? [xslt.mapping !== xslt.savedMapping, xslt.mappingPath ?? 'the unsaved mapping']
    : editor === 'groovy' ? [groovy.script !== groovy.savedScript, groovy.scriptPath ?? 'the unsaved script']
    : editor === 'xsd' ? [xsd.schema !== xsd.savedSchema, xsd.schemaPath ?? 'the unsaved schema']
    : editor === 'file' ? [fileView.path !== null && fileView.content !== fileView.saved, fileView.path]
    : [false, null];
  return !dirty || confirm(`Discard the unsaved changes to ${name}?`);
}

export interface OpenOptions {
  /** The caller already asked about unsaved changes. */
  discardChecked?: boolean;
  /** Groovy step settings (from an iFlow step); a plain script runs processData. */
  functionName?: string;
  profile?: Profile;
}

export async function openFile(path: string, opts: OpenOptions = {}) {
  try {
    app.error = '';
    const lower = path.toLowerCase();
    if (lower.endsWith('case.json')) return await loadCase(path);
    if (!opts.discardChecked && !(await mayDiscard(editorFor(path)))) return;
    const content = await readFile(path);
    if (lower.endsWith('.xsl') || lower.endsWith('.xslt')) {
      xslt.mappingPath = path;
      xslt.mapping = content;
      xslt.savedMapping = content;
      xslt.casePath = null;
      app.tool = 'xslt';
      rerunIfLive('xslt');
    } else if (lower.endsWith('.groovy') || lower.endsWith('.gsh')) {
      groovy.scriptPath = path;
      groovy.script = content;
      groovy.savedScript = content;
      groovy.casePath = null;
      groovy.caseExtras = null;
      groovy.functionName = opts.functionName ?? 'processData';
      if (opts.profile && hasProfile(opts.profile)) app.groovyProfile = opts.profile;
      else if (content.includes('com.sap.it.script.v2.api') && hasProfile('groovy-2.x')) app.groovyProfile = 'groovy-2.x';
      else if (content.includes('com.sap.gateway.ip.core.customdev.util') && hasProfile('groovy-1.x')) app.groovyProfile = 'groovy-1.x';
      app.tool = 'groovy';
      rerunIfLive('groovy');
    } else if (lower.endsWith('.xsd')) {
      xsd.schemaPath = path;
      xsd.schema = content;
      xsd.savedSchema = content;
      xsd.casePath = null;
      app.tool = 'xsd';
    } else if (path.startsWith('inputs/')) {
      setInput(path, content);
    } else {
      fileView.path = path;
      fileView.content = content;
      fileView.saved = content;
      app.tool = 'file';
    }
  } catch (e) {
    fail(e);
  }
}

export async function saveMapping(): Promise<boolean> {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return false;
  }
  const path = xslt.mappingPath ?? await askNewPath('mapping', null, 'mappings/mapping.xsl');
  if (!path) return false;
  await writeFile(path, xslt.mapping);
  xslt.mappingPath = path;
  xslt.savedMapping = xslt.mapping;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

/** A project file that is not a step (platform data, configuration, iFlow files) open in the File tab. */
export const fileView = $state({ path: null as string | null, content: '', saved: '' });

export function closeFileView() {
  fileView.path = null;
  fileView.content = fileView.saved = '';
  if (app.tool === 'file') app.tool = app.stepTool;
}

export async function saveFileView(): Promise<boolean> {
  if (!fileView.path) return false;
  await writeFile(fileView.path, fileView.content);
  fileView.saved = fileView.content;
  app.status = 'Saved ' + fileView.path;
  return true;
}

function stepBody(tool: StepTool) {
  return tool === 'xsd' ? xsd.body : tool === 'xpath' ? xpath.body : tool === 'flow' ? flow.body
    : tool === 'groovy' ? groovy.body : xslt.body;
}

function setInput(path: string, content: string) {
  // XSLT, XSD and XPath read XML only; a JSON body can only feed a Groovy script (or the Flow Steps, on the same engine).
  const json = /\.json$/i.test(path) || /^\s*[[{]/.test(content);
  if (json && !app.profiles.some((p) => p.kind === 'groovy' && p.available)) {
    app.error = `${path} is JSON: only a Groovy script takes a JSON body, and no Groovy engine is available.`;
    return;
  }
  const tool: StepTool = json && app.stepTool !== 'flow' ? 'groovy' : app.stepTool;
  if (tool === 'xsd') xsd.body = content;
  else if (tool === 'xpath') xpath.body = content;
  else if (tool === 'flow') flow.body = content;
  else if (tool === 'groovy') groovy.body = content;
  else xslt.body = content;
  app.inputPaths[tool] = path;
  app.tool = tool;
  rerunIfLive(tool);
  app.status = `Loaded ${path} as input body`;
}

/** Loads any file as the input body of the current step tool. */
export async function useAsInput(path: string) {
  try {
    setInput(path, await readFile(path));
  } catch (e) {
    fail(e);
  }
}

/** Saves the input body of the current step tool under inputs/, so it survives opening other files. */
export async function saveInput() {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return;
  }
  const body = stepBody(app.stepTool);
  const ext = /^\s*[[{]/.test(body) ? 'json' : /^\s*</.test(body) ? 'xml' : 'txt';
  const r = await ask('Save input body as (path inside the project)', `inputs/input.${ext}`);
  if (!r) return;
  try {
    if (!(await mayOverwrite(r.value))) return;
    await writeFile(r.value, body);
    app.inputPaths[app.stepTool] = r.value;
    app.status = 'Saved input as ' + r.value;
    await refreshFiles();
  } catch (e) {
    fail(e);
  }
}

/** Points the editors that show `from` to `to`; with `to` null the editor keeps the text as unsaved. */
function movePaths(from: string, to: string | null) {
  if (xslt.mappingPath === from) {
    xslt.mappingPath = to;
    if (!to) xslt.savedMapping = '';
  }
  if (groovy.scriptPath === from) {
    groovy.scriptPath = to;
    if (!to) groovy.savedScript = '';
  }
  if (xsd.schemaPath === from) {
    xsd.schemaPath = to;
    if (!to) xsd.savedSchema = '';
  }
  if (xslt.casePath === from) xslt.casePath = to;
  if (groovy.casePath === from) groovy.casePath = to;
  if (xsd.casePath === from) xsd.casePath = to;
  if (fileView.path === from) {
    if (to) fileView.path = to;
    else closeFileView();
  }
}

export async function deleteFile(path: string) {
  if (!(await confirm(`Delete ${path}?`))) return;
  try {
    await call('file.delete', { path });
    movePaths(path, null);
    app.status = 'Deleted ' + path;
    await refreshFiles();
  } catch (e) {
    fail(e);
  }
}

/** Rename and copy go through read and write: the engines have no move call, and the tree lists text files only. */
export async function copyFile(path: string, move: boolean) {
  const r = await ask(move ? `Rename ${path} to` : `Copy ${path} to`, path);
  if (!r || r.value === path) return;
  try {
    if (!(await mayOverwrite(r.value))) return;
    await writeFile(r.value, await readFile(path));
    if (move) {
      await call('file.delete', { path });
      movePaths(path, r.value);
    }
    app.status = (move ? 'Renamed ' : 'Copied ') + path + ' to ' + r.value;
    await refreshFiles();
  } catch (e) {
    fail(e);
  }
}

function hasProfile(id: Profile) {
  return !!app.profiles.find((p) => p.id === id && p.available);
}

export async function saveScript(): Promise<boolean> {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return false;
  }
  const path = groovy.scriptPath ?? await askNewPath('script', null, 'scripts/script.groovy');
  if (!path) return false;
  await writeFile(path, groovy.script);
  groovy.scriptPath = path;
  groovy.savedScript = groovy.script;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

/** Switching 1.x <-> 2.x swaps the untouched sample script to the matching API. */
export function onGroovyProfileChange() {
  if (groovy.script === SAMPLE_GROOVY_V1 && app.groovyProfile === 'groovy-2.x') groovy.script = groovy.savedScript = SAMPLE_GROOVY_V2;
  if (groovy.script === SAMPLE_GROOVY_V2 && app.groovyProfile === 'groovy-1.x') groovy.script = groovy.savedScript = SAMPLE_GROOVY_V1;
}

export async function saveSchema(): Promise<boolean> {
  if (!app.project || !xsd.schemaPath) {
    const p = await askNewPath('schema', null, 'schemas/schema.xsd');
    if (!p) return false;
    xsd.schemaPath = p;
  }
  await writeFile(xsd.schemaPath!, xsd.schema);
  xsd.savedSchema = xsd.schema;
  app.status = 'Saved ' + xsd.schemaPath;
  await refreshFiles();
  return true;
}

function fileExists(path: string): boolean {
  const key = pathKey(path);
  return !!app.project?.files.some((f) => pathKey(f.path) === key);
}

/**
 * A name no file has yet: `path` itself (or path + suffix), else -2, -3, ...
 * mappings/x.xsl with suffix '-copy' -> mappings/x-copy.xsl, mappings/x-copy-2.xsl, ...
 */
export function freePath(path: string, suffix = ''): string {
  const dot = path.lastIndexOf('.');
  const [stem, ext] = dot > path.lastIndexOf('/') ? [path.slice(0, dot), path.slice(dot)] : [path, ''];
  let name = `${stem}${suffix}${ext}`;
  for (let n = 2; fileExists(name); n++) name = `${stem}${suffix}-${n}${ext}`;
  return name;
}

/** True if no file has this path or the user agreed to overwrite it. */
export async function mayOverwrite(path: string): Promise<boolean> {
  // Files may have appeared outside the app since the tree was listed.
  await refreshFiles().catch(() => undefined);
  return !fileExists(path) || confirm(`${path} exists. Overwrite it?`);
}

/** Asks for a new path; an existing file is only overwritten after a second question. */
async function askNewPath(what: string, path: string | null, fallback: string): Promise<string | null> {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return null;
  }
  const r = await ask(`Save ${what} as (path inside the project)`, path ? freePath(path, '-copy') : freePath(fallback));
  if (!r) return null;
  if ((!path || pathKey(r.value) !== pathKey(path)) && !(await mayOverwrite(r.value))) return null;
  return r.value;
}

export async function saveMappingAs(): Promise<boolean> {
  const path = await askNewPath('mapping', xslt.mappingPath, 'mappings/mapping.xsl');
  if (!path) return false;
  await writeFile(path, xslt.mapping);
  xslt.mappingPath = path;
  xslt.savedMapping = xslt.mapping;
  xslt.casePath = null;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

export async function saveScriptAs(): Promise<boolean> {
  const path = await askNewPath('script', groovy.scriptPath, 'scripts/script.groovy');
  if (!path) return false;
  await writeFile(path, groovy.script);
  groovy.scriptPath = path;
  groovy.savedScript = groovy.script;
  groovy.casePath = null;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

export async function saveSchemaAs(): Promise<boolean> {
  const path = await askNewPath('schema', xsd.schemaPath, 'schemas/schema.xsd');
  if (!path) return false;
  await writeFile(path, xsd.schema);
  xsd.schemaPath = path;
  xsd.savedSchema = xsd.schema;
  xsd.casePath = null;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

// ------------------------------------------------------------------ runs

let runSeq = 0;

function xsltRequest() {
  return {
    xslt: { content: xslt.mapping, path: xslt.mappingPath ?? 'mapping.xsl' },
    input: { body: xslt.body, headers: clean(xslt.headers), properties: clean(xslt.properties) },
    options: { paramPrecedence: xslt.paramPrecedence, missingParameter: xslt.missingParameter },
  };
}

function groovyRequest() {
  return {
    script: { content: groovy.script, path: groovy.scriptPath ?? 'script.groovy' },
    function: groovy.functionName || 'processData',
    input: {
      body: groovy.body, headers: clean(groovy.headers), properties: clean(groovy.properties),
      attachments: groovy.caseExtras?.attachments, soapHeaders: groovy.caseExtras?.soapHeaders,
    },
    options: { bodyType: groovy.bodyType },
    platform: groovy.caseExtras?.platform,
    caseDir: groovy.caseExtras?.dir,
  };
}

/** The other available runtime of the same kind, if any. */
export function otherProfile(current: Profile): Profile | null {
  const kind = app.profiles.find((p) => p.id === current)?.kind;
  return app.profiles.find((p) => p.available && p.kind === kind && p.id !== current)?.id ?? null;
}

async function compareRun<R>(method: string, params: any, current: Profile): Promise<CompareRun<R> | null> {
  const other = otherProfile(current);
  if (!other) return null;
  try {
    return { profile: other, result: await call<R>(method, params, other), error: '' };
  } catch (e) {
    return { profile: other, result: null, error: (e as Error).message ?? String(e) };
  }
}

/** A mapping, script or input loaded from a file re-runs like a change typed in the editor. */
function rerunIfLive(tool: StepTool) {
  if (tool === 'xslt' && xslt.live) void runXslt();
  else if (tool === 'groovy' && groovy.live) void runGroovy();
}

export async function runXslt() {
  const seq = ++runSeq;
  xslt.running = true;
  app.error = '';
  try {
    const params = xsltRequest();
    const [r, c] = await Promise.all([
      call<XsltResult>('xslt.run', params, app.profile),
      xslt.compareOn ? compareRun<XsltResult>('xslt.run', params, app.profile) : Promise.resolve(null),
    ]);
    if (seq === runSeq) {
      xslt.result = r;
      xslt.compare = c;
    }
  } catch (e) {
    if (seq === runSeq) fail(e);
  } finally {
    if (seq === runSeq) xslt.running = false;
  }
}

let groovySeq = 0;

export async function runGroovy() {
  const seq = ++groovySeq;
  groovy.running = true;
  app.error = '';
  try {
    const params = groovyRequest();
    const [r, c] = await Promise.all([
      call<GroovyResult>('groovy.run', params, app.groovyProfile),
      groovy.compareOn ? compareRun<GroovyResult>('groovy.run', params, app.groovyProfile) : Promise.resolve(null),
    ]);
    if (seq === groovySeq) {
      groovy.result = r;
      groovy.compare = c;
    }
  } catch (e) {
    if (seq === groovySeq) fail(e);
  } finally {
    if (seq === groovySeq) groovy.running = false;
  }
}

function clean(rows: Row[]) {
  return rows.filter((r) => r.name.trim() !== '').map((r) => ({ ...r, name: r.name.trim() }));
}

export async function runXsd() {
  xsd.running = true;
  app.error = '';
  try {
    xsd.result = await call('xsd.validate', {
      schema: { content: xsd.schema, path: xsd.schemaPath ?? 'schema.xsd' },
      input: { body: xsd.body },
    }, app.profile);
  } catch (e) {
    fail(e);
  } finally {
    xsd.running = false;
  }
}

export async function runXpath() {
  xpath.running = true;
  app.error = '';
  const ns: Record<string, string> = {};
  for (const r of xpath.namespaces) if (r.name.trim() && !r.disabled) ns[r.name.trim()] = r.value;
  try {
    xpath.result = await call('xpath.eval', { expression: xpath.expression, version: xpath.version,
      namespaces: ns, input: { body: xpath.body } }, app.profile);
  } catch (e) {
    fail(e);
  } finally {
    xpath.running = false;
  }
}

// ------------------------------------------------------------------ flow steps
// XPath runs in the XML engine (Saxon, XPath 3.1 as SAP documents for the Content Modifier), Camel Simple and
// the Content Modifier in the Groovy engine's Camel. The Router is evaluated here, route by route.

function namespaceMap() {
  const ns: Record<string, string> = {};
  for (const r of xpath.namespaces) if (r.name.trim() && !r.disabled) ns[r.name.trim()] = r.value;
  return ns;
}

/** XPath and the splitter use CPI's Saxon line (9.9) when it is installed, whatever the XSLT tab is set to. */
function flowXmlProfile(): Profile {
  return hasProfile('saxon-he-9.9') ? 'saxon-he-9.9' : app.profile;
}

function flowInput() {
  return { body: flow.body, headers: clean(flow.headers), properties: clean(flow.properties) };
}

async function xpathValue(expression: string, as: 'boolean' | 'string' | 'nodes') {
  // Headers and properties are XPath variables ($name), as in CPI's Camel.
  const r = await call('xpath.eval', { expression, version: '3.1', as, namespaces: namespaceMap(), input: flowInput() }, flowXmlProfile());
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'XPath failed');
  return r.value as string;
}

async function simpleEval(expression: string, mode: 'condition' | 'expression') {
  const r = await call('simple.eval', { expression, mode, input: flowInput() }, flow.camel);
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'Camel Simple failed');
  return r as { value: string | null; evaluated?: string };
}

export async function runFlowStep() {
  flow.running = true;
  app.error = '';
  try {
    if (flow.step === 'contentModifier') await runContentModifier();
    else if (flow.step === 'filter') await runFilter();
    else if (flow.step === 'router') await runRouter();
    else if (flow.step === 'splitter') await runSplitter();
    else await runGather();
  } catch (e) {
    fail(e);
  } finally {
    flow.running = false;
  }
}

async function runContentModifier() {
  // XPath rows read the incoming body, which the step changes only after all header and property rows.
  const rows = async (list: CmRow[]) => Promise.all(list.map(async (r) => {
    if (r.action !== 'Create' || r.type !== 'xpath') return { ...r };
    try {
      return { ...r, resolved: await xpathValue(r.value, r.datatype.startsWith('org.w3c.dom.') ? 'nodes' : 'string') };
    } catch (e) {
      throw new Error(`XPath of ${r.name}: ${(e as Error).message}`);
    }
  }));
  flow.cmResult = await call('contentModifier.run', {
    headers: await rows(flow.cm.headers), properties: await rows(flow.cm.properties),
    bodyType: flow.cm.bodyType, body: flow.cm.body, input: flowInput(),
  }, flow.camel);
}

async function runRouter() {
  const routes: RouteResult[] = flow.router.routes.map((r) => ({ status: r.default ? 'default' : 'not evaluated' }));
  let taken = '';
  for (const [i, r] of flow.router.routes.entries()) {
    if (r.default) continue;
    try {
      const s = r.expressionType === 'xml' ? { value: await xpathValue(r.condition, 'boolean') } : await simpleEval(r.condition, 'condition');
      routes[i] = { status: s.value === 'true' ? 'true' : 'false', camel: (s as { evaluated?: string }).evaluated };
    } catch (e) {
      routes[i] = { status: 'error', message: (e as Error).message };
      taken = 'Error: the route condition failed, so CPI raises an exception.';
      break;
    }
    if (routes[i].status === 'true') {
      taken = r.name;
      break;
    }
  }
  if (!taken) {
    const d = flow.router.routes.find((r) => r.default);
    taken = flow.router.throwException ? 'No route matched: Throw Exception is set, so the Router raises an exception.'
      : d ? d.name + ' (default route)' : 'No route matched and there is no default route.';
  }
  flow.routerResult = { routes, taken, diagnostics: [] };
}

async function runSplitter() {
  // XPath and grouping may refer to a header or property (SAP); Camel Simple resolves the reference first.
  const resolve = async (v: string) => (v.includes('${') ? (await simpleEval(v, 'expression')).value ?? '' : v);
  const s = flow.splitter;
  flow.splitResult = await call('split.run', {
    splitter: s.splitter, expressionType: s.expressionType, expression: await resolve(s.expression),
    grouping: await resolve(String(s.grouping ?? '')), streaming: s.streaming, namespaces: namespaceMap(), input: { body: flow.body },
  }, flowXmlProfile());
}

/** SAP: `function:simple('${property.x}')` in a Filter XPath; Camel Simple gives the value, as an XPath string. */
async function resolveSimpleFunctions(xpathExpr: string) {
  let out = xpathExpr;
  for (const m of xpathExpr.matchAll(/function:simple\(\s*(?:'([^']*)'|"([^"]*)")\s*\)/g)) {
    const v = (await simpleEval(m[1] ?? m[2], 'expression')).value ?? '';
    out = out.replace(m[0], "'" + v.replaceAll("'", "''") + "'");
  }
  return out;
}

async function runFilter() {
  flow.filterResult = await call('filter.run', {
    expression: await resolveSimpleFunctions(flow.filter.expression), valueType: flow.filter.valueType,
    namespaces: namespaceMap(), input: flowInput(),
  }, flowXmlProfile());
}

async function runGather() {
  const g = flow.gather;
  flow.gatherResult = await call('gather.run', {
    messageType: g.messageType, algorithm: g.algorithm, sourceXPath: g.sourceXPath, targetXPath: g.targetXPath,
    namespaces: namespaceMap(), messages: g.messages,
  }, flowXmlProfile());
}

/** The splitter's messages go to the Gather step, as in an iFlow's Splitter → … → Gather. */
export function gatherSplitResult() {
  flow.gather.messages = (flow.splitResult?.messages ?? []).map((m: { body: string }) => m.body);
  flow.gatherResult = null;
  flow.step = 'gather';
}

/** A result message becomes the next input, to try steps one after another. */
export function useAsFlowInput(body: string, headers?: Row[], properties?: Row[]) {
  flow.body = body;
  if (headers) flow.headers = headers.map((r) => ({ ...r }));
  if (properties) flow.properties = properties.map((r) => ({ ...r }));
  delete app.inputPaths.flow;
}

function openFlowStep(step: IflowStep) {
  const c = step.config ?? {};
  const row = (r: any): CmRow => ({ action: r.action === 'Delete' ? 'Delete' : 'Create', name: r.name ?? '', type: r.type ?? 'constant',
    value: r.value ?? '', datatype: r.datatype ?? '', default: r.default ?? '' });
  if (step.type === 'contentModifier') {
    flow.cm = { headers: (c.headers ?? []).map(row), properties: (c.properties ?? []).map(row),
      bodyType: c.bodyType === 'expression' ? 'expression' : 'constant', body: c.body ?? '' };
  } else if (step.type === 'router') {
    flow.router = { routes: c.routes ?? [], throwException: !!c.throwException };
  } else if (step.type === 'filter') {
    flow.filter = { expression: c.expression ?? '', valueType: c.valueType ?? 'nodelist' };
  } else if (step.type === 'gather') {
    // The messages stay: they come from the splitter, not from the iFlow.
    flow.gather = { ...flow.gather, messageType: c.messageType ?? 'sameXml', algorithm: c.algorithm ?? 'combine',
      sourceXPath: c.sourceXPath ?? '', targetXPath: c.targetXPath ?? '' };
  } else {
    flow.splitter = { splitter: c.splitter, expressionType: c.expressionType, expression: c.expression ?? '',
      grouping: c.grouping ?? '', streaming: c.streaming !== false };
  }
  flow.step = step.type as FlowStep;
  flow.sources[step.type as FlowStep] = `${step.iflow} · ${step.name || step.id}`;
  flow.cmResult = flow.routerResult = flow.splitResult = flow.filterResult = flow.gatherResult = null;
  app.tool = 'flow';
  app.status = `Loaded ${step.name || step.id} from ${step.iflow}`;
}

export async function formatXml(text: string): Promise<string> {
  const r = await call('xml.format', { text }, app.profile);
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'Not well-formed XML');
  return r.text;
}

// ------------------------------------------------------------------ test cases

function joinPath(dir: string, rel: string): string {
  const parts = [...dir.split('/').filter(Boolean)];
  for (const p of rel.split('/')) {
    if (p === '..') parts.pop();
    else if (p !== '.' && p !== '') parts.push(p);
  }
  return parts.join('/');
}

async function loadGroovyCase(path: string, dir: string, tc: any) {
  const scriptPath = joinPath(dir, tc.script);
  groovy.scriptPath = scriptPath;
  groovy.script = await readFile(scriptPath);
  groovy.savedScript = groovy.script;
  const body = tc.input?.body;
  groovy.body = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  groovy.headers = toRows(tc.input?.headers);
  groovy.properties = toRows(tc.input?.properties);
  groovy.functionName = tc.function ?? 'processData';
  groovy.bodyType = tc.options?.bodyType ?? 'String';
  if (tc.profile && hasProfile(tc.profile)) app.groovyProfile = tc.profile;
  const extras: CaseExtras = { dir, attachments: tc.input?.attachments, soapHeaders: tc.input?.soapHeaders, platform: tc.platform };
  groovy.caseExtras = extras.attachments || extras.soapHeaders || extras.platform ? extras : null;
  groovy.casePath = path;
  app.tool = 'groovy';
  app.status = 'Loaded test case ' + (tc.name ?? path);
  await runGroovy();
}

async function loadXsdCase(path: string, dir: string, tc: any) {
  const schemaPath = joinPath(dir, tc.schema);
  xsd.schemaPath = schemaPath;
  xsd.schema = await readFile(schemaPath);
  xsd.savedSchema = xsd.schema;
  const body = tc.input?.body;
  xsd.body = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  xsd.casePath = path;
  app.tool = 'xsd';
  app.status = 'Loaded test case ' + (tc.name ?? path);
  await runXsd();
}

export async function loadCase(path: string) {
  const tc = JSON.parse(await readFile(path));
  const dir = path.split('/').slice(0, -1).join('/');
  if (!(await mayDiscard(tc.step === 'groovy' ? 'groovy' : tc.step === 'xsd' ? 'xsd' : 'xslt'))) return;
  if (tc.step === 'groovy') return loadGroovyCase(path, dir, tc);
  if (tc.step === 'xsd') return loadXsdCase(path, dir, tc);
  if ((tc.step ?? 'xslt') !== 'xslt') {
    app.error = `Step '${tc.step}' cannot be opened in an editor view.`;
    return;
  }
  const mappingPath = joinPath(dir, tc.xslt);
  xslt.mappingPath = mappingPath;
  xslt.mapping = await readFile(mappingPath);
  xslt.savedMapping = xslt.mapping;
  const body = tc.input?.body;
  xslt.body = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  xslt.headers = toRows(tc.input?.headers);
  xslt.properties = toRows(tc.input?.properties);
  xslt.paramPrecedence = tc.options?.paramPrecedence ?? 'header';
  xslt.missingParameter = tc.options?.missingParameter ?? 'default';
  if (tc.profile && app.profiles.find((p) => p.id === tc.profile && p.available)) app.profile = tc.profile;
  xslt.casePath = path;
  app.tool = 'xslt';
  app.status = 'Loaded test case ' + (tc.name ?? path);
  await runXslt();
}
