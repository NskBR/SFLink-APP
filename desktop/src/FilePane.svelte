<script lang="ts">
  import { ArrowLeft, ArrowUp, RefreshCw, Search, FolderPlus, FolderOpen, Folder, Music2, Image, File, Monitor, Smartphone, Upload, Download, Pencil, Trash2, Cable, LoaderCircle } from '@lucide/svelte';
  import type { Entry, Listing, Side } from './lib/types';
  import { bytes } from './lib/types';
  import { focusInput } from './lib/focus';
  import PhotoThumbnail from './PhotoThumbnail.svelte';
  import type { Transfer } from './lib/types';
  export let side: Side;
  export let active = false;
  export let transfers: Transfer[] = [];
  export let onActivate: () => void = () => {};
  export let onVisible: (entries: Entry[]) => void = () => {};
  export let title: string;
  export let listing: Listing = { path: '', parent: null, entries: [] };
  export let loading = false;
  export let connected = true;
  export let error = '';
  export let selected: string[] = [];
  export let dropActive = false;
  export let desktop = true;
  export let canGoBack = false;
  export let onBack: () => void;
  export let onNavigate: (path: string) => void;
  export let onChoose: () => void;
  export let onConnect: () => void;
  export let onRefresh: () => void;
  export let onSelect: (entry: Entry, event: MouseEvent) => void;
  export let onOpen: (entry: Entry) => void;
  export let onNewFolder: () => void;
  export let onRename: () => void;
  export let onDelete: () => void;
  export let onTransfer: () => void;
  export let onDrag: (entry: Entry, event: PointerEvent) => void;
  let searchInput: HTMLInputElement;
  export function focusSearch() { searchInput?.focus(); searchInput?.select(); }
  let search = '';
  let editingPath = false;
  let typedPath = '';
  $: filtered = listing.entries.filter(entry => entry.name.toLocaleLowerCase().includes(search.toLocaleLowerCase()));
  $: onVisible(filtered);
  $: pieces = listing.path.split(/[\\/]/).filter(Boolean);
  function icon(entry: Entry) {
    if (entry.isDir) return Folder;
    if (/\.(mp3|flac|wav|m4a|ogg|aac|opus)$/i.test(entry.name)) return Music2;
    if (/\.(jpg|jpeg|png|webp|gif|svg)$/i.test(entry.name)) return Image;
    return File;
  }
</script>

