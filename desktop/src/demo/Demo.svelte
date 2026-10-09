<script lang="ts">
  import { Folder, ArrowDownUp, Smartphone, Settings, Home, Download, Music2, FileText, Image, ShieldCheck, Check, Wifi } from '@lucide/svelte';
  import FilePane from '../FilePane.svelte';
  import TitleBar from '../TitleBar.svelte';
  import logo from '../assets/sflink-logo.svg';
  import type { Listing } from '../lib/types';
  const noop = () => {};
  const callbacks = { onBack: noop, onNavigate: noop, onChoose: noop, onConnect: noop, onRefresh: noop, onSelect: noop, onOpen: noop, onNewFolder: noop, onRename: noop, onDelete: noop, onTransfer: noop, onDrag: noop };
  const local: Listing = { path: 'C:\\Users\\Demo\\Downloads', parent: 'C:\\Users\\Demo', entries: [
    { name: 'Documentos', isDir: true, size: 0 }, { name: 'Músicas', isDir: true, size: 0 },
    { name: 'Aurora.mp3', isDir: false, size: 6500000 }, { name: 'Horizonte.mp3', isDir: false, size: 8200000 },
    { name: 'Projeto.pdf', isDir: false, size: 2400000 }, { name: 'Foto de exemplo.png', isDir: false, size: 950000 }
  ].map(e => ({ ...e, path: `C:\\Users\\Demo\\Downloads\\${e.name}`, modified: null })) };
  const remote: Listing = { path: 'Music', parent: '/', entries: ['Aurora.mp3', 'Horizonte.mp3', 'Caminhos.mp3', 'Maré calma.mp3'].map((name, i) => ({ name, path: `Music/${name}`, isDir: false, size: 6500000 + i * 600000, modified: null })) };
</script>

<TitleBar desktop={false} onError={noop}/>
<div class="app-shell">
  <aside class="sidebar">
    <div class="brand"><span class="brand-icon"><img src={logo} alt="" width="38" height="32"/></span><div>SFLink<span>via Wi-Fi</span></div></div>
    <nav aria-label="Navegação principal">{#each [{ name: 'Arquivos', icon: Folder }, { name: 'Transferências', icon: ArrowDownUp }, { name: 'Dispositivos', icon: Smartphone }, { name: 'Configurações', icon: Settings }] as item}<button class:active={item.name === 'Arquivos'}><svelte:component this={item.icon} size={19}/><span>{item.name}</span></button>{/each}</nav>
    <div class="quick-access"><span class="sidebar-label">Acesso rápido</span>{#each [{ name: 'Início', icon: Home }, { name: 'Downloads', icon: Download }, { name: 'Músicas', icon: Music2 }, { name: 'Documentos', icon: FileText }, { name: 'Imagens', icon: Image }] as item}<button class:current={item.name === 'Downloads'}><svelte:component this={item.icon} size={16}/>{item.name}</button>{/each}</div>
    <div class="sidebar-bottom"><ShieldCheck size={17}/><div>Conexão local<span>Seus arquivos entre seus dispositivos</span></div></div>
  </aside>
  <main>
    <header class="topbar"><div><h1>Arquivos</h1><span>Organize e transfira, sem cabo.</span></div><div class="connection-status"><div class="connection-pill connected"><span class="status-dot online"></span><span>Celular de demonstração</span></div><button class="small-button">Desconectar</button></div></header>
    <div class="preview-banner"><ShieldCheck size={16}/>Demonstração · Todos os nomes, arquivos e estados de conexão são fictícios.</div>
    <div class="workspace"><FilePane side="local" title="Este PC" listing={local} selected={[local.entries[2].path]} {...callbacks}/><FilePane side="remote" title="Android Demo" listing={remote} {...callbacks}/></div>
    <section class="transfer-panel"><header class="queue-heading"><ArrowDownUp size={16}/><h2>Transferências</h2><span style="margin-left:auto;font-size:12px;color:#91a1b5">Limpar concluídas</span></header><div class="queue-list"><div class="transfer-row"><div class="transfer-direction complete"><Check size={21}/></div><div class="transfer-details"><strong>Aurora.mp3</strong><span>Concluído · 6,2 MB</span></div></div></div></section>
    <footer class="app-footer"><span><Wifi size={14}/>Transferência pela rede local</span><span><Smartphone size={15}/>84 GB livres de 128 GB · Dados fictícios</span></footer>
  </main>
</div>
