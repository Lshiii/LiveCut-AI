import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import type { CaptionSnapshot } from '../captions.ts'
import { captionRenderActive, captionRenderBase, captionRenderInput, captionRenderPath, captionRenderProgressCopy,
  captionRenderReceipt, createCaptionRenderReceiptStore, newestCaptionRender, parseCaptionRender, parseCaptionRenderList, CaptionRenderResponseError, CaptionRenderReceiptStorageError,
  type CaptionRenderInput, type CaptionRenderSource } from '../captionRenders.ts'

const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/caption-render.example.json', import.meta.url), 'utf8')) as Record<string, unknown>
const source: CaptionRenderSource = { export_id: fixture.source_export_id as string, clip_id: fixture.source_clip_id as string, clip_sha256: fixture.source_clip_sha256 as string }
const raw = () => structuredClone(fixture)
function actions(value: Record<string, unknown>) { return value.actions as Record<string, unknown> }
function success() {
  const value = raw(); Object.assign(value, { status: 'SUCCEEDED', state_version: 5, attempt: 1,
    content_url: `/api/caption-renders/${value.id}/content`, download_url: `/api/caption-renders/${value.id}/content?download=true`,
    size_bytes: 12345, media: { duration_ms: 4034, width: 1280, height: 720 } })
  actions(value).can_cancel = false
  return value
}
function confirmed(): CaptionSnapshot {
  return { revision: 1, status: 'CONFIRMED', confirmed_revisions: [0, 1], content_hash: 'a'.repeat(64), cues: [{ requires_review: false }] } as unknown as CaptionSnapshot
}

test('the documented render fixture is independent of the clean export and bound to exact source identities', () => {
  const value = parseCaptionRender(raw(), source, fixture.id as string)
  assert.equal(captionRenderBase(source), `/api/exports/${source.export_id}/clips/${source.clip_id}/caption-renders`)
  assert.equal(captionRenderPath(value.id), `/api/caption-renders/${value.id}`)
  assert.equal(value.profile_version, 'caption-burn-v1'); assert.equal(value.font_profile, 'noto-sans-cjk-sc-2.004')
  assert.equal(value.status, 'QUEUED'); assert.equal(value.content_url, null); assert.equal(value.media, null)
  for (const change of [
    (v: Record<string, unknown>) => { v.source_export_id = v.id },
    (v: Record<string, unknown>) => { v.source_clip_id = v.id },
    (v: Record<string, unknown>) => { v.source_clip_sha256 = 'c'.repeat(64) },
    (v: Record<string, unknown>) => { v.job_id = '../job' },
  ]) { const v = raw(); change(v); assert.throws(() => parseCaptionRender(v, source), CaptionRenderResponseError) }
  assert.throws(() => parseCaptionRender(raw(), source, source.clip_id), CaptionRenderResponseError)
  assert.throws(() => captionRenderPath('../secret'), CaptionRenderResponseError)
})

test('unknown status, presets, profile, unsafe error text and malformed source hashes are rejected', () => {
  for (const [field, value] of [
    ['status', 'PARTIAL_FAILED'], ['style_preset', '{arbitrary_filter}'], ['frame_preset', 'CROP'], ['font_profile', 'system-font'],
    ['profile_version', 'future'], ['caption_content_hash', 'wrong'], ['caption_revision', -1], ['state_version', 0],
    ['error_code', 'private https://provider/key'], ['progress', undefined], ['created_at', 'not-a-date'],
  ]) { const v = raw(); v[field as string] = value; assert.throws(() => parseCaptionRender(v, source), CaptionRenderResponseError) }
})

