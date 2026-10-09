<script lang="ts">
  import { onMount } from 'svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { Minus, Square, Copy, X } from '@lucide/svelte';
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
  <div class="window-controls" aria-label="Controles da janela">
    <button aria-label="Minimizar janela" title="Minimizar" disabled={!desktop} on:click={() => void action('minimize')}><Minus size={14}/></button>
    <button aria-label={maximized ? 'Restaurar janela' : 'Maximizar janela'} title={maximized ? 'Restaurar' : 'Maximizar'} disabled={!desktop} on:click={() => void action('maximize')}>{#if maximized}<Copy size={12}/>{:else}<Square size={12}/>{/if}</button>
    <button class="window-close" aria-label="Fechar aplicativo" title="Fechar" disabled={!desktop} on:click={() => void action('close')}><X size={15}/></button>
  </div>
</header>
