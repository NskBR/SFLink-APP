import { writable } from 'svelte/store';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';

export type UpdateInfo = { available: boolean; version: string; notes: string; size: number; installable: boolean };
type UpdateState = { info: UpdateInfo | null; checking: boolean; downloading: boolean; ready: boolean; installing: boolean; downloaded: number; total: number; error: string; message: string; transferring: boolean };
let current: UpdateState = { info: null, checking: false, downloading: false, ready: false, installing: false, downloaded: 0, total: 0, error: '', message: '', transferring: false };
export const updates = writable(current);
function patch(value: Partial<UpdateState>) { current = { ...current, ...value }; updates.set(current); }
export function setUpdateTransfers(transferring: boolean) { if (current.transferring !== transferring) patch({ transferring }); }

export async function checkUpdates(automatic = false): Promise<void> {
  if (!isTauri() || current.checking || current.downloading || current.installing || current.ready) return;
  patch({ checking: true, ...(automatic ? {} : { error: '', message: '' }) });
  try {
    const info = await invoke<UpdateInfo>('check_for_updates');
    patch({ info, error: '', message: info.available ? `SFLink ${info.version} disponível. Clique em Atualizar para instalar.` : info.notes || 'Você está na versão mais recente.' });
  } catch (error) { if (!automatic) patch({ error: String(error) }); }
  finally { patch({ checking: false }); }
}

// Scheduled checks only notify. Download/install always requires a click.
export async function applyUpdate(): Promise<void> {
  if (!isTauri() || current.checking || current.downloading || current.installing) return;
  if (current.transferring) { patch({ error: 'Aguarde a fila de transferências terminar antes de atualizar.' }); return; }
  patch({ error: '' });
  if (!current.ready) {
    if (!current.info?.available) await checkUpdates();
    if (!current.info?.available) return;
    if (!current.info.installable) { patch({ error: 'A release não contém um instalador Windows verificado.' }); return; }
    patch({ downloading: true, downloaded: 0, total: current.info.size, message: 'Baixando atualização…' });
    try { await invoke('download_update'); patch({ ready: true, message: 'Download verificado.' }); }
    catch (error) { patch({ error: String(error) }); return; }
    finally { patch({ downloading: false }); }
  }
  if (current.transferring) { patch({ message: 'Download pronto. Aguarde a fila terminar e clique em Atualizar.' }); return; }
  patch({ installing: true, message: 'Preparando atualização… O SFLink fechará e reabrirá ao concluir.' });
  try { await invoke('install_update'); }
  catch (error) { patch({ installing: false, error: String(error) }); }
}
export async function cancelUpdate(): Promise<void> {
  try { await invoke('cancel_update'); } catch (error) { patch({ error: String(error) }); }
}
export function startUpdateChecks(): () => void {
  if (!isTauri()) return () => {};
  let disposed = false;
  let interval: ReturnType<typeof setInterval> | undefined;
  let unlisten: (() => void) | undefined;
  void listen<{ downloaded: number; total: number }>('update-progress', ({ payload }) => patch(payload)).then(fn => { if (disposed) fn(); else unlisten = fn; });
  const initial = setTimeout(() => {
    void checkUpdates(true);
    interval = setInterval(() => void checkUpdates(true), 60_000);
  }, 10_000);
  return () => { disposed = true; clearTimeout(initial); if (interval) clearInterval(interval); unlisten?.(); };
}
