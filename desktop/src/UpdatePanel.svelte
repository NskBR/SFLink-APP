<script lang="ts">
  import { isTauri } from '@tauri-apps/api/core';
  import { updates, checkUpdates, applyUpdate, cancelUpdate } from './lib/updater';
  import { bytes } from './lib/types';
</script>
<div class="update-card">
  <div><h3>Atualizações do SFLink</h3><p>Versão 0.7.0 · GitHub Releases</p></div>
  <p>A primeira consulta ocorre após 10 segundos. Depois, a cada 60 segundos. O download e a instalação só começam quando você clicar em Atualizar.</p>
  {#if $updates.info?.available}<strong>Versão {$updates.info.version} disponível</strong>{#if $updates.info.notes}<p class="release-notes">{$updates.info.notes}</p>{/if}{/if}
  {#if $updates.downloading}<progress max={$updates.total || 1} value={$updates.downloaded}></progress><p>{bytes($updates.downloaded)} de {bytes($updates.total)}</p>{/if}
  {#if $updates.message}<p role="status">{$updates.message}</p>{/if}
  {#if $updates.error}<p class="update-error" role="alert">{$updates.error}</p>{/if}
  <div class="update-actions">
    {#if $updates.downloading}<button class="small-button" on:click={() => void cancelUpdate()}>Cancelar download</button>
    {:else}<button class="small-button" disabled={!isTauri() || $updates.checking || $updates.installing || $updates.ready} on:click={() => void checkUpdates()}>{$updates.checking ? 'Verificando…' : 'Verificar agora'}</button>
      {#if $updates.info?.available || $updates.ready}<button class="primary-button" disabled={$updates.checking || $updates.installing || $updates.transferring || !$updates.info?.installable} on:click={() => void applyUpdate()}>{$updates.installing ? 'Preparando atualização…' : $updates.ready ? 'Instalar e reabrir' : 'Baixar e atualizar'}</button>{/if}
    {/if}
  </div>
</div>
<style>
  .update-card{margin-top:22px;padding:22px;border:1px solid var(--line);border-radius:20px;display:flex;flex-direction:column;gap:12px;background:var(--panel)}h3{font-size:15px}p{font-size:12px;color:var(--muted);line-height:1.6}.update-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.release-notes{white-space:pre-wrap;max-height:180px;overflow:auto}.update-error{color:#f3adb3}progress{width:100%;height:8px;accent-color:var(--accent)}strong{color:var(--accent);font-size:14px}
</style>
