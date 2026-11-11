<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, type ExportJob } from './api'
import { AgentReadScope } from './videoAgent'
import { prepareVideoPackage, videoPackageBytes, videoPackageClipSelectable, videoPackageErrorCopy, videoPackageSelection,
  type PreparedVideoPackage } from './exportVideoPackage'
const props = defineProps<{ batch: ExportJob; sourceKey: string; active: boolean; busy?: boolean }>()
const selectedIds = ref<string[]>([]), prepared = ref<PreparedVideoPackage | null>(null), checking = ref(false), error = ref(''), notice = ref('')
const pageVisible = ref(!document.hidden), scope = new AgentReadScope()
let alive = true, version = 0
const selectable = computed(() => props.batch.clips.filter(clip => videoPackageClipSelectable(props.batch.id, clip)))
const invalidIds = computed(() => selectedIds.value.filter(id => !selectable.value.some(clip => clip.id === id)))
const selectedBytes = computed(() => props.batch.clips.filter(clip => selectedIds.value.includes(clip.id)).reduce((size, clip) => size + (clip.size_bytes ?? 0), 0))
const excludedFailed = computed(() => props.batch.clips.filter(clip => clip.status === 'FAILED' && !selectedIds.value.includes(clip.id)).length)
const excludedUnfinished = computed(() => props.batch.clips.filter(clip => clip.status !== 'SUCCEEDED' && clip.status !== 'FAILED' && !selectedIds.value.includes(clip.id)).length)
const selectionSignature = computed(() => {
  try { return videoPackageSelection(props.batch, selectedIds.value).signature }
  catch { return JSON.stringify(['INVALID', props.batch.id, selectedIds.value, props.batch.clips.map(clip => [clip.id, clip.status, clip.size_bytes])]) }
})
const currentKey = computed(() => JSON.stringify([props.sourceKey, selectionSignature.value]))
const available = () => alive && props.active && !props.busy && !document.hidden
const disabled = computed(() => !props.active || !pageVisible.value || !!props.busy)
function invalidate() { version++; scope.invalidate(); prepared.value = null; checking.value = false; error.value = ''; notice.value = '' }
function chooseAll() { if (available()) selectedIds.value = selectable.value.map(clip => clip.id) }
function clear() { if (available()) selectedIds.value = [] }
function choose(id: string, event: Event) {
  if (!available()) return
  const checked = (event.target as HTMLInputElement).checked
  if (checked && !selectable.value.some(clip => clip.id === id)) return
  selectedIds.value = checked ? [...new Set([...selectedIds.value, id])] : selectedIds.value.filter(value => value !== id)
}
async function prepare() {
  if (!available() || checking.value) return
  invalidate(); const operation = version, key = currentKey.value, read = scope.begin()
  const current = () => available() && read.current() && operation === version && key === currentKey.value
  checking.value = true
  try {
    const value = await prepareVideoPackage(props.batch, [...selectedIds.value], { signal: read.signal, current,
      get: (path, signal) => api<unknown>(path, { signal }) })
    if (current()) { prepared.value = value; notice.value = '所选成片的登记信息已核对。点击下载后，服务端将校验实际文件并构建 ZIP。' }
  } catch (value) { if (current()) error.value = videoPackageErrorCopy(value) }
  finally { read.release(); if (operation === version) checking.value = false }
}
function guardDownload(event: Event) {
  let matches = false
  try { matches = !!prepared.value && videoPackageSelection(props.batch, selectedIds.value, true).signature === prepared.value.signature }
  catch { /* Invalid selection cannot launch a native download. */ }
  if (!available() || !matches) { event.preventDefault(); invalidate() }
}
function visibilityChanged() { pageVisible.value = !document.hidden; if (document.hidden) invalidate() }
watch(currentKey, invalidate, { flush: 'sync' })
watch([() => props.sourceKey, () => props.batch.id], () => { selectedIds.value = []; invalidate() }, { flush: 'sync' })
watch([() => props.active, () => props.busy], () => { if (!props.active || props.busy) invalidate() }, { flush: 'sync' })
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onBeforeUnmount(() => { alive = false; invalidate(); scope.dispose(); document.removeEventListener('visibilitychange', visibilityChanged) })
</script>

