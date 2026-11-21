<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { api } from './api'
import ManualClipPanel from './ManualClipPanel.vue'
import { SequencePreview, type SequencePreviewState } from './sequencePreview'
import { TranscriptSearchWorkspace, transcriptHighlights, transcriptQueryError, transcriptQuote, transcriptTime,
  type TranscriptOwner, type TranscriptSource, type TranscriptSearchState, type TranscriptUtterance } from './transcriptSearch'

const props = defineProps<{ jobId: string; videoId: string; generation: number; videoHash: string; videoUrl: string; videoName: string; active: boolean }>()
const componentId = useId()
const view = ref<'search' | 'manual'>('search'), handoffError = ref('')
const manualEditor = ref<{ dirty: boolean; pending: boolean; unresolved: boolean; message: string;
  summary: { title: string; start: TranscriptUtterance | null; end: TranscriptUtterance | null; hasRange: boolean; planId: string | null };
  setBoundary(field: 'start' | 'end', source: TranscriptSource, item: TranscriptUtterance): boolean } | null>(null)
defineExpose({ dirty: computed(() => !!manualEditor.value?.dirty), pending: computed(() => !!manualEditor.value?.pending) })
const state = ref<TranscriptSearchState>(), queryDraft = ref(''), pageVisible = ref(!document.hidden)
const player = ref<HTMLVideoElement | null>(null), playerRegion = ref<HTMLElement | null>(null)
const previewState = ref<SequencePreviewState | null>(null), playbackError = ref(''), preparingPlayback = ref(false)
const sequence = new SequencePreview(value => { previewState.value = value })
const owner = (): TranscriptOwner => ({ job_id: props.jobId, video_id: props.videoId, generation: props.generation, source_video_sha256: props.videoHash })
const createWorkspace = () => new TranscriptSearchWorkspace(owner(), { get: (path, signal) => api<unknown>(path, { signal }), changed: value => { state.value = value } })
let workspace = createWorkspace(), stopped = false, playbackVersion = 0
state.value = workspace.state
const currentState = computed(() => state.value!)
const loadedPage = computed(() => currentState.value.page)
const queryError = computed(() => transcriptQueryError(queryDraft.value))
const usable = computed(() => { void state.value; return workspace.usable() })
const selectedUsable = computed(() => { void state.value; return workspace.canUseSelection() })
const canGoBack = computed(() => { void state.value; return workspace.canGoBack() })
const contextUsable = computed(() => selectedUsable.value && !!currentState.value.context && !currentState.value.contextLoading && !currentState.value.contextError)
const available = computed(() => props.active && view.value === 'search' && pageVisible.value && !stopped)
const highlighted = computed(() => loadedPage.value?.items.map(item => ({ item, chunks: transcriptHighlights(item.text, loadedPage.value!.q) })) ?? [])
const sourceKey = computed(() => JSON.stringify([props.jobId, props.videoId, props.generation, props.videoHash, props.videoUrl, props.videoName]))
const playbackKey = computed(() => JSON.stringify([sourceKey.value, currentState.value.page?.source.source_fingerprint,
  currentState.value.page?.q, currentState.value.page?.offset, currentState.value.selected?.utterance_id]))
