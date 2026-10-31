<script lang="ts">
  import { onMount } from 'svelte';
  import { invoke, convertFileSrc } from '@tauri-apps/api/core';
  import { X, Play, Pause, Volume2, Shuffle, Rewind, FastForward, Repeat, Repeat1 } from '@lucide/svelte';
  import { focusDialog } from './lib/focus';
  export let path: string;
  export let name: string;
  export let playlist: { path: string; name: string }[] = [];
  export let onClose: () => void;
  const audio = /\.(mp3|flac|m4a|wav|ogg|aac|opus)$/i.test(name);
  const tracks = playlist.filter(track => /\.(mp3|flac|m4a|wav|ogg|aac|opus)$/i.test(track.name));
  let trackPath = path, trackName = name, title = name.replace(/\.[^.]+$/, ''), artist = 'Arquivo local';
  let source = '', cover = '', error = '', element: HTMLAudioElement;
  let playing = false, current = 0, duration = 0, volume = 0.7, shuffle = false;
  let repeat: 'off' | 'all' | 'one' = 'off';
  let request = 0, disposed = false, autoplayNext = false;
  $: trackIndex = tracks.findIndex(track => track.path === trackPath);
  $: canPrevious = current > 3 || trackIndex > 0 || (repeat !== 'off' && tracks.length > 0);
  $: canNext = tracks.length > 0 && ((shuffle && tracks.length > 1) || trackIndex < tracks.length - 1 || repeat !== 'off');
  const time = (value: number) => Number.isFinite(value) ? `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, '0')}` : '0:00';
  async function play() {
    if (!element) return;
    try { await element.play(); } catch { error = 'Clique em reproduzir para ouvir. Se não funcionar, confira o formato do arquivo.'; }
  }
  function toggle() { if (!element) return; if (element.paused) void play(); else element.pause(); }
  async function load(track: { path: string; name: string }, autoplay = false) {
    const id = ++request; element?.pause(); source = ''; cover = ''; error = ''; current = 0; duration = 0;
    trackPath = track.path; trackName = track.name; title = track.name.replace(/\.[^.]+$/, ''); artist = 'Arquivo local'; autoplayNext = autoplay;
    void invoke<string>('prepare_media', { path: track.path }).then(value => { if (!disposed && id === request) source = convertFileSrc(value); }).catch(e => { if (!disposed && id === request) error = String(e); });
    if (audio) {
      void invoke<string>('image_thumbnail', { side: 'local', path: track.path }).then(value => { if (!disposed && id === request) cover = value; }).catch(() => {});
      void invoke<{ title: string | null; artist: string | null }>('audio_metadata', { path: track.path }).then(value => { if (!disposed && id === request) { title = value.title || title; artist = value.artist || 'Artista não informado'; } }).catch(() => {});
    }
  }
  function move(direction: number) {
    if (direction < 0 && current > 3) { if (element) element.currentTime = 0; return; }
    if (!tracks.length) return;
    let index = trackIndex + direction;
    if (shuffle && direction > 0 && tracks.length > 1) {
      const others = tracks.map((_, i) => i).filter(i => i !== trackIndex); index = others[Math.floor(Math.random() * others.length)];
    }
    if (index < 0 || index >= tracks.length) { if (repeat === 'off') return; index = (index + tracks.length) % tracks.length; }
    void load(tracks[index], true);
  }
  function ended() {
    playing = false;
    if (repeat === 'one') { if (element) { element.currentTime = 0; void play(); } }
    else if (canNext) move(1);
  }
  function loaded() {
    duration = Number.isFinite(element.duration) ? element.duration : 0; element.volume = volume;
    if (autoplayNext) { autoplayNext = false; void play(); }
  }
  function keys(event: KeyboardEvent) {
    if (!audio || (event.target as HTMLElement).matches('input,button')) return;
    if (event.code === 'Space') { event.preventDefault(); toggle(); }
    if (element && ['ArrowLeft','ArrowRight'].includes(event.key)) { event.preventDefault(); element.currentTime = Math.min(duration, Math.max(0, current + (event.key === 'ArrowLeft' ? -5 : 5))); }
  }
  onMount(() => { void load({ path, name }); return () => { disposed = true; request++; element?.pause(); }; });
