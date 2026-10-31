<script lang="ts">
  const KIND: Record<string, string> = { groovy: 'G', xslt: 'X', xsd: 'V', contentModifier: 'C', filter: 'F', router: 'R', splitter: 'S', gather: 'A' };
  const LABEL: Record<string, string> = { contentModifier: 'Content Modifier', filter: 'Filter', router: 'Router', splitter: 'Splitter', gather: 'Gather' };
  import { isDesktop, pickFolder, pickZip } from '../lib/engine';
  import { app, open, closeProject, openFile, mayDiscard, mayOverwrite, freePath, refreshFiles, writeFile, ask, xslt, xsd, groovy, SAMPLE_GROOVY_V1, SAMPLE_GROOVY_V2,
    importIflow, openIflowStep, loadIflowSteps, deleteFile, copyFile, useAsInput, fileView } from '../lib/store.svelte';

  let dirInput = $state('');
  let filter = $state('');
  let collapsed = $state<Record<string, boolean>>({});
  let menu = $state<{ x: number; y: number; path: string } | null>(null);

  function openMenu(e: MouseEvent, path: string) {
    e.preventDefault();
    menu = { x: Math.min(e.clientX, window.innerWidth - 180), y: Math.min(e.clientY, window.innerHeight - 160), path };
  }

  function pick(action: (path: string) => unknown) {
    const path = menu!.path;
    menu = null;
    action(path);
  }

  async function openDialog() {
    try {
      const dir = isDesktop ? await pickFolder() : dirInput.trim();
      if (dir) await open(dir);
    } catch (e) {
      app.error = (e as Error).message;
    }
  }

  async function importZip() {
    try {
      const zip = isDesktop ? await pickZip() : (await ask('Path of the exported iFlow (.zip)', ''))?.value;
      if (zip) await importIflow(zip);
    } catch (e) {
      app.error = (e as Error).message;
    }
  }

  async function initProject() {
    if (!app.project) return;
    await open(app.project.root, true).catch((e) => (app.error = e.message));
  }

  async function newFile(kind: 'xsl' | 'xsd' | 'groovy') {
    const def = freePath(kind === 'xsl' ? 'mappings/new-mapping.xsl' : kind === 'xsd' ? 'schemas/new-schema.xsd' : 'scripts/new-script.groovy');
    const r = await ask(kind === 'xsl' ? 'New mapping (path)' : kind === 'xsd' ? 'New schema (path)' : 'New Groovy script (path)', def);
    if (!r || !(await mayOverwrite(r.value))) return;
    // Ask before writing, so a "no" leaves neither a new file nor a lost editor.
    if (!(await mayDiscard(kind === 'xsl' ? 'xslt' : kind === 'xsd' ? 'xsd' : 'groovy'))) return;
    const content = kind === 'groovy' ? (app.groovyProfile === 'groovy-2.x' ? SAMPLE_GROOVY_V2 : SAMPLE_GROOVY_V1)
      : kind === 'xsl'
      ? `<?xml version="1.0" encoding="UTF-8"?>\n<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"\n    xmlns:hci="http://sap.com/it/" exclude-result-prefixes="hci">\n  <xsl:param name="exchange"/>\n  <xsl:output method="xml" indent="yes"/>\n\n  <xsl:template match="/">\n    <xsl:copy-of select="."/>\n  </xsl:template>\n</xsl:stylesheet>\n`
      : `<?xml version="1.0" encoding="UTF-8"?>\n<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="qualified">\n  <xs:element name="Root" type="xs:string"/>\n</xs:schema>\n`;
    try {
      await writeFile(r.value, content);
      await refreshFiles();
      await openFile(r.value, { discardChecked: true });
    } catch (e) {
      app.error = (e as Error).message;
    }
  }

  const RELEVANT = /\.(xsl|xslt|xsd|xml|json|groovy|txt|csv)$/i;
  let files = $derived((app.project?.files ?? []).filter((f) => !f.dir && RELEVANT.test(f.path)
    && (!filter || f.path.toLowerCase().includes(filter.toLowerCase()))));
  let groups = $derived.by(() => {
    const g = new Map<string, string[]>();
    for (const f of files) {
      const top = f.path.includes('/') ? f.path.split('/')[0] : '.';
      if (!g.has(top)) g.set(top, []);
      g.get(top)!.push(f.path);
    }
    const order = ['mappings', 'scripts', 'schemas', 'inputs', 'tests', 'platform', 'iflows'];
    return [...g.entries()].sort((a, b) => (order.indexOf(a[0]) + 1 || 99) - (order.indexOf(b[0]) + 1 || 99) || a[0].localeCompare(b[0]));
  });

  function label(path: string, group: string) {
    const rest = group === '.' ? path : path.slice(group.length + 1);
    return rest;
  }

  /** Marks the files of the tab in front: its input, its mapping, script or schema, and its case. */
  function isActive(path: string) {
    if (app.tool === 'file') return path === fileView.path;
    if (path === app.inputPaths[app.stepTool]) return true;
    if (app.stepTool === 'xslt') return path === xslt.mappingPath || path === xslt.casePath;
    if (app.stepTool === 'groovy') return path === groovy.scriptPath || path === groovy.casePath;
    if (app.stepTool === 'xsd') return path === xsd.schemaPath || path === xsd.casePath;
    return false;
  }