<template>
  <section class="video-package-panel" aria-label="仅 MP4 批量下载">
    <h4>仅 MP4 ZIP</h4><p class="video-package-help">只交付勾选的已完成视频，无需校对或确认字幕。原视频自带的硬字幕仍会保留。</p>
    <details class="video-package-selector"><summary>选择成片 · 已选 {{ selectedIds.length }} / {{ batch.clips.length }}</summary>
      <div class="video-package-selection-actions"><button type="button" class="text-button" :disabled="disabled" @click="chooseAll">全选已完成</button><button type="button" class="text-button" :disabled="disabled" @click="clear">清空</button></div>
      <div class="video-package-choices"><label v-for="clip in batch.clips" :key="clip.id"><input type="checkbox" :aria-label="`选择下载成片 ${clip.id}`" :checked="selectedIds.includes(clip.id)" :disabled="disabled || (!videoPackageClipSelectable(batch.id, clip) && !selectedIds.includes(clip.id))" @change="choose(clip.id, $event)"><span>{{ clip.title || clip.product_name || '视频成片' }}<small>{{ clip.status === 'SUCCEEDED' ? '已完成' : clip.status === 'FAILED' ? '导出失败' : '未完成' }} · {{ videoPackageBytes(clip.size_bytes ?? 0) }}</small></span></label></div>
    </details>
    <p class="video-package-count">已选 {{ selectedIds.length }} 个 · {{ videoPackageBytes(selectedBytes) }}；未纳入失败 {{ excludedFailed }} 个、其他未完成 {{ excludedUnfinished }} 个。</p>
    <p v-if="invalidIds.length" class="video-package-error" role="status">已选的 {{ invalidIds.length }} 个成片已不可用或不在当前清单，请明确取消这些选择或清空后重选；不会自动漏片。</p>
    <p v-if="selectedIds.length > 100" class="video-package-error" role="status">每包最多 100 个，请分批选择；未截断当前选择。</p>
    <div class="video-package-actions"><button type="button" class="secondary" :disabled="disabled || checking || !selectedIds.length || selectedIds.length > 100 || !!invalidIds.length" @click="prepare">{{ checking ? '正在核对所选成片…' : '核对所选成片' }}</button><a v-if="prepared" class="text-button" :href="prepared.url" download target="_blank" rel="noopener" @click="guardDownload">下载所选MP4 ZIP</a></div>
    <p v-if="error" class="video-package-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped>
.video-package-panel{margin:12px 0;padding:12px;border:1px solid #dbe4ed;border-radius:8px;background:#f8fbfd;min-width:0;font-size:12px;line-height:1.7;color:#475569;overflow-wrap:anywhere}.video-package-panel h4{margin:0 0 5px;font-size:13px;color:#334155}.video-package-panel p{margin:6px 0;font-size:12px;line-height:1.7}.video-package-help{color:#64748b}.video-package-selector{margin:9px 0}.video-package-selector summary{cursor:pointer;min-height:28px}.video-package-selection-actions,.video-package-actions{display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px}.video-package-panel button,.video-package-panel a{max-width:100%;font-size:12px;line-height:1.6}.video-package-choices{max-height:240px;overflow:auto;overscroll-behavior:contain}.video-package-choices label{display:flex;align-items:flex-start;gap:8px;padding:7px 0;border-bottom:1px solid #e8eef3;min-width:0}.video-package-choices input{flex:none;margin-top:5px}.video-package-choices span{min-width:0;overflow-wrap:anywhere}.video-package-choices small{display:block;font-size:11px;color:#64748b}.video-package-error{color:#985c32}.video-package-actions{margin-top:9px}
</style>