test('only a completed render can publish its own native media and download URLs', () => {
  const ready = parseCaptionRender(success(), source)
  assert.ok(ready.download_url?.endsWith('/content?download=true'))
  for (const [field, value] of [
    ['content_url', 'https://other.example/video.mp4'], ['content_url', `/api/caption-renders/${source.clip_id}/content`],
    ['download_url', 'javascript:alert(1)'], ['size_bytes', null], ['media', null], ['error_code', 'DECODING_FAILED'],
  ]) { const v = success(); v[field as string] = value; assert.throws(() => parseCaptionRender(v, source), CaptionRenderResponseError) }
  for (const field of ['content_url', 'download_url', 'size_bytes', 'media']) {
    const v = raw(); v[field] = success()[field]; assert.throws(() => parseCaptionRender(v, source), CaptionRenderResponseError)
  }
})

test('success dimensions match the chosen frame preset and the output must have even safe dimensions', () => {
  const landscape = success(); landscape.frame_preset = 'LANDSCAPE_720'; assert.equal(parseCaptionRender(landscape, source).media?.width, 1280)
  const portrait = success(); portrait.frame_preset = 'PORTRAIT_720'; portrait.media = { duration_ms: 4034, width: 720, height: 1280 }
  assert.equal(parseCaptionRender(portrait, source).media?.height, 1280)
  for (const [width, height] of [[1281, 720], [0, 720], [1280, 4098], [720, 1280]]) {
    const v = success(); v.frame_preset = 'LANDSCAPE_720'; v.media = { duration_ms: 4034, width, height }
    assert.throws(() => parseCaptionRender(v, source), CaptionRenderResponseError)
  }
})

test('real progress is bounded and encoding 100 percent is not represented as a completed file', () => {
  const v = raw(); v.status = 'RUNNING'; v.attempt = 1; v.state_version = 3
  v.progress = { phase: 'ENCODING', processed_ms: 4000, total_ms: 4000, updated_at: fixture.updated_at }
  const parsed = parseCaptionRender(v, source)
  assert.equal(parsed.content_url, null); assert.equal(captionRenderActive(parsed), true)
  assert.match(captionRenderProgressCopy(parsed.progress), /100%.*校验/)
  assert.match(captionRenderProgressCopy(null), /等待执行服务/)
  assert.match(captionRenderProgressCopy({ ...parsed.progress!, phase: 'VALIDATING' }), /检查/)
  for (const patch of [{ processed_ms: 4001 }, { processed_ms: -1 }, { total_ms: 0 }, { phase: 'DONE' }, { updated_at: 'invalid' }]) {
    const bad = { ...v, progress: { ...(v.progress as object), ...patch } }; assert.throws(() => parseCaptionRender(bad, source), CaptionRenderResponseError)
  }
})

test('cancellation cannot advertise retry and exhausted failures cannot advertise another attempt', () => {
  const cancelled = raw(); cancelled.status = 'CANCELLED'; actions(cancelled).can_cancel = false; actions(cancelled).retry_block_reason = 'CANCELLED_REQUIRES_NEW_RENDER'
  assert.equal(parseCaptionRender(cancelled, source).actions.can_retry, false)
  actions(cancelled).can_retry = true; actions(cancelled).retry_block_reason = null
  assert.throws(() => parseCaptionRender(cancelled, source), CaptionRenderResponseError)
  const failed = raw(); failed.status = 'FAILED'; failed.attempt = 3; actions(failed).can_cancel = false; actions(failed).can_retry = true; actions(failed).retry_block_reason = null
  assert.throws(() => parseCaptionRender(failed, source), CaptionRenderResponseError)
  failed.attempt = 2; assert.equal(parseCaptionRender(failed, source).actions.can_retry, true)
})

test('late polling cannot roll back versions, attempts, or revive cancelled and successful renders', () => {
  const done = parseCaptionRender(success(), source)
  assert.equal(newestCaptionRender(done, parseCaptionRender(raw(), source)), done)
  const cancelledRaw = raw(); cancelledRaw.status = 'CANCELLED'; cancelledRaw.state_version = 6; actions(cancelledRaw).can_cancel = false; actions(cancelledRaw).retry_block_reason = 'CANCELLED_REQUIRES_NEW_RENDER'
  const cancelled = parseCaptionRender(cancelledRaw, source), resurrected = raw(); resurrected.state_version = 7
  assert.equal(newestCaptionRender(cancelled, parseCaptionRender(resurrected, source)), cancelled)
  assert.equal(newestCaptionRender(done, parseCaptionRender({ ...raw(), state_version: 20 }, source)), done)
})

