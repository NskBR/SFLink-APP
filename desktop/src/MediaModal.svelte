<script lang="ts">
  import { onMount } from 'svelte';
  import { invoke, convertFileSrc } from '@tauri-apps/api/core';
  import { X, Play, Pause, Volume2, Music2, Shuffle, SkipBack, SkipForward, Repeat, Repeat1 } from '@lucide/svelte';
  import { focusDialog } from './lib/focus';
  export let path: string;
  export let name: string;
  export let playlist: { path: string; name: string }[] = [];
  export let onClose: () => void;
  const audio = /\.(mp3|flac|m4a|wav|ogg|aac|opus)$/i.test(name);
  const video = /\.(mp4|webm|m4v|mov)$/i.test(name);
  const playable = audio || video;
  let showVolume = false;
  const tracks = playlist.filter(track => video ? /\.(mp4|webm|m4v|mov)$/i.test(track.name) : /\.(mp3|flac|m4a|wav|ogg|aac|opus)$/i.test(track.name));
  let trackPath = path, trackName = name, title = name.replace(/\.[^.]+$/, ''), artist = 'Arquivo local';
  let source = '', cover = '', error = '', element: HTMLMediaElement;
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
      void invoke<string>('image_thumbnail', { side: 'local', path: track.path, large: true }).then(value => { if (!disposed && id === request) cover = value; }).catch(() => {});
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
    if (!playable || (event.target as HTMLElement).matches('input,button')) return;
    if (event.code === 'Space') { event.preventDefault(); toggle(); }
    if (element && ['ArrowLeft','ArrowRight'].includes(event.key)) { event.preventDefault(); element.currentTime = Math.min(duration, Math.max(0, current + (event.key === 'ArrowLeft' ? -5 : 5))); }
  }
  onMount(() => { void load({ path, name }); return () => { disposed = true; request++; element?.pause(); }; });
</script>

