<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { VideoAsset } from './api'
import { parseTopicTime, topicTime } from './topics'
import FrameStrip from './FrameStrip.vue'

const props = defineProps<{ video?: Pick<VideoAsset, 'id' | 'content_url' | 'original_filename'>; active: boolean; compose?: boolean; setupFirst?: boolean; playheads?: Map<string, number> }>()
const emit = defineEmits<{ cover: [url: string, image: string] }>()
const player = ref<HTMLVideoElement | null>(null)
const duration = ref(0), at = ref(0), width = ref(0), height = ref(0), playing = ref(false), muted = ref(false)
const rate = ref(1), volume = ref(1), error = ref(''), fullscreen = ref(false)
const clockDraft = ref<string | null>(null), clockError = ref('')
const parts = computed(() => duration.value ? [{ start_ms: 0, end_ms: duration.value }] : [])
const ticks = computed(() => [0, .25, .5, .75, 1].map(fraction => topicTime(Math.round(duration.value * fraction)).split('.')[0]))
const percent = computed(() => duration.value ? 100 * at.value / duration.value : 0)
function clear() { at.value = duration.value = width.value = height.value = 0; playing.value = muted.value = fullscreen.value = false; rate.value = volume.value = 1; error.value = '' }
function metadata(event: Event) {
  const video = event.currentTarget as HTMLVideoElement
  if (video !== player.value || !Number.isFinite(video.duration)) return
  duration.value = Math.floor(video.duration * 1000); width.value = video.videoWidth; height.value = video.videoHeight
  if (props.active) restorePosition(video)
  sync()
}
function restorePosition(video: HTMLVideoElement) {
  const saved = props.video && props.playheads?.get(props.video.content_url)
  const atMs = saved ?? (duration.value > 2000 ? 1000 : 0)
  if (duration.value && Math.abs(video.currentTime * 1000 - atMs) >= 1) video.currentTime = Math.min(duration.value, atMs) / 1000
}
function rememberPosition(ms: number) {
  if (props.active && props.video && duration.value) props.playheads?.set(props.video.content_url, ms)
}
function sync() {
  const video = player.value
  if (!video) return
  at.value = Math.min(duration.value, Math.round(video.currentTime * 1000))
  rememberPosition(at.value)
  playing.value = !video.paused && !video.ended; muted.value = video.muted; rate.value = video.playbackRate
  volume.value = video.volume
}
function seek(ms: number) {
  const video = player.value
  if (video && props.active && duration.value && Number.isFinite(ms)) {
    video.pause()
    const target = Math.max(0, Math.min(ms, duration.value))
    try { video.currentTime = target / 1000; at.value = target; rememberPosition(target) } catch { error.value = '暂时无法定位原片，请等待媒体加载。' }
  }
}
function jumpTime(event: KeyboardEvent) {
  if (event.isComposing || clockDraft.value === null) return
  const ms = parseTopicTime(clockDraft.value)
  if (ms === null || ms > duration.value) { clockError.value = '请输入原片时长内的时间，如 12:30。'; return }
  seek(ms); (event.currentTarget as HTMLInputElement).blur()
}
function clearClock() { clockDraft.value = null; clockError.value = '' }
async function togglePlay() {
  const video = player.value
  if (!video || !props.active || !duration.value) return
  if (!video.paused) { video.pause(); return }
  try { await video.play(); if (video !== player.value || !props.active || document.hidden) video.pause() }
  catch { if (video === player.value && props.active) error.value = '暂时无法播放原片，请重试。' }
}
function onPlay() {
  if (!props.active || document.hidden) player.value?.pause()
  else { error.value = ''; sync() }
}
function setRate(event: Event) { if (player.value) player.value.playbackRate = Number((event.target as HTMLSelectElement).value) }
function toggleMute() {
  const video = player.value
  if (!video) return
  if (video.muted && video.volume === 0) video.volume = 1
  video.muted = !video.muted
}
function setVolume(event: Event) {
  const video = player.value
  if (!video) return
  video.volume = (event.target as HTMLInputElement).valueAsNumber / 100
  video.muted = video.volume === 0
}
async function expand() {
  const video = player.value
  try { await video?.requestFullscreen() } catch { error.value = '当前浏览器暂不支持全屏播放。' }
}
function onFullscreen() { fullscreen.value = !!player.value && document.fullscreenElement === player.value }
function onVisibility() { if (document.hidden) player.value?.pause() }
watch(() => props.video?.id, () => { clear(); clearClock() }, { flush: 'sync' })
watch(() => props.active, active => {
  if (!active) { player.value?.pause(); clearClock() }
  else if (player.value && duration.value) restorePosition(player.value)
}, { flush: 'sync' })
onMounted(() => { document.addEventListener('visibilitychange', onVisibility); document.addEventListener('fullscreenchange', onFullscreen) })
onBeforeUnmount(() => { player.value?.pause(); document.removeEventListener('visibilitychange', onVisibility); document.removeEventListener('fullscreenchange', onFullscreen) })
</script>

