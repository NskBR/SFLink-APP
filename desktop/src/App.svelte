<script lang="ts">
  import { onMount } from 'svelte';
  import { invoke, isTauri } from '@tauri-apps/api/core';
  import { listen } from '@tauri-apps/api/event';
  import { getCurrentWebview } from '@tauri-apps/api/webview';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { open } from '@tauri-apps/plugin-dialog';
  import { Wifi, Folder, ArrowDownUp, Smartphone, Settings, Plus, ArrowUpRight, Check, X, LoaderCircle, RefreshCw, Monitor, ChevronDown, ShieldCheck, Upload, Download, CircleHelp, FolderOpen, Home, Music2, Image, FileText } from '@lucide/svelte';
  import FilePane from './FilePane.svelte';
  import TitleBar from './TitleBar.svelte';
  import SettingsPanel from './SettingsPanel.svelte';
  import MediaModal from './MediaModal.svelte';
  import { startUpdateChecks, setUpdateTransfers } from './lib/updater';
  import logo from './assets/sflink-logo.svg';
  import type { Entry, Listing, Shortcut, Device, Side, Transfer } from './lib/types';
  import { bytes } from './lib/types';
  import { focusDialog } from './lib/focus';

  const desktop = isTauri();
  const blank = (): Listing => ({ path: '', parent: null, entries: [] });
  let section = 'Arquivos';
  let activeSide: Side = 'local';
  let media: { path: string; name: string; playlist: { path: string; name: string }[] } | null = null;
  let mediaRequest = 0;
  let forwardHistory: Record<Side, string[]> = { local: [], remote: [] };
  let contextMenu: { side: Side; entry: Entry | null; x: number; y: number } | null = null;
  let contextElement: HTMLDivElement;
  let localPane: FilePane, remotePane: FilePane;
  let visibleLocal: Entry[] = [], visibleRemote: Entry[] = [];
  let anchors: Record<Side, string> = { local: '', remote: '' };
  let local = blank(), remote = blank();
  let localLoading = false, remoteLoading = false;
  let localError = '', remoteError = '';
  let localSelected: string[] = [], remoteSelected: string[] = [];
  let quick: Shortcut[] = [];
  let device: Device | null = null;
  type KnownDevice = { id: string; name: string; address: string | null };
  let remembered: KnownDevice[] = [];
  let scanning = false, quickConnecting = false, autoConnect = true;
  let connectedSavedId = '';
  let discoveryError = '';
  let discoveryTimer: ReturnType<typeof setInterval>;
  async function quickConnect(saved: KnownDevice, automatic = false) {
    if (!saved.address || quickConnecting || busy || device) return;
    quickConnecting = true;
    try {
      device = await invoke<Device>('connect_remembered', { id: saved.id, address: saved.address });
      connectedSavedId = saved.id; autoConnect = true;
      await load('remote', device.root);
      notify(`${device.name} conectado por acesso rápido.`);
    } catch (error) { if (!automatic) notify(String(error)); }
    finally { quickConnecting = false; }
  }
  async function refreshRemembered() {
    if (!desktop || scanning || quickConnecting || busy) return;
    scanning = true;
    try {
      if (device) {
        const monitoredDevice = device;
        try { await invoke('connection_health'); }
        catch { if (device === monitoredDevice) { await disconnect(false); notify('A conexão com o celular foi encerrada.'); } }
      }
      remembered = await invoke<KnownDevice[]>('remembered_devices'); discoveryError = '';
      const available = remembered.filter(item => item.address);
      if (!device && autoConnect && !modal && available.length === 1) await quickConnect(available[0], true);
    } catch (error) { discoveryError = String(error); }
    finally { scanning = false; }
  }
  async function forgetSaved(id: string) {
    try {
      if (connectedSavedId === id) await disconnect();
      await invoke('forget_remembered', { id });
      remembered = remembered.filter(item => item.id !== id);
      notify('Celular removido deste PC. Para revogar o acesso também no celular, esqueça este PC no Android.');
    } catch (error) { notify(String(error)); }
  }
  let transfers: Transfer[] = [];
  let worker = false;
  let queueOpen = true;
  let toast = '';
  let toastTimer: ReturnType<typeof setTimeout>;
  let modal: 'connect' | 'mkdir' | 'rename' | 'delete' | null = null;
  let modalSide: Side = 'local';
  let inputName = '';
  let address = '', token = '', certificatePath = '';
  let certificatePem = '', importedConnection = false;
  let pairingVerification = '';
  let modalError = '', busy = false;
  let drag: { side: Side; entries: Entry[]; x: number; y: number; active: boolean } | null = null;
  let dropSide: Side | null = null;
  let nativeDrop = false;
  let loadVersions = { local: 0, remote: 0 };
  let history: Record<Side, string[]> = { local: [], remote: [] };
  $: activeTransfers = transfers.filter(t => t.status === 'running' || t.status === 'queued');
  $: setUpdateTransfers(activeTransfers.length > 0);
  $: running = transfers.find(t => t.status === 'running');
  $: progress = running ? running.total ? Math.min(100, Math.round(running.transferred / running.total * 100)) : 0 : 0;

  function notify(message: string) { toast = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => toast = '', 6500); }
  async function load(side: Side, path: string, record = true, direction: 'back' | 'forward' | null = null) {
    if (!desktop || !path) return;
    const version = ++loadVersions[side];
    if (side === 'local') { localLoading = true; localError = ''; localSelected = []; } else { remoteLoading = true; remoteError = ''; remoteSelected = []; }
    try {
      const listing = await invoke<Listing>(side === 'local' ? 'local_list' : 'remote_list', { path });
      if (version !== loadVersions[side]) return;
      const previous = (side === 'local' ? local : remote).path;
      if (previous && previous !== listing.path) {
        if (direction === 'back') { history = { ...history, [side]: history[side].slice(0, -1) }; forwardHistory = { ...forwardHistory, [side]: [...forwardHistory[side], previous] }; }
        else if (direction === 'forward') { forwardHistory = { ...forwardHistory, [side]: forwardHistory[side].slice(0, -1) }; history = { ...history, [side]: [...history[side], previous] }; }
        else if (record) { history = { ...history, [side]: [...history[side], previous] }; forwardHistory = { ...forwardHistory, [side]: [] }; }
      }
      listing.entries.sort((a,b) => Number(b.isDir) - Number(a.isDir) || a.name.localeCompare(b.name, 'pt-BR', { numeric: true }));
      if (side === 'local') local = listing; else remote = listing;
    } catch (error) {
      if (version !== loadVersions[side]) return;
      if (side === 'local') localError = String(error); else remoteError = String(error);
    } finally { if (version === loadVersions[side]) { if (side === 'local') localLoading = false; else remoteLoading = false; } }
  }
  function goBack(side: Side) { const path = history[side].at(-1); if (path) void load(side, path, false, 'back'); }
  function goForward(side: Side) { const path = forwardHistory[side].at(-1); if (path) void load(side, path, false, 'forward'); }
  function mouseNavigate(event: MouseEvent) {
    if (event.button !== 3 && event.button !== 4) return;
    event.preventDefault();
    if (section !== 'Arquivos' || modal || media || busy) return;
    const side = (event.target as HTMLElement)?.closest('[data-side]')?.getAttribute('data-side') as Side || activeSide;
    activeSide = side;
    if (event.type === 'mouseup') { if (event.button === 3) goBack(side); else goForward(side); }
  }
  function showContext(side: Side, entry: Entry | null, event: MouseEvent) {
    event.preventDefault(); activeSide = side;
    if (entry && !(side === 'local' ? localSelected : remoteSelected).includes(entry.path)) { if (side === 'local') localSelected = [entry.path]; else remoteSelected = [entry.path]; }
    if (!entry) { if (side === 'local') localSelected = []; else remoteSelected = []; }
    contextMenu = { side, entry, x: Math.max(8, Math.min(event.clientX, window.innerWidth - 248)), y: Math.max(8, Math.min(event.clientY, window.innerHeight - 360)) };
    setTimeout(() => contextElement?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus(), 0);
  }
  function contextAction(action: string) {
    const menu = contextMenu; contextMenu = null; if (!menu) return;
    const { side, entry } = menu;
    if (action === 'open' && entry) void openEntry(side, entry);
    if (action === 'transfer') enqueue(side);
    if (action === 'rename' || action === 'delete' || action === 'mkdir') showModal(action, side);
    if (action === 'refresh') void load(side, (side === 'local' ? local : remote).path);
    if (action === 'external' && entry && side === 'local') void invoke('local_open', { path: entry.path }).catch(e => notify(String(e)));
    if (action === 'select-all') { const paths = (side === 'local' ? visibleLocal : visibleRemote).map(e => e.path); if (side === 'local') localSelected = paths; else remoteSelected = paths; }
  }
  function contextKeys(event: KeyboardEvent) {
    const buttons = [...contextElement.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')];
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (['ArrowDown','ArrowUp','Home','End'].includes(event.key)) { event.preventDefault(); buttons[event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length]?.focus(); }
    if (event.key === 'Tab' || event.key === 'Escape') contextMenu = null;
  }
  async function openLocal(path: string, name: string) {
    if (/\.(jpe?g|png|webp|gif|bmp|mp3|m4a|flac|wav|ogg|aac|opus)$/i.test(name)) { mediaRequest++; media = { path, name, playlist: [...local.entries.filter(e => !e.isDir && /\.(mp3|flac|m4a|wav|ogg|aac|opus)$/i.test(e.name)).map(e => ({ path: e.path, name: e.name })), ...(!local.entries.some(e => e.path === path) ? [{ path, name }] : [])] }; }
    else { try { await invoke('local_open', { path }); } catch (error) { notify(String(error)); } }
  }
  async function chooseLocal() {
    if (!desktop) return;
    const path = await open({ directory: true, multiple: false, title: 'Escolher pasta do PC' });
    if (typeof path === 'string') await load('local', path);
  }
  function select(side: Side, entry: Entry, event: MouseEvent) {
    const selected = side === 'local' ? localSelected : remoteSelected;
    const visible = side === 'local' ? visibleLocal : visibleRemote;
    let next: string[];
    if (event.shiftKey && anchors[side]) {
      const start = visible.findIndex(e => e.path === anchors[side]), end = visible.findIndex(e => e.path === entry.path);
      const range = start < 0 ? [entry.path] : visible.slice(Math.min(start, end), Math.max(start, end) + 1).map(e => e.path);
      next = event.ctrlKey || event.metaKey ? [...new Set([...selected, ...range])] : range;
    } else {
      next = event.ctrlKey || event.metaKey ? selected.includes(entry.path) ? selected.filter(p => p !== entry.path) : [...selected, entry.path] : [entry.path];
      anchors[side] = entry.path;
    }
    activeSide = side;
    if (side === 'local') localSelected = next; else remoteSelected = next;
  }

  async function openEntry(side: Side, entry: Entry) {
    if (entry.isDir) await load(side, entry.path);
    else if (side === 'local') await openLocal(entry.path, entry.name);
    else {
      if (transfers.some(t => t.openAfter && t.source === entry.path && ['queued', 'running'].includes(t.status))) { notify('Este arquivo já está sendo baixado para abrir.'); return; }
      if (!local.path) await chooseLocal();
      if (local.path) { enqueue('remote', [entry], true); notify(`Baixando ${entry.name}. O arquivo abrirá ao concluir.`); }
    }
  }
  function showModal(type: typeof modal, side: Side = 'local') {
    if (quickConnecting) { notify('Aguarde a conexão rápida terminar.'); return; }
    modalSide = side; modalError = ''; inputName = ''; pairingVerification = '';
    if (type === 'rename') inputName = (side === 'local' ? local : remote).entries.find(e => (side === 'local' ? localSelected : remoteSelected).includes(e.path))?.name || '';
    modal = type;
  }
  async function submitModal() {
    busy = true; modalError = '';
    try {
      if (modal === 'connect') {
        if (!desktop) throw 'Abra o aplicativo Windows para conectar ao celular.';
        const connected = importedConnection || certificatePath ? await invoke<Device>('connect_device', { address, token, certificatePath: certificatePath || null, certificatePem: certificatePem || null }) : await invoke<Device>('pair_device', { address, code: token });
        device = connected; autoConnect = true; connectedSavedId = ''; token = ''; certificatePem = ''; importedConnection = false; modal = null;
        await load('remote', connected.root);
        notify(`${connected.name} conectado.`);
      } else {
        const listing = modalSide === 'local' ? local : remote;
        const selected = modalSide === 'local' ? localSelected : remoteSelected;
        if (modal === 'mkdir') {
          if (modalSide === 'local') await invoke('local_mkdir', { parent: listing.path, name: inputName });
          else await invoke('remote_action', { action: 'mkdir', path: listing.path, name: inputName });
        } else if (modal === 'rename') {
          if (modalSide === 'local') await invoke('local_rename', { path: selected[0], name: inputName });
          else await invoke('remote_action', { action: 'rename', path: selected[0], name: inputName });
        } else if (modal === 'delete') {
          if (modalSide === 'local') await invoke('local_trash', { paths: selected });
          else for (const path of selected) await invoke('remote_action', { action: 'delete', path, name: null });
        }
        modal = null; await load(modalSide, listing.path);
      }
    } catch (error) { modalError = String(error); } finally { busy = false; }
  }
  async function disconnect(manual = true) {
    if (manual) autoConnect = false;
    connectedSavedId = '';
    try { await invoke('disconnect_device'); } catch (error) { notify(String(error)); return; }
    device = null; ++loadVersions.remote; remote = blank(); remoteSelected = []; remoteError = ''; remoteLoading = false; history = { ...history, remote: [] }; forwardHistory = { ...forwardHistory, remote: [] };
    transfers = transfers.map(t => t.status === 'queued' ? { ...t, status: 'cancelled' } : t);
  }
  function enqueue(side: Side, entries?: Entry[], openAfter = false) {
    if (!device) { showModal('connect'); return; }
    const selection = entries || (side === 'local' ? local : remote).entries.filter(e => (side === 'local' ? localSelected : remoteSelected).includes(e.path));
    const files = selection.filter(e => !e.isDir);
    if (files.length < selection.length) notify('Nesta primeira versão, selecione os arquivos dentro da pasta para transferir.');
    const destination = side === 'local' ? remote.path : local.path;
    if (!destination) { notify('Abra uma pasta de destino primeiro.'); return; }
    transfers = [...transfers, ...files.map(file => ({ id: crypto.randomUUID(), openAfter, name: file.name, source: file.path, destination, direction: side === 'local' ? 'upload' as const : 'download' as const, total: file.size, transferred: 0, status: 'queued' as const }))];
    queueOpen = true; void runQueue();
  }
  function updateTransfer(id: string, patch: Partial<Transfer>) { transfers = transfers.map(t => t.id === id ? { ...t, ...patch } : t); }
  async function runQueue() {
    if (worker) return;
    worker = true;
    try {
      while (device) {
        const next = transfers.find(t => t.status === 'queued');
        if (!next) break;
        updateTransfer(next.id, { status: 'running' });
        try {
          const downloadedPath = await invoke<string | null>('transfer_file', { id: next.id, direction: next.direction, source: next.source, destination: next.destination, openAfter: !!next.openAfter });
          updateTransfer(next.id, { status: 'done', transferred: transfers.find(t => t.id === next.id)?.total || next.total });
          if (next.openAfter && downloadedPath) await openLocal(downloadedPath, next.name);
          const side = next.direction === 'upload' ? 'remote' : 'local';
          if (device && next.destination === (side === 'local' ? local.path : remote.path)) await load(side, next.destination);
        } catch (error) { updateTransfer(next.id, { status: String(error).includes('cancelada') ? 'cancelled' : 'error', error: String(error) }); }
      }
    } finally { worker = false; }
  }
  async function cancel(id: string) {
    const transfer = transfers.find(t => t.id === id);
    if (transfer?.status === 'queued') updateTransfer(id, { status: 'cancelled' });
    else { try { await invoke('cancel_transfer', { id }); } catch (error) { notify(String(error)); } }
  }
  function keydown(event: KeyboardEvent) {
    const key = event.key.toLowerCase(), ctrl = event.ctrlKey || event.metaKey;
    if (media) { if (event.key === 'Escape') { event.preventDefault(); media = null; } return; }
    if (contextMenu) { if (event.key === 'Escape') contextMenu = null; return; }
    if (event.altKey && ['ArrowLeft','ArrowRight'].includes(event.key)) { event.preventDefault(); if (!modal && section === 'Arquivos') { if (event.key === 'ArrowLeft') goBack(activeSide); else goForward(activeSide); } return; }
    const editing = event.target instanceof HTMLElement && !!event.target.closest('input, textarea, [contenteditable="true"]');
    if (ctrl && key === 'f') {
      event.preventDefault();
      if (section === 'Arquivos' && !modal) (activeSide === 'local' ? localPane : remotePane)?.focusSearch();
      return;
    }
    if (event.key === 'Escape' && !busy) { if (modal) modal = null; else if (!editing) { localSelected = []; remoteSelected = []; } drag = null; dropSide = null; }
    if (editing || modal || section !== 'Arquivos' || busy || event.altKey) return;
    const listing = activeSide === 'local' ? local : remote;
    const selected = activeSide === 'local' ? localSelected : remoteSelected;
    const visible = activeSide === 'local' ? visibleLocal : visibleRemote;
    if (ctrl && key === 'a') { event.preventDefault(); const paths = visible.map(e => e.path); if (activeSide === 'local') localSelected = paths; else remoteSelected = paths; }
    else if (key === 'delete') { event.preventDefault(); if (selected.length) showModal('delete', activeSide); }
    else if (key === 'f2') { event.preventDefault(); if (selected.length === 1) showModal('rename', activeSide); }
    else if (key === 'f5' || (ctrl && key === 'r')) { event.preventDefault(); void load(activeSide, listing.path); }
    else if (ctrl && event.shiftKey && key === 'n') { event.preventDefault(); if (listing.path) showModal('mkdir', activeSide); }
    else if (key === 'backspace') { event.preventDefault(); if (listing.parent) void load(activeSide, listing.parent); }
  }
  function beginDrag(side: Side, entry: Entry, event: PointerEvent) {
    if (event.button !== 0 || entry.isDir) return;
    const selected = side === 'local' ? localSelected : remoteSelected;
    const listing = side === 'local' ? local : remote;
    drag = { side, entries: selected.includes(entry.path) ? listing.entries.filter(e => selected.includes(e.path)) : [entry], x: event.clientX, y: event.clientY, active: false };
  }
  function pointerMove(event: PointerEvent) {
    if (!drag) return;
    if (event.buttons === 0) { drag = null; dropSide = null; return; }
    if (!drag.active && Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 8) return;
    drag = { ...drag, active: true, x: event.clientX, y: event.clientY };
    const side = document.elementFromPoint(event.clientX, event.clientY)?.closest('[data-side]')?.getAttribute('data-side') as Side | null;
    dropSide = side !== drag.side ? side : null;
  }
  function pointerUp() { if (drag?.active && dropSide) enqueue(drag.side, drag.entries); drag = null; dropSide = null; }
  async function pickCertificate() { const path = await open({ multiple: false, title: 'Certificado do celular', filters: [{ name: 'Certificado PEM', extensions: ['pem', 'crt'] }] }); if (typeof path === 'string') { certificatePath = path; certificatePem = ''; importedConnection = false; } }
  async function importConnection() {
    modalError = '';
    try {
      const path = await open({ multiple: false, title: 'Importar conexão do Android', filters: [{ name: 'Arquivo de conexão ou imagem do QR', extensions: ['json', 'png', 'jpg', 'jpeg', 'webp'] }] });
      if (typeof path !== 'string') return;
      const document = await invoke<{ address: string; token: string; certificatePem: string }>('read_pairing', { path });
      address = document.address; token = document.token; certificatePem = document.certificatePem; certificatePath = ''; importedConnection = true;
    } catch (error) { modalError = String(error); }
  }
  onMount(() => {
    const cleanups: (() => void)[] = [startUpdateChecks()];
    let destroyed = false;
    async function setup() {
      if (!desktop) return;
      void refreshRemembered();
      discoveryTimer = setInterval(() => void refreshRemembered(), 6000);
      quick = await invoke<Shortcut[]>('shortcuts');
      const initial = quick.find(s => s.name === 'Downloads') || quick[0];
      if (initial) await load('local', initial.path);
      const unlisten = await listen<{ id: string; transferred: number; total: number }>('transfer-progress', ({ payload }) => updateTransfer(payload.id, { transferred: payload.transferred, total: payload.total || transfers.find(t => t.id === payload.id)?.total || 0 }));
      if (destroyed) unlisten(); else cleanups.push(unlisten);
      const unpair = await listen<string>('pairing-verification', ({ payload }) => { if (modal === 'connect' && busy) pairingVerification = payload; });
      if (destroyed) unpair(); else cleanups.push(unpair);
      const stopDrop = await getCurrentWebview().onDragDropEvent(async ({ payload }) => {
        if (payload.type === 'leave') { nativeDrop = false; return; }
        const scale = await getCurrentWindow().scaleFactor();
        const target = document.elementFromPoint(payload.position.x / scale, payload.position.y / scale)?.closest('[data-side]')?.getAttribute('data-side');
        nativeDrop = target === 'remote';
        if (payload.type !== 'drop') return;
        nativeDrop = false;
        if (target !== 'remote') { notify('Solte os arquivos no painel do celular.'); return; }
        const entries: Entry[] = payload.paths.map(path => ({ path, name: path.split(/[\\/]/).pop() || path, isDir: false, size: 0, modified: null }));
        enqueue('local', entries);
      });
      if (destroyed) stopDrop(); else cleanups.push(stopDrop);
    }
    void setup().catch(error => notify(String(error)));
    return () => { destroyed = true; cleanups.forEach(fn => fn()); clearTimeout(toastTimer); clearInterval(discoveryTimer); };
  });
</script>

<svelte:window on:mousedown={mouseNavigate} on:mouseup={mouseNavigate} on:auxclick={(event) => { if ([3, 4].includes(event.button)) event.preventDefault(); }} on:resize={() => contextMenu = null} on:pointermove={pointerMove} on:pointerup={pointerUp} on:pointercancel={() => { drag = null; dropSide = null; }} on:blur={() => { drag = null; dropSide = null; }} on:keydown={keydown}/>

<TitleBar {desktop} onError={notify}/>
<div class="app-shell">
  <aside class="sidebar">
    <div class="brand"><span class="brand-icon"><img src={logo} alt="" style="width:38px;height:32px"/></span><div>SFLink<span>via Wi-Fi</span></div></div>
    <nav aria-label="Navegação principal">
      {#each [{ name: 'Arquivos', icon: Folder }, { name: 'Transferências', icon: ArrowDownUp }, { name: 'Dispositivos', icon: Smartphone }, { name: 'Configurações', icon: Settings }] as item}
        <button class:active={section === item.name} on:click={() => section = item.name}><svelte:component this={item.icon} size={19}/><span>{item.name}</span>{#if item.name === 'Transferências' && activeTransfers.length}<span class="nav-count">{activeTransfers.length}</span>{/if}</button>
      {/each}
    </nav>
    <div class="quick-access"><span class="sidebar-label">Acesso rápido</span>{#each quick.slice(0, 5) as shortcut}<button title={shortcut.path} class:current={local.path === shortcut.path} on:click={() => { section = 'Arquivos'; void load('local', shortcut.path); }}><svelte:component this={shortcut.name === 'Início' ? Home : shortcut.name === 'Downloads' ? Download : shortcut.name === 'Músicas' ? Music2 : shortcut.name === 'Imagens' ? Image : FileText} size={16}/>{shortcut.name}</button>{/each}{#if !quick.length}<p>Suas pastas aparecerão aqui no aplicativo Windows.</p>{/if}</div>
    <div class="sidebar-bottom"><ShieldCheck size={17}/><div>Conexão local<span>Seus arquivos entre seus dispositivos</span></div></div>
  </aside>
  <main>
    <header class="topbar"><div><h1>{section}</h1><span>{section === 'Arquivos' ? 'Organize e transfira, sem cabo.' : section === 'Transferências' ? 'Acompanhe os arquivos enviados e recebidos.' : section === 'Dispositivos' ? 'Seu Android conectado ao computador.' : 'Preferências do aplicativo.'}</span></div><div class="connection-status"><div class="connection-pill" class:connected={!!device}><span class="status-dot" class:online={device}></span><span>{device ? 'Celular conectado' : quickConnecting ? 'Conectando…' : 'Nenhum celular conectado'}</span></div>{#if device}<button class="small-button" on:click={() => void disconnect()}>Desconectar</button>{:else}<button class="small-button" disabled={quickConnecting} on:click={() => showModal('connect')}><Plus size={16}/>Conectar</button>{/if}</div></header>
    {#if !desktop}<div class="preview-banner"><CircleHelp size={16}/>Prévia visual · Execute a versão Windows para acessar seus arquivos.</div>{/if}
    {#if section === 'Arquivos'}
      <div class="workspace">
        <FilePane canGoForward={forwardHistory.local.length > 0} onForward={() => goForward('local')} onContext={(entry, event) => showContext('local', entry, event)} bind:this={localPane} active={activeSide === 'local'} {transfers} onActivate={() => activeSide = 'local'} onVisible={entries => visibleLocal = entries} canGoBack={history.local.length > 0} onBack={() => goBack('local')} side="local" title="Este PC" listing={local} loading={localLoading} error={localError} selected={localSelected} {desktop} dropActive={dropSide === 'local'} onNavigate={path => void load('local', path)} onChoose={() => void chooseLocal()} onConnect={() => showModal('connect')} onRefresh={() => void load('local', local.path)} onSelect={(entry, event) => select('local', entry, event)} onOpen={entry => void openEntry('local', entry)} onNewFolder={() => showModal('mkdir')} onRename={() => showModal('rename')} onDelete={() => showModal('delete')} onTransfer={() => enqueue('local')} onDrag={(entry, event) => beginDrag('local', entry, event)}/>
        <FilePane canGoForward={forwardHistory.remote.length > 0} onForward={() => goForward('remote')} onContext={(entry, event) => showContext('remote', entry, event)} bind:this={remotePane} active={activeSide === 'remote'} {transfers} onActivate={() => activeSide = 'remote'} onVisible={entries => visibleRemote = entries} canGoBack={history.remote.length > 0} onBack={() => goBack('remote')} side="remote" title={device?.name || 'Meu Android'} listing={remote} loading={remoteLoading} connected={!!device} error={remoteError} selected={remoteSelected} {desktop} dropActive={dropSide === 'remote' || nativeDrop} onNavigate={path => void load('remote', path)} onChoose={() => {}} onConnect={() => showModal('connect')} onRefresh={() => void load('remote', remote.path)} onSelect={(entry, event) => select('remote', entry, event)} onOpen={entry => void openEntry('remote', entry)} onNewFolder={() => showModal('mkdir', 'remote')} onRename={() => showModal('rename', 'remote')} onDelete={() => showModal('delete', 'remote')} onTransfer={() => enqueue('remote')} onDrag={(entry, event) => beginDrag('remote', entry, event)}/>
      </div>
    {:else if section === 'Dispositivos'}
      <section class="page-section"><div class="section-heading"><h2>Seus dispositivos</h2><button class="primary-button" on:click={() => showModal('connect')}><Plus size={17}/>Conectar celular</button></div>{#if device}<div class="device-card"><Smartphone size={36}/><div><h3>{device.name}</h3><p>Conectado nesta sessão</p><span>{bytes(device.freeBytes)} disponíveis de {bytes(device.totalBytes)}</span></div><button class="small-button" on:click={() => void disconnect()}>Desconectar</button></div>{:else if !remembered.length}<div class="empty-state full-empty"><Smartphone size={44}/><h3>Seu primeiro dispositivo</h3><p>Ative a conexão no Android e informe o IP e o código para acessar as pastas pelo PC.</p><button class="primary-button" on:click={() => showModal('connect')}>Configurar conexão</button></div>{/if}<div class="remembered-heading"><div><h3>Dispositivos lembrados</h3><p>Abra o Android na mesma rede para conectar sem IP nem código.</p></div><button class="small-button" disabled={scanning || quickConnecting} on:click={() => { autoConnect = true; void refreshRemembered(); }}><RefreshCw size={16}/>{scanning ? 'Procurando…' : 'Atualizar'}</button></div>
      {#each remembered as saved}<div class="device-card remembered-card"><Smartphone size={28}/><div><h3>{saved.name}</h3><p class:online={!!saved.address}>{saved.address ? 'Disponível na rede' : 'Offline · Abra o app no celular'}</p></div><button class="primary-button" disabled={!saved.address || quickConnecting || !!device || busy} on:click={() => void quickConnect(saved)}>{quickConnecting ? 'Conectando…' : 'Conectar'}</button><button class="text-button" disabled={quickConnecting} on:click={() => void forgetSaved(saved.id)}>Esquecer</button></div>{/each}
      {#if !remembered.length}<p class="remembered-help">Na primeira autorização no Android, marque “Lembrar dispositivo”. Depois, basta abrir os dois aplicativos.</p>{/if}
      {#if discoveryError}<p class="remembered-help">{discoveryError}</p>{/if}</section>
    {/if}
      <div class="settings-shell" hidden={section !== 'Configurações'}><SettingsPanel/></div>

    {#if section === 'Arquivos' || section === 'Transferências'}
      <section class="transfer-panel" class:expanded={section === 'Transferências'}>
        <header><button class="queue-heading" on:click={() => queueOpen = !queueOpen}><ArrowDownUp size={20}/><h2>Transferências</h2>{#if activeTransfers.length}<span class="queue-count">{activeTransfers.length}</span>{/if}<ChevronDown size={17} class={queueOpen ? 'rotate' : ''}/></button>{#if transfers.some(t => !['queued', 'running'].includes(t.status))}<button class="text-button" on:click={() => transfers = transfers.filter(t => ['queued', 'running'].includes(t.status))}>Limpar concluídas</button>{/if}</header>
        {#if queueOpen || section === 'Transferências'}
          {#if !transfers.length}<div class="queue-empty"><div class="queue-icon"><ArrowUpRight size={23}/></div><div><strong>Tudo pronto para transferir</strong><p>Conecte o celular e arraste os arquivos entre os painéis.</p></div><span class="queue-empty-status">Nenhuma transferência</span></div>
          {:else}<div class="queue-list">{#each transfers as transfer (transfer.id)}<div class="transfer-row"><div class="transfer-direction" class:complete={transfer.status === 'done'}>{#if transfer.status === 'done'}<Check size={21}/>{:else if transfer.direction === 'upload'}<Upload size={21}/>{:else}<Download size={21}/>{/if}</div><div class="transfer-details"><strong>{transfer.name}</strong><span>{transfer.status === 'done' ? `Concluído · ${bytes(transfer.total)}` : transfer.status === 'queued' ? 'Na fila' : transfer.status === 'cancelled' ? 'Cancelado' : transfer.status === 'error' ? transfer.error : `${transfer.direction === 'upload' ? 'Enviando' : 'Baixando'} · ${bytes(transfer.transferred)} de ${bytes(transfer.total)}`}</span>{#if transfer.status === 'running'}<div class="progress-track"><div style={`width:${progress}%`}></div></div>{/if}</div>{#if transfer.status === 'running'}<span class="progress-label">{progress}%</span>{/if}{#if ['queued', 'running'].includes(transfer.status)}<button class="icon-button" title="Cancelar transferência" aria-label={`Cancelar ${transfer.name}`} on:click={() => void cancel(transfer.id)}><X size={17}/></button>{:else if transfer.status === 'error' && device}<button class="icon-button" title="Tentar novamente" aria-label={`Tentar novamente ${transfer.name}`} on:click={() => { updateTransfer(transfer.id, { status: 'queued', transferred: 0, error: undefined }); void runQueue(); }}><RefreshCw size={16}/></button>{/if}</div>{/each}</div>{/if}
        {/if}
      </section>
    {/if}
    <footer class="app-footer"><span><Wifi size={14}/>Transferência pela rede local</span>{#if device}<div><Smartphone size={15}/><span>{bytes(device.freeBytes)} livres de {bytes(device.totalBytes)}</span></div>{:else}<span>Android aguardando conexão</span>{/if}</footer>
  </main>
</div>
{#if drag?.active}<div class="drag-chip" style={`left:${drag.x + 16}px;top:${drag.y + 12}px`}><Folder size={17}/>{drag.entries.filter(e => !e.isDir).length} arquivo(s)</div>{/if}
{#if toast}<div class="toast" role="status">{toast}<button aria-label="Fechar mensagem" on:click={() => toast = ''}><X size={16}/></button></div>{/if}
{#if modal}
  <div class="modal-backdrop" role="presentation" on:click={(event) => { if (event.target === event.currentTarget && !busy) modal = null; }}>
    <div use:focusDialog class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title" tabindex="-1">
      <button class="modal-close icon-button" aria-label="Fechar" disabled={busy} on:click={() => modal = null}><X size={19}/></button>
      <div class="modal-symbol">{#if modal === 'connect'}<Smartphone size={27}/>{:else}<Folder size={27}/>{/if}</div>
      <h2 id="modal-title">{modal === 'connect' ? 'Conectar seu Android' : modal === 'mkdir' ? 'Nova pasta' : modal === 'rename' ? 'Renomear' : modalSide === 'local' ? 'Mover para a Lixeira?' : 'Excluir do celular?'}</h2>
      <form on:submit|preventDefault={submitModal}>
        {#if modal === 'connect'}<p>Ative a conexão no Android. Digite o IP e o código temporário mostrado no celular.</p><div class="modal-notice"><Wifi size={18}/><span>Mantenha PC e celular na mesma rede.</span></div><button type="button" class="primary-button import-connection" disabled={!desktop || busy} on:click={() => void importConnection()}><FolderOpen size={17}/>Importar conexão do Android</button>{#if importedConnection}<p class="imported-note">Conexão importada. Clique em Conectar para continuar.</p>{/if}<label>Endereço do celular<input placeholder="192.168.1.20" type="text" bind:value={address} required disabled={busy}/></label><label>Código temporário<input placeholder="482 719" type="text" autocomplete="off" bind:value={token} required disabled={busy}/></label>{#if pairingVerification && busy}<div class="modal-notice"><ShieldCheck size={22}/><span>Confira no Android: <strong>{pairingVerification.match(/.{1,4}/g)?.join(' ')}</strong><br/>Se a verificação for igual, autorize no celular. Aguardando aprovação…</span></div>{/if}<details><summary>Conexão avançada por certificado</summary><p>Para a integração inicial, selecione o certificado PEM fornecido pelo app Android.</p><button type="button" class="small-button" disabled={!desktop || busy} on:click={() => void pickCertificate()}><FolderOpen size={16}/>{certificatePath ? 'Trocar certificado' : 'Selecionar certificado'}</button>{#if certificatePath}<span class="certificate-name">{certificatePath.split(/[\\/]/).pop()}</span>{/if}</details>
        {:else if modal === 'delete'}<p>{modalSide === 'local' ? 'Os itens selecionados serão enviados para a Lixeira do Windows.' : 'Os itens selecionados serão excluídos do celular. Esta operação pode ser permanente.'}</p><span class="delete-count">{(modalSide === 'local' ? localSelected : remoteSelected).length} item(s) selecionado(s)</span>
        {:else}<label>Nome<input bind:value={inputName} required maxlength="240" disabled={busy}/></label>{/if}
        {#if modalError}<div class="form-error" role="alert">{modalError}</div>{/if}
        <div class="modal-actions"><button type="button" class="small-button" disabled={busy} on:click={() => modal = null}>Cancelar</button><button class="primary-button" class:danger={modal === 'delete' && modalSide === 'remote'} disabled={busy || (!desktop && modal === 'connect')}>{#if busy}<LoaderCircle class="spin" size={17}/>{/if}{modal === 'connect' ? 'Conectar' : modal === 'mkdir' ? 'Criar pasta' : modal === 'rename' ? 'Salvar nome' : 'Confirmar'}</button></div>
      </form>
    </div>
  </div>
{/if}

{#if media}{#key mediaRequest}<MediaModal path={media.path} name={media.name} playlist={media.playlist} onClose={() => media = null}/>{/key}{/if}
{#if contextMenu}
  <div class="context-dismiss" role="presentation" on:pointerdown={() => contextMenu = null} on:contextmenu|preventDefault={() => contextMenu = null}></div>
  <div bind:this={contextElement} class="file-context-menu" role="menu" aria-label="Opções de arquivos" style={`left:${contextMenu.x}px;top:${contextMenu.y}px`} on:keydown={contextKeys} tabindex="-1">
    <span class="context-caption">{contextMenu.entry?.name || 'Nesta pasta'}</span>
    {#if contextMenu.entry}<button role="menuitem" on:click={() => contextAction('open')}><FolderOpen size={16}/>Abrir <kbd>Enter</kbd></button>{#if !contextMenu.entry.isDir}<button role="menuitem" on:click={() => contextAction('transfer')}><ArrowDownUp size={16}/>{contextMenu.side === 'local' ? 'Enviar ao celular' : 'Baixar no PC'}</button>{/if}{#if contextMenu.side === 'local' && !contextMenu.entry.isDir}<button role="menuitem" on:click={() => contextAction('external')}><ArrowUpRight size={16}/>Abrir no Windows</button>{/if}<div class="context-divider"></div><button role="menuitem" disabled={(contextMenu.side === 'local' ? localSelected : remoteSelected).length !== 1} on:click={() => contextAction('rename')}>Renomear <kbd>F2</kbd></button><button role="menuitem" class="context-danger" on:click={() => contextAction('delete')}>Excluir <kbd>Delete</kbd></button><div class="context-divider"></div>{/if}
    <button role="menuitem" on:click={() => contextAction('mkdir')}><Plus size={16}/>Nova pasta</button><button role="menuitem" on:click={() => contextAction('select-all')}>Selecionar tudo <kbd>Ctrl+A</kbd></button><button role="menuitem" on:click={() => contextAction('refresh')}><RefreshCw size={16}/>Atualizar <kbd>F5</kbd></button>
  </div>
{/if}
