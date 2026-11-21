<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { SequencePreview, type SequencePreviewPart } from './sequencePreview'
import { topicTime } from './topics'

const props = defineProps<{ videoUrl: string; part: SequencePreviewPart | null; active: boolean }>()
const player = ref<HTMLVideoElement | null>(null)
const loaded = ref(false), failed = ref(false), visible = ref(!document.hidden), playing = ref('')
const allowed = computed(() => props.active && visible.value && !!props.part && loaded.value && !failed.value)
const preview = new SequencePreview((state, reason) => {
  playing.value = state?.id ?? ''
  if (reason === 'failed') feedback.value = '播放失败，可以重新试听。'
})
const feedback = ref('')
function stop() { preview.cancel(); player.value?.pause() }
function changeVisibility() { visible.value = !document.hidden }
function current(video: HTMLVideoElement) {
  return player.value === video && video.currentSrc === new URL(props.videoUrl, document.baseURI).href
}
function metadata(event: Event) {
  if (!current(event.currentTarget as HTMLVideoElement)) return
  loaded.value = true; failed.value = false
  showCutFrame()
}
function mediaError(event: Event) {
  if (event.currentTarget === player.value) { failed.value = true; stop() }
}
function guardPlay(event: Event) {
  const video = event.currentTarget as HTMLVideoElement
  if (!current(video) || !playing.value || !allowed.value) video.pause()
}
function showCutFrame() {
  const video = player.value
  if (video && current(video) && video.paused && allowed.value && props.part) {
    try { video.currentTime = props.part.start_ms / 1000 } catch { /* a manual audition can retry */ }
  }
}
function listen(edge: 'start' | 'end' | 'range') {
  const video = player.value, part = props.part
  if (!video || !part || !allowed.value || !current(video)) return
  if (playing.value === edge) { stop(); return }
  const start_ms = edge === 'end' ? Math.max(part.start_ms, part.end_ms - 3000) : part.start_ms
  const end_ms = edge === 'start' ? Math.min(part.end_ms, part.start_ms + 3000) : part.end_ms
  feedback.value = ''
  void preview.start(video, { id: edge, parts: [{ start_ms, end_ms }] })
}
watch([() => props.part, () => props.active, visible], () => { stop(); feedback.value = ''; showCutFrame() }, { flush: 'sync' })
watch(() => props.videoUrl, () => { stop(); loaded.value = false; failed.value = false }, { flush: 'sync' })
onMounted(() => document.addEventListener('visibilitychange', changeVisibility))
onBeforeUnmount(() => { document.removeEventListener('visibilitychange', changeVisibility); stop() })
</script>
<template>
  <section class="range-monitor" aria-label="试听待应用区间">
    <video :key="videoUrl" ref="player" :src="videoUrl" preload="metadata" playsinline aria-label="区间原片回看"
      @loadedmetadata="metadata" @error="mediaError" @play="guardPlay" @playing="guardPlay"
      @timeupdate="preview.timeUpdate($event.currentTarget as HTMLVideoElement)"
      @seeking="preview.seeking($event.currentTarget as HTMLVideoElement)" @seeked="preview.seeked($event.currentTarget as HTMLVideoElement)"
      @pause="preview.pause($event.currentTarget as HTMLVideoElement)" @ended="preview.ended($event.currentTarget as HTMLVideoElement)"
      @ratechange="preview.rateChange($event.currentTarget as HTMLVideoElement)" />
    <div class="range-listen"><strong>剪点试听</strong>
      <div class="range-listen-actions">
        <button type="button" :disabled="!allowed" :aria-pressed="playing === 'start'" @click="listen('start')">{{ playing === 'start' ? '停止开头' : '听开头' }}</button>
        <button type="button" :disabled="!allowed" :aria-pressed="playing === 'end'" @click="listen('end')">{{ playing === 'end' ? '停止结尾' : '听结尾' }}</button>
        <button type="button" :disabled="!allowed" :aria-pressed="playing === 'range'" @click="listen('range')">{{ playing === 'range' ? '停止区间' : '听整段' }}</button>
      </div>
      <small v-if="failed || feedback" role="status">{{ failed ? '原片暂时无法播放，仍可按原句调整。' : feedback }}</small>
      <small v-else>{{ part ? '两端各试听最多 3 秒 · 整段 ' + topicTime(part.end_ms - part.start_ms) : '选好起止原句后可试听' }}</small>
    </div>
  </section>
</template>
<style scoped>
.range-monitor { min-width: 0; }
.range-monitor video { display: block; width: 100%; aspect-ratio: 16/9; object-fit: contain; background: var(--workspace-video,#211f1b); border-radius: 7px; }
.range-listen { display: grid; gap: 7px; margin: 10px 0; }
.range-listen strong { font-size: 14px; }
.range-listen small { color: var(--workspace-muted,#675f56); font-size: 12px; }
.range-listen-actions { display: flex; flex-wrap: wrap; gap: 5px; }
.range-listen-actions button { min-height: 34px; border: 1px solid var(--workspace-border,#ddd); border-radius: 6px; padding: 4px 9px; background: var(--workspace-surface); color: var(--workspace-text,#292621); font: inherit; font-size: 13px; }
.range-listen-actions button[aria-pressed=true] { color: var(--workspace-accent,#a94424); border-color: currentColor; }
@media(max-width:700px) {
  .range-monitor { display: grid; grid-template-columns: minmax(72px, 27%) 1fr; align-items: center; gap: 10px; }
  .range-listen { margin: 0; gap: 3px; }
  .range-listen strong { display: none; }
}
</style>