</script>

<aside class="side">
  <div class="proj">
    {#if app.project}
      <div class="root" title={app.project.root}>{app.project.root.split(/[\\/]/).pop()}</div>
      <div class="row">
        <button class="small" onclick={() => refreshFiles()} title="Reload file list">↻</button>
        <button class="small" onclick={() => newFile('xsl')}>+ Mapping</button>
        <button class="small" onclick={() => newFile('xsd')}>+ Schema</button>
        <button class="small" onclick={() => newFile('groovy')}>+ Script</button>
        <button class="small" onclick={importZip} title="Import an iFlow exported from Integration Suite (zip)">Import iFlow</button>
        <button class="small" onclick={initProject} title="Create mappings/, schemas/, tests/ and cpi-workbench.json">Init</button>
        <button class="small" onclick={() => void closeProject()} title="Close project">✕</button>
      </div>
      <input class="filter" placeholder="Filter files" bind:value={filter} aria-label="Filter files" />
    {:else}
      <p class="muted">Open a project folder (e.g. an exported iFlow's <code>src/main/resources</code> or your own mapping repo).</p>
      {#if !isDesktop}
        <input class="mono" placeholder="/path/to/project" bind:value={dirInput} onkeydown={(e) => e.key === 'Enter' && openDialog()} aria-label="Project folder" />
      {/if}
      <button class="primary" onclick={openDialog}>{isDesktop ? 'Open folder…' : 'Open'}</button>
      <p class="muted small">Without a project you can still try mappings in the editor.</p>
    {/if}
  </div>
  {#if app.project && app.iflowSteps.length}
    <div class="steps" aria-label="iFlow steps">
      <div class="group static">iFlow steps <button class="icon" title="Rescan" onclick={() => { refreshFiles(); loadIflowSteps(); }}>↻</button></div>
      {#each app.iflowSteps as s}
        <button class="file step" class:missing={(s.type === 'groovy' || s.type === 'xslt' || s.type === 'xsd') && !s.fileFound} title={`${s.iflow} · ${LABEL[s.type] ?? s.type}${s.componentVersion ? ' ' + s.componentVersion : ''}${s.file ? ' · ' + s.file : ''}${s.note ? ' · ' + s.note : ''}`}
          onclick={() => openIflowStep(s)}>
          <span class="kind {s.type}">{KIND[s.type] ?? '!'}</span>{s.name || s.id}
        </button>
      {/each}
    </div>
  {/if}
  {#if app.project}
    <nav class="tree" aria-label="Project files">
      {#each groups as [group, paths]}
        <button class="group" onclick={() => (collapsed[group] = !collapsed[group])}>{collapsed[group] ? '▸' : '▾'} {group === '.' ? '(root)' : group}</button>
        {#if !collapsed[group]}
          {#each paths as p}
            <button class="file" class:active={isActive(p)} class:menu-open={menu?.path === p} title={p} onclick={() => openFile(p)}
              oncontextmenu={(e) => openMenu(e, p)}>{label(p, group)}</button>
          {/each}
        {/if}
      {/each}
      {#if files.length === 0}<p class="muted pad">No mapping, schema or XML files.</p>{/if}
    </nav>
  {/if}
</aside>

<svelte:window onkeydown={(e) => { if (menu && e.key === 'Escape') menu = null; }} onblur={() => (menu = null)} onresize={() => (menu = null)} />

{#if menu}
  <div class="menu-backdrop" role="presentation" onclick={() => (menu = null)} oncontextmenu={(e) => { e.preventDefault(); menu = null; }}></div>
  <div class="menu" role="menu" aria-label={`Actions for ${menu.path}`} style:left="{menu.x}px" style:top="{menu.y}px">
    <button role="menuitem" onclick={() => pick(openFile)}>Open</button>
    {#if /\.(xml|json|txt|csv)$/i.test(menu.path)}
      <button role="menuitem" onclick={() => pick(useAsInput)}>Use as input</button>
    {/if}
    <button role="menuitem" onclick={() => pick((p) => copyFile(p, true))}>Rename…</button>
    <button role="menuitem" onclick={() => pick((p) => copyFile(p, false))}>Copy…</button>
    <button role="menuitem" onclick={() => pick((p) => navigator.clipboard?.writeText(p))}>Copy path</button>
    <hr />
    <button role="menuitem" class="danger" onclick={() => pick(deleteFile)}>Delete…</button>
  </div>
{/if}

<style>
  .side { width: 240px; min-width: 200px; border-right: 1px solid var(--border); display: flex; flex-direction: column; background: var(--surface); min-height: 0; }
  .proj { padding: 10px; border-bottom: 1px solid var(--border); display: flex; flex-direction: column; gap: 6px; }
  .root { font-weight: 600; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .row { display: flex; gap: 4px; flex-wrap: wrap; }
  .filter, .proj input { width: 100%; box-sizing: border-box; }
  .muted { color: var(--muted); font-size: 12px; margin: 0; }
  .small { font-size: 11px; }
  .pad { padding: 10px; }
  .tree { overflow: auto; flex: 1; padding: 4px 0; }
  .group { display: block; width: 100%; text-align: left; border: none; background: none; color: var(--muted); font-size: 11px; font-weight: 600; text-transform: uppercase; padding: 6px 10px 2px; cursor: pointer; }
  .file { display: block; width: 100%; text-align: left; border: none; background: none; color: var(--fg); font-size: 12px; padding: 3px 10px 3px 22px; cursor: pointer; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-family: var(--mono); }
  .file:hover { background: var(--hover); }
  .file.active { background: var(--active); color: var(--accent); }
  .file.menu-open { outline: 1px solid var(--accent); outline-offset: -1px; }
  .menu-backdrop { position: fixed; inset: 0; z-index: 90; }
  .menu { position: fixed; z-index: 91; min-width: 160px; background: var(--surface); border: 1px solid var(--border); border-radius: 6px; padding: 4px; box-shadow: 0 6px 20px rgba(0,0,0,.3); display: flex; flex-direction: column; }
  .menu button { border: none; background: none; color: var(--fg); text-align: left; font-size: 12px; padding: 5px 10px; border-radius: 4px; cursor: pointer; }
  .menu button:hover, .menu button:focus-visible { background: var(--hover); }
  .menu .danger { color: var(--err); }
  .menu hr { border: none; border-top: 1px solid var(--border); margin: 4px 0; }
  .steps { max-height: 30%; overflow: auto; border-bottom: 1px solid var(--border); padding-bottom: 4px; }
  .group.static { cursor: default; display: flex; align-items: center; justify-content: space-between; }
  .step { font-family: inherit; padding-left: 12px; }
  .step.missing { opacity: 0.6; }
  .kind { display: inline-block; width: 16px; font-size: 10px; font-weight: 700; font-family: var(--mono); }
  .kind.groovy { color: var(--ok); }
  .kind.xslt { color: var(--accent); }
  .kind.xsd { color: var(--warn); }
  .kind.error { color: var(--err); }
  .kind.contentModifier, .kind.filter, .kind.router, .kind.splitter, .kind.gather { color: var(--muted); }
  @media (max-width: 800px) { .side { width: 100%; border-right: none; border-bottom: 1px solid var(--border); max-height: 35vh; } }
</style>