let playbackTimer: number | undefined
let preparedPlayback: { version: number; key: string; start: number; end: number | null } | null = null
function stopPlayback() {
  playbackVersion++; preparedPlayback = null; window.clearTimeout(playbackTimer); playbackTimer = undefined
  preparingPlayback.value = false; sequence.cancel(); player.value?.pause()
}
function playbackCurrent(value: NonNullable<typeof preparedPlayback>) {
  return available.value && selectedUsable.value && value.version === playbackVersion && value.key === playbackKey.value
}
function sourceMatches(element: HTMLVideoElement) {
  try { return element.currentSrc === new URL(props.videoUrl, document.baseURI).href } catch { return false }
}
async function finishPlayback() {
  const value = preparedPlayback, element = player.value
  if (!value || !element || !playbackCurrent(value) || element.readyState < 1 || !sourceMatches(element)) return
  preparedPlayback = null; window.clearTimeout(playbackTimer); playbackTimer = undefined; preparingPlayback.value = false
  try {
    if (value.end === null) { element.pause(); element.currentTime = value.start / 1000 }
    else {
      const outcome = await sequence.start(element, { id: value.key, parts: [{ start_ms: value.start, end_ms: value.end }] })
      if (outcome === 'failed' && playbackCurrent(value)) playbackError.value = '暂时无法连续预览，请检查原视频后重试。'
    }
  } catch { if (playbackCurrent(value)) playbackError.value = '暂时无法定位原视频，请稍后重试。' }
}
async function requestPlayback(preview: boolean) {
  if (!selectedUsable.value || preview && !contextUsable.value) return
  stopPlayback(); playbackError.value = ''
  const context = currentState.value.context, center = currentState.value.selected!
  const start = preview ? context!.previous?.start_ms ?? center.start_ms : center.start_ms
  const end = preview ? context!.next?.end_ms ?? center.end_ms : null
  const value = { version: playbackVersion, key: playbackKey.value, start, end }
  preparedPlayback = value; preparingPlayback.value = true
  playbackTimer = window.setTimeout(() => {
    if (preparedPlayback === value && value.version === playbackVersion) { stopPlayback(); playbackError.value = '原视频尚未准备好，请稍后重试定位。' }
  }, 15000)
  await nextTick()
  if (!playbackCurrent(value)) return
  playerRegion.value?.scrollIntoView({ block: 'nearest' })
  await finishPlayback()
}
function manualSeek(event: Event) {
  const element = event.currentTarget as HTMLVideoElement
  if (element !== player.value) return
  if (preparedPlayback) stopPlayback()
  else sequence.seeking(element)
}
function paused(event: Event) {
  const element = event.currentTarget as HTMLVideoElement
  if (element !== player.value) return
  if (preparedPlayback && element.paused) stopPlayback()
  else sequence.pause(element)
}
function guardPlayback(event: Event) {
  const element = event.currentTarget as HTMLVideoElement
  if (element !== player.value) return
  if (!available.value || !sourceMatches(element)) { element.pause(); return }
  if (preparedPlayback) {
    playbackVersion++; preparedPlayback = null; window.clearTimeout(playbackTimer); playbackTimer = undefined; preparingPlayback.value = false
  }
}
function metadataLoaded(event: Event) { if (event.currentTarget === player.value) void finishPlayback() }
function mediaTimeUpdate(event: Event) { if (event.currentTarget === player.value) sequence.timeUpdate(event.currentTarget as HTMLVideoElement) }
function mediaSeeked(event: Event) { if (event.currentTarget === player.value) sequence.seeked(event.currentTarget as HTMLVideoElement) }
function mediaEnded(event: Event) { if (event.currentTarget === player.value) sequence.ended(event.currentTarget as HTMLVideoElement) }
function mediaRateChange(event: Event) { if (event.currentTarget === player.value) sequence.rateChange(event.currentTarget as HTMLVideoElement) }
function mediaError(event: Event) {
  if (event.currentTarget !== player.value) return
  stopPlayback(); playbackError.value = '原视频暂时无法播放，请稍后重试。'
}
function choose(item: TranscriptUtterance) { stopPlayback(); playbackError.value = ''; void workspace.select(item.utterance_id) }
function search() { if (!available.value || queryError.value) return; stopPlayback(); playbackError.value = ''; void workspace.search(queryDraft.value) }
function pageAction(action: 'next' | 'previous' | 'retry' | 'reloadSource') { stopPlayback(); playbackError.value = ''; void workspace[action]() }
function copy() {
  void workspace.copySelected(props.videoName, value => {
    if (!navigator.clipboard?.writeText) return Promise.reject(new Error('CLIPBOARD_UNAVAILABLE'))
    return navigator.clipboard.writeText(value)
  })
}
function setManualBoundary(field: 'start' | 'end') {
  if (!selectedUsable.value || !currentState.value.page || !currentState.value.selected) return
  stopPlayback(); handoffError.value = ''
  if (!manualEditor.value?.setBoundary(field, currentState.value.page.source, currentState.value.selected)) handoffError.value = manualEditor.value?.message || '人工片段暂不能接收原句，当前选择与人工草稿均保留。'
}
function viewKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault(); view.value = event.key === 'Home' ? 'search' : event.key === 'End' ? 'manual' : view.value === 'search' ? 'manual' : 'search'
  void nextTick(() => document.getElementById(`${componentId}-${view.value}-tab`)?.focus())
}
function syncAvailability() {
  pageVisible.value = !document.hidden
  if (!props.active || view.value !== 'search' || document.hidden) stopPlayback()
  workspace.setAvailability(props.active && view.value === 'search', !document.hidden)
}
watch(sourceKey, () => {
  stopPlayback(); workspace.dispose(); workspace = createWorkspace(); state.value = workspace.state; queryDraft.value = ''; playbackError.value = ''
  syncAvailability()
}, { flush: 'sync' })
watch(() => props.active, syncAvailability, { immediate: true, flush: 'sync' })
watch(view, syncAvailability, { flush: 'sync' })
watch(playbackKey, stopPlayback, { flush: 'sync' })
watch([selectedUsable, available], ([selection, active]) => { if (!selection || !active) stopPlayback() }, { flush: 'sync' })
onMounted(() => document.addEventListener('visibilitychange', syncAvailability))
onBeforeUnmount(() => { stopped = true; stopPlayback(); workspace.dispose(); document.removeEventListener('visibilitychange', syncAvailability) })
</script>

