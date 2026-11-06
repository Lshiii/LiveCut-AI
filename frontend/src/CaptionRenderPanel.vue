<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post } from './api'
import { captionBase, parseCaptionSnapshot, sameCaptionSource, type CaptionSnapshot } from './captions'
import { captionRenderActive, captionRenderBase, captionRenderFrames, captionRenderInput, captionRenderPath,
  captionRenderProgressCopy, createCaptionRenderReceiptStore, captionRenderStyles, newestCaptionRender, parseCaptionRender,
  parseCaptionRenderList, CaptionRenderResponseError, CaptionRenderReceiptStorageError, type CaptionRenderFrame,
  type CaptionRenderSnapshot, type CaptionRenderStyle } from './captionRenders'
import FailureDiagnostic from './FailureDiagnostic.vue'

const props = withDefaults(defineProps<{ snapshot: CaptionSnapshot; captionDirty: boolean; captionPending: boolean; active?: boolean }>(), { active: true })
const rows = ref<CaptionRenderSnapshot[]>([]), selectedId = ref(''), action = ref('')
const error = ref(''), refreshError = ref(''), notice = ref(''), loading = ref(true)
const revision = ref<number | null>(null), confirmedCaption = ref<CaptionSnapshot | null>(null), revisionBusy = ref(false)
const style = ref<CaptionRenderStyle>('WHITE_OUTLINE_V1'), frame = ref<CaptionRenderFrame>('SOURCE')
const player = ref<HTMLVideoElement | null>(null), previewBox = ref<HTMLElement | null>(null)
const previewDismissed = ref(false), previewMessage = ref('')
const previewEnlarged = ref(false), enlargeButton = ref<HTMLButtonElement | null>(null)
const revisionCache = new Map<number, CaptionSnapshot>(), controllers = new Set<AbortController>()
const pending = computed(() => !!action.value)
const source = computed(() => ({ export_id: props.snapshot.export_id, clip_id: props.snapshot.clip_id, clip_sha256: props.snapshot.source.clip_sha256 }))
const selected = computed(() => rows.value.find(value => value.id === selectedId.value))
const confirmedRevisions = computed(() => [...props.snapshot.confirmed_revisions].sort((a, b) => b - a))
const matchingActive = computed(() => rows.value.find(value => captionRenderActive(value) && value.caption_revision === revision.value
  && value.caption_content_hash === confirmedCaption.value?.content_hash && value.style_preset === style.value && value.frame_preset === frame.value))
const canCreate = computed(() => !pending.value && !props.captionPending && !props.captionDirty && !revisionBusy.value
  && !!confirmedCaption.value?.cues.length && !matchingActive.value)
const statusNames = { QUEUED: '等待执行', RUNNING: '正在生成', SUCCEEDED: '已完成', FAILED: '生成失败', CANCELLED: '已取消' }
const now = ref(Date.now())
let stopped = false, listBusy = false, epoch = 0, revisionEpoch = 0, timer: number | undefined, clock: number | undefined
const receipts = createCaptionRenderReceiptStore(`livecut-caption-render-create-${props.snapshot.export_id}-${props.snapshot.clip_id}`, {
  getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value), removeItem: key => localStorage.removeItem(key),
}, () => crypto.randomUUID())
let fullscreenRequestVersion = 0
defineExpose({ pending })

