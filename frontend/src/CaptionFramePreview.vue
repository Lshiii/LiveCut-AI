<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { CaptionSnapshot } from './captions'
import { captionRenderBase, captionRenderInput, type CaptionRenderFrame, type CaptionRenderStyle } from './captionRenders'
import { topicTime } from './topics'

const props = defineProps<{ caption: CaptionSnapshot; stylePreset: CaptionRenderStyle; frame: CaptionRenderFrame; enabled: boolean }>()
const maxMs = computed(() => props.caption.source.media_duration_ms - 1)
const position = ref(0), imageUrl = ref(''), busy = ref(false), error = ref('')
let request: AbortController | null = null

function clear() {
  request?.abort()
  request = null
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''; busy.value = false; error.value = ''
}
watch(() => props.caption, value => {
  const cue = value.cues[0]
  position.value = cue ? Math.min(maxMs.value, Math.floor((cue.start_ms + cue.end_ms) / 2)) : 0
  clear()
}, { immediate: true, flush: 'sync' })
watch([position, () => props.stylePreset, () => props.frame, () => props.enabled], clear, { flush: 'sync' })
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
  if (!props.enabled || busy.value) return
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
    <label>成片时间 <output>{{ topicTime(position) }}</output>
      <input v-model.number="position" type="range" min="0" :max="maxMs" step="1" aria-label="预览成片时间" :disabled="!enabled">
    </label>
    <button type="button" class="render-button" :disabled="!enabled || busy" @click="preview">{{ busy ? '正在渲染预览帧…' : '预览这一帧' }}</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <img v-if="imageUrl" :src="imageUrl" :alt="`成片 ${topicTime(position)} 的字幕和画幅预览`">
  </section>
</template>

<style scoped>
.frame-preview { display: grid; gap: .4rem; }
.frame-preview label { display: grid; grid-template-columns: 1fr auto; gap: .25rem; }
.frame-preview input, .frame-preview img { grid-column: 1 / -1; width: 100%; }
.frame-preview button { justify-self: start; }
.frame-preview img { max-height: 42dvh; object-fit: contain; background: #171a1b; }
.frame-preview p { margin: 0; color: #902f2a; }
</style>