<template>
  <div class="studio-desk" :class="{ 'compose-desk': compose, 'has-start': !!$slots.start, 'setup-first': setupFirst }">
    <section class="monitor" aria-label="素材监看">
      <header><h2><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/></svg>素材监看</h2>
        <span v-if="width && height" class="readout">{{ width }} × {{ height }}</span></header>
      <div class="screen">
        <video v-if="video" :key="video.id" ref="player" :src="video.content_url" :controls="fullscreen" playsinline :preload="active ? 'metadata' : 'none'"
          aria-label="素材监看播放器" @click="!fullscreen && togglePlay()" @loadedmetadata="metadata" @timeupdate="sync" @seeked="sync"
          @play="onPlay" @pause="sync" @ended="sync" @volumechange="sync" @ratechange="sync" @error="error = '无法载入原片，请检查文件或连接。'" />
        <div v-else class="no-source"><svg viewBox="0 0 48 48" aria-hidden="true"><path d="M5 17V7h10m18 0h10v10M5 31v10h10m18 0h10V31M19 16l13 8-13 8z"/></svg><p>导入视频后即可预览</p></div>
        <p v-if="error" class="monitor-error" role="status">{{ error }}</p>
      </div>
      <div class="transport" v-if="video">
        <input class="jump-time" aria-label="跳到原片时间" title="输入分:秒，按回车跳转" inputmode="text" :disabled="!duration"
          :value="clockDraft ?? topicTime(at)" :aria-invalid="!!clockError" @focus="clockDraft = topicTime(at); ($event.target as HTMLInputElement).select()"
          @input="clockDraft = ($event.target as HTMLInputElement).value; clockError = ''" @keydown.enter.prevent="jumpTime" @keydown.esc.stop="($event.target as HTMLInputElement).blur()" @blur="clearClock">
        <span class="duration">/ {{ topicTime(duration) }}</span>
        <div class="transport-keys">
          <button aria-label="后退五秒" title="后退五秒" :disabled="!duration" @click="seek(at - 5000)"><svg viewBox="0 0 24 24"><path d="M5 5v14m14-14L8 12l11 7z"/></svg></button>
          <button class="play-key" :aria-label="playing ? '暂停原片' : '播放原片'" :title="playing ? '暂停原片' : '播放原片'" :disabled="!duration" @click="togglePlay"><svg viewBox="0 0 24 24"><path v-if="playing" d="M8 5v14m8-14v14"/><path v-else d="m8 5 11 7-11 7z" fill="currentColor"/></svg></button>
          <button aria-label="前进五秒" title="前进五秒" :disabled="!duration" @click="seek(at + 5000)"><svg viewBox="0 0 24 24"><path d="M19 5v14M5 5l11 7-11 7z"/></svg></button>
        </div>
        <select :value="rate" aria-label="预览倍速" title="预览倍速" @change="setRate"><option v-for="speed in [.5, 1, 1.5, 2]" :key="speed" :value="speed">{{ speed }}×</option></select>
        <div class="volume-control">
          <button :aria-label="muted ? '取消静音' : '静音'" :title="muted ? '取消静音' : '静音'" @click="toggleMute"><svg viewBox="0 0 24 24"><path d="M4 9h4l5-4v14l-5-4H4z"/><path v-if="muted" d="m17 9 5 6m0-6-5 6"/><path v-else d="M17 8q4 4 0 8"/></svg></button>
          <label class="volume-popup"><input type="range" min="0" max="100" step="1" :value="muted ? 0 : Math.round(volume * 100)" aria-label="预览音量" @input="setVolume"></label>
        </div>
        <button aria-label="全屏预览" title="全屏预览" :disabled="!duration" @click="expand"><svg viewBox="0 0 24 24"><path d="M3 9V4h6m6 0h6v5M3 15v5h6m6 0h6v-5"/></svg></button>
      </div>
      <p v-if="clockError" class="clock-error" role="alert">{{ clockError }}</p>
    </section>
    <aside class="studio-inspector" :aria-label="compose ? '剪辑要求' : '素材与任务设置'"><slot name="start"/><slot /></aside>
    <section class="source-timeline" aria-label="原片时间线">
      <header><h2><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="7" rx="1.5"/><path d="M3 15h12m-12 4h8m5-4h5"/></svg>原片时间线</h2>
        <span v-if="duration" class="readout">{{ topicTime(duration) }}</span></header>
      <template v-if="duration && video">
        <div class="ruler"><span v-for="(tick, i) in ticks" :key="i">{{ tick }}</span></div>
        <div class="film-row" :style="{ '--head': percent + '%' }">
          <FrameStrip v-if="active" :url="video.content_url" :source-aspect="height ? width / height : 0" :parts="parts" :from-ms="0" :to-ms="duration" @cover="(url, img) => emit('cover', url, img)" />
          <div class="clip-name">{{ video.original_filename }}</div>
          <span class="playhead" aria-hidden="true"></span>
          <input type="range" min="0" :max="duration" step="1" :value="at" aria-label="原片播放位置" :aria-valuetext="topicTime(at)" @input="seek(($event.target as HTMLInputElement).valueAsNumber)">
        </div>
      </template>
      <p v-else class="empty-track">{{ video ? '等待读取原片时长…' : '尚未载入素材' }}</p>
    </section>
  </div>