function message(value: unknown) {
  const raw = value instanceof ApiError ? value.code : value instanceof CaptionRenderResponseError ? 'INVALID_CAPTION_RENDER_RESPONSE'
    : value instanceof CaptionRenderReceiptStorageError ? 'CAPTION_RENDER_RECEIPT_UNAVAILABLE' : 'CLIENT_ERROR'
  const code = /^[A-Z][A-Z0-9_]{0,99}$/.test(raw) ? raw : 'CLIENT_ERROR'
  const messages: Record<string, string> = {
    NETWORK_UNAVAILABLE: '暂时无法连接服务，字幕修改与已有成片仍保留。',
    REQUEST_TIMEOUT: '请求超时。生成按钮可再次点击，系统会复用同一请求标识；取消或重试请先刷新状态再操作。',
    CAPTION_RENDER_INVALID_INPUT: '生成配置无效，请重新核对字幕版本、样式和画幅。',
    CAPTION_RENDER_EMPTY: '所选字幕版本没有字幕，请先校对并确认有字幕的版本。',
    CAPTION_RENDER_REQUIRES_CONFIRMATION: '所选字幕版本尚未确认，请回到字幕校对页确认后再生成。',
    CAPTION_RENDER_REVISION_MISMATCH: '所选字幕版本与服务端不一致，请重新读取该版本后再生成。',
    CAPTION_RENDER_SOURCE_UNAVAILABLE: '原片或已确认字幕暂不可用，请检查素材完整性后再生成。',
    CAPTION_RENDER_FONT_UNAVAILABLE: '字幕字体尚未准备好，请检查执行环境后再试。',
    OUTPUT_WRITE_FAILED: '成片文件写入失败，已保存的生成配置仍保留。可点击“重试此任务”继续。',
    CAPTION_RENDER_ENCODE_FAILED: '视频编码未完成，可点击“重试此任务”，继续使用已保存的生成配置。',
    EXPORT_DECODE_FAILED: '成片完整性校验未通过，尚未开放下载。可点击“重试此任务”重新生成。',
    CAPTION_RENDER_NOT_FOUND: '没有找到这条生成记录，请刷新历史。',
    CAPTION_RENDER_NOT_CANCELLABLE: '此任务已不能取消，请刷新查看最新结果。',
    CAPTION_RENDER_NOT_RETRYABLE: '此任务当前不能重试，请刷新状态并核对失败原因。',
    CAPTION_RENDER_TIMING_UNSUPPORTED: '有字幕时间短于烧录支持的时间精度，请回到校对页调整后保存、确认新版本。',
    CAPTION_RENDER_GLYPH_UNSUPPORTED: '字幕含有当前字体不支持的字符，请在校对页改用受支持文字，保存并确认新版本后生成。',
    CAPTION_RENDER_TEXT_TOO_LARGE: '字幕超出所选画幅的可读区域。可改用更宽的画幅，或缩短过长字幕、保存并确认新版后生成。',
    ATTEMPTS_EXHAUSTED: '这条任务已达到重试上限。请检查失败原因后重新生成。',
    IDEMPOTENCY_CONFLICT: '请求标识与配置不一致，已有记录仍保留，请刷新核对。',
    CAPTION_RENDER_RECEIPT_UNAVAILABLE: '浏览器暂时无法保存生成请求，尚未提交新任务。请恢复本网站的存储权限或释放浏览器空间后再试，已有任务仍保留。',
    INVALID_CAPTION_RENDER_RESPONSE: '返回的成片来源、版本或文件信息不符合约定，已有内容仍保留。',
  }
  return messages[code] ?? `带字幕视频操作未完成：${code}`
}
function read() {
  const controller = new AbortController(), version = epoch
  controllers.add(controller)
  return { signal: controller.signal, current: () => !stopped && version === epoch && !controller.signal.aborted,
    release: () => controllers.delete(controller) }
}
function merge(value: CaptionRenderSnapshot) {
  const known = rows.value.find(row => row.id === value.id)
  const latest = newestCaptionRender(known, value)
  rows.value = [...rows.value.filter(row => row.id !== latest.id), latest].sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at)).slice(0, 50)
  return latest
}
async function refresh() {
  if (listBusy || stopped) return
  listBusy = true
  const request = read(), identity = source.value
  try {
    const raw = await api<unknown>(captionRenderBase(identity), { signal: request.signal })
    if (!request.current()) return
    for (const value of parseCaptionRenderList(raw, identity)) merge(value)
    if (selectedId.value && !rows.value.some(value => value.id === selectedId.value)) selectedId.value = ''
    if (!selectedId.value && !previewDismissed.value && rows.value[0]) selectedId.value = rows.value[0].id
    refreshError.value = ''
  } catch (value) { if (request.current()) refreshError.value = message(value) }
  finally { request.release(); listBusy = false; loading.value = false }
}
async function poll() { await refresh(); if (!stopped) timer = window.setTimeout(() => { void poll() }, 5000) }
async function readRevision() {
  const wanted = revision.value, version = ++revisionEpoch
  confirmedCaption.value = null; error.value = ''
  if (wanted === null) { revisionBusy.value = false; return }
  const cached = revisionCache.get(wanted)
  if (cached) { confirmedCaption.value = cached; revisionBusy.value = false; return }
  revisionBusy.value = true
  const request = read(), identity = source.value
  try {
    const raw = await api<unknown>(`${captionBase(identity.export_id, identity.clip_id)}?revision=${wanted}`, { signal: request.signal })
    if (!request.current() || version !== revisionEpoch || revision.value !== wanted) return
    const value = parseCaptionSnapshot(raw, identity.export_id, identity.clip_id, wanted)
    sameCaptionSource(props.snapshot, value)
    if (value.status !== 'CONFIRMED') throw new CaptionRenderResponseError()
    revisionCache.set(wanted, value); confirmedCaption.value = value
  } catch (value) { if (request.current() && version === revisionEpoch) error.value = message(value) }
  finally { request.release(); if (!stopped && version === revisionEpoch) revisionBusy.value = false }
}
async function create() {
  if (!canCreate.value || !confirmedCaption.value) return
  let body, sentReceipt
  try { body = captionRenderInput(confirmedCaption.value, style.value, frame.value); sentReceipt = receipts.prepare(body) }
  catch (value) { error.value = message(value); return }
  const identity = source.value, version = epoch
  action.value = 'create'; error.value = ''; notice.value = ''
  try {
    const raw = await post<unknown>(captionRenderBase(identity), body, { 'Idempotency-Key': sentReceipt.key })
    if (stopped || version !== epoch) return
    const value = parseCaptionRender(raw, identity)
    if (value.caption_revision !== body.caption_revision || value.caption_content_hash !== body.caption_content_hash
      || value.style_preset !== body.style_preset || value.frame_preset !== body.frame_preset) throw new CaptionRenderResponseError()
    selectedId.value = merge(value).id
    const cleaned = receipts.accept(body, sentReceipt)
    notice.value = `已保存生成任务，使用已确认字幕 r${value.caption_revision}。关闭此窗口不会停止后台生成。${cleaned ? '' : '本地请求记录暂时无法更新，再次提交相同配置可能返回这条已有任务。'}`
    await nextTick()
    if (!stopped && props.active && version === epoch && selectedId.value === value.id) document.getElementById(`caption-render-record-${value.id}`)?.scrollIntoView({ block: 'nearest' })
  } catch (value) { if (!stopped && version === epoch) error.value = message(value) }
  finally { if (!stopped && version === epoch) action.value = '' }
}
async function operate(value: CaptionRenderSnapshot, kind: 'cancel' | 'retry') {
  if (pending.value || props.captionPending || !(kind === 'cancel' ? value.actions.can_cancel : value.actions.can_retry)) return
  const version = epoch, identity = source.value
  action.value = `${kind}:${value.id}`; error.value = ''; notice.value = ''
  try {
    const raw = await post<unknown>(`${captionRenderPath(value.id)}/${kind}`)
    if (stopped || version !== epoch) return
    const result = parseCaptionRender(raw, identity, value.id)
    if (kind === 'cancel' && result.status !== 'CANCELLED') throw new CaptionRenderResponseError()
    merge(result)
    notice.value = kind === 'cancel' ? '已取消本条生成任务，原片与字幕版本保持不变。' : '已提交重试，继续使用这条任务原先冻结的字幕、样式和画幅。'
  } catch (value) { if (!stopped && version === epoch) { error.value = message(value); void refresh() } }
  finally { if (!stopped && version === epoch) action.value = '' }
}
async function selectPreview(id: string) {
  resetPreviewLayout(); previewDismissed.value = false; previewMessage.value = ''; selectedId.value = id
  await nextTick()
  if (!stopped && props.active && selectedId.value === id) previewBox.value?.scrollIntoView({ block: 'nearest' })
}
function pausePreview(video = player.value) {
  video?.pause()
  if (video && document.fullscreenElement === video) void document.exitFullscreen().catch(() => { /* Pausing remains effective if fullscreen exit is denied. */ })
}
function resetPreviewLayout() { fullscreenRequestVersion++; previewEnlarged.value = false; pausePreview() }
async function restorePreviewLayout() {
  resetPreviewLayout(); previewMessage.value = ''
  await nextTick()
  if (!stopped && props.active && selected.value?.status === 'SUCCEEDED') enlargeButton.value?.focus()
}
function previewEscape(event: KeyboardEvent) {
  if (!previewEnlarged.value) return
  event.preventDefault(); event.stopPropagation(); void restorePreviewLayout()
}
async function enlargePreview() {
  const video = player.value, id = selectedId.value, requestVersion = ++fullscreenRequestVersion
  if (!video || stopped || !props.active || selected.value?.status !== 'SUCCEEDED') return
  const current = () => !stopped && props.active && selectedId.value === id && player.value === video && requestVersion === fullscreenRequestVersion
  if (typeof video.requestFullscreen !== 'function') { if (current()) previewEnlarged.value = true; return }
  previewMessage.value = ''
  try {
    await video.requestFullscreen()
    if (!current()) pausePreview(video)
    else if (document.fullscreenElement !== video) previewEnlarged.value = true
  } catch { if (current()) previewEnlarged.value = true }
}
async function closePreview() {
  const id = selectedId.value
  resetPreviewLayout(); previewDismissed.value = true; selectedId.value = ''; previewMessage.value = ''
  await nextTick()
  if (!stopped && props.active && id) document.getElementById(`caption-render-preview-${id}`)?.focus()
}
function fullscreenChanged() { if (stopped || !props.active) pausePreview() }
function guardPlayback(event: Event) { if (stopped || !props.active || selected.value?.status !== 'SUCCEEDED') (event.currentTarget as HTMLVideoElement).pause() }
function elapsed(value: CaptionRenderSnapshot) { const seconds = Math.max(0, Math.floor((now.value - Date.parse(value.created_at)) / 1000)); return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
watch(confirmedRevisions, values => { if (revision.value === null || !values.includes(revision.value)) revision.value = values[0] ?? null }, { immediate: true })
watch(revision, () => { void readRevision() }, { immediate: true })
watch(selectedId, () => resetPreviewLayout(), { flush: 'sync' })
watch(() => props.active, value => { if (!value) resetPreviewLayout() }, { flush: 'sync' })
onMounted(() => { void poll(); clock = window.setInterval(() => { now.value = Date.now() }, 1000); document.addEventListener('fullscreenchange', fullscreenChanged) })
onBeforeUnmount(() => { stopped = true; epoch++; revisionEpoch++; resetPreviewLayout(); document.removeEventListener('fullscreenchange', fullscreenChanged); window.clearTimeout(timer); window.clearInterval(clock); for (const controller of controllers) controller.abort(); controllers.clear() })
</script>

<template>
  <section class="caption-render-panel" :class="{ 'preview-enlarged': previewEnlarged }" aria-label="带字幕视频生成" @keydown.esc="previewEscape">
    <p v-if="error" class="render-message error" role="alert">{{ error }}</p><p v-if="notice" class="render-message" role="status">{{ notice }}</p>
    <div class="render-body">
      <section class="render-config" aria-label="带字幕视频配置">
        <h3>生成带字幕 MP4</h3><p class="render-help">将已确认字幕写入视频画面，生成独立文件。原视频已有的字幕也会保留。</p>
        <label>已确认字幕版本<select v-model.number="revision" :disabled="pending || revisionBusy || !confirmedRevisions.length" aria-label="烧录字幕版本"><option v-if="!confirmedRevisions.length" :value="null">请先确认字幕</option><option v-for="item in confirmedRevisions" :key="item" :value="item">r{{ item }}{{ item === snapshot.revision ? ' · 当前版本' : ' · 历史确认版本' }}</option></select></label>
        <p v-if="revisionBusy" class="render-help" role="status">正在核对所选确认版本…</p><button v-else-if="revision !== null && !confirmedCaption" class="render-link" :disabled="pending" @click="readRevision">重新读取所选版本</button>
        <p v-if="confirmedCaption" class="render-help">已确认 r{{ confirmedCaption.revision }} · {{ confirmedCaption.cues.length }} 条字幕。{{ confirmedCaption.revision !== snapshot.revision ? '本页其他版本的修改不会进入这条视频。' : '' }}</p>
        <label>字幕样式<select v-model="style" :disabled="pending" aria-label="烧录字幕样式"><option v-for="(name, key) in captionRenderStyles" :key="key" :value="key">{{ name }}</option></select></label>
        <div class="render-style-sample" :class="{ boxed: style === 'DARK_BOX_V1' }" aria-label="字幕样式示意"><span>字幕会写入视频画面</span></div><p class="render-help sample-note">样式示意，最终以生成成片为准。</p>
        <label>输出画幅<select v-model="frame" :disabled="pending" aria-label="烧录输出画幅"><option v-for="(name, key) in captionRenderFrames" :key="key" :value="key">{{ name }}</option></select></label><p class="render-help">横竖屏均保留完整画面，不裁切内容，空白处补边。</p>
        <p v-if="captionDirty" class="render-warning" role="status">字幕有未保存修改。请先回到校对页保存并确认，再生成；已有成片仍可查看和下载。</p>
        <p v-else-if="!confirmedRevisions.length" class="render-warning">请先在“校对字幕”中确认一个字幕版本。</p>
        <p v-else-if="confirmedCaption && !confirmedCaption.cues.length" class="render-warning">这个确认版本没有字幕，请校对并确认有字幕的版本。</p>
        <p v-if="matchingActive" class="render-help" role="status">相同配置正在生成，请查看生成记录。</p>
        <button class="render-button primary render-create" :disabled="!canCreate" @click="create">{{ action === 'create' ? '正在提交…' : '生成带字幕 MP4' }}</button><p class="render-help">不新增语音识别或模型请求。原 MP4、字幕文件与素材包继续保留。</p>
      </section>
      <section class="render-results" aria-label="带字幕视频记录">
        <div class="render-history-heading"><h3>生成记录 <span>{{ rows.length }}</span></h3><button class="render-link" :disabled="pending" @click="refresh">刷新记录</button></div>
        <p v-if="refreshError" class="render-message error" role="status">{{ refreshError }} 已有记录仍保留，将继续刷新。</p>
        <div v-if="selected?.status === 'SUCCEEDED' && selected.content_url" ref="previewBox" class="render-preview" :class="{ portrait: selected.media && selected.media.height > selected.media.width }"><div class="render-preview-heading"><strong>带字幕成片 · r{{ selected.caption_revision }}</strong><div class="render-preview-actions"><button ref="enlargeButton" class="render-button" @click="previewEnlarged ? restorePreviewLayout() : enlargePreview()">{{ previewEnlarged ? '恢复布局' : '放大预览' }}</button><button class="render-button" @click="closePreview">收起预览</button><a v-if="selected.download_url" class="render-button" :href="selected.download_url" download target="_blank" rel="noopener">下载带字幕 MP4</a></div></div><video ref="player" :key="selected.id" :src="selected.content_url" controls preload="metadata" playsinline @play="guardPlayback"></video><p v-if="previewMessage" class="render-help" role="status">{{ previewMessage }}</p><p v-if="selected.media" class="render-help">{{ selected.media.width }}×{{ selected.media.height }} · {{ ((selected.size_bytes ?? 0) / 1048576).toFixed(2) }} MB · {{ captionRenderStyles[selected.style_preset] }}</p></div>
        <div class="render-history"><p v-if="loading && !rows.length" class="render-empty">正在读取生成记录…</p><p v-else-if="!rows.length" class="render-empty">还没有带字幕视频。选择已确认字幕、样式和画幅后生成。</p>
          <article v-for="value in rows" :key="value.id" :id="`caption-render-record-${value.id}`" class="render-record" :class="{ selected: selectedId === value.id }"><div class="render-record-heading"><strong>字幕 r{{ value.caption_revision }}</strong><span class="render-status" :class="value.status.toLowerCase()">{{ statusNames[value.status] }}</span></div><p class="render-help">{{ captionRenderStyles[value.style_preset] }} · {{ captionRenderFrames[value.frame_preset] }}</p><p class="render-record-date">{{ date(value.created_at) }}<span v-if="value.attempt"> · 第 {{ value.attempt }} 次执行</span><span v-if="captionRenderActive(value)"> · 距创建 {{ elapsed(value) }}</span></p>
            <template v-if="captionRenderActive(value)"><p class="render-progress" role="status">{{ captionRenderProgressCopy(value.progress) }}</p><progress v-if="value.progress?.phase === 'ENCODING'" :value="value.progress.processed_ms" :max="value.progress.total_ms" aria-label="字幕视频编码进度"></progress></template>
            <p v-if="value.status === 'FAILED'" class="render-warning">{{ message(new ApiError(value.error_code ?? 'CLIENT_ERROR')) }}</p><FailureDiagnostic v-if="value.actions.retry_block_reason !== 'INPUT_REQUIRES_NEW_RENDER'" :diagnostic="value.diagnostic" :error-code="value.error_code" :attempt="value.attempt" />
            <p v-if="value.status === 'CANCELLED'" class="render-help">此记录已取消。需要时可按生成配置重新生成。</p><p v-if="value.actions.retry_block_reason === 'ATTEMPTS_EXHAUSTED'" class="render-help">已达到 {{ value.actions.max_attempts }} 次执行上限，请先检查失败原因。</p><p v-if="value.actions.retry_block_reason === 'INPUT_REQUIRES_NEW_RENDER'" class="render-help">这条记录无法直接重试。请根据失败原因调整字幕或画幅，再重新生成。</p>
            <div class="render-record-actions"><template v-if="value.status === 'SUCCEEDED'"><button :id="`caption-render-preview-${value.id}`" class="render-button" @click="selectPreview(value.id)">预览成片</button><a v-if="value.download_url" class="render-button" :href="value.download_url" download target="_blank" rel="noopener">下载 MP4</a></template><button v-if="value.actions.can_cancel" class="render-button" :disabled="pending || captionPending" @click="operate(value, 'cancel')">{{ action === `cancel:${value.id}` ? '正在取消…' : '取消生成' }}</button><button v-if="value.actions.can_retry" class="render-button" :disabled="pending || captionPending" @click="operate(value, 'retry')">{{ action === `retry:${value.id}` ? '正在重试…' : '重试此任务' }}</button></div>
          </article>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.caption-render-panel { display: flex; flex-direction: column; flex: 1; min-height: 0; min-width: 0; color: #334155; font: 14px/1.65 system-ui, sans-serif; overflow: hidden; }
.caption-render-panel h3, .caption-render-panel p { margin-top: 0; }
.render-body { flex: 1; display: grid; grid-template-columns: minmax(250px, .8fr) minmax(0, 1.4fr); min-height: 0; min-width: 0; gap: 20px; padding: 16px 18px; }
.render-config { min-width: 0; min-height: 0; overflow-y: auto; padding-right: 6px; }
.render-config h3, .render-history-heading h3 { color: #1f2937; font-size: 16px; margin: 0 0 8px; }
.render-config > label { display: block; min-width: 0; margin: 14px 0 0; font-size: 13px; color: #475569; }
.render-config select { display: block; width: 100%; min-width: 0; max-width: 100%; margin-top: 6px; border: 1px solid #cbd5e1; border-radius: 6px; background: white; color: #1f2937; padding: 9px 10px; font: inherit; font-size: 14px; }
.render-help { color: #64748b; font-size: 13px; line-height: 1.7; margin: 7px 0; overflow-wrap: anywhere; }
.render-message, .render-warning { flex: none; padding: 9px 12px; margin: 8px 18px; background: #f1f5f9; color: #475569; border-radius: 6px; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.render-message { max-height: 72px; overflow-y: auto; }.render-message.error, .render-warning { background: #fff6eb; color: #8b632f; }
.render-config > .render-warning, .render-record > .render-warning { margin: 10px 0; }
.render-style-sample { display: grid; place-items: center; min-height: 94px; border-radius: 7px; margin-top: 10px; padding: 16px 12px; background: linear-gradient(135deg, #65758b, #aab7c3); overflow: hidden; }
.render-style-sample span { color: white; font-size: 18px; text-align: center; line-height: 1.6; font-weight: 600; overflow-wrap: anywhere; text-shadow: -1px -1px 0 #111, 1px -1px 0 #111, -1px 1px 0 #111, 1px 1px 0 #111; }
.render-style-sample.boxed span { text-shadow: none; background: #101827d9; padding: 3px 8px; border-radius: 3px; }.sample-note { margin-top: 5px; }
.render-button { display: inline-flex; justify-content: center; align-items: center; min-height: 38px; max-width: 100%; padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 6px; background: white; color: #334155; font: inherit; font-size: 13px; line-height: 1.5; text-decoration: none; cursor: pointer; white-space: normal; overflow-wrap: anywhere; }
.render-button.primary { background: #e47650; border-color: #e47650; color: white; font-size: 14px; }.render-create { width: 100%; margin: 12px 0 0; }
.render-link { min-height: 32px; border: 0; background: none; color: #475569; padding: 4px 0; font: inherit; font-size: 13px; cursor: pointer; }
.render-button:disabled, .render-link:disabled { opacity: .5; cursor: not-allowed; }
.caption-render-panel button:focus-visible, .caption-render-panel a:focus-visible, .caption-render-panel select:focus-visible { outline: 2px solid #e89778; outline-offset: 3px; }
.render-results { display: flex; flex-direction: column; min-width: 0; min-height: 0; }.render-history-heading { flex: none; display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }.render-history-heading h3 { margin: 0; }.render-history-heading h3 > span { color: #64748b; font-size: 13px; font-weight: 400; margin-left: 5px; }
.render-preview { flex: none; min-width: 0; margin-bottom: 14px; }.render-preview-heading { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; font-size: 13px; }.render-preview video { display: block; width: 100%; min-width: 0; max-height: 240px; aspect-ratio: 16/9; object-fit: contain; background: #0f172a; border-radius: 7px; }
.render-preview-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
.render-history { flex: 1; min-height: 0; min-width: 0; overflow-y: auto; overscroll-behavior: contain; padding-right: 6px; scrollbar-gutter: stable; }.render-empty { padding: 20px 12px; background: #f8fafc; border-radius: 6px; color: #64748b; font-size: 14px; line-height: 1.8; }
.render-record { border: 1px solid #e2e8f0; border-radius: 7px; padding: 12px; margin-bottom: 10px; min-width: 0; }.render-record.selected { border-color: #e9b49f; background: #fffcfa; }.render-record-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 7px; }.render-record-heading strong { color: #334155; font-size: 14px; }.render-status { padding: 3px 7px; border-radius: 4px; color: #64748b; background: #f1f5f9; font-size: 12px; }.render-status.running, .render-status.queued { color: #8b632f; background: #fff5e4; }.render-status.failed { color: #a0533c; background: #fff0e9; }.render-status.succeeded { color: #55745c; background: #edf5ef; }
.render-record-date { margin: 5px 0; color: #64748b; font-size: 12px; overflow-wrap: anywhere; }.render-progress { font-size: 13px; color: #475569; margin: 10px 0 6px; line-height: 1.7; }.render-record progress { display: block; width: 100%; height: 8px; accent-color: #e47650; }.render-record-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.render-record :deep(.failure-diagnostic p) { font-size: 13px; }.render-record :deep(.failure-diagnostic code) { font-size: 12px; }
@media(max-width:760px) { .render-body { display: block; overflow-y: auto; padding: 12px 14px; }.render-config { overflow: visible; padding-right: 0; }.render-style-sample { min-height: 70px; }.render-results { margin-top: 20px; border-top: 1px solid #e2e8f0; padding-top: 14px; }.render-history { overflow: visible; padding-right: 0; scrollbar-gutter: auto; }.render-preview video { max-height: 190px; }.render-record-actions > .render-button { flex: 1; }.render-message { margin-inline: 14px; } }
@media(min-width:761px) and (max-height:760px) { .render-preview video { max-height: 170px; }.render-style-sample { min-height: 75px; } }
.render-preview.portrait video { width: auto; max-width: 100%; height: min(360px, 44dvh); max-height: min(360px, 44dvh); margin-inline: auto; aspect-ratio: 9/16; }
.render-preview video:fullscreen { width: 100vw; height: 100dvh; max-width: none; max-height: none; aspect-ratio: auto; border-radius: 0; object-fit: contain; }
.caption-render-panel.preview-enlarged { position: relative; }
.preview-enlarged .render-body { position: relative; display: block; padding: 0; }
.preview-enlarged .render-config, .preview-enlarged .render-history, .preview-enlarged .render-history-heading { display: none; }
.preview-enlarged .render-results { position: absolute; inset: 0; display: flex; min-height: 0; margin: 0; padding: 12px 18px; border: 0; background: white; }
.preview-enlarged .render-preview { display: flex; flex: 1; flex-direction: column; min-height: 0; margin: 0; }
.preview-enlarged .render-preview-heading, .preview-enlarged .render-preview > .render-help { flex: none; }
.preview-enlarged .render-preview video, .preview-enlarged .render-preview.portrait video { flex: 1; min-height: 0; width: 100%; max-width: 100%; height: 0; max-height: none; margin: 0; aspect-ratio: auto; object-fit: contain; }
.preview-enlarged .render-preview video:fullscreen, .preview-enlarged .render-preview.portrait video:fullscreen { flex: none; width: 100vw; height: 100dvh; max-width: none; max-height: none; }
</style>