test('permanent subtitle-content failures use server actions and require a new confirmed input', () => {
  for (const error of ['CAPTION_RENDER_GLYPH_UNSUPPORTED', 'CAPTION_RENDER_TEXT_TOO_LARGE', 'CAPTION_RENDER_TIMING_UNSUPPORTED']) {
    const value = raw(); value.status = 'FAILED'; value.error_code = error; value.attempt = 1
    Object.assign(actions(value), { can_cancel: false, can_retry: false, retry_block_reason: 'INPUT_REQUIRES_NEW_RENDER' })
    const parsed = parseCaptionRender(value, source)
    assert.equal(parsed.actions.can_retry, false); assert.equal(parsed.actions.retry_block_reason, 'INPUT_REQUIRES_NEW_RENDER')
    actions(value).can_retry = true
    assert.throws(() => parseCaptionRender(value, source), CaptionRenderResponseError)
  }
})

test('a failed render may retry with the same frozen input while immutable identity changes are rejected', () => {
  const failed = raw(); failed.status = 'FAILED'; failed.state_version = 4; failed.attempt = 1; actions(failed).can_cancel = false; actions(failed).can_retry = true; actions(failed).retry_block_reason = null
  const known = parseCaptionRender(failed, source), retried = parseCaptionRender({ ...raw(), state_version: 5, attempt: 1 }, source)
  assert.equal(newestCaptionRender(known, retried), retried)
  for (const patch of [{ caption_content_hash: 'c'.repeat(64) }, { caption_revision: 2 }, { style_preset: 'DARK_BOX_V1' }, { frame_preset: 'LANDSCAPE_720' }, { job_id: source.export_id }]) {
    const changed = parseCaptionRender({ ...raw(), ...patch, state_version: 10 }, source)
    assert.throws(() => newestCaptionRender(known, changed), CaptionRenderResponseError)
  }
  const done = parseCaptionRender(success(), source), changed = success(); changed.state_version = 6; changed.size_bytes = 22222
  assert.throws(() => newestCaptionRender(done, parseCaptionRender(changed, source)), CaptionRenderResponseError)
})

test('history rejects duplicates, cross-job records, and oversized responses while ordering newest first', () => {
  const second = raw(); second.id = '55555555-5555-4555-8555-555555555555'; second.created_at = '2026-11-07T01:00:00Z'
  assert.equal(parseCaptionRenderList({ items: [raw(), second] }, source)[0]?.id, second.id)
  assert.throws(() => parseCaptionRenderList({ items: [raw(), raw()] }, source), CaptionRenderResponseError)
  second.job_id = source.export_id
  assert.throws(() => parseCaptionRenderList({ items: [raw(), second] }, source), CaptionRenderResponseError)
  assert.throws(() => parseCaptionRenderList({ items: Array(51).fill(raw()) }, source), CaptionRenderResponseError)
})

test('the create body freezes exactly a nonempty explicitly confirmed caption revision', () => {
  const caption = confirmed(), input = captionRenderInput(caption, 'DARK_BOX_V1', 'PORTRAIT_720')
  assert.deepEqual(input, { caption_revision: 1, caption_content_hash: 'a'.repeat(64), style_preset: 'DARK_BOX_V1', frame_preset: 'PORTRAIT_720' })
  caption.revision = 0; assert.equal(captionRenderInput(caption, 'WHITE_OUTLINE_V1', 'SOURCE').caption_revision, 0)
  for (const change of [
    (v: CaptionSnapshot) => { v.status = 'DRAFT' }, (v: CaptionSnapshot) => { v.confirmed_revisions = [] },
    (v: CaptionSnapshot) => { v.cues = [] }, (v: CaptionSnapshot) => { v.cues[0]!.requires_review = true },
  ]) { const v = confirmed(); change(v); assert.throws(() => captionRenderInput(v, 'WHITE_OUTLINE_V1', 'SOURCE'), CaptionRenderResponseError) }
})

