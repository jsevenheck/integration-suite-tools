<script lang="ts">
  import { onMount } from 'svelte';
  import Sidebar from './components/Sidebar.svelte';
  import XsltView from './components/XsltView.svelte';
  import GroovyView from './components/GroovyView.svelte';
  import XsdView from './components/XsdView.svelte';
  import XPathView from './components/XPathView.svelte';
  import FlowStepsView from './components/FlowStepsView.svelte';
  import Dialog from './components/Dialog.svelte';
  import FileView from './components/FileView.svelte';
  import { isDesktop } from './lib/engine';
  import { app, xslt, groovy, flow, fileView, init, runXslt, runXsd, runXpath, runFlowStep, runGroovy, onGroovyProfileChange } from './lib/store.svelte';

  onMount(async () => {
    await init();
    if (app.tool === 'xslt' && !xslt.result && hasKind('xslt')) runXslt();
    if (app.tool === 'groovy' && !groovy.result && hasKind('groovy')) runGroovy();
  });

  function hasKind(kind: 'xslt' | 'groovy') {
    return app.profiles.some((p) => p.kind === kind && p.available);
  }

  $effect(() => {
    if (app.tool !== 'file') app.stepTool = app.tool;
  });

  let kind = $derived<'xslt' | 'groovy'>(app.tool === 'groovy' || app.tool === 'flow' ? 'groovy' : 'xslt');
  let profile = $derived(app.profiles.find((p) => p.id === (kind === 'groovy' ? app.groovyProfile : app.profile)));
  let kindProfiles = $derived(app.profiles.filter((p) => p.kind === kind));

  function run() {
    if (app.tool === 'xslt') runXslt();
    else if (app.tool === 'groovy') runGroovy();
    else if (app.tool === 'xsd') runXsd();
    else if (app.tool === 'xpath') runXpath();
    else if (app.tool === 'flow') runFlowStep();
  }

  function onProfile() {
    if (app.tool === 'xslt') runXslt();
    if (app.tool === 'groovy') {
      onGroovyProfileChange();
      runGroovy();
    }
  }

  /** SAP: tenant software 7.x / 8.x runs on Camel 3, 5.x / 6.x on Camel 2. */
  const CAMEL: Record<string, string> = { 'groovy-2.x': 'Camel 3.14 (tenant 7.x / 8.x)', 'groovy-1.x': 'Camel 2.24 (tenant 5.x / 6.x)' };

  function selectTool(id: typeof app.tool) {
    app.tool = id;
    if (id === 'groovy' && !groovy.result && hasKind('groovy')) runGroovy();
    if (id === 'xslt' && !xslt.result && hasKind('xslt')) runXslt();
  }

  /** The webview's own menu (Back, Reload, Print) has nothing to offer in the desktop app; text fields keep theirs. */
  function onContextMenu(e: MouseEvent) {
    const t = e.target as HTMLElement | null;
    if (isDesktop && !t?.closest('input, textarea, [contenteditable="true"]')) e.preventDefault();
  }

  const tools = [
    { id: 'xslt', label: 'XSLT Mapping', needs: 'xslt' },
    { id: 'groovy', label: 'Groovy Script', needs: 'groovy' },
    { id: 'xsd', label: 'XSD Validation', needs: 'xslt' },
    { id: 'xpath', label: 'XPath', needs: 'xslt' },
    { id: 'flow', label: 'Flow Steps', needs: 'groovy' },
  ] as const;
</script>

