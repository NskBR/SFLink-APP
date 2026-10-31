<script lang="ts">
  import { onMount } from 'svelte';
  import { invoke, convertFileSrc } from '@tauri-apps/api/core';
  import { X, Music2, Play, Pause, Volume2 } from '@lucide/svelte';
  import { focusDialog } from './lib/focus';
  export let path: string;
  export let name: string;
  export let onClose: () => void;
  const audio = /\.(mp3|flac|m4a|wav|ogg|aac|opus)$/i.test(name);
  let source = '', cover = '', error = '', element: HTMLAudioElement;
  let playing = false, current = 0, duration = 0, volume = 0.7;
  const time = (value: number) => Number.isFinite(value) ? `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, '0')}` : '0:00';
  async function toggle() { if (!element) return; if (element.paused) { try { await element.play(); } catch { error = 'Não foi possível reproduzir este formato no Windows.'; } } else element.pause(); }
  onMount(() => {
    let disposed = false, allowed = '';
    void invoke<string>('prepare_media', { path }).then(value => {
      allowed = value;
      if (disposed) return;
      source = convertFileSrc(value);
    }).catch(e => { if (!disposed) error = String(e); });
    if (audio) void invoke<string>('image_thumbnail', { side: 'local', path }).then(value => { if (!disposed) cover = value; }).catch(() => {});
    return () => { disposed = true; element?.pause(); };
  });
</script>

<div class="modal-backdrop media-backdrop" role="presentation" on:click={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  <!-- svelte-ignore a11y_no_noninteractive_tabindex a11y_no_noninteractive_element_to_interactive_role -->
  <section use:focusDialog class="media-modal" class:audio role="dialog" aria-modal="true" aria-label={audio ? `Reproduzir ${name}` : `Visualizar ${name}`} tabindex="-1">
    <header><div><span>{audio ? 'Reprodutor de música' : 'Visualizador de imagem'}</span><h2>{name}</h2></div><button class="icon-button" aria-label="Fechar visualização" on:click={onClose}><X size={22}/></button></header>
    {#if error}<p class="media-error" role="alert">{error}</p>{/if}
    {#if audio}
      <div class="album-art">{#if cover}<img src={cover} alt="Capa da música"/>{:else}<Music2 size={80}/>{/if}</div>
      {#if source}<audio bind:this={element} src={source} preload="metadata" on:loadedmetadata={() => { duration = Number.isFinite(element.duration) ? element.duration : 0; element.volume = volume; }} on:timeupdate={() => current = element.currentTime} on:play={() => playing = true} on:pause={() => playing = false} on:ended={() => playing = false} on:error={() => error = 'Este formato não pôde ser reproduzido pelo Windows.'}></audio>{/if}
      <label class="sr-only" for="media-seek">Posição da música</label><input id="media-seek" class="seek" type="range" min="0" max={duration || 1} step="0.1" value={current} disabled={!duration} on:input={(e) => { if (element) { current = Number(e.currentTarget.value); element.currentTime = current; } }}/>
      <div class="duration"><span>{time(current)}</span><span>{time(duration)}</span></div>
      <div class="play-controls"><button class="play-button" aria-label={playing ? 'Pausar música' : 'Reproduzir música'} disabled={!source} on:click={() => void toggle()}>{#if playing}<Pause size={24}/>{:else}<Play size={24}/>{/if}</button><label class="volume"><Volume2 size={18}/><span class="sr-only">Volume</span><input aria-label="Volume" type="range" min="0" max="1" step="0.01" bind:value={volume} on:input={(e) => { volume = Number(e.currentTarget.value); if (element) element.volume = volume; }}/></label></div>
    {:else if source}<img class="full-image" src={source} alt={name} on:error={() => error = 'Não foi possível exibir esta imagem.'}/>{:else if !error}<p class="loading-media">Abrindo imagem…</p>{/if}
  </section>
</div>

<style>
  .media-modal{width:min(980px,94vw);max-height:90vh;overflow:auto;border:1px solid var(--line);border-radius:24px;background:var(--panel);padding:24px;box-shadow:0 30px 100px #0009}.media-modal.audio{width:min(470px,94vw)}header{display:flex;justify-content:space-between;gap:20px;margin-bottom:24px}header span{font-size:11px;color:var(--accent);text-transform:uppercase;letter-spacing:1px}h2{font-size:16px;margin-top:7px;overflow-wrap:anywhere}header button{align-self:flex-start}.full-image{display:block;max-width:100%;max-height:70vh;object-fit:contain;margin:auto;border-radius:12px}.album-art{width:220px;height:220px;max-width:65vw;max-height:65vw;border-radius:20px;background:linear-gradient(135deg,var(--accent-dark),var(--base));color:var(--accent);display:flex;align-items:center;justify-content:center;margin:12px auto 30px;overflow:hidden}.album-art img{width:100%;height:100%;object-fit:cover}.seek{width:100%}input[type=range]{accent-color:var(--accent)}.duration{display:flex;justify-content:space-between;color:var(--muted);font-size:11px;margin-top:8px;font-variant-numeric:tabular-nums}.play-controls{display:flex;align-items:center;justify-content:space-between;margin-top:22px}.play-button{width:56px;height:56px;border-radius:50%;background:var(--accent);color:var(--base);display:grid;place-items:center}.volume{display:flex;gap:10px;align-items:center;color:var(--muted)}.volume input{width:110px}.media-error{color:#f3adb3;font-size:13px;margin-bottom:20px}.loading-media{color:var(--muted);padding:60px;text-align:center}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}
</style>