test('unknown create outcomes reuse the receipt only for the same four frozen fields', () => {
  const input = captionRenderInput(confirmed(), 'WHITE_OUTLINE_V1', 'SOURCE')
  let issued = 0; const issue = () => `key-${++issued}`
  const first = captionRenderReceipt(null, input, issue)
  assert.deepEqual(captionRenderReceipt(JSON.parse(JSON.stringify(first)), input, issue), first); assert.equal(issued, 1)
  for (const patch of [{ caption_revision: 0 }, { caption_content_hash: 'b'.repeat(64) }, { style_preset: 'DARK_BOX_V1' }, { frame_preset: 'PORTRAIT_720' }]) {
    assert.notEqual(captionRenderReceipt(first, { ...input, ...patch } as CaptionRenderInput, issue).key, first.key)
  }
  assert.notEqual(captionRenderReceipt({ input: first.input, key: 'unsafe key with spaces' }, input, issue).key, first.key)
  assert.notEqual(captionRenderReceipt(null, input, issue).key, first.key)
  assert.throws(() => captionRenderReceipt(null, input, () => '../invalid'), CaptionRenderResponseError)
})

function receiptStorage() {
  const values = new Map<string, string>()
  return { values, getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value) }, removeItem: (key: string) => { values.delete(key) } }
}

test('unknown A survives B submission and successful B acknowledgement, including page reload', () => {
  const storage = receiptStorage(), base = 'caption-source-one'
  const a = captionRenderInput(confirmed(), 'WHITE_OUTLINE_V1', 'SOURCE'), b = { ...a, frame_preset: 'PORTRAIT_720' } as CaptionRenderInput
  let issued = 0; const issue = () => `request-${++issued}`
  const firstPage = createCaptionRenderReceiptStore(base, storage, issue)
  const pendingA = firstPage.prepare(a), pendingB = firstPage.prepare(b)
  assert.notEqual(pendingA.key, pendingB.key)
  assert.deepEqual(firstPage.prepare(a), pendingA, 'changing configuration must not replace mounted unknown A')
  assert.equal(firstPage.accept(b, pendingB), true)
  assert.deepEqual([...storage.values.values()].map(value => JSON.parse(value)), [pendingA], 'accepting B must leave A persisted')
  const reloadedPage = createCaptionRenderReceiptStore(base, storage, issue)
  assert.deepEqual(reloadedPage.prepare(a), pendingA, 'reload must recover exactly the original unknown key')
  assert.equal(issued, 2)
  assert.notEqual(reloadedPage.prepare(b).key, pendingB.key, 'an acknowledged configuration can intentionally create a new task')
  assert.deepEqual(reloadedPage.prepare(a), pendingA)
})

test('all frozen input fields and source identities isolate persisted unknown outcomes', () => {
  const storage = receiptStorage(), input = captionRenderInput(confirmed(), 'WHITE_OUTLINE_V1', 'SOURCE')
  let issued = 0; const issue = () => `request-${++issued}`
  const variants: CaptionRenderInput[] = [input, { ...input, caption_revision: 0 }, { ...input, caption_content_hash: 'b'.repeat(64) },
    { ...input, style_preset: 'DARK_BOX_V1' }, { ...input, frame_preset: 'LANDSCAPE_720' }]
  const one = createCaptionRenderReceiptStore('source-one', storage, issue)
  const receipts = variants.map(value => one.prepare(value))
  assert.equal(new Set(receipts.map(value => value.key)).size, variants.length)
  const two = createCaptionRenderReceiptStore('source-two', storage, issue), other = two.prepare(input)
  assert.notEqual(other.key, receipts[0]!.key)
  const reload = createCaptionRenderReceiptStore('source-one', storage, issue)
  assert.deepEqual(variants.map(value => reload.prepare(value)), receipts)
  assert.equal(storage.values.size, variants.length + 1)
})

