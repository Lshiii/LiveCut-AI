<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { sha256 } from 'hash-wasm'
import type { VideoAsset } from './api'
import { hashFileInWorker } from './fileHash'
import { ResumableUploader, uploadErrorText, validateUploadFile, type UploadState } from './upload'

const props = defineProps<{ canStart: () => boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; started: []; complete: [video: VideoAsset] }>()
const input = ref<HTMLInputElement | null>(null), selected = ref<File | null>(null), localError = ref('')
const view = ref<UploadState>({ phase: 'idle', filename: '', size: 0, hashedBytes: 0, snapshot: null, resumedParts: 0, retryAttempt: 0, error: '', storageAvailable: true })
let stopped = false, lastBusy = false
let storage: Storage | null = null
try { storage = window.localStorage } catch { /* This page can still pause; refreshing may lose its receipt. */ }
const uploader = new ResumableUploader(value => {
  if (stopped) return
  view.value = value
  const next = uploader.busy
  if (next !== lastBusy) { lastBusy = next; emit('busy', next) }
}, { storage, hashFile: hashFileInWorker, hashPart: async blob => sha256(new Uint8Array(await blob.arrayBuffer())) })
const labels: Record<string, string> = { idle: '选择视频', hashing: '校验文件', checking: '核对续传', uploading: '上传分片', merging: '后台合并', paused: '已暂停', completed: '上传完成', error: '上传未完成', discarding: '清理上传' }
const busy = computed(() => ['hashing', 'checking', 'uploading', 'merging', 'discarding'].includes(view.value.phase))
const canContinue = computed(() => selected.value !== null && ['paused', 'error'].includes(view.value.phase) && view.value.snapshot?.status !== 'FAILED')
const percent = computed(() => Math.min(100, Math.round((view.value.snapshot?.received_bytes ?? view.value.hashedBytes) / Math.max(1, view.value.size) * 100)))
function bytes(value: number) { return value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KiB` : `${(value / 1024 / 1024).toFixed(1)} MiB` }
function chooseFile() { if (!busy.value && props.canStart()) input.value?.click() }
defineExpose({ chooseFile })
async function run(file: File) {
  if (!props.canStart() || stopped) return
  localError.value = ''
  try {
    validateUploadFile(file); selected.value = file; emit('started')
    const video = await uploader.start(file)
    if (!stopped && video) emit('complete', video)
  } catch (error) { if (!stopped) localError.value = uploadErrorText(error) }
}
function change(event: Event) {
  const element = event.target as HTMLInputElement, file = element.files?.[0]
  element.value = ''
  if (file) void run(file)
}
function resume() { if (selected.value) void run(selected.value) }
async function discard() {
  if (stopped || !props.canStart()) return
  localError.value = ''
  await uploader.discard()
  if (!stopped && view.value.phase === 'idle') selected.value = null
}
onBeforeUnmount(() => { stopped = true; uploader.destroy() })
</script>

<template>
  <input ref="input" class="visually-hidden" type="file" accept=".mp4,video/mp4" aria-label="上传 MP4 视频" @change="change">
  <p v-if="view.phase === 'idle' && !localError" class="upload-hint">支持分片上传与暂停。刷新后重新选择同一原文件，可核对已保存进度并继续。</p>
  <p v-if="localError" class="message error" role="alert">{{ localError }}</p>
  <section v-if="view.phase !== 'idle'" class="panel upload-panel" aria-label="视频上传进度">
    <div class="upload-panel-top"><div class="upload-file"><span class="eyebrow">VIDEO UPLOAD</span><h2>{{ view.filename }}</h2></div><span class="status" :class="view.phase === 'error' ? 'failed' : view.phase === 'completed' ? 'confirmed' : 'running'">{{ labels[view.phase] }}</span></div>
    <div class="upload-meter"><progress :value="percent" max="100" :aria-label="view.snapshot ? '已确认上传进度' : '文件校验进度'"></progress><b>{{ percent }}%</b></div>
    <div class="upload-facts"><span>{{ view.snapshot ? '服务端已确认' : '文件已校验' }} {{ bytes(view.snapshot?.received_bytes ?? view.hashedBytes) }} / {{ bytes(view.size) }}</span><span v-if="view.snapshot">{{ view.snapshot.received_parts.length }} / {{ view.snapshot.total_parts }} 个分片</span></div>
    <p v-if="view.phase === 'hashing'" class="upload-description">先核对原文件内容，避免把不同视频续接在同一份上传中。</p>
    <p v-else-if="view.phase === 'checking'" class="upload-description">正在查询已保存进度。网络异常时会保留续传记录。</p>
    <p v-else-if="view.phase === 'merging'" class="upload-description" role="status">分片已收齐，服务器正在校验并合并。完成后才可创建分析任务。</p>
    <p v-else-if="view.phase === 'completed'" class="upload-description" role="status">视频已保存。可以选择视频来源并创建分析任务。</p>
    <p v-else-if="view.phase === 'paused' && view.snapshot?.status === 'MERGING'" class="upload-description">已暂停等待；服务器仍会继续合并。点击继续可查询完成结果。</p>
    <p v-else-if="view.phase === 'paused'" class="upload-description">上传已暂停，已确认进度保留。继续时会重新核对原文件和服务端记录。</p>
    <p v-if="view.resumedParts > 0 && view.phase !== 'completed'" class="upload-description">已恢复 {{ view.resumedParts }} 个确认分片，本次只补传缺片。</p>
    <p v-if="view.retryAttempt" class="upload-description" role="status">本轮曾触发请求重试，每个请求最多重试 3 次。</p>
    <p v-if="!view.storageAvailable" class="upload-description upload-warning">浏览器未允许保存续传记录。本页仍可暂停和继续，刷新后可能需要重新上传。</p>
    <p v-if="view.error" class="upload-failure" role="alert">{{ view.error }}</p>
    <div class="upload-controls">
      <button v-if="busy && view.phase !== 'discarding'" class="secondary" @click="uploader.pause()">{{ view.phase === 'merging' ? '稍后查看' : '暂停上传' }}</button>
      <button v-if="canContinue" class="primary" @click="resume">{{ view.snapshot?.status === 'MERGING' ? '查询合并结果' : '继续上传' }}</button>
      <button v-if="!busy" class="secondary" @click="chooseFile">{{ view.phase === 'completed' ? '选择其他视频' : '重新选择文件' }}</button>
      <button v-if="!busy && view.phase !== 'completed'" class="text-button" @click="discard">放弃本次上传</button>
    </div>
  </section>
</template>
