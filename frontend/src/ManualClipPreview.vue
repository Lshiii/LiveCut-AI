<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { SequencePreview, type SequencePreviewState } from './sequencePreview'
import { transcriptTime } from './transcriptSearch'
const props = defineProps<{ videoUrl: string; sourceKey: string; range: { start_ms: number; end_ms: number } | null; active: boolean }>()
const player = ref<HTMLVideoElement | null>(null), playing = ref<SequencePreviewState | null>(null), waiting = ref(false), error = ref('')
const sequence = new SequencePreview(value => { playing.value = value })
let version = 0, alive = true, timer: number | undefined
let intent: { version: number; key: string; start: number; end: number | null } | null = null
const key = computed(() => JSON.stringify([props.sourceKey, props.videoUrl, props.range]))
const available = () => alive && props.active && !document.hidden
function stop() { version++; intent = null; window.clearTimeout(timer); timer = undefined; waiting.value = false; sequence.cancel(); player.value?.pause() }
defineExpose({ stop })
function matches(element: HTMLVideoElement) { try { return element.currentSrc === new URL(props.videoUrl, document.baseURI).href } catch { return false } }
function current(value: NonNullable<typeof intent>) { return available() && value.version === version && value.key === key.value && !!props.range }
async function finish() {
  const value = intent, element = player.value
  if (!value || !element || !current(value) || element.readyState < 1 || !matches(element)) return
  intent = null; window.clearTimeout(timer); timer = undefined; waiting.value = false
  try {
    if (value.end === null) { element.pause(); element.currentTime = value.start / 1000 }
    else if (await sequence.start(element, { id: value.key, parts: [{ start_ms: value.start, end_ms: value.end }] }) === 'failed' && current(value)) error.value = '暂时无法预览该区间，请稍后重试。'
  } catch { if (current(value)) error.value = '原视频暂时无法定位，请稍后重试。' }
}
async function start(preview: boolean) {
  if (!available() || !props.range) return
  if (playing.value && preview) { stop(); return }
  stop(); error.value = ''; const value = { version, key: key.value, start: props.range.start_ms, end: preview ? props.range.end_ms : null }
  intent = value; waiting.value = true
  timer = window.setTimeout(() => { if (intent === value && value.version === version) { stop(); error.value = '原视频尚未准备好，请重试。' } }, 15000)
  await nextTick(); if (current(value)) await finish()
}
function media(event: Event, kind: 'metadata' | 'time' | 'seek' | 'seeked' | 'pause' | 'ended' | 'rate' | 'play' | 'error') {
  const element = event.currentTarget as HTMLVideoElement
  if (element !== player.value) return
  if (kind === 'error') { stop(); error.value = '原视频暂时无法播放，请稍后重试。'; return }
  if (kind === 'metadata') { void finish(); return }
  if (kind === 'play') {
    if (!available() || !matches(element)) { element.pause(); return }
    if (intent) { version++; intent = null; window.clearTimeout(timer); timer = undefined; waiting.value = false }
  } else if (kind === 'seek') { if (intent) stop(); else sequence.seeking(element) }
  else if (kind === 'pause') { if (intent && element.paused) stop(); else sequence.pause(element) }
  else if (kind === 'seeked') sequence.seeked(element)
  else if (kind === 'time') sequence.timeUpdate(element)
  else if (kind === 'ended') sequence.ended(element)
  else if (kind === 'rate') sequence.rateChange(element)
}
watch([key, () => props.active], stop, { flush: 'sync' })
function visibility() { if (document.hidden) stop() }
document.addEventListener('visibilitychange', visibility)
onBeforeUnmount(() => { alive = false; stop(); document.removeEventListener('visibilitychange', visibility) })
</script>
<template>
  <section class="manual-range-preview" aria-label="人工片段原视频预览"><h3>完整选定区间回看</h3><video ref="player" :key="sourceKey" :src="active ? videoUrl : undefined" controls preload="metadata" playsinline @loadedmetadata="media($event, 'metadata')" @timeupdate="media($event, 'time')" @playing="media($event, 'time')" @seeking="media($event, 'seek')" @seeked="media($event, 'seeked')" @pause="media($event, 'pause')" @ended="media($event, 'ended')" @ratechange="media($event, 'rate')" @play="media($event, 'play')" @error="media($event, 'error')"></video><div><button type="button" class="secondary" :disabled="!active || !range" @click="start(false)">定位人工片段起点</button><button type="button" class="secondary" :disabled="!active || !range || waiting" @click="start(true)">{{ playing ? '停止人工区间预览' : '预览完整人工区间' }}</button></div><p v-if="range">原视频 {{ transcriptTime(range.start_ms, true) }} → {{ transcriptTime(range.end_ms, true) }}。包含中间全部音画、原句及静音；预览不保存或导出。</p><p v-if="waiting" role="status">正在准备人工区间定位…</p><p v-if="error" class="manual-preview-error" role="status">{{ error }}</p></section>
</template>
<style scoped>.manual-range-preview{min-width:0}.manual-range-preview h3{font-size:14px;margin:0 0 10px}.manual-range-preview video{display:block;width:100%;max-height:330px;background:#151a21;border-radius:7px}.manual-range-preview>div{display:flex;flex-wrap:wrap;gap:8px;margin:10px 0}.manual-range-preview button{max-width:100%;font-size:12px;white-space:normal}.manual-range-preview p{font-size:12px;line-height:1.8;color:#64748b;overflow-wrap:anywhere}.manual-preview-error{color:#985c32!important}</style>
