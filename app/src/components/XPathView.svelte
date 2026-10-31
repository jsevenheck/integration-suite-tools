<script lang="ts">
  import Gutter from './Gutter.svelte';
  import { paneSizes, paneColumns } from '../lib/split.svelte';
  import Editor from './Editor.svelte';
  import KeyValueTable from './KeyValueTable.svelte';
  import { app, xpath, xslt, runXpath, saveInput } from '../lib/store.svelte';

  const panes = paneSizes('xpath', [1.3, 1]);

  // The header names the file the body came from: the XSLT input's file, none for the output.
  function useXsltOutput() {
    if (!xslt.result?.output) return;
    xpath.body = xslt.result.output.body;
    delete app.inputPaths.xpath;
  }
  function useXsltInput() {
    xpath.body = xslt.body;
    app.inputPaths.xpath = app.inputPaths.xslt;
  }

  function key(e: KeyboardEvent) {
    if (e.key === 'Enter') runXpath();
  }
</script>

<div class="xp">
  <div class="bar">
    <label class="expr">XPath
      <input class="mono" bind:value={xpath.expression} onkeydown={key} placeholder="//Order/@id" aria-label="XPath expression" />
    </label>
    <label>Version
      <select bind:value={xpath.version}>
        <option value="1.0">1.0 (JDK)</option>
        <option value="3.1">3.1 (Saxon, as in Content Modifier and Router)</option>
      </select>
    </label>
    <button class="primary" onclick={runXpath} disabled={xpath.running}>Evaluate</button>
  </div>
  <div class="grid" style:grid-template-columns={paneColumns(panes.sizes)}>
    <section class="pane">
      <header><h2 title={app.inputPaths.xpath ?? ''}>XML document{#if app.inputPaths.xpath} <span class="from">· {app.inputPaths.xpath.split('/').pop()}</span>{/if}</h2><div class="spacer"></div>
        <button class="small" onclick={useXsltInput}>Use XSLT input</button>
        <button class="small" onclick={useXsltOutput} disabled={!xslt.result?.output}>Use XSLT output</button>
        <button class="small" onclick={saveInput} disabled={!app.project} title={app.project ? 'Save the input body under inputs/' : 'Open a project to save'}>Save…</button>
      </header>
      <div class="body"><Editor bind:value={xpath.body} language="xml" onrun={runXpath} label="XML document" /></div>
    </section>
    <Gutter {panes} i={0} />

    <section class="pane side">
      <header><h2>Namespace mapping</h2></header>
      <div class="ns"><KeyValueTable bind:rows={xpath.namespaces} emptyText="Prefix → namespace URI (like the iFlow's Namespace Mapping)" /></div>
      <header><h2>Result{#if xpath.result} <span class="muted">· {xpath.result.count} item(s) · {xpath.result.engine ?? ''}</span>{/if}</h2></header>
      <div class="res">
        {#if xpath.result}
          {#each xpath.result.diagnostics as d}<div class="err">{d.message}</div>{/each}
          {#each xpath.result.items as it, i}
            <div class="item"><span class="k">{i + 1} · {it.kind}{it.name ? ' ' + it.name : ''}{it.type ? ' (' + it.type + ')' : ''}</span><pre>{it.value}</pre></div>
          {/each}
        {:else}<p class="muted">Press Enter in the expression field.</p>{/if}
      </div>
    </section>
  </div>
</div>

<style>
  .xp { display: flex; flex-direction: column; height: 100%; min-height: 0; }
  .bar { display: flex; gap: 12px; align-items: end; padding: 8px 10px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
  .bar label { display: flex; flex-direction: column; font-size: 11px; color: var(--muted); gap: 2px; }
  .expr { flex: 1; min-width: 240px; }
  .expr input { width: 100%; box-sizing: border-box; font-size: 13px; padding: 5px 6px; }
  .grid { flex: 1; display: grid; gap: 0; background: var(--border); min-height: 0; }
  .pane { background: var(--bg); display: flex; flex-direction: column; min-height: 0; min-width: 0; }
  header { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); min-height: 34px; box-sizing: border-box; }
  h2 { font-size: 12px; margin: 0; font-weight: 600; }
  .spacer { flex: 1; }
  .body { flex: 1; min-height: 0; }
  .ns { max-height: 30%; overflow: auto; }
  .res { flex: 1; overflow: auto; padding: 8px; font-size: 12px; }
  .item { border-bottom: 1px solid var(--border-soft); padding: 4px 0; }
  .k { color: var(--muted); font-size: 11px; }
  pre { margin: 2px 0 0; font-family: var(--mono); white-space: pre-wrap; word-break: break-all; }
  .err { color: var(--err); font-family: var(--mono); }
  .muted { color: var(--muted); font-weight: 400; }
  @media (max-width: 900px) { .grid { grid-template-columns: 1fr !important; gap: 1px; } .grid > :global(.gutter) { display: none; } }
  .from { color: var(--muted); font-weight: 400; font-family: var(--mono); }
</style>
