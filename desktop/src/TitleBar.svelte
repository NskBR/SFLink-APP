<script lang="ts">
  import { onMount } from 'svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { Minus, Square, Copy, X, Download, LoaderCircle } from '@lucide/svelte';
  import { updates, applyUpdate } from './lib/updater';
  export let desktop: boolean;
  export let onError: (message: string) => void;
  let maximized = false;
  async function action(kind: 'minimize' | 'maximize' | 'close') {
    if (!desktop) return;
    try {
      const window = getCurrentWindow();
      if (kind === 'minimize') await window.minimize();
      else if (kind === 'close') await window.close();
      else { await window.toggleMaximize(); maximized = await window.isMaximized(); }
    } catch (error) { onError(`Não foi possível controlar a janela: ${String(error)}`); }
  }
  onMount(() => {
    let disposed = false;
    let unlisten: (() => void) | undefined;
    if (desktop) void (async () => {
      try {
        const window = getCurrentWindow();
        maximized = await window.isMaximized();
        const stop = await window.onResized(async () => {
          const value = await window.isMaximized();
          if (!disposed) maximized = value;
        });
        if (disposed) stop(); else unlisten = stop;
      } catch (error) { if (!disposed) onError(String(error)); }
    })();
    return () => { disposed = true; unlisten?.(); };
  });
</script>

<header class="window-titlebar">
  <!-- Tauri's drag region also handles native double-click maximize/restore. -->
  <div class="window-drag-region" data-tauri-drag-region>
  </div>
  {#if $updates.info?.available || $updates.ready || $updates.downloading || $updates.installing}
  <button class="title-update" class:available={$updates.info?.available || $updates.ready} aria-label={$updates.downloading ? 'Baixando atualização' : $updates.installing ? 'Instalando atualização' : $updates.info?.available ? `Atualizar para ${$updates.info.version}` : 'Verificar e atualizar SFLink'} title={$updates.transferring ? 'Aguarde as transferências terminarem' : $updates.error || ($updates.info?.available ? `SFLink ${$updates.info.version} disponível — clique para atualizar` : $updates.message || 'Verificar e atualizar SFLink')} disabled={!desktop || $updates.checking || $updates.downloading || $updates.installing || $updates.transferring} on:click={() => void applyUpdate()}>
    {#if $updates.checking || $updates.downloading || $updates.installing}<LoaderCircle size={14} class="spin"/>{:else}<Download size={14}/>{/if}
    <span>{$updates.downloading ? `${$updates.total ? Math.round($updates.downloaded / $updates.total * 100) : 0}%` : $updates.installing ? 'Atualizando…' : 'Atualizar'}</span>
    {#if $updates.info?.available && !$updates.downloading && !$updates.installing}<span class="update-dot"></span>{/if}
  </button>
  {/if}
  <div class="window-controls" aria-label="Controles da janela">
    <button aria-label="Minimizar janela" title="Minimizar" disabled={!desktop} on:click={() => void action('minimize')}><Minus size={14}/></button>
    <button aria-label={maximized ? 'Restaurar janela' : 'Maximizar janela'} title={maximized ? 'Restaurar' : 'Maximizar'} disabled={!desktop} on:click={() => void action('maximize')}>{#if maximized}<Copy size={12}/>{:else}<Square size={12}/>{/if}</button>
    <button class="window-close" aria-label="Fechar aplicativo" title="Fechar" disabled={!desktop} on:click={() => void action('close')}><X size={15}/></button>
  </div>
</header>

<style>
  .title-update{height:26px;align-self:center;display:flex;align-items:center;gap:7px;margin-right:12px;padding:0 11px;border-radius:7px;color:var(--muted);font-size:11px}.title-update:hover:not(:disabled){background:var(--raised);color:var(--text)}.title-update.available{color:var(--accent);background:var(--accent-dark)}.update-dot{width:5px;height:5px;border-radius:50%;background:var(--accent)}
</style>
