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
  import UpdatePanel from './UpdatePanel.svelte';
  import logo from './assets/sflink-logo.svg';
  import type { Entry, Listing, Shortcut, Device, Side, Transfer } from './lib/types';
  import { bytes } from './lib/types';
  import { focusDialog } from './lib/focus';

  const desktop = isTauri();
  const blank = (): Listing => ({ path: '', parent: null, entries: [] });
  let section = 'Arquivos';
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
  $: running = transfers.find(t => t.status === 'running');
  $: progress = running ? running.total ? Math.min(100, Math.round(running.transferred / running.total * 100)) : 0 : 0;

  function notify(message: string) { toast = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => toast = '', 6500); }
  async function load(side: Side, path: string, record = true) {
    if (!desktop || !path) return;
    const version = ++loadVersions[side];
    if (side === 'local') { localLoading = true; localError = ''; localSelected = []; } else { remoteLoading = true; remoteError = ''; remoteSelected = []; }
    try {
      const listing = await invoke<Listing>(side === 'local' ? 'local_list' : 'remote_list', { path });
      if (version !== loadVersions[side]) return;
      const previous = (side === 'local' ? local : remote).path;
      if (record && previous && previous !== listing.path) history = { ...history, [side]: [...history[side], previous] };
      listing.entries.sort((a,b) => Number(b.isDir) - Number(a.isDir) || a.name.localeCompare(b.name, 'pt-BR', { numeric: true }));
      if (side === 'local') local = listing; else remote = listing;
    } catch (error) {
      if (version !== loadVersions[side]) return;
      if (side === 'local') localError = String(error); else remoteError = String(error);
    } finally { if (version === loadVersions[side]) { if (side === 'local') localLoading = false; else remoteLoading = false; } }
  }
  function goBack(side: Side) {
    const path = history[side].at(-1);
    if (!path) return;
    history = { ...history, [side]: history[side].slice(0, -1) };
    void load(side, path, false);
  }
  async function chooseLocal() {
    if (!desktop) return;
    const path = await open({ directory: true, multiple: false, title: 'Escolher pasta do PC' });
    if (typeof path === 'string') await load('local', path);
  }
  function select(side: Side, entry: Entry, event: MouseEvent) {
    const selected = side === 'local' ? localSelected : remoteSelected;
    const next = event.ctrlKey || event.metaKey ? selected.includes(entry.path) ? selected.filter(p => p !== entry.path) : [...selected, entry.path] : [entry.path];
    if (side === 'local') localSelected = next; else remoteSelected = next;
  }
  async function openEntry(side: Side, entry: Entry) {
    if (entry.isDir) await load(side, entry.path);
    else if (side === 'local') { try { await invoke('local_open', { path: entry.path }); } catch (error) { notify(String(error)); } }
    else notify('Use Baixar para copiar este arquivo para a pasta aberta no PC.');
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
    device = null; ++loadVersions.remote; remote = blank(); remoteSelected = []; remoteError = ''; remoteLoading = false; history = { ...history, remote: [] };
    transfers = transfers.map(t => t.status === 'queued' ? { ...t, status: 'cancelled' } : t);
  }
  function enqueue(side: Side, entries?: Entry[]) {
    if (!device) { showModal('connect'); return; }
    const selection = entries || (side === 'local' ? local : remote).entries.filter(e => (side === 'local' ? localSelected : remoteSelected).includes(e.path));
    const files = selection.filter(e => !e.isDir);
    if (files.length < selection.length) notify('Nesta primeira versão, selecione os arquivos dentro da pasta para transferir.');
    const destination = side === 'local' ? remote.path : local.path;
    if (!destination) { notify('Abra uma pasta de destino primeiro.'); return; }
    transfers = [...transfers, ...files.map(file => ({ id: crypto.randomUUID(), name: file.name, source: file.path, destination, direction: side === 'local' ? 'upload' as const : 'download' as const, total: file.size, transferred: 0, status: 'queued' as const }))];
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
          await invoke('transfer_file', { id: next.id, direction: next.direction, source: next.source, destination: next.destination });
          updateTransfer(next.id, { status: 'done', transferred: next.total });
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
    const cleanups: (() => void)[] = [];
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

<svelte:window on:pointermove={pointerMove} on:pointerup={pointerUp} on:pointercancel={() => { drag = null; dropSide = null; }} on:blur={() => { drag = null; dropSide = null; }} on:keydown={(event) => { if (event.key === 'Escape' && !busy) { modal = null; drag = null; dropSide = null; } }}/>

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
        <FilePane canGoBack={history.local.length > 0} onBack={() => goBack('local')} side="local" title="Este PC" listing={local} loading={localLoading} error={localError} selected={localSelected} {desktop} dropActive={dropSide === 'local'} onNavigate={path => void load('local', path)} onChoose={() => void chooseLocal()} onConnect={() => showModal('connect')} onRefresh={() => void load('local', local.path)} onSelect={(entry, event) => select('local', entry, event)} onOpen={entry => void openEntry('local', entry)} onNewFolder={() => showModal('mkdir')} onRename={() => showModal('rename')} onDelete={() => showModal('delete')} onTransfer={() => enqueue('local')} onDrag={(entry, event) => beginDrag('local', entry, event)}/>
        <FilePane canGoBack={history.remote.length > 0} onBack={() => goBack('remote')} side="remote" title={device?.name || 'Meu Android'} listing={remote} loading={remoteLoading} connected={!!device} error={remoteError} selected={remoteSelected} {desktop} dropActive={dropSide === 'remote' || nativeDrop} onNavigate={path => void load('remote', path)} onChoose={() => {}} onConnect={() => showModal('connect')} onRefresh={() => void load('remote', remote.path)} onSelect={(entry, event) => select('remote', entry, event)} onOpen={entry => void openEntry('remote', entry)} onNewFolder={() => showModal('mkdir', 'remote')} onRename={() => showModal('rename', 'remote')} onDelete={() => showModal('delete', 'remote')} onTransfer={() => enqueue('remote')} onDrag={(entry, event) => beginDrag('remote', entry, event)}/>
      </div>
    {:else if section === 'Dispositivos'}
      <section class="page-section"><div class="section-heading"><h2>Seus dispositivos</h2><button class="primary-button" on:click={() => showModal('connect')}><Plus size={17}/>Conectar celular</button></div>{#if device}<div class="device-card"><Smartphone size={36}/><div><h3>{device.name}</h3><p>Conectado nesta sessão</p><span>{bytes(device.freeBytes)} disponíveis de {bytes(device.totalBytes)}</span></div><button class="small-button" on:click={() => void disconnect()}>Desconectar</button></div>{:else if !remembered.length}<div class="empty-state full-empty"><Smartphone size={44}/><h3>Seu primeiro dispositivo</h3><p>Ative a conexão no Android e informe o IP e o código para acessar as pastas pelo PC.</p><button class="primary-button" on:click={() => showModal('connect')}>Configurar conexão</button></div>{/if}<div class="remembered-heading"><div><h3>Dispositivos lembrados</h3><p>Abra o Android na mesma rede para conectar sem IP nem código.</p></div><button class="small-button" disabled={scanning || quickConnecting} on:click={() => { autoConnect = true; void refreshRemembered(); }}><RefreshCw size={16}/>{scanning ? 'Procurando…' : 'Atualizar'}</button></div>
      {#each remembered as saved}<div class="device-card remembered-card"><Smartphone size={28}/><div><h3>{saved.name}</h3><p class:online={!!saved.address}>{saved.address ? 'Disponível na rede' : 'Offline · Abra o app no celular'}</p></div><button class="primary-button" disabled={!saved.address || quickConnecting || !!device || busy} on:click={() => void quickConnect(saved)}>{quickConnecting ? 'Conectando…' : 'Conectar'}</button><button class="text-button" disabled={quickConnecting} on:click={() => void forgetSaved(saved.id)}>Esquecer</button></div>{/each}
      {#if !remembered.length}<p class="remembered-help">Na primeira autorização no Android, marque “Lembrar dispositivo”. Depois, basta abrir os dois aplicativos.</p>{/if}
      {#if discoveryError}<p class="remembered-help">{discoveryError}</p>{/if}</section>
    {/if}
      <section class="page-section settings-page" hidden={section !== 'Configurações'}><h2>Preferências</h2><div class="setting-row"><div><h3>Aparência</h3><p>Tema escuro com destaque em verde esmeralda.</p></div><span class="setting-value">Escuro</span></div><div class="setting-row"><div><h3>Arquivos com o mesmo nome</h3><p>Um arquivo existente nunca é substituído automaticamente.</p></div><span class="setting-value">Preservar original</span></div><div class="setting-row"><div><h3>Exclusão no computador</h3><p>Os arquivos excluídos são enviados para a Lixeira do Windows.</p></div><span class="setting-value">Lixeira</span></div><div class="setting-row"><div><h3>Transferências</h3><p>Um arquivo por vez, com progresso e cancelamento.</p></div><span class="setting-value">Fila sequencial</span></div><UpdatePanel transferring={activeTransfers.length > 0} onAvailable={(version) => notify(`SFLink ${version} disponível. Abra Configurações para atualizar.`)}/><div class="about-app"><img src={logo} alt="Logo SFLink" style="width:36px;height:30px"/><div><strong>SFLink</strong><p>Versão 0.3.0 · Gerenciador de arquivos via Wi-Fi</p></div></div></section>

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
