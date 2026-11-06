import test from 'node:test'
import assert from 'node:assert/strict'
import { isProxy, reactive } from 'vue'
import type { ExportJob } from '../api.ts'
import { CaptionBatchError, CaptionResponseError, captionBase, captionDraftInvalid, captionFileUrl, captionPayload,
  captionTime, captionValidation, editCaptionTime, parseCaptionSnapshot, parseCaptionTime, prepareCaptionBatch,
  sameCaptionSource, copyCaptionCue, copyCaptionCues, type CaptionSnapshot, type CaptionTimeDrafts } from '../captions.ts'

const EXPORT = '11111111-1111-4111-8111-111111111111'
const clipId = (n = 1) => `22222222-2222-4222-8222-${String(n).padStart(12, '0')}`
function snapshot(id = clipId()): CaptionSnapshot {
  return { export_id: EXPORT, clip_id: id, clip_title: '缓存原因片段', content_url: `/api/exports/${EXPORT}/clips/${id}/content`,
    revision: 0, status: 'DRAFT', confirmed_revisions: [], content_hash: 'a'.repeat(64),
    source: { snapshot_hash: 'b'.repeat(64), clip_sha256: 'c'.repeat(64), transcript_hash: 'd'.repeat(64), duration_ms: 4000, media_duration_ms: 4017,
      timing_basis: 'SOURCE_EDL_SENTENCE_MS', parts: [{ start_ms: 10000, end_ms: 12000 }, { start_ms: 20000, end_ms: 22000 }] },
    cues: [{ id: 'c000001', part_index: 0, source_utterance_id: 'u10', source_start_ms: 9000, source_end_ms: 11000, retained_start_ms: 10000, retained_end_ms: 11000,
      start_ms: 0, end_ms: 1000, text: '缓存原句前半', source_text: '完整的缓存原句前半', boundary_partial: true, reviewed: false, requires_review: true },
    { id: 'c000002', part_index: 1, source_utterance_id: 'u2', source_start_ms: 20000, source_end_ms: 21000, retained_start_ms: 20000, retained_end_ms: 21000,
      start_ms: 2000, end_ms: 3000, text: '后一个区间', source_text: '后一个区间', boundary_partial: false, reviewed: false, requires_review: false }] }
}
function confirmed(id = clipId(), revision = 1) {
  const value = snapshot(id); value.revision = revision; value.status = 'CONFIRMED'; value.confirmed_revisions = [revision]
  value.cues[0]!.reviewed = true; value.cues[0]!.requires_review = false
  return value
}
function batch(count = 2): Pick<ExportJob, 'id' | 'clips'> {
  return { id: EXPORT, clips: Array.from({ length: count }, (_, i) => ({ id: clipId(i + 1), segment_id: `out-${i + 1}`, product_id: null, product_name: null, title: `成片${i + 1}`,
    start_ms: 10000, end_ms: 22000, stage_id: '33333333-3333-4333-8333-333333333333', status: 'SUCCEEDED', attempt: 1, error_code: null,
    size_bytes: 1000, content_url: `/api/exports/${EXPORT}/clips/${clipId(i + 1)}/content`, download_url: `/api/exports/${EXPORT}/clips/${clipId(i + 1)}/content?download=true` })) }
}
test('discard can copy a reactive snapshot after text and invalid time edits without DataCloneError or shared state', () => {
  const saved = reactive(snapshot()), draft = reactive(copyCaptionCues(saved.cues))
  assert.equal(isProxy(saved.cues), true)
  draft[0]!.text = '尚未保存的修改'
  const times: CaptionTimeDrafts = {}; editCaptionTime(draft[0]!, times, 'start_ms', '00:')
  assert.equal(captionDraftInvalid(times), true)
  const restored = copyCaptionCues(saved.cues)
  assert.equal(isProxy(restored), false); assert.equal(isProxy(restored[0]), false)
  assert.equal(restored[0]!.text, '缓存原句前半'); assert.equal(restored[0]!.start_ms, 0)
  restored[0]!.text = '独立的新修改'
  assert.equal(saved.cues[0]!.text, '缓存原句前半')
  assert.equal(draft[0]!.text, '尚未保存的修改')
})
test('delete and undo can snapshot a reactive cue without retaining its proxy or sharing primitive fields', () => {
  const cue = reactive(snapshot().cues[0]!)
  assert.equal(isProxy(cue), true)
  const deleted = copyCaptionCue(cue)
  assert.equal(isProxy(deleted), false)
  cue.text = '删除后别处修改'; cue.reviewed = true
  assert.equal(deleted.text, '缓存原句前半'); assert.equal(deleted.reviewed, false)
  const restored = reactive(copyCaptionCue(deleted)); restored.text = '撤销后修改'
  assert.equal(deleted.text, '缓存原句前半')
})

