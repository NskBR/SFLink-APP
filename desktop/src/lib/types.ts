export interface Entry { name: string; path: string; isDir: boolean; size: number; modified: number | null }
export interface Listing { path: string; parent: string | null; entries: Entry[] }
export interface Shortcut { name: string; path: string }
export interface Device { name: string; root: string; freeBytes: number; totalBytes: number }
export type Side = 'local' | 'remote';
export interface Transfer { id: string; name: string; source: string; destination: string; direction: 'upload' | 'download'; total: number; transferred: number; status: 'queued' | 'running' | 'done' | 'error' | 'cancelled'; error?: string }
export function bytes(value: number): string {
  if (!value) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: index > 1 ? 1 : 0 }).format(value / 1024 ** index)} ${units[index]}`;
}