</template>

<style scoped>
.studio-desk { display:grid; grid-template-columns:minmax(0,1fr) clamp(275px,29%,365px); gap:8px; align-items:stretch; min-width:0; }
.monitor,.studio-inspector,.source-timeline { min-width:0; background:var(--workspace-surface); border:1px solid var(--workspace-border); border-radius:9px; }
.monitor { display:flex; flex-direction:column; overflow:hidden; }
.monitor header,.source-timeline header { display:flex; align-items:center; justify-content:space-between; min-height:45px; padding:8px 16px; gap:12px; }
h2 { display:flex; align-items:center; gap:9px; margin:0; font-size:14px; font-weight:600; }
svg { display:block; width:17px; height:17px; fill:none; stroke:currentColor; stroke-width:1.7; stroke-linecap:round; stroke-linejoin:round; }
header svg { color:var(--workspace-muted); }
.readout,.transport strong,.duration,.ruler { font-family:var(--workspace-mono); font-variant-numeric:tabular-nums; }
.readout { font-size:11px; color:var(--workspace-muted); }
.screen { position:relative; display:grid; flex:1; min-height:240px; place-items:center; background:#090b0d; overflow:hidden; }
.screen video { position:absolute; inset:0; width:100%; height:100%; object-fit:contain; cursor:pointer; }
.screen video:fullscreen { background:#000; }
.no-source { text-align:center; color:var(--workspace-muted); } .no-source svg { margin:0 auto 12px; width:40px; height:40px; stroke-width:1.4; }
.monitor-error { position:absolute; bottom:8px; padding:4px 8px; background:#201c19; color:var(--studio-warning); border-radius:4px; }
.transport { display:flex; align-items:center; flex-wrap:wrap; padding:6px 12px; gap:4px; min-height:49px; }
.transport strong { font-size:13px; font-weight:550; color:var(--workspace-accent); } .duration { font-size:11px; color:var(--workspace-muted); }
.transport-keys { display:flex; align-items:center; gap:3px; margin-inline:auto; }
.transport button,.transport select { display:grid; place-items:center; min-width:32px; min-height:32px; border:0; border-radius:5px; background:transparent; color:var(--workspace-muted); }
.transport button:hover,.transport select:hover { color:var(--workspace-text); background:var(--studio-raised); }
.transport .play-key { color:var(--workspace-text); background:var(--studio-raised); }
.transport .jump-time { width:10ch; min-width:0; padding:5px 3px; border:1px solid transparent; border-radius:4px; background:transparent; color:var(--workspace-accent); font:600 13px var(--workspace-mono); }
.transport .jump-time:hover,.transport .jump-time:focus-visible { border-color:var(--workspace-control-border); background:var(--studio-well); }
.clock-error { color:var(--studio-warning); font-size:12px; padding:0 12px 8px; }
.transport select { font-size:12px; }
.volume-control { position:relative; }
.volume-popup { display:none; position:absolute; z-index:1; bottom:100%; right:0; padding:10px; background:var(--studio-raised); border:1px solid var(--workspace-control-border); border-radius:5px; }
.volume-popup input { display:block; width:85px; padding:0; margin:0; accent-color:var(--workspace-accent); }
.volume-control:hover .volume-popup,.volume-control:focus-within .volume-popup { display:block; }
.studio-inspector { display:flex; flex-direction:column; }
.studio-inspector > :deep(*) { flex:none; }
.source-timeline { grid-column:1/-1; padding:0 15px 18px; }
.source-timeline header { padding:8px 0; } .ruler { display:flex; justify-content:space-between; height:28px; margin:0 1px 4px; font-size:10px; color:var(--workspace-muted); background:repeating-linear-gradient(90deg,var(--workspace-control-border) 0 1px,transparent 1px 2.5%) left bottom/100% 5px no-repeat; }
.film-row { height:clamp(65px,10vh,96px); position:relative; isolation:isolate; background:var(--studio-well); border:1px solid #427970; border-radius:5px; }
.film-row :deep(.frame-strip) { border-radius:4px; } .clip-name { position:absolute; top:4px; left:5px; max-width:90%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding:2px 6px; border-radius:3px; font-size:11px; background:#0b171be6; color:#e7f2ee; pointer-events:none; }
.playhead { position:absolute; top:-8px; bottom:-4px; left:clamp(0%,var(--head),calc(100% - 1px)); width:2px; background:var(--workspace-accent); pointer-events:none; }
.playhead::before { content:''; position:absolute; top:-2px; left:-4px; width:10px; height:10px; clip-path:polygon(0 0,100% 0,100% 55%,50% 100%,0 55%); background:var(--workspace-accent); }
.film-row input { position:absolute; inset:0; width:100%; height:100%; padding:0; margin:0; opacity:0; appearance:none; cursor:crosshair; }
.film-row input::-webkit-slider-runnable-track { height:100%; }
.film-row input::-webkit-slider-thumb { appearance:none; width:1px; height:100%; }
.film-row input::-moz-range-thumb { width:1px; border:0; }
.film-row:focus-within { outline:2px solid var(--workspace-accent); outline-offset:3px; }
.empty-track { color:var(--workspace-muted); padding:14px 0; }
@media(min-width:761px) { .monitor { min-height:clamp(360px,53dvh,620px); } .studio-inspector { contain:size; min-height:0; overflow:auto; } }
@media(max-width:760px) {
  .studio-desk { grid-template-columns:minmax(0,1fr); }
  .screen { min-height:0; aspect-ratio:16/9; }
  .source-timeline { grid-row:2; } .transport { padding-inline:8px; }
  .transport button { min-width:28px; } .duration { display:none; }
  .compose-desk .studio-inspector { grid-row:1; } .compose-desk .source-timeline { grid-row:3; }
  .setup-first .studio-inspector { grid-row:1; } .setup-first .source-timeline { grid-row:3; }
  .has-start .studio-inspector { display:contents; }
  .has-start .studio-inspector > :deep(.edit-start) { grid-row:1; }
  .has-start .monitor { grid-row:2; } .has-start .source-timeline { grid-row:3; }
}
</style>
