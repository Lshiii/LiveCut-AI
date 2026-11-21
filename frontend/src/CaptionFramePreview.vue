<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { captionTime, parseCaptionTime, type CaptionCue, type CaptionSnapshot } from './captions'
import { captionRenderBase, captionRenderInput, type CaptionRenderFrame, type CaptionRenderStyle } from './captionRenders'

const props = defineProps<{ caption: CaptionSnapshot; stylePreset: CaptionRenderStyle; frame: CaptionRenderFrame; enabled: boolean; initialCueId?: string }>()
const maxMs = computed(() => props.caption.source.media_duration_ms - 1)
const position = ref(0), imageUrl = ref(''), busy = ref(false), error = ref('')
const timeText = ref(captionTime(0))
const frameCues = computed(() => props.caption.cues.filter(cue => cue.start_ms <= maxMs.value))
const nextCueIndex = computed(() => frameCues.value.findIndex(cue => cue.start_ms > position.value))
const priorIndex = computed(() => (nextCueIndex.value < 0 ? frameCues.value.length : nextCueIndex.value) - 1)
const currentCue = computed(() => {
  const cue = frameCues.value[priorIndex.value]
  return cue && position.value < cue.end_ms ? cue : undefined
})
const previousCue = computed(() => frameCues.value[priorIndex.value - (currentCue.value ? 1 : 0)])
const nextCue = computed(() => frameCues.value[nextCueIndex.value])
const validTime = computed(() => {
  const ms = parseCaptionTime(timeText.value)
  return ms !== null && ms <= maxMs.value
})
let request: AbortController | null = null

function clear() {
  request?.abort()
  request = null
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''; busy.value = false; error.value = ''
}
function moveTo(ms: number) { position.value = ms; timeText.value = captionTime(ms) }
function selectCue(cue?: CaptionCue) {
  if (cue) moveTo(Math.min(maxMs.value, Math.floor((cue.start_ms + cue.end_ms) / 2)))
}
function inputTime(event: Event) {
  timeText.value = (event.target as HTMLInputElement).value
  const ms = parseCaptionTime(timeText.value)
  if (ms !== null && ms <= maxMs.value) position.value = ms
  else clear()
}
watch([() => props.caption, () => props.initialCueId, () => props.enabled], () => {
  clear()
  if (props.enabled) {
    const cue = frameCues.value.find(item => item.id === props.initialCueId) ?? frameCues.value[0]
    if (cue) selectCue(cue)
    else moveTo(0)
  }
}, { immediate: true, flush: 'sync' })
watch([position, () => props.stylePreset, () => props.frame], clear, { flush: 'sync' })
onBeforeUnmount(clear)

const errors: Record<string, string> = {
  CAPTION_PREVIEW_BUSY: '另一个预览正在生成，请稍后再试。',
  CAPTION_PREVIEW_TIME_INVALID: '时间点已超出成片范围，请重新选择。',
  CAPTION_RENDER_FONT_UNAVAILABLE: '渲染字体不可用，请检查字幕字体配置。',
  CAPTION_RENDER_GLYPH_UNSUPPORTED: '字幕含有当前字体缺失的字符，请先校对字幕。',
  CAPTION_RENDER_TEXT_TOO_LARGE: '字幕超出当前画幅，请换画幅或缩短字幕。',
  CAPTION_RENDER_REVISION_MISMATCH: '字幕版本已变化，请重新选择确认版本。',
}
async function preview() {
  if (!props.enabled || busy.value || !validTime.value) return
  clear()
  const controller = new AbortController()
  request = controller; busy.value = true
  const timeout = window.setTimeout(() => controller.abort(), 65000)
  try {
    const caption = props.caption
    const base = captionRenderBase({ export_id: caption.export_id, clip_id: caption.clip_id, clip_sha256: caption.source.clip_sha256 })
    const response = await fetch(`${base}/preview?at_ms=${position.value}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, cache: 'no-store', signal: controller.signal,
      body: JSON.stringify(captionRenderInput(caption, props.stylePreset, props.frame)),
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(errors[body?.error_code] ?? '无法生成预览帧，请核对渲染环境后重试。')
    }
    if (response.headers.get('Content-Type')?.split(';')[0] !== 'image/jpeg') throw new Error('服务返回的预览格式错误。')
    const blob = await response.blob()
    if (request === controller) imageUrl.value = URL.createObjectURL(blob)
  } catch (failure) {
    if (request === controller) error.value = controller.signal.aborted ? '预览超时，请稍后重试。'
      : failure instanceof Error ? failure.message : '无法生成预览帧。'
  } finally {
    window.clearTimeout(timeout)
    if (request === controller) { request = null; busy.value = false }
  }
}
</script>

<template>
  <section class="frame-preview" aria-label="成片画面预览">
    <div class="frame-preview-title"><strong>字幕画面预览</strong><label>时间<input :value="timeText" type="text" maxlength="24" aria-label="预览时间码" :aria-invalid="!validTime" :disabled="!enabled" @input="inputTime"></label></div>
    <input :value="position" type="range" min="0" :max="maxMs" step="1" aria-label="预览成片时间" :disabled="!enabled" @input="moveTo(Number(($event.target as HTMLInputElement).value))">
    <p v-if="!validTime" class="frame-error">时间范围 00:00.000 至 {{ captionTime(maxMs) }}，格式为 分:秒.毫秒。</p>
    <blockquote>{{ currentCue?.text ?? '这个时间点没有字幕' }}</blockquote>
    <div class="frame-actions">
    <button type="button" :disabled="!enabled || !previousCue" @click="selectCue(previousCue)">上一句</button>
    <button type="button" :disabled="!enabled || !nextCue" @click="selectCue(nextCue)">下一句</button>
    <button type="button" class="render-button" :disabled="!enabled || busy || !validTime" @click="preview">{{ busy ? '正在渲染预览帧…' : '预览这一帧' }}</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <img v-if="imageUrl" :src="imageUrl" :alt="`成片 ${captionTime(position)} 的字幕和画幅预览`">
  </section>
</template>

<style scoped>
.frame-preview { display: grid; min-width: 0; margin-top: 14px; padding: 12px; gap: 9px; border: 1px solid #dedbd4; border-radius: 8px; background: #fcfbf8; font-size: 14px; }
.frame-preview-title, .frame-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 10px; }
.frame-preview-title { justify-content: space-between; }
.frame-preview-title label { display: flex; align-items: center; gap: 5px; margin: 0; }
.frame-preview-title input { width: 10ch; padding: 4px; border: 1px solid #d5d8dc; border-radius: 4px; font: inherit; font-variant-numeric: tabular-nums; }
.frame-preview input[type=range], .frame-preview img { width: 100%; min-width: 0; }
.frame-preview blockquote { margin: 0; padding-left: 8px; border-left: 2px solid #d4a089; color: #344252; overflow-wrap: anywhere; }
.frame-actions button { padding: 5px 9px; min-height: 34px; border: 1px solid #d5d8dc; border-radius: 5px; background: white; color: #334155; font: inherit; cursor: pointer; }
.frame-actions button:disabled { opacity: .5; cursor: default; }
.frame-actions .render-button { margin-left: auto; color: #994126; }
.frame-preview img { max-height: 42dvh; object-fit: contain; background: #171a1b; border-radius: 4px; }
.frame-preview p { margin: 0; color: #902f2a; }
</style>