</script>

<div class="modal-backdrop media-backdrop" class:photo-view={!audio} role="presentation" on:click={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  <!-- svelte-ignore a11y_no_noninteractive_tabindex a11y_no_noninteractive_element_to_interactive_role -->
  <section use:focusDialog class="media-modal" class:audio class:photo={!audio} role="dialog" aria-modal="true" aria-label={audio ? `Reproduzir ${trackName}` : `Visualizar ${name}`} tabindex="-1" on:keydown={keys}>
    <button class="media-close" aria-label="Fechar visualização" on:click={onClose}><X size={22}/></button>
    {#if audio}
      <div class="track-heading">{#if cover}<img class="album-cover" src={cover} alt="Capa da música"/>{/if}<div><span class="now-playing">Reproduzindo agora</span><h2 title={title}>{title}</h2><p class="artist">{artist}</p></div></div>
      <div class="transport">
        <button class="transport-mode" class:enabled={shuffle} aria-label="Reprodução aleatória" aria-pressed={shuffle} title="Reprodução aleatória" on:click={() => shuffle = !shuffle}><Shuffle size={25}/></button>
        <button aria-label="Música anterior" title="Música anterior" disabled={!canPrevious} on:click={() => move(-1)}><Rewind size={34}/></button>
        <button class="play-button" aria-label={playing ? 'Pausar música' : 'Reproduzir música'} disabled={!source} on:click={toggle}>{#if playing}<Pause size={40} strokeWidth={2.2}/>{:else}<Play size={40} strokeWidth={2.2}/>{/if}</button>
        <button aria-label="Próxima música" title="Próxima música" disabled={!canNext} on:click={() => move(1)}><FastForward size={34}/></button>
        <button class="transport-mode" class:enabled={repeat !== 'off'} aria-label={`Repetição: ${repeat === 'off' ? 'desativada' : repeat === 'all' ? 'lista' : 'uma música'}`} aria-pressed={repeat !== 'off'} title={`Repetir: ${repeat === 'off' ? 'desativado' : repeat === 'all' ? 'lista' : 'uma música'}`} on:click={() => repeat = repeat === 'off' ? 'all' : repeat === 'all' ? 'one' : 'off'}>{#if repeat === 'one'}<Repeat1 size={25}/>{:else}<Repeat size={25}/>{/if}</button>
      </div>
      {#if source}{#key trackPath}<audio bind:this={element} src={source} preload="metadata" on:loadedmetadata={loaded} on:timeupdate={() => current = element.currentTime} on:play={() => { playing = true; error = ''; }} on:pause={() => playing = false} on:ended={ended} on:error={() => error = 'Este formato não pôde ser reproduzido pelo Windows.'}></audio>{/key}{/if}
      <label class="sr-only" for="media-seek">Posição da música</label><input id="media-seek" class="seek" type="range" min="0" max={duration || 1} step="0.1" value={current} style={`--played:${duration ? current / duration * 100 : 0}%`} disabled={!duration} on:input={(e) => { if (element) { current = Number(e.currentTarget.value); element.currentTime = current; } }}/>
      <div class="player-footer"><div class="duration"><span>{time(current)}</span><span class="time-divider">/</span><span>{time(duration)}</span></div><label class="volume"><Volume2 size={16}/><span class="sr-only">Volume</span><input aria-label="Volume" type="range" min="0" max="1" step="0.01" bind:value={volume} on:input={(e) => { volume = Number(e.currentTarget.value); if (element) element.volume = volume; }}/></label></div>
      {#if error}<p class="media-error" role="alert">{error}</p>{/if}
    {:else}
      <span class="photo-name">{name}</span>
      {#if error}<p class="media-error photo-error" role="alert">{error}</p>{:else if source}<img class="full-image" src={source} alt={name} on:error={() => error = 'Não foi possível exibir esta imagem.'}/>{:else}<p class="loading-media">Abrindo imagem…</p>{/if}
    {/if}
  </section>
</div>
<style>
  .media-modal{position:relative;outline:none}.media-modal.audio{width:min(600px,94vw);padding:36px 48px 27px;border:1px solid #555;border-radius:36px;background:linear-gradient(145deg,#1d1d1d,#363636);color:#f4f4f4;box-shadow:0 24px 100px #0008;max-height:90vh;overflow:auto}.media-close{position:absolute;top:15px;right:16px;display:grid;place-items:center;color:#aaa;border-radius:50%;width:30px;height:30px}.media-close:hover{background:#ffffff12;color:white}.track-heading{display:flex;gap:16px;align-items:center;justify-content:center;min-height:88px;text-align:center}.track-heading>div{min-width:0;max-width:100%}.now-playing{font-size:11px;color:#aaa;letter-spacing:.3px}.album-cover{width:64px;height:64px;object-fit:cover;border-radius:10px;box-shadow:0 8px 20px #0005;flex-shrink:0}h2{font-size:25px;font-weight:500;letter-spacing:-.5px;line-height:1.3;margin:5px 0 6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.artist{font-size:14px;color:#c6c6c6;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.transport{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:28px 0 30px}.transport button{display:grid;place-items:center;color:#f2f2f2;flex-shrink:0;padding:6px}.transport button:hover:not(:disabled){color:white;filter:drop-shadow(0 0 5px #fff5)}.transport button:disabled{opacity:.22}.transport .play-button{width:84px;height:84px;border:2px solid #eee;border-radius:50%;background:transparent}.transport .transport-mode{color:#b6b6b6}.transport-mode.enabled{color:white!important;background:#ffffff12;border-radius:9px;box-shadow:inset 0 -2px white}.seek{width:100%;display:block;appearance:none;-webkit-appearance:none;height:4px;background:linear-gradient(to right,#f4f4f4 var(--played),#777 var(--played));border-radius:5px;cursor:pointer;accent-color:white}.seek::-webkit-slider-thumb{appearance:none;-webkit-appearance:none;width:13px;height:13px;border-radius:50%;background:#fff;box-shadow:0 1px 5px #0004}.seek::-moz-range-thumb{width:13px;height:13px;border:0;border-radius:50%;background:#fff}.player-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:15px}.duration{display:flex;align-items:center;gap:7px;color:#c5c5c5;font-size:11px;font-variant-numeric:tabular-nums}.time-divider{color:#777}.volume{display:flex;gap:8px;align-items:center;color:#aaa}.volume input{width:86px;height:3px;accent-color:#eee}.media-error{color:#ffc1c1;font-size:12px;line-height:1.5;margin-top:16px}.photo-view{background:#060708eF;backdrop-filter:blur(4px)}.media-modal.photo{padding:0;border:0;border-radius:0;box-shadow:none;background:transparent;width:auto;max-width:94vw;max-height:84vh}.photo .media-close{position:fixed;top:22px;right:24px;width:36px;height:36px;color:#ddd}.photo-name{position:fixed;top:28px;left:32px;max-width:75vw;text-overflow:ellipsis;overflow:hidden;white-space:nowrap;font-size:13px;color:#bbb}.full-image{display:block;max-width:94vw;max-height:84vh;object-fit:contain;margin:0;box-shadow:0 8px 50px #0005}.photo-error,.loading-media{color:#ddd;padding:30px;font-size:14px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}@media(max-width:600px){.media-modal.audio{padding:36px 24px 24px;border-radius:26px}.transport{gap:6px}.transport .play-button{width:72px;height:72px}h2{font-size:20px}.album-cover{width:50px;height:50px}}
</style>