<section on:pointerdown={onActivate} on:focusin={onActivate} class:active-pane={active} class:drop-active={dropActive} class:connected class="file-pane" data-side={side} aria-label={title}>
  <header class="pane-heading">
    <div class="pane-title">{#if side === 'local'}<Monitor size={24}/>{:else}<Smartphone size={24}/>{/if}<h2>{title}</h2></div>
    <span class="pane-kind">{side === 'local' ? 'Neste computador' : 'Via Wi-Fi'}</span>
  </header>
  <div class="pane-toolbar">
    <button class="icon-button" title="Pasta anterior" aria-label="Pasta anterior" disabled={!canGoBack || loading} on:click={onBack}><ArrowLeft size={18}/></button>
    <button class="icon-button" title="Subir uma pasta" aria-label="Subir uma pasta" disabled={!listing.parent || loading} on:click={() => listing.parent && onNavigate(listing.parent)}><ArrowUp size={18}/></button>
    {#if editingPath}
      <form class="path-form" on:submit|preventDefault={() => { editingPath = false; onNavigate(typedPath); }}><input use:focusInput aria-label="Caminho da pasta" bind:value={typedPath} on:keydown={(event) => { if (event.key === 'Escape') editingPath = false; }}/></form>
    {:else}
      <button class="breadcrumb" title={listing.path || 'Escolha uma pasta'} disabled={!listing.path || loading} on:click={() => { typedPath = listing.path; editingPath = true; }}>
        <span>{listing.path || (side === 'local' ? 'Escolha uma pasta' : 'Armazenamento interno')}</span>
      </button>
    {/if}
    <button class="icon-button" title="Atualizar pasta" aria-label="Atualizar pasta" disabled={!listing.path || loading} on:click={onRefresh}><RefreshCw size={17} class={loading ? 'spin' : ''}/></button>
  </div>
  <div class="pane-tools">
    <label class="search"><Search size={16}/><input bind:this={searchInput} data-search={side} placeholder="Pesquisar nesta pasta…" aria-label={`Pesquisar em ${title}`} bind:value={search} disabled={!connected}/>{#if search}<button title="Limpar pesquisa" aria-label="Limpar pesquisa" on:click={() => search = ''}>×</button>{/if}</label>
    <button class="small-button" title="Criar pasta" disabled={!listing.path || loading} on:click={onNewFolder}><FolderPlus size={16}/><span>Nova pasta</span></button>
    {#if side === 'local'}<button class="icon-button" aria-label="Escolher pasta do PC" title="Escolher pasta do PC" disabled={!desktop} on:click={onChoose}><FolderOpen size={17}/></button>{/if}
  </div>
  <div class="table-head"><span>Nome</span><span>Tamanho</span></div>
  <div class="file-list">
    {#if !connected}
      <div class="empty-state connection-empty">
        <div class="device-illustration"><Smartphone size={46}/><span class="signal-dot"></span></div>
        <h3>Seu celular, sem cabo.</h3>
        <p>Conecte o Android para explorar as pastas e transferir seus arquivos.</p>
        <button class="primary-button" on:click={onConnect}><Cable size={17}/>Conectar celular</button>
        <span class="quiet-note">PC e celular na mesma rede</span>
      </div>
    {:else if loading && !listing.entries.length}
      <div class="empty-state"><LoaderCircle class="spin" size={30}/><p>Carregando arquivos…</p></div>
    {:else if error}
      <div class="empty-state"><FolderOpen size={34}/><h3>Não foi possível abrir a pasta</h3><p class="error-copy">{error}</p><button class="small-button" on:click={onRefresh}>Tentar novamente</button></div>
    {:else if !listing.path}
      <div class="empty-state"><FolderOpen size={36}/><h3>{desktop ? 'Escolha uma pasta do PC' : 'Prévia da interface'}</h3><p>{desktop ? 'Abra uma pasta para começar a organizar seus arquivos.' : 'A navegação real pelos arquivos fica disponível no aplicativo Windows.'}</p>{#if desktop}<button class="small-button" on:click={onChoose}>Escolher pasta</button>{/if}</div>
    {:else if !filtered.length}
      <div class="empty-state"><FolderOpen size={32}/><h3>{search ? 'Nenhum resultado' : 'Esta pasta está vazia'}</h3><p>{search ? 'Tente pesquisar por outro nome.' : 'Os arquivos adicionados aparecerão aqui.'}</p></div>
    {:else}
      {#each filtered as entry (entry.path)}
        {@const Icon = icon(entry)}
        {@const status = [...transfers].reverse().find(t => t.source === entry.path && t.direction === (side === 'local' ? 'upload' : 'download'))}
        <button class="file-row" class:selected={selected.includes(entry.path)} aria-pressed={selected.includes(entry.path)} title={entry.name} on:click={(event) => onSelect(entry, event)} on:dblclick={() => onOpen(entry)} on:keydown={(event) => { if (event.key === 'Enter') { event.preventDefault(); onOpen(entry); } }} on:pointerdown={(event) => { event.currentTarget.focus({ preventScroll: true }); onDrag(entry, event); }}>
          <span class="file-name">{#if !entry.isDir && /\.(jpe?g|png|webp)$/i.test(entry.name)}{#key `${entry.path}:${entry.modified}:${entry.size}`}<PhotoThumbnail {side} path={entry.path}/>{/key}{:else}<span class="file-icon" class:folder={entry.isDir} class:audio={Icon === Music2} class:image={Icon === Image}><Icon size={21} fill={entry.isDir ? 'currentColor' : 'none'} strokeWidth={1.6}/></span>{/if}<span class="file-label"><span>{entry.name}</span>{#if status}<span class="row-transfer-status" class:failed={status.status === 'error'}>{status.status === 'running' ? `${status.direction === 'upload' ? 'Enviando' : 'Baixando'} · ${status.total ? Math.min(100, Math.round(status.transferred / status.total * 100)) + '%' : bytes(status.transferred)}` : status.status === 'queued' ? `Na fila para ${status.direction === 'upload' ? 'enviar' : 'baixar'}` : status.status === 'done' ? (status.direction === 'upload' ? 'Enviado' : 'Baixado') : status.status === 'cancelled' ? 'Cancelado' : 'Falha na transferência'}</span>{#if status.status === 'running'}<span class="row-progress"><span style={`width:${status.total ? Math.min(100, status.transferred / status.total * 100) : 0}%`}></span></span>{/if}{/if}</span></span>
          <span class="file-size">{entry.isDir ? '—' : bytes(entry.size)}</span>
        </button>
      {/each}
    {/if}
  </div>
  {#if side === 'remote' && connected}<div class="drop-hint"><Upload size={21}/><span>Arraste arquivos para esta pasta</span></div>{/if}
  <footer class="pane-footer">
    <span>{selected.length ? `${selected.length} selecionado${selected.length > 1 ? 's' : ''}` : connected && listing.path ? `${filtered.length} itens` : 'Aguardando conexão'}</span>
    <div class="selection-actions">
      <button class="icon-button" title="Renomear" aria-label="Renomear selecionado" disabled={selected.length !== 1 || loading} on:click={onRename}><Pencil size={15}/></button>
      <button class="icon-button" title={side === 'local' ? 'Mover para a Lixeira' : 'Excluir'} aria-label="Excluir selecionados" disabled={!selected.length || loading} on:click={onDelete}><Trash2 size={15}/></button>
      <button class="small-button transfer-button" disabled={!selected.length || loading} on:click={onTransfer}>{#if side === 'local'}<Upload size={15}/>Enviar{:else}<Download size={15}/>Baixar{/if}</button>
    </div>
  </footer>
</section>

<style>
  .file-pane.active-pane{outline:1px solid #205444;outline-offset:-1px}
  .file-label{display:flex;flex-direction:column;gap:4px;min-width:0;flex:1}.file-label>span:first-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .row-transfer-status{font-size:10px;color:var(--accent)}.row-transfer-status.failed{color:#f3adb3}.row-progress{height:3px;max-width:180px;border-radius:3px;background:#26342e;overflow:hidden}.row-progress>span{display:block;height:100%;background:var(--accent)}
</style>