<template>
  <section class="panel transcript-search" aria-label="全文原句搜索与回看">
    <div class="transcript-view-tabs" role="tablist" aria-label="原句与人工剪辑视图" @keydown="viewKey"><button :id="`${componentId}-search-tab`" role="tab" :aria-controls="`${componentId}-search-view`" :aria-selected="view === 'search'" :tabindex="view === 'search' ? 0 : -1" @click="view = 'search'">搜索与回看</button><button :id="`${componentId}-manual-tab`" role="tab" :aria-controls="`${componentId}-manual-view`" :aria-selected="view === 'manual'" :tabindex="view === 'manual' ? 0 : -1" @click="view = 'manual'">人工剪辑<span v-if="manualEditor?.dirty" class="draft-dot" aria-label="人工片段有未提交内容"></span></button></div>
    <div v-show="view === 'search'" :id="`${componentId}-search-view`" role="tabpanel" :aria-labelledby="`${componentId}-search-tab`">
    <header class="transcript-heading"><div><h2>原句搜索</h2><p>从已完成转写中直接找文字或时间，无需请求 AI。ASR 原句尚未经人工校对。</p></div><span>当前视频 · {{ videoName }}</span></header>
    <form class="transcript-search-form" @submit.prevent="search"><label :for="`${componentId}-query`">搜索原句或时间<input :id="`${componentId}-query`" v-model="queryDraft" type="search" autocomplete="off" maxlength="200" placeholder="例如 缓存 过期，或 02:30" :disabled="!available" :aria-describedby="`${componentId}-help`"></label><button class="primary" type="submit" :disabled="!available || !!queryError">搜索原句</button></form>
    <p :id="`${componentId}-help`" class="transcript-help">空格分隔的词需同时出现在同一句，匹配原文和显示时间；留空可分页浏览。输入后点击搜索，翻页沿用已提交搜索词。</p>
    <p v-if="queryError" class="transcript-error" role="status">{{ queryError }}</p>
    <p v-if="currentState.pageLoading" class="transcript-read-status" role="status">正在读取原句 · 查询“{{ currentState.requested?.q || '全部原句' }}”…</p>
    <div v-if="currentState.pageError" class="transcript-read-error" role="alert"><p>{{ currentState.pageError }}</p><p v-if="currentState.page">下方仍为上次成功结果，查询“{{ currentState.page.q || '全部原句' }}”，第 {{ currentState.page.offset + 1 }} 项起；本次请求尚未替换它。</p><button class="secondary" :disabled="!available || currentState.pageLoading" @click="pageAction('retry')">重试读取</button><button v-if="currentState.sourceStale" class="secondary" :disabled="!available || currentState.pageLoading" @click="pageAction('reloadSource')">重新核对来源</button></div>
    <p v-if="currentState.page" class="transcript-results-summary">已读取结果：{{ currentState.page.q ? `“${currentState.page.q}”` : '全部原句' }} · 命中 {{ currentState.page.matched_utterances }} / 全部 {{ currentState.page.total_utterances }} 句 · {{ currentState.page.items.length ? `${currentState.page.offset + 1}–${currentState.page.offset + currentState.page.items.length}` : '本页 0' }} 项</p>
    <div class="transcript-layout">
      <div class="transcript-results"><p v-if="currentState.page && !currentState.page.items.length" class="transcript-empty">没有匹配原句。可换个关键词、搜索时间，或清空后搜索。</p><p v-else-if="!currentState.page && !currentState.pageLoading && !currentState.pageError" class="transcript-empty">打开当前工作区后读取原句；其他页面的草稿继续保留。</p>
        <ol aria-label="原句搜索结果"><li v-for="({ item, chunks }, index) in highlighted" :key="item.utterance_id"><button type="button" class="transcript-result" :class="{ selected: currentState.selected?.utterance_id === item.utterance_id }" :disabled="!usable" :aria-label="`选择原句 ${currentState.page!.offset + index + 1} ${transcriptTime(item.start_ms)}`" :aria-pressed="currentState.selected?.utterance_id === item.utterance_id" @click="choose(item)"><span class="transcript-result-time">{{ transcriptTime(item.start_ms) }} → {{ transcriptTime(item.end_ms) }}</span><span class="transcript-result-text"><template v-for="(chunk, chunkIndex) in chunks" :key="chunkIndex"><mark v-if="chunk.hit">{{ chunk.text }}</mark><template v-else>{{ chunk.text }}</template></template></span></button></li></ol>
        <nav v-if="currentState.page" class="transcript-pagination" aria-label="原句结果分页"><button class="secondary" :disabled="!canGoBack" @click="pageAction('previous')">上一页</button><span>每次最多 20 句；长原句可能减少本页条数。</span><button class="secondary" :disabled="!usable || currentState.page.next_offset === null" @click="pageAction('next')">下一页</button></nav>
      </div>
      <aside ref="playerRegion" class="transcript-reference" aria-label="原句上下文与原视频">
        <h3>原视频回看</h3><video ref="player" :key="sourceKey" :src="available ? videoUrl : undefined" controls preload="metadata" playsinline @loadedmetadata="metadataLoaded" @timeupdate="mediaTimeUpdate" @playing="mediaTimeUpdate" @seeking="manualSeek" @seeked="mediaSeeked" @pause="paused" @ended="mediaEnded" @ratechange="mediaRateChange" @play="guardPlayback" @error="mediaError"></video>
        <div class="transcript-playback-actions"><button class="secondary" :disabled="!selectedUsable" @click="requestPlayback(false)">定位所选原句</button><button class="secondary" :disabled="!contextUsable || preparingPlayback" @click="previewState ? stopPlayback() : requestPlayback(true)">{{ previewState ? '停止上下文预览' : '连续预览前后文' }}</button><button class="text-button" :disabled="!selectedUsable || currentState.copyBusy" @click="copy">{{ currentState.copyBusy ? '正在复制…' : '复制所选原句引用' }}</button></div>
        <section class="transcript-manual-handoff" aria-label="原句人工范围草稿"><div><button type="button" class="secondary" :disabled="!selectedUsable || !!manualEditor?.pending || !!manualEditor?.unresolved" @click="setManualBoundary('start')">设为人工起句</button><button type="button" class="secondary" :disabled="!selectedUsable || !!manualEditor?.pending || !!manualEditor?.unresolved" @click="setManualBoundary('end')">设为人工止句</button><button type="button" class="text-button" @click="view = 'manual'">查看人工片段</button></div><p v-if="manualEditor?.summary.hasRange">已保留起句 {{ manualEditor.summary.start ? transcriptTime(manualEditor.summary.start.start_ms, true) : '未选择' }} / 止句 {{ manualEditor.summary.end ? transcriptTime(manualEditor.summary.end.end_ms, true) : '未选择' }}。跨页与换词不清范围；尚未保存。</p><p v-else>明确设起止句后，在人工剪辑中核对完整范围、保存、确认并生成 MP4；不请求 AI。</p><p v-if="handoffError" class="transcript-error" role="alert">{{ handoffError }}</p></section>
        <p v-if="preparingPlayback" class="transcript-help" role="status">正在准备原视频定位…</p><p v-if="previewState" class="transcript-help" role="status">连续预览 {{ transcriptTime(previewState.start_ms) }} → {{ transcriptTime(previewState.end_ms) }}；手动操作将停止本次预览。</p><p v-if="playbackError" class="transcript-error" role="status">{{ playbackError }}</p>
        <p v-if="currentState.copyNotice" class="transcript-help" role="status">{{ currentState.copyNotice }}</p><div v-if="currentState.copyError" class="transcript-error" role="status"><p>{{ currentState.copyError }}</p><textarea v-if="currentState.selected" readonly aria-label="所选原句引用文本" :value="transcriptQuote(videoName, currentState.selected)"></textarea></div>
        <p v-if="!currentState.selected" class="transcript-help">选择一条原句查看前后各一句；选择不会自动播放或改动剪辑。</p>
        <p v-if="currentState.contextLoading" class="transcript-help" role="status">正在核对所选原句的前后文…</p><div v-if="currentState.contextError" class="transcript-error" role="alert"><p>{{ currentState.contextError }}</p><button class="secondary" :disabled="!selectedUsable || currentState.contextLoading" @click="workspace.retryContext()">重试前后文</button><button v-if="currentState.sourceStale" class="secondary" :disabled="!available || currentState.pageLoading" @click="pageAction('reloadSource')">重新核对来源</button></div>
        <div v-if="currentState.context && currentState.selected?.utterance_id === currentState.context.center.utterance_id" class="transcript-context"><div v-for="(item, index) in [currentState.context.previous, currentState.context.center, currentState.context.next]" :key="index" :class="{ center: index === 1 }"><strong>{{ index === 0 ? '前一句' : index === 1 ? '所选原句' : '后一句' }}<template v-if="item"> · {{ transcriptTime(item.start_ms, true) }} → {{ transcriptTime(item.end_ms, true) }}</template></strong><p>{{ item?.text ?? (index === 0 ? '已是第一句。' : '已是最后一句。') }}</p></div></div>
      </aside>
    </div>
    </div>
    <div v-show="view === 'manual'" :id="`${componentId}-manual-view`" role="tabpanel" :aria-labelledby="`${componentId}-manual-tab`"><ManualClipPanel ref="manualEditor" :job-id="jobId" :video-id="videoId" :generation="generation" :video-hash="videoHash" :video-url="videoUrl" :video-name="videoName" :active="props.active && view === 'manual'" /></div>
  </section>