test('caption time is based on the composed clip rather than bounding source span or encoded rounding', () => {
  const parsed = parseCaptionSnapshot(snapshot(), EXPORT, clipId())
  assert.equal(parsed.source.duration_ms, 4000)
  assert.equal(parsed.source.media_duration_ms, 4017)
  assert.equal(parsed.cues[1]!.start_ms, 2000)
  assert.notEqual(parsed.source.duration_ms, 22000 - 10000)
  const value = snapshot(); value.source.duration_ms = 12000
  assert.throws(() => parseCaptionSnapshot(value, EXPORT, clipId()), CaptionResponseError)
})
test('caption identities and playback addresses must match the chosen immutable export clip', () => {
  for (const change of [
    (v: CaptionSnapshot) => { v.export_id = clipId() }, (v: CaptionSnapshot) => { v.clip_id = EXPORT },
    (v: CaptionSnapshot) => { v.content_url = 'https://other.example/private' },
    (v: CaptionSnapshot) => { v.source.clip_sha256 = 'invalid' },
  ]) { const value = snapshot(); change(value); assert.throws(() => parseCaptionSnapshot(value, EXPORT, clipId()), CaptionResponseError) }
  assert.throws(() => captionBase('../secret', clipId()), CaptionResponseError)
  assert.throws(() => parseCaptionSnapshot(snapshot(), EXPORT, clipId(), 3), CaptionResponseError)
})
test('partial metadata stays source-bound and requires an explicit reviewed flag', () => {
  const value = snapshot(); value.cues[0]!.text = '校正了术语'
  assert.equal(parseCaptionSnapshot(value, EXPORT, clipId()).cues[0]!.requires_review, true)
  value.cues[0]!.requires_review = false
  assert.throws(() => parseCaptionSnapshot(value, EXPORT, clipId()), CaptionResponseError)
  value.cues[0]!.reviewed = true
  assert.equal(parseCaptionSnapshot(value, EXPORT, clipId()).cues[0]!.requires_review, false)
  value.cues[0]!.boundary_partial = false
  assert.throws(() => parseCaptionSnapshot(value, EXPORT, clipId()), CaptionResponseError)
})
test('a confirmed revision cannot claim success while a boundary-partial cue still requires review', () => {
  const value = snapshot(); value.status = 'CONFIRMED'; value.confirmed_revisions = [0]
  assert.equal(value.cues[0]!.requires_review, true)
  assert.throws(() => parseCaptionSnapshot(value, EXPORT, clipId()), CaptionResponseError)
  value.cues[0]!.reviewed = true; value.cues[0]!.requires_review = false
  assert.equal(parseCaptionSnapshot(value, EXPORT, clipId()).status, 'CONFIRMED')
})
test('retained source pieces must lie inside the declared part and originating utterance', () => {
  for (const change of [
    (v: CaptionSnapshot) => { v.cues[0]!.part_index = 2 },
    (v: CaptionSnapshot) => { v.cues[0]!.retained_start_ms = 8000 },
    (v: CaptionSnapshot) => { v.cues[0]!.retained_end_ms = 12500 },
    (v: CaptionSnapshot) => { v.source.parts.reverse() },
  ]) { const value = snapshot(); change(value); assert.throws(() => parseCaptionSnapshot(value, EXPORT, clipId()), CaptionResponseError) }
})
test('saving sends only mutable caption fields and deletion omits the cue instead of mutating original ASR', () => {
  const value = snapshot(), original = structuredClone(value)
  const payload = captionPayload(value.cues.slice(1))
  assert.deepEqual(Object.keys(payload.cues[0]!).sort(), ['end_ms', 'id', 'reviewed', 'start_ms', 'text'])
  assert.equal(payload.cues[0]!.id, 'c000002')
  assert.equal(JSON.stringify(payload).includes('source_text'), false)
  assert.deepEqual(value, original)
})
test('edited captions must remain ordered and nonoverlapping inside logical clip duration', () => {
  const value = snapshot()
  for (const [start, end] of [[-1, 1000], [0, 0], [0, 4001]]) {
    const cues = structuredClone(value.cues); cues[0]!.start_ms = start!; cues[0]!.end_ms = end!
    assert.ok(captionValidation(cues, 4000))
  }
  value.cues[0]!.end_ms = 2001
  assert.ok(captionValidation(value.cues, 4000))
  value.cues[0]!.end_ms = 2000
  assert.equal(captionValidation(value.cues, 4000), '')
  value.cues.reverse(); assert.ok(captionValidation(value.cues, 4000))
})
test('empty caption sets are supported explicitly while individual blank or oversized cues are rejected', () => {
  const value = confirmed(); value.cues = []
  assert.equal(parseCaptionSnapshot(value, EXPORT, clipId()).cues.length, 0)
  for (const text of ['', '   ', 'x'.repeat(2001)]) { const cues = snapshot().cues; cues[0]!.text = text; assert.ok(captionValidation(cues, 4000)) }
})
test('text is preserved literally and the shared UTF8 budget counts Chinese bytes', () => {
  const value = snapshot(); value.cues[0]!.text = '<script>alert(1)</script>\n缓存 & 正文'
  assert.equal(parseCaptionSnapshot(value, EXPORT, clipId()).cues[0]!.text, value.cues[0]!.text)
  const many = Array.from({ length: 400 }, (_, i) => ({ ...snapshot().cues[1]!, id: `c${i}`, start_ms: i * 2, end_ms: i * 2 + 1, text: '字'.repeat(2000) }))
  assert.match(captionValidation(many, 1000), /2 MiB/)
})
test('raw invalid time inputs are retained without replacing valid stored boundaries', () => {
  const cue = snapshot().cues[0]!, drafts: CaptionTimeDrafts = {}
  editCaptionTime(cue, drafts, 'start_ms', '00:xx.000')
  assert.equal(cue.start_ms, 0); assert.equal(drafts[cue.id]!.start_ms, '00:xx.000'); assert.equal(captionDraftInvalid(drafts), true)
  assert.ok(captionValidation([cue], 4000, drafts))
  editCaptionTime(cue, drafts, 'start_ms', '00:00.120')
  assert.equal(cue.start_ms, 120); assert.equal(captionDraftInvalid(drafts), false)
  assert.equal(parseCaptionTime('120:01.003'), 7201003)
  assert.equal(captionTime(7201003), '120:01.003')
  for (const text of ['00:60.000', '00:00', '-01:00.000', 'NaN']) assert.equal(parseCaptionTime(text), null)
})
test('a later revision may change text but cannot silently change clip or transcript identity', () => {
  const first = snapshot(), next = confirmed()
  next.cues[0]!.text = '术语已校正'
  assert.doesNotThrow(() => sameCaptionSource(first, next))
  const reordered = { ...next, source: Object.fromEntries(Object.entries(next.source).reverse()) } as CaptionSnapshot
  assert.doesNotThrow(() => sameCaptionSource(first, reordered))
  next.source.transcript_hash = 'f'.repeat(64)
  assert.throws(() => sameCaptionSource(first, next), CaptionResponseError)
  const conflict = snapshot(); conflict.content_hash = 'f'.repeat(64)
  assert.throws(() => sameCaptionSource(first, conflict), CaptionResponseError)
})
test('only confirmed revisions receive native download URLs including when a newer draft exists', () => {
  const value = snapshot(); value.revision = 3; value.confirmed_revisions = [1]
  const parsed = parseCaptionSnapshot(value, EXPORT, clipId())
  assert.equal(captionFileUrl(parsed, 1, 'srt'), `${captionBase(EXPORT, clipId())}/files/srt?revision=1`)
  assert.equal(captionFileUrl(parsed, 1, 'package'), `${captionBase(EXPORT, clipId())}/package?revision=1`)
  assert.throws(() => captionFileUrl(parsed, 3, 'txt'), CaptionResponseError)
  const historical = confirmed(); historical.confirmed_revisions = [1, 3]
  assert.equal(parseCaptionSnapshot(historical, EXPORT, clipId(), 1).revision, 1)
})
test('batch preparation rejects unfinished clips before reading or omitting any item', async () => {
  const value = batch(); value.clips[1]!.status = 'FAILED'; let reads = 0
  await assert.rejects(prepareCaptionBatch(value, async () => { reads++; return confirmed() }), error => error instanceof CaptionBatchError && error.code === 'BATCH_INCOMPLETE' && error.clips.length === 1)
  assert.equal(reads, 0)
})
test('batch packaging requires every caption set to exist and have a confirmed revision', async () => {
  await assert.rejects(prepareCaptionBatch(batch(), async () => { throw { code: 'CAPTIONS_NOT_INITIALIZED' } }), error => error instanceof CaptionBatchError && error.code === 'CAPTIONS_MISSING' && error.clips.length === 2)
  await assert.rejects(prepareCaptionBatch(batch(1), async () => snapshot()), error => error instanceof CaptionBatchError && error.code === 'CAPTIONS_UNCONFIRMED')
  await assert.rejects(prepareCaptionBatch(batch(1), async () => { throw { code: 'NETWORK_UNAVAILABLE' } }), error => !!error && typeof error === 'object' && 'code' in error && error.code === 'NETWORK_UNAVAILABLE')
})
test('a batch freezes the highest confirmed revision per clip into a same-origin streaming URL', async () => {
  const result = await prepareCaptionBatch(batch(), async url => {
    const id = url.split('/')[5]!
    const value = confirmed(id, 2); value.revision = 4; value.status = 'DRAFT'; value.confirmed_revisions = [1, 2]
    return value
  })
  assert.deepEqual(result.items, [{ clip_id: clipId(1), caption_revision: 2 }, { clip_id: clipId(2), caption_revision: 2 }])
  const url = new URL(result.url, 'http://127.0.0.1:5173')
  assert.equal(url.pathname, `/api/exports/${EXPORT}/package`)
  assert.equal(url.searchParams.get('items'), `${clipId(1)}.2,${clipId(2)}.2`)
})
test('100-clip packages stay bounded in request concurrency and GET URL size', async () => {
  let active = 0, peak = 0
  const result = await prepareCaptionBatch(batch(100), async url => {
    active++; peak = Math.max(peak, active); await Promise.resolve(); active--
    return confirmed(url.split('/')[5]!, 12345)
  })
  assert.equal(result.items.length, 100); assert.ok(peak <= 6); assert.ok(result.url.length < 6500)
})
