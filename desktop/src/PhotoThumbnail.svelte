<script lang="ts">
  import { onMount } from 'svelte';
  import { invoke, isTauri } from '@tauri-apps/api/core';
  import { Image, Music2 } from '@lucide/svelte';
  import type { Side } from './lib/types';
  export let side: Side;
  export let audio = false;
  export let path: string;
  let host: HTMLSpanElement;
  let source = '';
  onMount(() => {
    if (!isTauri()) return;
    let disposed = false, requested = false;
    const observer = new IntersectionObserver(entries => {
      if (requested || !entries.some(entry => entry.isIntersecting)) return;
      requested = true; observer.disconnect();
      void invoke<string>('image_thumbnail', { side, path }).then(value => { if (!disposed) source = value; }).catch(() => {});
    }, { root: host.closest('.file-list'), rootMargin: '100px' });
    observer.observe(host);
    return () => { disposed = true; observer.disconnect(); };
  });
</script>
<span bind:this={host} class="photo-thumbnail" aria-hidden="true">
  {#if source}<img src={source} alt="" draggable="false"/>{:else if audio}<Music2 size={21}/>{:else}<Image size={21}/>{/if}
</span>
<style>
  .photo-thumbnail{width:34px;height:34px;display:flex;align-items:center;justify-content:center;flex-shrink:0;border-radius:6px;overflow:hidden;color:var(--accent);background:#131d22}
  img{width:100%;height:100%;object-fit:cover;pointer-events:none}
</style>