</template>

<style scoped>
.transcript-search { min-width: 0; padding: 24px; color: var(--workspace-text, #292621); }
.transcript-view-tabs { display: flex; flex-wrap: wrap; gap: 8px; border-bottom: 1px solid var(--workspace-border, #e4e0d8); padding-bottom: 12px; margin-bottom: 20px; }
.transcript-view-tabs button { min-height: 36px; padding: 8px 12px; border-radius: 8px; background: var(--workspace-canvas, #f4f3ef); color: var(--workspace-muted, #675f56); font-size: 13px; }
.transcript-view-tabs button[aria-selected=true] { background: var(--workspace-accent-soft, #f7ebe5); color: var(--workspace-accent, #a94424); }
.transcript-search button:focus-visible, .transcript-search input:focus-visible, .transcript-search textarea:focus-visible, .transcript-search summary:focus-visible, .transcript-search a:focus-visible { outline: 2px solid var(--workspace-accent, #a94424); outline-offset: 3px; }
.transcript-manual-handoff { border: 1px solid var(--workspace-border, #e4e0d8); background: var(--workspace-accent-soft, #f7ebe5); border-radius: 8px; padding: 12px; margin: 12px 0; }
.transcript-manual-handoff > div { display: flex; flex-wrap: wrap; gap: 8px; }
.transcript-manual-handoff button { max-width: 100%; font-size: 12px; white-space: normal; }
.transcript-manual-handoff p { font-size: 12px; line-height: 1.8; color: var(--workspace-muted, #675f56); margin-top: 8px; overflow-wrap: anywhere; }
.transcript-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; min-width: 0; }
.transcript-heading h2 { font-size: 18px; font-weight: 500; margin: 0 0 8px; }
.transcript-heading p, .transcript-heading > span { font-size: 13px; line-height: 1.7; color: var(--workspace-muted, #675f56); }
.transcript-heading > span { max-width: 38%; overflow-wrap: anywhere; }
.transcript-search-form { display: flex; align-items: flex-end; gap: 12px; margin-top: 20px; }
.transcript-search-form label { flex: 1; min-width: 0; font-size: 13px; color: var(--workspace-muted, #675f56); }
.transcript-search-form input { display: block; width: 100%; margin-top: 8px; padding: 12px; border: 1px solid var(--workspace-border, #e4e0d8); border-radius: 8px; background: var(--workspace-surface, #fff); color: var(--workspace-text, #292621); font: inherit; font-size: 14px; }
.transcript-search-form button { flex: none; min-height: 44px; }
.transcript-help, .transcript-results-summary, .transcript-read-status, .transcript-read-error { font-size: 13px; line-height: 1.8; margin-top: 8px; color: var(--workspace-muted, #675f56); overflow-wrap: anywhere; }
.transcript-read-error { padding: 12px; background: color-mix(in srgb, var(--studio-warning) 12%, var(--workspace-surface)); border-radius: 8px; }
.transcript-read-error button { margin: 8px 8px 0 0; }
.transcript-error { font-size: 13px; line-height: 1.8; color: var(--studio-danger); margin: 8px 0; overflow-wrap: anywhere; }
.transcript-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(300px, .9fr); align-items: start; gap: 20px; margin-top: 16px; }
.transcript-results { min-width: 0; }
.transcript-results ol { list-style: none; padding: 0; margin: 0; }
.transcript-result { width: 100%; display: block; text-align: left; border: 1px solid var(--workspace-border, #e4e0d8); border-radius: 8px; padding: 12px; margin-bottom: 12px; background: var(--workspace-surface, #fff); color: var(--workspace-text, #292621); }
.transcript-result:hover:not(:disabled) { background: var(--workspace-canvas, #f4f3ef); }
.transcript-result.selected, .transcript-result.selected:hover { border-color: var(--workspace-accent, #a94424); background: var(--workspace-accent-soft, #f7ebe5); }
.transcript-result-time { display: block; font-size: 12px; color: var(--workspace-muted, #675f56); margin-bottom: 8px; }
.transcript-result-text { display: block; font-size: 14px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.transcript-result mark { background: var(--studio-warning); color: var(--studio-ink); border-radius: 2px; }
.transcript-pagination { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; justify-content: space-between; padding-top: 8px; }
.transcript-pagination span { font-size: 12px; color: var(--workspace-muted, #675f56); flex: 1; min-width: 110px; }
.transcript-reference { min-width: 0; position: sticky; top: 12px; padding: 16px; background: var(--workspace-canvas, #f4f3ef); border: 1px solid var(--workspace-border, #e4e0d8); border-radius: 12px; }
.transcript-reference h3 { font-size: 14px; font-weight: 600; margin: 0 0 12px; }
.transcript-reference video { display: block; width: 100%; max-height: 330px; background: var(--workspace-video, #211f1b); border-radius: 8px; }
.transcript-playback-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; }
.transcript-playback-actions button { max-width: 100%; min-height: 36px; font-size: 12px; line-height: 1.6; white-space: normal; }
.transcript-context { margin-top: 16px; }
.transcript-context > div { padding: 12px; border-left: 2px solid var(--workspace-border, #e4e0d8); font-size: 13px; line-height: 1.8; }
.transcript-context > div.center { background: var(--workspace-accent-soft, #f7ebe5); border-color: var(--workspace-accent, #a94424); }
.transcript-context strong { font-size: 12px; font-weight: 400; color: var(--workspace-muted, #675f56); }
.transcript-result-time, .transcript-context strong { font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace; font-variant-numeric: tabular-nums; }
.transcript-context p { margin: 8px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.transcript-error textarea { display: block; width: 100%; min-height: 130px; margin-top: 8px; padding: 12px; resize: vertical; font-size: 13px; }
.transcript-empty { font-size: 13px; padding: 24px 12px; color: var(--workspace-muted, #675f56); line-height: 1.8; }
@media (max-width: 1050px) {
  .transcript-layout { grid-template-columns: minmax(0, 1fr); }
  .transcript-reference { position: static; grid-row: 1; }
  .transcript-reference video { max-height: 300px; }
  .transcript-heading > span { max-width: 45%; }
}
@media (max-width: 600px) {
  .transcript-search { padding: 16px; }
  .transcript-heading { display: block; }
  .transcript-heading > span { display: block; max-width: 100%; margin-top: 8px; }
  .transcript-search-form { align-items: stretch; flex-direction: column; gap: 8px; margin-top: 16px; }
  .transcript-search-form button { align-self: flex-start; min-height: 36px; }
  .transcript-reference { padding: 12px; }
  .transcript-pagination { justify-content: flex-start; }
  .transcript-pagination span { flex-basis: 100%; order: 3; }
  .transcript-layout { gap: 16px; }
  .transcript-result { padding: 12px; }
}
</style>
