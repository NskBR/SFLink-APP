<script lang="ts">
  import { onMount } from 'svelte';
  import { invoke, isTauri } from '@tauri-apps/api/core';
  import { listen } from '@tauri-apps/api/event';
  import { bytes } from './lib/types';
  export let transferring = false;
  export let onAvailable: (version: string) => void = () => {};
  type Info = { available: boolean; version: string; notes: string; size: number; installable: boolean };
  let info: Info | null = null, busy = false, downloading = false, ready = false, error = '', message = '';
  let downloaded = 0, total = 0;
  async function check(automatic = false) {
    if (!isTauri() || busy || downloading || ready) return;
    busy = true; error = ''; message = '';
    try { info = await invoke<Info>('check_for_updates'); if (!info.available) message = info.notes || 'Você está na versão mais recente.'; else if (automatic) onAvailable(info.version); }
    catch (e) { if (!automatic) error = String(e); }
    finally { busy = false; }
  }
  async function download() {
    downloading = true; error = ''; downloaded = 0; total = info?.size || 0;
    try { await invoke('download_update'); ready = true; message = 'Instalador verificado. O SFLink fechará ao iniciar a instalação.'; }
    catch (e) { error = String(e); }
    finally { downloading = false; }
  }
  async function install() {
    if (transferring) return;
    busy = true; error = '';
    try { await invoke('install_update'); } catch (e) { error = String(e); }
    finally { busy = false; }
  }
  onMount(() => {
    let disposed = false, cleanup: (() => void) | undefined;
    if (isTauri()) {
      void listen<{ downloaded: number; total: number }>('update-progress', ({ payload }) => { downloaded = payload.downloaded; total = payload.total; }).then(fn => { if (disposed) fn(); else cleanup = fn; });
      void check(true);
    }
    return () => { disposed = true; cleanup?.(); };
  });
</script>

<div class="update-card">
  <div><h3>Atualizações do SFLink</h3><p>Versão 0.3.1 · Atualizações pelo GitHub Releases</p></div>
  {#if info?.available}<strong>Versão {info.version} disponível</strong>{#if info.notes}<p class="release-notes">{info.notes}</p>{/if}{/if}
  {#if downloading}<progress max={total || 1} value={downloaded}></progress><p>{bytes(downloaded)} de {bytes(total)}</p>{/if}
  {#if message}<p role="status">{message}</p>{/if}
  {#if error}<p class="update-error" role="alert">{error}</p>{/if}
  {#if info?.available && !info.installable}<p>A release ainda não contém um instalador Windows com SHA-256 válido.</p>{/if}
  <div class="update-actions">
    {#if downloading}<button class="small-button" on:click={() => void invoke('cancel_update').catch(e => error = String(e))}>Cancelar download</button>
    {:else if ready}<button class="primary-button" disabled={busy || transferring} on:click={() => void install()}>Instalar e fechar SFLink</button>{#if transferring}<p>Aguarde a fila de transferências terminar.</p>{/if}
    {:else}<button class="small-button" disabled={busy || !isTauri()} on:click={() => void check()}>{busy ? 'Verificando…' : 'Verificar atualizações'}</button>{#if info?.available && info.installable}<button class="primary-button" on:click={() => void download()}>Baixar atualização · {bytes(info.size)}</button>{/if}{/if}
  </div>
</div>

<style>
  .update-card{margin-top:22px;padding:22px;border:1px solid var(--border,#202830);border-radius:20px;display:flex;flex-direction:column;gap:12px;background:#0d1116}
  h3{font-size:15px}p{font-size:12px;color:#91a1b5;line-height:1.6}.update-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.release-notes{white-space:pre-wrap;max-height:180px;overflow:auto}.update-error{color:#f3adb3}progress{width:100%;height:8px;accent-color:#20e3ad}strong{color:#20e3ad;font-size:14px}
</style>