<div class="modal-backdrop media-backdrop" class:photo-view={!playable} role="presentation" on:click={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  <!-- svelte-ignore a11y_no_noninteractive_tabindex a11y_no_noninteractive_element_to_interactive_role -->
  <section use:focusDialog class="media-modal" class:audio={playable} class:photo={!playable} role="dialog" aria-modal="true" aria-label={playable ? `Reproduzir ${trackName}` : `Visualizar ${name}`} tabindex="-1" on:keydown={keys}>
    <button class="media-close" aria-label="Fechar visualização" on:click={onClose}><X size={22}/></button>
    {#if playable}
      <div class="player-artwork">
        {#if video && source}
        <!-- svelte-ignore a11y_media_has_caption Local user-provided video has no caption file. -->
        <video bind:this={element} src={source} preload="metadata" playsinline on:loadedmetadata={loaded} on:timeupdate={() => current = element.currentTime} on:play={() => { playing = true; error = ''; }} on:pause={() => playing = false} on:ended={ended} on:error={() => error = 'Este vídeo não pôde ser reproduzido pelo Windows.'}></video>
        {:else if cover}<img src={cover} alt="Capa da música"/>{:else}<Music2 size={84} strokeWidth={1}/>{/if}
      </div>
      <div class="track-heading"><h2 title={title}>{title}</h2><p class="artist">{video ? 'Vídeo local' : artist}</p></div>
      <div class="timeline"><label class="sr-only" for="media-seek">Posição da música</label><input id="media-seek" class="seek" type="range" min="0" max={duration || 1} step="0.1" value={current} style={`--played:${duration ? current / duration * 100 : 0}%`} disabled={!duration} on:input={(e) => { if (element) { current = Number(e.currentTarget.value); element.currentTime = current; } }}/><div class="duration"><span>{time(current)}</span><span>{time(duration)}</span></div></div>
      <div class="transport">
        <button class="transport-mode" class:enabled={shuffle} aria-label="Reprodução aleatória" aria-pressed={shuffle} title="Reprodução aleatória" on:click={() => shuffle = !shuffle}><Shuffle size={19}/></button>
        <button aria-label="Música anterior" title="Música anterior" disabled={!canPrevious} on:click={() => move(-1)}><SkipBack size={23} fill="currentColor"/></button>
        <button class="play-button" aria-label={playing ? 'Pausar música' : 'Reproduzir música'} disabled={!source} on:click={toggle}>{#if playing}<Pause size={26} fill="currentColor"/>{:else}<Play size={26} fill="currentColor"/>{/if}</button>
        <button aria-label="Próxima música" title="Próxima música" disabled={!canNext} on:click={() => move(1)}><SkipForward size={23} fill="currentColor"/></button>
        <button class="transport-mode" class:enabled={repeat !== 'off'} aria-label={`Repetição: ${repeat === 'off' ? 'desativada' : repeat === 'all' ? 'lista' : 'uma música'}`} aria-pressed={repeat !== 'off'} title={`Repetir: ${repeat === 'off' ? 'desativado' : repeat === 'all' ? 'lista' : 'uma música'}`} on:click={() => repeat = repeat === 'off' ? 'all' : repeat === 'all' ? 'one' : 'off'}>{#if repeat === 'one'}<Repeat1 size={19}/>{:else}<Repeat size={19}/>{/if}</button>
      </div>
      {#if source && !video}{#key trackPath}<audio bind:this={element} src={source} preload="metadata" on:loadedmetadata={loaded} on:timeupdate={() => current = element.currentTime} on:play={() => { playing = true; error = ''; }} on:pause={() => playing = false} on:ended={ended} on:error={() => error = 'Este formato não pôde ser reproduzido pelo Windows.'}></audio>{/key}{/if}
      <div class="volume-area"><button class="volume-button" aria-label="Ajustar volume" aria-expanded={showVolume} on:click={() => showVolume = !showVolume}><Volume2 size={18}/></button>{#if showVolume}<label class="volume-popover"><span>Volume · {Math.round(volume * 100)}%</span><input aria-label="Volume" type="range" min="0" max="1" step="0.01" bind:value={volume} on:input={(e) => { volume = Number(e.currentTarget.value); if (element) element.volume = volume; }}/></label>{/if}</div>
      {#if error}<p class="media-error" role="alert">{error}</p>{/if}
    {:else}
      <span class="photo-name">{name}</span>
      {#if error}<p class="media-error photo-error" role="alert">{error}</p>{:else if source}<img class="full-image" src={source} alt={name} on:error={() => error = 'Não foi possível exibir esta imagem.'}/>{:else}<p class="loading-media">Abrindo imagem…</p>{/if}
    {/if}
  </section>
</div>
<style>
  .media-modal{position:relative;outline:none;min-width:0}.media-modal.audio{width:min(360px,calc(100vw - 48px));padding:42px 28px 18px;border:1px solid #333;border-radius:18px;background:#202020;color:#f4f4f4;box-shadow:0 24px 100px #0008;max-height:calc(100dvh - 48px);overflow:hidden;display:flex;flex-direction:column;gap:18px}.media-close{position:absolute;top:8px;right:9px;display:grid;place-items:center;color:#aaa;border-radius:50%;width:28px;height:28px}.media-close:hover{background:#ffffff12;color:white}.player-artwork{align-self:center;width:min(100%,calc(100dvh - 335px));aspect-ratio:1;min-height:60px;flex-shrink:1;border-radius:10px;overflow:hidden;background:linear-gradient(145deg,#555,#343434);color:#b7b7b7;display:grid;place-items:center}.player-artwork img,.player-artwork video{display:block;width:100%;height:100%;object-fit:contain}.player-artwork img{object-fit:cover}.track-heading{text-align:center;min-width:0;flex-shrink:0}h2{font-size:16px;font-weight:600;line-height:1.3;margin:0 0 5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.artist{font-size:12px;color:#999;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.timeline{flex-shrink:0;min-width:0}.seek{width:100%;min-width:0;margin:0;display:block;appearance:none;-webkit-appearance:none;height:3px;background:linear-gradient(to right,#20e3ad var(--played),#777 var(--played));border-radius:5px;cursor:pointer;accent-color:#20e3ad}.seek::-webkit-slider-thumb{appearance:none;-webkit-appearance:none;width:12px;height:12px;border-radius:50%;background:#fff}.seek::-moz-range-thumb{width:12px;height:12px;border:0;border-radius:50%;background:#fff}.duration{display:flex;justify-content:space-between;color:#aaa;font-size:10px;margin-top:10px;font-variant-numeric:tabular-nums}.transport{display:flex;align-items:center;justify-content:space-between;gap:8px;min-width:0;flex-shrink:0}.transport button{display:grid;place-items:center;color:#eee;flex-shrink:0;padding:5px;width:30px;height:30px}.transport button:hover:not(:disabled){color:white;background:#ffffff09;border-radius:50%}.transport button:disabled{opacity:.22}.transport .play-button{width:48px;height:48px;border:0;border-radius:50%;background:#424242}.transport .transport-mode{color:#929292}.transport-mode.enabled{color:#20e3ad!important}.volume-area{position:relative;display:flex;justify-content:center;margin-top:-8px;flex-shrink:0}.volume-button{color:#999;display:grid;place-items:center;padding:5px;border-radius:50%}.volume-button:hover{color:#fff}.volume-popover{position:absolute;bottom:34px;right:0;width:165px;border:1px solid #555;border-radius:10px;background:#303030;padding:12px;box-shadow:0 10px 30px #0006;display:flex;flex-direction:column;gap:12px;font-size:11px;color:#ccc}.volume-popover input{width:100%;min-width:0;margin:0;height:4px;accent-color:#20e3ad}.media-error{color:#ffc1c1;font-size:11px;line-height:1.4;margin:0;max-height:34px;overflow:hidden}.photo-view{background:#060708ef;backdrop-filter:blur(4px)}.media-modal.photo{padding:0;border:0;border-radius:0;box-shadow:none;background:transparent;width:auto;max-width:94vw;max-height:84vh}.photo .media-close{position:fixed;top:22px;right:24px;width:36px;height:36px;color:#ddd}.photo-name{position:fixed;top:28px;left:32px;max-width:75vw;text-overflow:ellipsis;overflow:hidden;white-space:nowrap;font-size:13px;color:#bbb}.full-image{display:block;max-width:94vw;max-height:84vh;object-fit:contain;margin:0;box-shadow:0 8px 50px #0005}.photo-error,.loading-media{color:#ddd;padding:30px;font-size:14px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}@media(max-height:650px){.media-modal.audio{gap:12px;padding-top:36px}.player-artwork{width:min(100%,calc(100dvh - 310px))}}
</style>
