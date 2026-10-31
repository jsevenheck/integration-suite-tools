<script lang="ts">
  import Gutter from './Gutter.svelte';
  import { paneSizes, paneColumns } from '../lib/split.svelte';
  import Editor from './Editor.svelte';
  import KeyValueTable from './KeyValueTable.svelte';
  import CmTable from './CmTable.svelte';
  import { app, flow, groovy, xpath, runFlowStep, useAsFlowInput, gatherSplitResult, saveInput, type FlowStep } from '../lib/store.svelte';

  const panes = paneSizes('flow', [1, 1.2, 1]);
  const STEPS: [FlowStep, string][] = [['contentModifier', 'Content Modifier'], ['filter', 'Filter'], ['router', 'Router'],
    ['splitter', 'Splitter'], ['gather', 'Gather']];
  // SAP: which Aggregation Algorithm each Incoming Format allows.
  const ALGORITHMS: Record<string, [string, string][]> = {
    sameXml: [['combine', 'Combine'], ['combineAtXPath', 'Combine at XPath']],
    differentXml: [['combine', 'Combine']],
    plainText: [['concatenate', 'Concatenate']],
  };

  let inputTab = $state<'body' | 'headers' | 'properties' | 'namespaces'>('body');
  let cmTab = $state<'headers' | 'properties' | 'body'>('headers');
  let language = $derived(/^\s*[[{]/.test(flow.body) ? 'json' : 'xml');
  let cm = $derived(flow.cmResult);
  let split = $derived(flow.splitResult);
  let filtered = $derived(flow.filterResult);
  let gathered = $derived(flow.gatherResult);

  function useGroovyInput() {
    useAsFlowInput(groovy.body, groovy.headers, groovy.properties);
    app.inputPaths.flow = app.inputPaths.groovy;
  }

  function addRoute() {
    flow.router.routes = [...flow.router.routes, { name: 'Route ' + (flow.router.routes.length + 1), expressionType: 'nonxml', condition: '', default: false }];
  }
  function setDefault(i: number) {
    flow.router.routes = flow.router.routes.map((r, j) => ({ ...r, default: i === j }));
  }
  function setFormat(format: string) {
    flow.gather.messageType = format;
    if (!ALGORITHMS[format]?.some(([a]) => a === flow.gather.algorithm)) flow.gather.algorithm = ALGORITHMS[format]?.[0][0] ?? 'combine';
  }
</script>

<div class="flow" style:grid-template-columns={paneColumns(panes.sizes)}>
  <section class="pane" aria-label="Input message">
    <header>
      <h2 title={app.inputPaths.flow ?? ''}>Input message{#if app.inputPaths.flow} <span class="from">· {app.inputPaths.flow.split('/').pop()}</span>{/if}</h2>
      <div class="tabs" role="tablist">
        <button role="tab" class:active={inputTab === 'body'} onclick={() => (inputTab = 'body')}>Body</button>
        <button role="tab" class:active={inputTab === 'headers'} onclick={() => (inputTab = 'headers')}>Headers <span class="count">{flow.headers.length}</span></button>
        <button role="tab" class:active={inputTab === 'properties'} onclick={() => (inputTab = 'properties')}>Properties <span class="count">{flow.properties.length}</span></button>
        <button role="tab" class:active={inputTab === 'namespaces'} onclick={() => (inputTab = 'namespaces')}
          title="The iFlow's Namespace Mapping (Runtime Configuration); shared with the XPath tab">Namespaces <span class="count">{xpath.namespaces.length}</span></button>
      </div>
      <div class="spacer"></div>
      <button class="small" onclick={useGroovyInput}>Use Groovy input</button>
      <button class="small" onclick={saveInput} disabled={!app.project} title={app.project ? 'Save the input body under inputs/' : 'Open a project to save'}>Save…</button>
    </header>
    <div class="body">
      {#if inputTab === 'body'}<Editor bind:value={flow.body} {language} onrun={runFlowStep} label="Input body" />
      {:else if inputTab === 'headers'}<KeyValueTable bind:rows={flow.headers} emptyText="No headers." />
      {:else if inputTab === 'properties'}<KeyValueTable bind:rows={flow.properties} emptyText="No exchange properties." />
      {:else}<KeyValueTable bind:rows={xpath.namespaces} emptyText="Prefix → namespace URI, like the iFlow's Namespace Mapping." />{/if}
    </div>
  </section>

  <Gutter {panes} i={0} />

  <section class="pane" aria-label="Step configuration">
    <header>
      <div class="tabs" role="tablist" aria-label="Flow step">
        {#each STEPS as [id, label]}
          <button role="tab" class:active={flow.step === id} onclick={() => (flow.step = id)}>{label}</button>
        {/each}
      </div>
      <div class="spacer"></div>
      <button class="primary small" onclick={runFlowStep} disabled={flow.running}>Run step</button>
    </header>
    {#if flow.sources[flow.step]}<p class="hint src">From {flow.sources[flow.step]}</p>{/if}
    <div class="cfg">
      {#if flow.step === 'contentModifier'}
        <div class="tabs" role="tablist">
          <button role="tab" class:active={cmTab === 'headers'} onclick={() => (cmTab = 'headers')}>Message Header <span class="count">{flow.cm.headers.length}</span></button>
          <button role="tab" class:active={cmTab === 'properties'} onclick={() => (cmTab = 'properties')}>Exchange Property <span class="count">{flow.cm.properties.length}</span></button>
          <button role="tab" class:active={cmTab === 'body'} onclick={() => (cmTab = 'body')}>Message Body</button>
        </div>
        {#if cmTab === 'headers'}<CmTable bind:rows={flow.cm.headers} what="header rows" />
        {:else if cmTab === 'properties'}<CmTable bind:rows={flow.cm.properties} what="property rows" />
        {:else}
          <label class="field">Type
            <select bind:value={flow.cm.bodyType}><option value="constant">Constant</option><option value="expression">Expression</option></select>
          </label>
          <textarea class="mono" bind:value={flow.cm.body} rows="10" spellcheck="false" aria-label="Message body"
            placeholder="Empty: the body stays as it is. With Expression, ${'{'}in.body{'}'} and ${'{'}property.x{'}'} are replaced."></textarea>
        {/if}
        <p class="hint">Rows run top to bottom, headers before properties, then the body. XPath rows read the incoming body.</p>
      {:else if flow.step === 'filter'}
        <label class="field">XPath Expression
          <input class="mono" bind:value={flow.filter.expression} aria-label="Filter XPath" placeholder="/Order/Item[@qty > 1]" />
        </label>
        <label class="field">Value Type
          <select bind:value={flow.filter.valueType} aria-label="Value Type">
            <option value="string">String</option><option value="integer">Integer</option><option value="boolean">Boolean</option>
            <option value="node">Node</option><option value="nodelist">Nodelist</option>
          </select>
        </label>
        <p class="hint">XPath 3.1 on the body; the result becomes the new body. <code>function:simple('${'{'}property.x{'}'}')</code> takes the value from Camel Simple.</p>
      {:else if flow.step === 'gather'}
        <label class="field">Incoming Format
          <select value={flow.gather.messageType} onchange={(e) => setFormat(e.currentTarget.value)} aria-label="Incoming Format">
            <option value="sameXml">XML (Same Format)</option><option value="differentXml">XML (Different Format)</option><option value="plainText">Plain Text</option>
          </select>
        </label>
        <label class="field">Aggregation Algorithm
          <select bind:value={flow.gather.algorithm} aria-label="Aggregation Algorithm">
            {#each ALGORITHMS[flow.gather.messageType] ?? [] as [v, l]}<option value={v}>{l}</option>{/each}
          </select>
        </label>
        {#if flow.gather.algorithm === 'combineAtXPath'}
          <label class="field">Combine from source (XPath)
            <input class="mono" bind:value={flow.gather.sourceXPath} aria-label="Combine from source" placeholder="/Order/Item" />
          </label>
          <label class="field">Combine at target (XPath)
            <input class="mono" bind:value={flow.gather.targetXPath} aria-label="Combine at target" placeholder="empty: the source's parent" />
          </label>
        {/if}
        <h3>{flow.gather.messages.length} message(s)
          <button class="small" onclick={gatherSplitResult} disabled={!split?.messages?.length}>From Splitter result</button>
          <button class="small" onclick={() => (flow.gather.messages = [...flow.gather.messages, flow.body])}>+ Input body</button></h3>
        {#each flow.gather.messages as _, i}
          <div class="gmsg">
            <textarea class="mono" bind:value={flow.gather.messages[i]} rows="3" spellcheck="false" aria-label={'Message ' + (i + 1)}></textarea>
            <button class="icon" onclick={() => (flow.gather.messages = flow.gather.messages.filter((_, j) => j !== i))} aria-label="Remove message">✕</button>
          </div>
        {/each}
        <p class="hint">Headers and properties are not aggregated (SAP).</p>
      {:else if flow.step === 'router'}
        <table class="routes">
          <colgroup><col style="width: 24%" /><col style="width: 92px" /><col /><col style="width: 52px" /><col style="width: 28px" /></colgroup>
          <thead><tr><th>Route</th><th>Expression Type</th><th>Condition</th><th title="Default route">Default</th><th></th></tr></thead>
          <tbody>
            {#each flow.router.routes as r, i}
              <tr class:taken={flow.routerResult?.routes[i]?.status === 'true'}>
                <td><input bind:value={r.name} aria-label="Route name" /></td>
                <td><select bind:value={r.expressionType} aria-label="Expression type" disabled={r.default}>
                  <option value="nonxml">Non-XML</option><option value="xml">XML</option></select></td>
                <td><input class="mono" bind:value={r.condition} aria-label="Condition" disabled={r.default}
                  placeholder={r.default ? '' : r.expressionType === 'xml' ? "/Order/@id = '4711'" : "${header.SenderId} = '1'"} /></td>
                <td><input type="radio" name="default-route" checked={r.default} onchange={() => setDefault(i)} aria-label="Default route" /></td>
                <td><button class="icon" onclick={() => (flow.router.routes = flow.router.routes.filter((_, j) => j !== i))} aria-label="Remove route">✕</button></td>
              </tr>
            {/each}
          </tbody>
        </table>
        <button class="small" onclick={addRoute}>+ Route</button>
        <label class="check"><input type="checkbox" bind:checked={flow.router.throwException} /> Throw Exception when no route matches</label>
        <p class="hint">Conditions run in this order; the first true one wins. Non-XML is Camel Simple (CPI's <code>=</code>, <code>and</code>, <code>or</code> work); XML is XPath on the body.</p>
      {:else}
        <label class="field">Splitter
          <select bind:value={flow.splitter.splitter}><option value="general">General Splitter</option><option value="iterating">Iterating Splitter</option></select>
        </label>
        <label class="field">Expression Type
          <select bind:value={flow.splitter.expressionType}>
            <option value="xpath">XPath</option><option value="lineBreak">Line Break</option>
            {#if flow.splitter.splitter === 'iterating'}<option value="token">Token</option>{/if}
          </select>
        </label>
        {#if flow.splitter.expressionType !== 'lineBreak'}
          <label class="field">{flow.splitter.expressionType === 'token' ? 'Token' : 'XPath Expression'}
            <input class="mono" bind:value={flow.splitter.expression} aria-label="Split expression"
              placeholder={flow.splitter.expressionType === 'token' ? 'customers' : '/Order/Item or ${property.path}'} />
          </label>
        {/if}
        <label class="field">Grouping <input class="mono" bind:value={flow.splitter.grouping} placeholder="1" aria-label="Grouping" /></label>
        <label class="check"><input type="checkbox" bind:checked={flow.splitter.streaming} /> Streaming (CamelSplitSize only on the last message)</label>
        <p class="hint">General keeps the enveloping elements, Iterating does not. Only paths such as <code>/A/B</code> or <code>//B</code>, as in CPI.</p>
      {/if}
    </div>
  </section>

  <Gutter {panes} i={1} />

  <section class="pane" aria-label="Step result">
    <header><h2>Result</h2></header>
    <div class="res">
      {#if flow.step === 'contentModifier' && cm}
        {#each cm.diagnostics as d}<div class="d {d.severity}">{d.message}</div>{/each}
        {#if cm.ok}
          <h3>Rows <span class="muted">· {cm.engine}</span></h3>
          {#each cm.trace as t}
            <div class="row"><span class="k">{t.table}</span> <b>{t.name}</b> {#if t.deleted}<span class="muted">deleted</span>{:else}= <code>{t.value ?? 'null'}</code> <span class="muted">{t.type ?? ''}</span>{/if}</div>
          {/each}
          <h3>Output <button class="small" onclick={() => useAsFlowInput(cm.output.body, cm.output.headers, cm.output.properties)}>Use as input</button></h3>
          <pre>{cm.output.body}</pre>
          <h3>Headers</h3>
          <KeyValueTable rows={cm.output.headers} readOnly added={cm.changes.headers.added} changed={cm.changes.headers.changed} removed={cm.changes.headers.removed} emptyText="No headers" />
          <h3>Properties</h3>
          <KeyValueTable rows={cm.output.properties} readOnly added={cm.changes.properties.added} changed={cm.changes.properties.changed} removed={cm.changes.properties.removed} emptyText="No properties" />
        {/if}
      {:else if flow.step === 'router' && flow.routerResult}
        <p class="taken-line">→ <b>{flow.routerResult.taken}</b></p>
        {#each flow.router.routes as r, i}
          {@const s = flow.routerResult.routes[i]}
          {#if s}<div class="row"><span class="st {s.status.replace(' ', '-')}">{s.status}</span> {r.name}{#if s.camel}<div class="muted mono">as Camel: {s.camel}</div>{/if}{#if s.message}<div class="d error">{s.message}</div>{/if}</div>{/if}
        {/each}
      {:else if flow.step === 'splitter' && split}
        {#each split.diagnostics as d}<div class="d {d.severity}">{d.message}</div>{/each}
        {#if split.ok}<h3>{split.count} message(s) <span class="muted">· {split.engine}</span>
          {#if split.count}<button class="small" onclick={gatherSplitResult} title="Pass the messages to a Gather step">Gather</button>{/if}</h3>{/if}
        {#each split.messages as m}
          <div class="msg">
            <div class="k">CamelSplitIndex {m.index} · CamelSplitComplete {m.complete}{#if m.size !== undefined} · CamelSplitSize {m.size}{/if}
              <button class="small" onclick={() => useAsFlowInput(m.body)}>Use as input</button></div>
            <pre>{m.body}</pre>
          </div>
        {/each}
      {:else if (flow.step === 'filter' && filtered) || (flow.step === 'gather' && gathered)}
        {@const r = flow.step === 'filter' ? filtered : gathered}
        {#each r.diagnostics as d}<div class="d {d.severity}">{d.message}</div>{/each}
        {#if r.ok}
          <h3>Output <span class="muted" title={r.engine}>· {r.count ?? 1} {flow.step === 'filter' ? 'item(s) selected' : 'message(s) gathered'}</span>
            <button class="small" onclick={() => useAsFlowInput(r.body)}>Use as input</button></h3>
          <pre>{r.body}</pre>
        {/if}
      {:else}<p class="hint">Run the step (Ctrl+Enter in the body). Open a flow step from an imported iFlow to load its settings.</p>{/if}
    </div>
  </section>
</div>

<style>
  .flow { display: grid; gap: 0; background: var(--border); height: 100%; min-height: 0; }
  .pane { background: var(--bg); display: flex; flex-direction: column; min-width: 0; min-height: 0; }
  header { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); flex-wrap: wrap; min-height: 34px; box-sizing: border-box; }
  h2 { font-size: 12px; font-weight: 600; margin: 0; }
  h3 { font-size: 11px; text-transform: uppercase; color: var(--muted); margin: 12px 0 4px; display: flex; gap: 8px; align-items: center; }
  h3 .muted { text-transform: none; }
  .spacer { flex: 1; }
  .body { flex: 1; min-height: 0; overflow: auto; }
  .cfg, .res { flex: 1; overflow: auto; padding: 8px 10px; font-size: 12px; display: flex; flex-direction: column; gap: 6px; align-items: stretch; }
  .res { display: block; }
  .field { display: flex; flex-direction: column; gap: 2px; font-size: 11px; color: var(--muted); }
  .field input, .field select { font-size: 12px; }
  .check { display: flex; align-items: center; gap: 6px; font-size: 12px; }
  textarea { width: 100%; box-sizing: border-box; font-family: var(--mono); font-size: 12px; padding: 5px 6px; border: 1px solid var(--border); border-radius: 4px; background: var(--bg); color: var(--fg); resize: vertical; }
  table { width: 100%; border-collapse: collapse; table-layout: fixed; }
  th { text-align: left; font-weight: 500; color: var(--muted); font-size: 11px; padding: 2px 3px; }
  td { padding: 2px 3px; }
  td input:not([type]), td select { width: 100%; box-sizing: border-box; }
  tr.taken td { background: var(--ok-bg); }
  .hint { color: var(--muted); font-size: 12px; margin: 4px 0; }
  .src { padding: 4px 10px; margin: 0; border-bottom: 1px solid var(--border-soft); font-family: var(--mono); font-size: 11px; }
  .row { padding: 3px 0; border-bottom: 1px solid var(--border-soft); }
  .k { color: var(--muted); font-size: 11px; display: flex; align-items: center; gap: 8px; }
  .row .k { display: inline; }
  .muted { color: var(--muted); font-weight: 400; }
  pre { font-family: var(--mono); font-size: 12px; white-space: pre-wrap; word-break: break-word; margin: 4px 0; background: var(--surface); border: 1px solid var(--border-soft); border-radius: 4px; padding: 6px; }
  code { font-family: var(--mono); }
  .d { font-family: var(--mono); white-space: pre-wrap; padding: 3px 0; }
  .d.error { color: var(--err); }
  .d.warning { color: var(--warn); }
  .d.info { color: var(--muted); }
  .msg { border-bottom: 1px solid var(--border-soft); padding: 4px 0; }
  .gmsg { display: flex; gap: 4px; align-items: flex-start; }
  .taken-line { font-size: 13px; margin: 4px 0 8px; }
  .st { display: inline-block; min-width: 86px; font-size: 10px; text-transform: uppercase; font-weight: 600; color: var(--muted); }
  .st.true { color: var(--ok); }
  .st.error { color: var(--err); }
  .from { color: var(--muted); font-weight: 400; font-family: var(--mono); }
  @media (max-width: 1100px) {
    .flow > :global(.gutter) { display: none; }
    .flow { grid-template-columns: 1fr !important; gap: 1px; grid-template-rows: minmax(220px, 1fr) minmax(300px, 1fr) minmax(260px, 1fr); }
  }
</style>
