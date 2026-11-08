<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError } from './api'
import { parseIntentSnapshot, type IntentOutput, type IntentSnapshot } from './intentClips'
import { IntentManualReviewError, manualReviewCanonical, manualReviewEligible, manualReviewInput, manualReviewReceiptMatchesOwner,
  assertStoredManualReviewReceipt, inspectManualReviewRevisionConflict, manualReviewSelectedIds, parseIntentManualReviewReceipt, persistManualReviewReceipt, recoverManualReviewReceipt, removeManualReviewReceipt, restoreManualReviewReceipt,
  sameManualReviewReceipt, validManualReviewNote, verifyIntentManualReviewReceipt, verifyIntentManualReviewSnapshot,
  type IntentManualReview, type IntentManualReviewReceipt } from './intentManualReview'
import { topicTime } from './topics'
import { SequencePreview } from './sequencePreview'

const props = defineProps<{ snapshot: IntentSnapshot; videoUrl: string; active: boolean; blocked: boolean }>()
const emit = defineEmits<{ snapshot: [value: IntentSnapshot]; saved: [value: string] }>()
const opened = ref(false), busy = ref(false), chosen = ref<string[]>([]), notes = ref<Record<string, string>>({}), accepted = ref<Record<string, boolean>>({})
const receipt = ref<IntentManualReviewReceipt | null>(null), storageError = ref(''), error = ref(''), notice = ref('')
const conflictHead = ref<IntentSnapshot | null>(null)
const initial = ref(''), base = ref(''), panel = ref<HTMLElement | null>(null), video = ref<HTMLVideoElement | null>(null)
let alive = true, generation = 0, opener: HTMLElement | null = null, acceptedProof: IntentManualReview | null = null
let previewGeneration = 0, previewTimer: number | undefined
interface PreviewIntent { token: number; signature: string; url: string; part: IntentOutput['parts'][number]; player: HTMLVideoElement }
let preparedPreview: PreviewIntent | null = null
const sequence = new SequencePreview(() => {})
const storageKey = `livecut-intent-manual-review-${props.snapshot.job_id}-${props.snapshot.id}`
const receiptStore = { read: () => localStorage.getItem(storageKey), write: (value: string) => localStorage.setItem(storageKey, value), remove: () => localStorage.removeItem(storageKey) }
const candidates = computed(() => props.snapshot.plan?.outputs.filter(manualReviewEligible) ?? [])
const signature = () => manualReviewCanonical({ revision: props.snapshot.revision, hash: props.snapshot.plan_hash, plan: props.snapshot.plan, source: props.snapshot.source })
const inputSignature = () => JSON.stringify({ chosen: chosen.value, notes: notes.value, accepted: accepted.value })
const dirty = computed(() => opened.value && initial.value !== inputSignature())
const stale = computed(() => opened.value && base.value !== signature())
const needsCheck = computed(() => !!receipt.value || !!storageError.value)
const invalid = computed(() => chosen.value.some(id => !accepted.value[id] || !validManualReviewNote(notes.value[id])))
defineExpose({ open, dirty, pending: busy, needsCheck, opened })
function message(value: unknown): string {
  const code = value instanceof ApiError || value instanceof IntentManualReviewError ? value.code : 'CLIENT_ERROR'
  const messages: Record<string, string> = {
    CLIP_MANUAL_REVIEW_UNAVAILABLE: '当前服务未明确提供人工核对字段，未提交。请更新后端后重试。',
    CLIP_MANUAL_REVIEW_OTHER_PAGE: '另一个页面留下了不同的待核对操作。未覆盖或删除其记录，请先在原页面核对。',
    CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE: '浏览器存储不可用，未提交。请恢复存储访问后重试。',
    CLIP_MANUAL_REVIEW_UNKNOWN: '原核对结果尚未确认，请先核对原操作，不能替换原请求标识或内容。',
    CLIP_MANUAL_REVIEW_BLOCKED: '原记录当前不存在，但本页还有其他未完成输入或操作。已保留原标识；请先核对创建/保存结果或放弃本页草稿，再按原内容重试。',
    CLIP_MANUAL_REVIEW_MISMATCH: '服务端方案或人工摘要已变化。当前核对输入保留，请重新读取核对。',
    CLIP_MANUAL_REVIEW_INVALID: '服务端人工核对证明未通过校验，当前内容保留。',
    CLIP_MANUAL_REVIEW_NOT_ALLOWED: '当前目标不允许人工选用，请核对候选状态和来源。',
    CLIP_PLAN_REVISION_CONFLICT: '方案已有新版本，当前核对输入保留。请放弃本页核对输入后重新打开。',
    INVALID_CLIP_MANUAL_REVIEW: '请为所选目标填写 1～1000 个 UTF-16 单位的核对说明，并接受整句边界。',
    INVALID_CLIP_MANUAL_REVIEW_RESPONSE: '人工核对响应的来源、内容或摘要不一致，尚未授予确认和导出权限。',
    NETWORK_UNAVAILABLE: '无法连接服务。原请求标识和输入保留，请核对原操作。',
    REQUEST_TIMEOUT: '请求结果未知。原请求标识和输入保留，请核对原操作。',
  }
  return messages[code] ?? `核对未完成：${code}。原请求标识和输入仍保留。`
}
function readStored(): IntentManualReviewReceipt | null {
  let raw: string | null
  try { raw = localStorage.getItem(storageKey) } catch { throw new IntentManualReviewError('CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE') }
  let parsed: IntentManualReviewReceipt | null = null
  try { parsed = raw ? parseIntentManualReviewReceipt(JSON.parse(raw)) : null } catch { /* Keep invalid storage for inspection. */ }
  if (raw && !parsed || parsed && !manualReviewReceiptMatchesOwner(parsed, props.snapshot)) throw new IntentManualReviewError('CLIP_MANUAL_REVIEW_OTHER_PAGE')
  return parsed
}
function checkStorage() {
  try {
    const stored = readStored()
    if (stored && receipt.value && !sameManualReviewReceipt(stored, receipt.value)) throw new IntentManualReviewError('CLIP_MANUAL_REVIEW_OTHER_PAGE')
    if (stored) receipt.value = stored
    storageError.value = ''
  } catch (value) { storageError.value = message(value) }
}
function setInputs() {
  chosen.value = manualReviewSelectedIds(props.snapshot)
  notes.value = Object.fromEntries((props.snapshot.manual_review?.outputs ?? []).map(item => [item.output_id, item.note]))
  accepted.value = Object.fromEntries(chosen.value.map(id => [id, true]))
  base.value = signature(); initial.value = inputSignature(); error.value = ''; notice.value = ''
}
function open() {
  if (!alive || !props.active || props.blocked && !needsCheck.value || busy.value) return false
  checkStorage()
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  if (!opened.value) setInputs()
  opened.value = true
  void nextTick(() => panel.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus())
  return true
}
function close(discard = false) {
  if (busy.value) return
  if (dirty.value && !discard) { error.value = '本页核对输入尚未提交。请提交，或点击“放弃本页核对输入并关闭”；原待核对回执不会被删除。'; return }
  generation++; stopPreview(); opened.value = false
  if (discard) setInputs()
  void nextTick(() => { if (props.active && opener?.isConnected) opener.focus() })
}
function toggle(id: string, checked: boolean) { chosen.value = checked ? [...chosen.value, id] : chosen.value.filter(value => value !== id) }
function preview(part: IntentOutput['parts'][number]) {
  if (!props.active || !opened.value || busy.value || !video.value) return
  stopPreview()
  const intent: PreviewIntent = { token: previewGeneration, signature: signature(), url: props.videoUrl, part: { ...part }, player: video.value }
  preparedPreview = intent
  previewTimer = window.setTimeout(() => { if (preparedPreview === intent) { stopPreview(); notice.value = '原视频尚未准备好，请确认视频可播放后重试。' } }, 15000)
  void startPreview(intent)
}
function stopPreview() { previewGeneration++; preparedPreview = null; window.clearTimeout(previewTimer); previewTimer = undefined; sequence.cancel(); video.value?.pause() }
function previewCurrent(intent: PreviewIntent) { return alive && props.active && opened.value && !document.hidden && !busy.value
  && intent.token === previewGeneration && intent.signature === signature() && intent.url === props.videoUrl && video.value === intent.player }
