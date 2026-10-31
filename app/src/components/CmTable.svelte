<script lang="ts">
  import type { CmRow } from '../lib/store.svelte';

  let { rows = $bindable([]), what }: { rows: CmRow[]; what: string } = $props();

  // Values as the iFlow stores them; labels as the Content Modifier shows them.
  const TYPES = [
    ['constant', 'Constant'], ['header', 'Header'], ['property', 'Property'], ['xpath', 'XPath'], ['expression', 'Expression'],
    ['persisted variables', 'Local Variable'], ['global persisted variables', 'Global Variable'],
    ['numberRange', 'Number Range'], ['external parameter', 'External Parameter'],
  ];

  function add() {
    rows = [...rows, { action: 'Create', name: '', type: 'constant', value: '', datatype: '', default: '' }];
  }
  function move(i: number, d: number) {
    const j = i + d;
    if (j < 0 || j >= rows.length) return;
    const next = [...rows];
    [next[i], next[j]] = [next[j], next[i]];
    rows = next;
  }
</script>

{#each rows as r, i}
  <div class="entry">
    <div class="line">
      <select class="action" bind:value={r.action} aria-label="Action"><option>Create</option><option>Delete</option></select>
      <input class="mono name" bind:value={r.name} aria-label="Name" placeholder={r.action === 'Delete' ? 'Name or $include=X-*,$exclude=…' : 'Name'}
        title={r.action === 'Delete' ? 'A name, or $include=pattern,$exclude=pattern as the iFlow stores it' : ''} />
      {#if r.action === 'Create'}
        <select class="type" bind:value={r.type} aria-label="Source Type">{#each TYPES as [v, l]}<option value={v}>{l}</option>{/each}</select>
      {/if}
      <span class="actions">
        <button class="icon" onclick={() => move(i, -1)} disabled={i === 0} aria-label="Move up">↑</button>
        <button class="icon" onclick={() => move(i, 1)} disabled={i === rows.length - 1} aria-label="Move down">↓</button>
        <button class="icon" onclick={() => (rows = rows.filter((_, j) => j !== i))} aria-label="Remove">✕</button>
      </span>
    </div>
    {#if r.action === 'Create'}
      <div class="line">
        <input class="mono value" bind:value={r.value} aria-label="Source Value" placeholder="Source Value" />
        {#if r.type === 'xpath' || r.type === 'expression'}
          <input class="mono small-field" bind:value={r.datatype} aria-label="Data Type" placeholder="Data Type (java.lang.String)" />
        {/if}
        {#if r.type.includes('persisted')}
          <input class="mono small-field" bind:value={r.default} aria-label="Default" placeholder="Default Value" />
        {/if}
      </div>
    {/if}
  </div>
{/each}
{#if rows.length === 0}<p class="muted">No {what}.</p>{/if}
<button class="small" onclick={add}>+ Add</button>

<style>
  .entry { border-bottom: 1px solid var(--border-soft); padding: 4px 0; display: flex; flex-direction: column; gap: 3px; }
  .line { display: flex; gap: 4px; align-items: center; min-width: 0; }
  .line input, .line select { min-width: 0; box-sizing: border-box; font-size: 12px; }
  .action { flex: 0 0 76px; }
  .name { flex: 1 1 40%; }
  .type { flex: 0 0 128px; }
  .value { flex: 2 1 0; }
  .small-field { flex: 1 1 0; }
  .actions { white-space: nowrap; margin-left: auto; }
  .muted { color: var(--muted); font-size: 12px; margin: 4px 3px; }
  button.small { margin: 4px 0; align-self: flex-start; }
</style>