<div class="app">
  <header class="top">
    <div class="brand"><img src="./icon.svg" alt="" width="20" height="20" /> CPI Workbench</div>
    <div class="tools" role="tablist" aria-label="Tools">
      {#each tools as t}
        <button role="tab" class:active={app.tool === t.id} onclick={() => selectTool(t.id)}
          disabled={app.profiles.length > 0 && t.needs !== null && !hasKind(t.needs)}
          title={app.profiles.length > 0 && t.needs !== null && !hasKind(t.needs) ? 'The engine for this tool is not installed or not running' : ''}>{t.label}</button>
      {/each}
      {#if fileView.path}
        <button role="tab" class="file-tab" class:active={app.tool === 'file'} onclick={() => (app.tool = 'file')}
          title={fileView.path}>{fileView.path.split('/').pop()}{#if fileView.content !== fileView.saved} ●{/if}</button>
      {/if}
    </div>
    <div class="spacer"></div>
    {#if app.tool === 'flow'}
      <label class="profile" title="Content Modifier, Router and Splitter have no Groovy version; what matters is the tenant's Camel line">Camel
        <select bind:value={flow.camel} onchange={runFlowStep} aria-label="Camel version">
          {#each [...kindProfiles].reverse() as p}
            <option value={p.id} disabled={!p.available}>{CAMEL[p.id] ?? p.label}{p.available ? '' : ' (not installed)'}</option>
          {/each}
        </select>
      </label>
    {:else if app.tool !== 'file'}
      <label class="profile" title={profile?.note ?? ''}>Runtime
        {#if kind === 'groovy'}
        <select bind:value={app.groovyProfile} onchange={onProfile} aria-label="Runtime profile">
          {#each kindProfiles as p}
            <option value={p.id} disabled={!p.available}>{p.label}{p.available ? '' : ' (not installed)'}</option>
          {/each}
        </select>
        {:else}
        <select bind:value={app.profile} onchange={onProfile} aria-label="Runtime profile">
          {#each kindProfiles as p}
            <option value={p.id} disabled={!p.available}>{p.label}{p.available ? '' : ' (not installed)'}</option>
          {/each}
        </select>
        {/if}
      </label>
    {/if}
    {#if app.tool === 'xslt'}
      <label class="live" title="Re-run on every change"><input type="checkbox" bind:checked={xslt.live} /> Live</label>
    {:else if app.tool === 'groovy'}
      <label class="live" title="Re-run on every change"><input type="checkbox" bind:checked={groovy.live} /> Live</label>
    {/if}
    {#if app.tool !== 'file'}
      <button class="primary" onclick={run} title="Run (Ctrl+Enter)">▶ Run</button>
    {/if}
  </header>

  {#if app.error}
    <div class="banner error" role="alert">
      <span>{app.error}</span>
      <button class="icon" onclick={() => (app.error = '')} aria-label="Dismiss">✕</button>
    </div>
  {/if}

  <div class="main">
    <Sidebar />
    <main class="content">
      {#if app.tool === 'xslt'}<XsltView />
      {:else if app.tool === 'groovy'}<GroovyView />
      {:else if app.tool === 'xsd'}<XsdView />
      {:else if app.tool === 'xpath'}<XPathView />
      {:else if app.tool === 'flow'}<FlowStepsView />
      {:else}<FileView />{/if}
    </main>
  </div>

  <footer class="status">
    <span>{app.status || 'Ready'}</span>
    <span class="spacer"></span>
    {#if app.engineInfo}
      <span title="Engines">{app.profiles.filter((p) => p.available).length} engine(s) available · offline</span>
    {:else}
      <span class="err">engine not reachable</span>
    {/if}
  </footer>
</div>

<Dialog />

<svelte:window oncontextmenu={onContextMenu} />

<style>
  .app { display: flex; flex-direction: column; height: 100vh; }
  .top { display: flex; align-items: center; gap: 10px; padding: 6px 10px; border-bottom: 1px solid var(--border); background: var(--surface); flex-wrap: wrap; }
  .brand { display: flex; align-items: center; gap: 6px; font-weight: 700; font-size: 14px; margin-right: 8px; }
  .tools { display: flex; gap: 2px; flex-wrap: wrap; }
  .tools button { border: none; background: none; padding: 6px 10px; border-radius: 6px; color: var(--muted); font-weight: 600; font-size: 12px; cursor: pointer; }
  .tools button.active { background: var(--active); color: var(--accent); }
  .tools .file-tab { font-family: var(--mono); font-weight: 500; border-left: 1px solid var(--border); border-radius: 0 6px 6px 0; }
  .tools button:disabled { opacity: 0.5; cursor: default; }
  .spacer { flex: 1; }
  .profile { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); }
  .live { display: flex; align-items: center; gap: 4px; font-size: 12px; }
  .banner { display: flex; align-items: center; gap: 8px; padding: 6px 10px; font-size: 12px; }
  .banner span { flex: 1; white-space: pre-wrap; }
  .banner.error { background: var(--err-bg); color: var(--err); border-bottom: 1px solid var(--border); }
  .main { flex: 1; display: flex; min-height: 0; }
  .content { flex: 1; min-width: 0; min-height: 0; }
  .status { display: flex; gap: 8px; font-size: 11px; color: var(--muted); padding: 3px 10px; border-top: 1px solid var(--border); background: var(--surface); }
  .err { color: var(--err); }
  @media (max-width: 800px) { .main { flex-direction: column; } .app { height: auto; min-height: 100vh; } .content { height: 140vh; } }
</style>