async function startPreview(intent: PreviewIntent) {
  await nextTick()
  if (preparedPreview !== intent || !previewCurrent(intent)) return
  if (intent.player.readyState < 1) return
  preparedPreview = null; window.clearTimeout(previewTimer); previewTimer = undefined
  const result = await sequence.start(intent.player, { id: `manual-review:${intent.token}`, parts: [{ start_ms: intent.part.start_ms, end_ms: intent.part.end_ms }] })
  if (previewCurrent(intent) && result === 'failed') notice.value = '区间暂时无法播放。请先点击原视频播放按钮，再重试回看。'
}
function previewMetadata() { if (preparedPreview) void startPreview(preparedPreview) }
function guardPreview(event: Event) { if (!props.active || !opened.value || document.hidden || busy.value) (event.currentTarget as HTMLVideoElement).pause() }
function sourceText(id: string) { return props.snapshot.utterances.find(item => item.utterance_id === id)?.text ?? id }
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.stopPropagation(); close(); return }
  if (event.key !== 'Tab') return
  const nodes = [...(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), video, a[href]') ?? [])]
  if (event.shiftKey && document.activeElement === nodes[0]) { event.preventDefault(); nodes.at(-1)?.focus() }
  else if (!event.shiftKey && document.activeElement === nodes.at(-1)) { event.preventDefault(); nodes[0]?.focus() }
}
async function fresh(current: () => boolean) {
  const raw = await api<unknown>(`/api/clip-requests/${props.snapshot.id}`)
  if (!current()) return null
  const value = await verifyIntentManualReviewSnapshot(parseIntentSnapshot(raw, props.snapshot.job_id, props.snapshot.id))
  return current() ? value : null
}
function assertReceiptOwner(expected: IntentManualReviewReceipt) {
  assertStoredManualReviewReceipt(receiptStore, expected)
}
async function run(reconcile = false) {
  if (!alive || busy.value || !props.active || document.hidden || props.blocked && !reconcile || !opened.value) return
  checkStorage()
  if (storageError.value) { error.value = storageError.value; return }
  if (receipt.value && !reconcile) { error.value = message(new IntentManualReviewError('CLIP_MANUAL_REVIEW_UNKNOWN')); return }
  if (!receipt.value && (reconcile || stale.value || invalid.value)) return
  const operation = ++generation, sourceSignature = signature()
  const current = () => alive && operation === generation && props.active && !document.hidden && opened.value
  busy.value = true; error.value = ''; notice.value = ''; stopPreview()
  let posted = false
  try {
    let expected = receipt.value, proof = acceptedProof
    if (!expected) {
      const latest = await fresh(current)
      if (!latest || !current()) return
      if (manualReviewCanonical({ revision: latest.revision, hash: latest.plan_hash, plan: latest.plan, source: latest.source }) !== sourceSignature) {
        emit('snapshot', latest); throw new ApiError('CLIP_PLAN_REVISION_CONFLICT')
      }
      const body = manualReviewInput(latest, latest.plan!.outputs.filter(item => chosen.value.includes(item.output_id)).map(item => ({
        output_id: item.output_id, note: notes.value[item.output_id] ?? '', accept_sentence_boundaries: true as const,
      })))
      // CAS re-read and durable receipt happen synchronously immediately before POST.
      expected = persistManualReviewReceipt(receiptStore, latest, body, () => crypto.randomUUID())
      receipt.value = expected
      posted = true
      const raw = await api<unknown>(`/api/clip-requests/${expected.request_id}/manual-reviews`, { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': expected.key }, body: expected.input })
      if (!current()) return
      proof = await verifyIntentManualReviewReceipt(raw, expected)
    } else if (!proof || proof.id !== expected.key) {
      const frozen = expected
      proof = await recoverManualReviewReceipt(frozen, {
        get: () => api<unknown>(`/api/clip-requests/${frozen.request_id}/manual-reviews/${frozen.key}`), current,
        post: () => {
          if (!current()) throw new IntentManualReviewError('CLIP_MANUAL_REVIEW_UNKNOWN')
          if (props.blocked) throw new IntentManualReviewError('CLIP_MANUAL_REVIEW_BLOCKED')
          // A late GET/hash may not authorize a different tab's write.
          assertReceiptOwner(frozen)
          posted = true
          return api<unknown>(`/api/clip-requests/${frozen.request_id}/manual-reviews`, { method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Idempotency-Key': frozen.key }, body: frozen.input })
        },
      })
    }
    if (!current() || !proof || !expected) return
    acceptedProof = proof
    notice.value = '人工核对记录已保存，正在只读核对当前方案版本。'
    const latest = await fresh(current)
    if (!latest || !current()) return
    if (latest.revision < proof.revision || latest.revision === proof.revision && latest.manual_review?.review_hash !== proof.review_hash) throw new IntentManualReviewError()
    removeManualReviewReceipt(receiptStore, expected)
    receipt.value = null; acceptedProof = null; conflictHead.value = null; emit('snapshot', latest)
    // Parent adopts synchronously; permission comes exclusively from the fresh verified snapshot.
    await nextTick()
    if (!current()) return
    setInputs()
    notice.value = latest.revision === proof.revision ? `人工核对 r${proof.revision} 已保存。请关闭窗口，再分别确认目标和生成 MP4。`
      : `原人工核对 r${proof.revision} 已完成；当前已是 r${latest.revision}，旧核对记录不授权当前版本。`
    emit('saved', notice.value)
  } catch (value) {
    if (current()) {
      error.value = message(value)
      if (acceptedProof) notice.value = '人工核对记录已保存，当前版本尚待只读核对；不会重复创建核对记录。'
      else if (posted && receipt.value && value instanceof ApiError && value.status === 409 && value.code === 'CLIP_PLAN_REVISION_CONFLICT') {
        try {
          const result = await inspectConflict(receipt.value, current)
          if (!result || !current()) return
          if (result.kind === 'completed') { acceptedProof = result.proof; notice.value = '原人工记录已找到，请再次核对原操作以读取当前版本。' }
          else { conflictHead.value = result.snapshot; error.value = ''; notice.value = `原操作因版本更新未落库，当前为 r${result.snapshot.revision}。原回执仍保留，可下载后明确解除阻塞。` }
        } catch (inspection) { if (current()) error.value = message(inspection) }
      }
    }
  }
  finally { if (alive && operation === generation) busy.value = false }
}
function inspectConflict(expected: IntentManualReviewReceipt, current: () => boolean) {
  return inspectManualReviewRevisionConflict(expected, {
    get: () => api<unknown>(`/api/clip-requests/${expected.request_id}/manual-reviews/${expected.key}`), head: () => fresh(current), current,
  })
}
async function resolveConflict() {
  if (!receipt.value || !conflictHead.value || busy.value || !props.active || !opened.value || document.hidden) return
  const expected = receipt.value, operation = ++generation
  const current = () => alive && operation === generation && props.active && !document.hidden && opened.value
  busy.value = true; error.value = ''; stopPreview()
  try {
    const result = await inspectConflict(expected, current)
    if (!result || !current()) return
    if (result.kind === 'completed') { acceptedProof = result.proof; conflictHead.value = null; notice.value = '原记录已经出现，不能按未落库解除。请核对原操作。'; return }
    removeManualReviewReceipt(receiptStore, expected)
    receipt.value = null; acceptedProof = null; conflictHead.value = null; emit('snapshot', result.snapshot)
    await nextTick()
    if (!current()) return
    setInputs(); notice.value = '原操作阻塞已明确解除，已读取服务端新版；尚未新建核对、确认或导出。本页其他草稿仍保留。'
    emit('saved', notice.value)
  } catch (value) { if (current()) error.value = message(value) }
  finally { if (alive && operation === generation) busy.value = false }
}
function exportReceipt() {
  if (!receipt.value) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(receipt.value, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a'); link.href = url; link.download = `manual-review-${receipt.value.key}.json`; link.click(); URL.revokeObjectURL(url)
}
async function importReceipt(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || file.size > 32000 || busy.value || props.blocked) return
  const operation = ++generation, owner = props.snapshot.id
  try {
    const parsed = parseIntentManualReviewReceipt(JSON.parse(await file.text()))
    if (!alive || operation !== generation || !props.active || props.snapshot.id !== owner || !opened.value || props.blocked || document.hidden || busy.value) return
    if (!parsed || !manualReviewReceiptMatchesOwner(parsed, props.snapshot)) throw new IntentManualReviewError()
    receipt.value = restoreManualReviewReceipt(receiptStore, parsed, receipt.value); acceptedProof = null; conflictHead.value = null
    notice.value = '已恢复待核对标识与原输入；尚未联网、保存方案、确认或导出。请手动核对原操作。'
  } catch (value) { if (alive && operation === generation) error.value = message(value) }
}
function storageChanged(event: StorageEvent) { if (event.key === storageKey || event.key === null) checkStorage() }
function visibilityChanged() { if (document.hidden) { generation++; busy.value = false; stopPreview() } }
watch(() => props.active, active => { if (!active) { generation++; busy.value = false; stopPreview() } })
watch(() => props.videoUrl, stopPreview)
watch(() => signature(), stopPreview)
onMounted(() => { checkStorage(); window.addEventListener('storage', storageChanged); document.addEventListener('visibilitychange', visibilityChanged) })
onBeforeUnmount(() => { alive = false; generation++; stopPreview(); window.removeEventListener('storage', storageChanged); document.removeEventListener('visibilitychange', visibilityChanged) })
</script>

<template>
  <div v-if="opened" v-show="active" :inert="!active ? true : undefined" class="manual-backdrop" @keydown="keydown">
    <section ref="panel" role="dialog" aria-modal="true" aria-label="批量人工核对待复核候选" class="manual-dialog">
      <header><div><h3>人工核对待复核候选</h3><p>当前 r{{ snapshot.revision }} · 每次保存替换整批人工选用集合</p></div><button class="text-button" :disabled="busy" @click="close()">关闭</button></header>
      <div class="manual-body">
        <p class="manual-warning">AI 原分类与说明保持不变。请回看原视频、核对实际内容；ASR 只有整句边界，句内可能包含无关话术，系统不保证语义完整或逐词定位。</p>
        <p v-if="stale" class="manual-warning" role="status">方案已更新。本页核对输入保留，不能提交到新版本；请放弃本页核对输入后重新打开。</p>
        <p v-if="storageError || error" class="manual-warning" role="alert">{{ storageError || error }}</p><p v-if="notice" role="status">{{ notice }}</p>
        <div v-if="receipt" class="manual-receipt"><strong>原操作结果待核对 · #{{ receipt.key.slice(0, 8) }}</strong><p>仅核对原记录；只有明确的业务“记录不存在”才按原标识和原内容重试。不会自动确认或导出。</p><details><summary>查看原提交内容</summary><pre>{{ JSON.stringify(JSON.parse(receipt.input), null, 2) }}</pre></details><button class="secondary" :disabled="busy || !active" @click="run(true)">核对原操作</button><button class="text-button" @click="exportReceipt">下载待核对回执</button></div>
        <div v-if="receipt && conflictHead" class="manual-warning"><p>已只读核对原 ID 仍不存在，且服务端版本已推进。解除前会再次核对来源、新版和原记录，并只清除本页这份回执。</p><button class="secondary" :disabled="busy || !active" @click="resolveConflict">解除原操作阻塞并采用新版</button></div>
        <video ref="video" :src="videoUrl" controls preload="metadata" playsinline @play="guardPreview" @loadedmetadata="previewMetadata" @timeupdate="sequence.timeUpdate($event.currentTarget as HTMLVideoElement)" @playing="sequence.timeUpdate($event.currentTarget as HTMLVideoElement)" @seeking="sequence.seeking($event.currentTarget as HTMLVideoElement)" @seeked="sequence.seeked($event.currentTarget as HTMLVideoElement)" @pause="sequence.pause($event.currentTarget as HTMLVideoElement)" @ended="sequence.ended($event.currentTarget as HTMLVideoElement)" @ratechange="sequence.rateChange($event.currentTarget as HTMLVideoElement)" @error="stopPreview()"></video>
        <p class="help">点击下方区间回看对应原视频，核对每段的完整解释与混合话术。预览不创建导出任务。</p>
        <article v-for="output in candidates" :key="output.output_id" class="manual-candidate">
          <label class="manual-choice"><input type="checkbox" :checked="chosen.includes(output.output_id)" :disabled="busy || !!receipt || stale" @change="toggle(output.output_id, ($event.target as HTMLInputElement).checked)">人工选用：{{ output.title }}</label>
          <p>AI 分类：需进一步核对</p><p>{{ output.reason }}</p>
          <div v-for="(part, index) in output.parts" :key="index" class="manual-source"><button class="text-button" :disabled="busy || !active" @click="preview(part)">回看第 {{ index + 1 }} 段 {{ topicTime(part.start_ms) }} → {{ topicTime(part.end_ms) }}</button><p>起句：{{ sourceText(part.start_utterance_id) }}</p><p>止句：{{ sourceText(part.end_utterance_id) }}</p></div>
          <template v-if="chosen.includes(output.output_id)"><label>核对说明（必填，{{ (notes[output.output_id] ?? '').length }} / 1000）<textarea v-model="notes[output.output_id]" maxlength="1000" rows="3" :disabled="busy || !!receipt || stale" placeholder="说明已核对哪些内容、为何接受当前片段及可能混入的话术"></textarea></label><label class="manual-choice"><input v-model="accepted[output.output_id]" type="checkbox" :disabled="busy || !!receipt || stale">我已人工核对并接受当前整句边界，包括句内可能混合的内容。</label></template>
        </article>
        <p v-if="!candidates.length" class="help">当前没有可人工选用的待复核候选。未找到或缺少原句证据的目标不能选用。</p>
        <details><summary>恢复待核对回执</summary><p>只恢复待核对标识和输入，不恢复确认或导出权限。</p><input type="file" accept="application/json,.json" :disabled="busy || blocked" aria-label="导入人工核对回执" @change="importReceipt"></details>
      </div>
      <footer><p>已选 {{ chosen.length }} 个待复核目标。{{ chosen.length ? '保存会创建新版本；已有 FOUND 选择保持不变。' : '保存将明确清空人工选用集合，也会创建新版本。' }}</p><div><button class="text-button" :disabled="busy" @click="close(true)">放弃本页核对输入并关闭</button><button class="primary" :disabled="busy || !!receipt || !!storageError || stale || invalid || blocked || !active" @click="run()">{{ busy ? '正在核对…' : chosen.length ? '保存整批人工选用' : '清空人工选用' }}</button></div></footer>
    </section>
  </div>
</template>

<style scoped>
.manual-backdrop{position:fixed;inset:0;z-index:80;background:#102b35aa;display:flex;justify-content:center;align-items:center;padding:20px}.manual-dialog{width:min(900px,100%);max-height:calc(100dvh - 40px);display:flex;flex-direction:column;background:#fffdf8;border-radius:16px;box-shadow:0 18px 70px #102b3540;overflow:hidden}.manual-dialog header,.manual-dialog footer{padding:18px 22px;flex-shrink:0;background:#f4f7f1}.manual-dialog header{display:flex;justify-content:space-between;gap:12px}.manual-dialog h3{margin:0}.manual-dialog p{line-height:1.6;margin:.5em 0}.manual-body{padding:18px 22px;overflow:auto;min-height:0}.manual-body video{display:block;max-height:260px;width:100%;background:#112a30}.manual-warning{padding:12px;background:#fff0d9;border-left:3px solid #d28433}.manual-receipt,.manual-candidate{padding:16px;margin:16px 0;border:1px solid #cfdbd0;border-radius:10px}.manual-receipt pre{white-space:pre-wrap;overflow-wrap:anywhere}.manual-choice{display:flex;gap:10px;align-items:flex-start;margin:10px 0}.manual-choice input{flex:none;margin-top:5px}.manual-candidate textarea{display:block;box-sizing:border-box;width:100%;margin:8px 0}.manual-source{padding:8px 12px;background:#f5f6f1;margin:8px 0;overflow-wrap:anywhere}.manual-dialog footer>div{display:flex;justify-content:flex-end;gap:12px;flex-wrap:wrap}.manual-candidate label,.manual-dialog p{overflow-wrap:anywhere}@media(max-width:600px){.manual-backdrop{padding:6px}.manual-dialog{max-height:calc(100dvh - 12px)}.manual-dialog header,.manual-dialog footer,.manual-body{padding:12px}.manual-dialog footer>div{justify-content:stretch}.manual-dialog footer button{flex:1}}
</style>