test('legacy unknown receipts are reused only for their exact body and never cleared by another configuration', () => {
  const storage = receiptStorage(), base = 'legacy-source'
  const a = captionRenderInput(confirmed(), 'WHITE_OUTLINE_V1', 'SOURCE'), b = { ...a, style_preset: 'DARK_BOX_V1' } as CaptionRenderInput
  const legacyA = captionRenderReceipt(null, a, () => 'legacy-A')
  storage.setItem(base, JSON.stringify(legacyA))
  const store = createCaptionRenderReceiptStore(base, storage, () => 'new-B'), pendingB = store.prepare(b)
  store.accept(b, pendingB)
  assert.deepEqual(JSON.parse(storage.getItem(base)!), legacyA)
  assert.deepEqual(store.prepare(a), legacyA)
  const reload = createCaptionRenderReceiptStore(base, storage, () => 'must-not-use-new-key')
  assert.deepEqual(reload.prepare(a), legacyA)
  reload.accept(a, legacyA)
  assert.equal(storage.values.size, 0)
})

test('acknowledgement cannot delete a later key from another tab or a different frozen input', () => {
  const storage = receiptStorage(), input = captionRenderInput(confirmed(), 'WHITE_OUTLINE_V1', 'SOURCE')
  const store = createCaptionRenderReceiptStore('source', storage, () => 'old-key'), old = store.prepare(input)
  const location = [...storage.values.keys()][0]!
  const newer = captionRenderReceipt(null, input, () => 'newer-key')
  storage.setItem(location, JSON.stringify(newer))
  store.accept(input, old)
  assert.deepEqual(JSON.parse(storage.getItem(location)!), newer)
  assert.deepEqual(createCaptionRenderReceiptStore('source', storage, () => 'unexpected').prepare(input), newer)
})

test('a stale mounted receipt cannot overwrite a newer persisted request for the same configuration', () => {
  const storage = receiptStorage(), input = captionRenderInput(confirmed(), 'WHITE_OUTLINE_V1', 'SOURCE')
  const oldPage = createCaptionRenderReceiptStore('source', storage, () => 'older-key')
  oldPage.prepare(input)
  const location = [...storage.values.keys()][0]!, newer = captionRenderReceipt(null, input, () => 'newer-key')
  storage.setItem(location, JSON.stringify(newer))
  assert.deepEqual(oldPage.prepare(input), newer)
  assert.deepEqual(JSON.parse(storage.getItem(location)!), newer)
})

test('storage failures block new submission without evicting unknown receipts, and cleanup failure preserves accepted identity', () => {
  const storage = receiptStorage(), a = captionRenderInput(confirmed(), 'WHITE_OUTLINE_V1', 'SOURCE')
  const store = createCaptionRenderReceiptStore('source', storage, () => 'pending-A'), pendingA = store.prepare(a)
  const before = [...storage.values]
  const broken = { ...storage, setItem: () => { throw new Error('quota exceeded') } }
  const newPage = createCaptionRenderReceiptStore('source', broken, () => 'unused-new-key')
  assert.throws(() => newPage.prepare({ ...a, frame_preset: 'PORTRAIT_720' }), CaptionRenderReceiptStorageError)
  assert.deepEqual([...storage.values], before)
  const cannotClean = createCaptionRenderReceiptStore('source', { ...storage, removeItem: () => { throw new Error('storage denied') } }, () => 'unexpected')
  assert.equal(cannotClean.accept(a, pendingA), false)
  assert.deepEqual(createCaptionRenderReceiptStore('source', storage, () => 'unexpected').prepare(a), pendingA)
})
