import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { ManualClipError, manualClipCanonical, manualClipDetailUrl, manualClipDraftError, manualClipInput, manualClipListUrl,
  manualClipRangeUrl, manualClipRevisionInput, manualClipSubmissionPlanMatches, parseManualClipInput, parseManualClipList, parseManualClipPlan,
  parseManualClipRange, parseManualClipSource, parseManualClipSubmission, verifyManualClipPlan, verifyManualClipSubmission,
  type ManualClipInput, type ManualClipRevisionInput } from '../manualClips.ts'
import { assertManualClipPending, clearManualClipPending, createManualClipReceiptStore, manualClipReceiptSlot, manualClipReceiptStorageKey, manualClipSubmissionMissing, manualClipSubmissionUrl, scanManualClipPending,
  manualClipWriteUrl, parseManualClipExportInput, parseManualClipPending, prepareManualClipPending, readManualClipPending,
  type ManualClipReceiptStore } from '../manualClipReceipts.ts'

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const owner = { job_id: id(1), video_id: id(2), generation: 3, source_video_sha256: 'a'.repeat(64) }
const source = { ...owner, transcript_sha256: 'b'.repeat(64), analysis_sha256: 'c'.repeat(64), duration_ms: 10000, source_fingerprint: 'd'.repeat(64) }
const sentence = (n: number) => ({ utterance_id: `u${n}`, start_ms: n * 1000, end_ms: (n + 1) * 1000, text: `原句 ${n} <script>原文</script>` })
const input = (): ManualClipInput => ({ source_fingerprint: source.source_fingerprint, title: '  人工片段 标题  ', start_utterance_id: 'u0', end_utterance_id: 'u2' })
const revisionInput = (): ManualClipRevisionInput => ({ base_revision: 1, base_plan_hash: 'e'.repeat(64), ...input() })
function plan(revision = 1, latest = revision) {
  return { schema_version: '1.0', data_kind: 'manual_clip_plan', source_kind: 'MANUAL_ASR', id: id(5), source: { ...source }, revision, latest_revision: latest,
    plan_hash: 'e'.repeat(64), status: 'DRAFT', confirmed_revisions: [] as number[], created_at: '2026-11-11T10:00:00.123456Z', updated_at: '2026-11-11T10:00:00.123456Z',
    title: input().title, start_utterance_id: 'u0', end_utterance_id: 'u2', range: { start_ms: 0, end_ms: 3000, utterance_count: 3, start_text: sentence(0).text, end_text: sentence(2).text } }
}
function submission(operation: 'CREATE' | 'REVISION' = 'CREATE') {
  return { schema_version: '1.0', data_kind: 'manual_clip_submission', operation, job_id: owner.job_id, plan_id: id(5), idempotency_key: id(6),
    input: operation === 'CREATE' ? input() : revisionInput(), request_hash: 'f'.repeat(64), revision: operation === 'CREATE' ? 1 : 2, plan_hash: 'e'.repeat(64) }
}
const clone = <T>(value: T): T => structuredClone(value)
const code = (expected: string) => (error: unknown) => error instanceof ManualClipError && error.code === expected
const sha = (value: string) => createHash('sha256').update(value, 'utf8').digest('hex')
function storage(raw: string | null = null) {
  const value = { raw, writes: 0, removes: 0 }
  const store: ManualClipReceiptStore = { read: () => value.raw, write: text => { value.writes++; value.raw = text }, remove: () => { value.removes++; value.raw = null } }
  return { value, store }
}

test('manual draft uses exact title and ASR IDs without client times or model fields, validates UTF16 and rejects reversed boundaries', () => {
  assert.equal(manualClipDraftError(source, input().title, sentence(0), sentence(2)), '')
  const value = manualClipInput(source, input().title, sentence(0), sentence(2)); assert.deepEqual(value, input()); assert.ok(Object.isFrozen(value))
  assert.deepEqual(Object.keys(value).sort(), ['end_utterance_id', 'source_fingerprint', 'start_utterance_id', 'title'])
  for (const title of ['', '  ', '😀'.repeat(61), 'x\u0000', '\ud800']) assert.ok(manualClipDraftError(source, title, sentence(0), sentence(2)))
  assert.equal(manualClipDraftError(source, '😀'.repeat(60), sentence(0), sentence(0)), '')
  assert.match(manualClipDraftError(source, '标题', sentence(2), sentence(0)), /不会自动交换/)
  assert.throws(() => manualClipInput(source, '标题', null, sentence(2)), code('MANUAL_CLIP_INVALID_RANGE'))
  assert.throws(() => parseManualClipInput({ ...input(), start_ms: 0 }), ManualClipError)
})

test('manual details strictly validate complete source, range, r1+ revisions, confirmations and exact requested revision', () => {
  const raw = plan(), parsed = parseManualClipPlan(raw, owner, { id: id(5), revision: 1, source })
  assert.equal(parsed.range.start_ms, 0); assert.equal(parsed.range.utterance_count, 3)
  assert.ok(Object.isFrozen(parsed) && Object.isFrozen(parsed.source) && Object.isFrozen(parsed.range) && Object.isFrozen(parsed.confirmed_revisions))
  const confirmed = plan(1, 2); confirmed.status = 'CONFIRMED'; confirmed.confirmed_revisions = [1]
  assert.equal(parseManualClipPlan(confirmed, owner).status, 'CONFIRMED')
  const changes: Array<(v: any) => void> = [v => { v.source.job_id = id(9) }, v => { v.source.video_id = id(9) }, v => { v.source.generation++ },
    v => { v.source.source_video_sha256 = '0'.repeat(64) }, v => { v.source.transcript_sha256 = '0'.repeat(64) }, v => { v.range.end_ms = 10001 },
    v => { v.range.utterance_count = 1 }, v => { v.revision = 0 }, v => { v.latest_revision = 0 }, v => { v.confirmed_revisions = [1] },
    v => { v.confirmed_revisions = [2, 1] }, v => { v.range.extra = true }, v => { v.source.private_key = 'bad' }, v => { v.extra = true }]
  for (const change of changes) { const value = clone(raw); change(value); assert.throws(() => parseManualClipPlan(value, owner, { source }), ManualClipError) }
  const single = plan(); single.end_utterance_id = 'u0'; single.range.end_ms = 1000; single.range.utterance_count = 1; single.range.end_text = single.range.start_text
  assert.equal(parseManualClipPlan(single, owner).range.utterance_count, 1)
})

test('manual source equality rejects changed lineage even if its fingerprint label is unchanged', () => {
  for (const field of ['transcript_sha256', 'analysis_sha256', 'duration_ms', 'source_fingerprint']) {
    const changed: any = { ...source }; changed[field] = field === 'duration_ms' ? 20000 : '0'.repeat(64)
    assert.throws(() => parseManualClipSource(changed, owner, source), code('MANUAL_CLIP_SOURCE_CHANGED'))
  }
})

test('manual history is metadata only and validates source owner, duplicate IDs and exact requested page', () => {
  const detail = plan(), item = { id: detail.id, job_id: owner.job_id, video_id: owner.video_id, title: detail.title,
    revision: detail.revision, plan_hash: detail.plan_hash, status: detail.status, created_at: detail.created_at, updated_at: detail.updated_at }
  const raw = { schema_version: '1.0', data_kind: 'manual_clip_plan_list', job_id: owner.job_id, items: [item], total: 1, offset: 0, limit: 20 }
  const checked = parseManualClipList(raw, owner, { offset: 0, limit: 20 }); assert.equal(checked.items.length, 1); assert.ok(!('range' in checked.items[0]!))
  assert.throws(() => parseManualClipPlan(checked.items[0], owner), ManualClipError)
  for (const update of [{ items: [item, item], total: 2 }, { offset: 1 }, { total: 0 }, { job_id: id(9) }, { limit: 50 }]) assert.throws(() => parseManualClipList({ ...raw, ...update }, owner, { offset: 0, limit: 20 }), ManualClipError)
  assert.ok(manualClipListUrl(owner.job_id, Number.MAX_SAFE_INTEGER).includes(String(Number.MAX_SAFE_INTEGER)))
  assert.equal(manualClipDetailUrl(id(5), 1), `/api/manual-clip-plans/${id(5)}?revision=1`)
})

test('manual range GET pins all source fields and exact start/end texts and includes unselected middle sentence count', () => {
  const raw = { schema_version: '1.0', data_kind: 'transcript_range', source, start_utterance_id: 'u0', end_utterance_id: 'u2', range: plan().range }
  const checked = parseManualClipRange(raw, owner, source, sentence(0), sentence(2)); assert.equal(checked.range.utterance_count, 3)
  const url = new URL(manualClipRangeUrl(source, 'u0', 'u2'), 'http://local'); assert.equal(url.searchParams.get('source_fingerprint'), source.source_fingerprint)
  for (const change of [(v: any) => { v.start_utterance_id = 'foreign' }, (v: any) => { v.range.start_ms = 1 },
    (v: any) => { v.range.end_text = 'different' }, (v: any) => { v.range.utterance_count = 1 }, (v: any) => { v.source.analysis_sha256 = '0'.repeat(64) }]) {
    const value = clone(raw); change(value); assert.throws(() => parseManualClipRange(value, owner, source, sentence(0), sentence(2)), ManualClipError)
  }
})

test('manual revision payload requires the actual head and never carries prior confirmation permissions', () => {
  const p = parseManualClipPlan(plan(), owner), payload = manualClipRevisionInput(p, input())
  assert.deepEqual(payload, revisionInput()); assert.ok(!('status' in payload)); assert.ok(!('confirmed_revisions' in payload))
  assert.throws(() => manualClipRevisionInput(parseManualClipPlan(plan(1, 2), owner), input()), code('MANUAL_CLIP_REVISION_CONFLICT'))
})

test('manual immutable receipt pins operation/key/body/revision and cannot be acknowledged with a newer head', () => {
  for (const operation of ['CREATE', 'REVISION'] as const) {
    const raw = submission(operation), expected = { operation, job_id: owner.job_id, plan_id: operation === 'CREATE' ? null : id(5), key: id(6), input: JSON.stringify(raw.input) }
    const receipt = parseManualClipSubmission(raw, expected)
    assert.equal(manualClipSubmissionPlanMatches(parseManualClipPlan(plan(receipt.revision, receipt.revision + 1), owner), receipt), true)
    assert.equal(manualClipSubmissionPlanMatches(parseManualClipPlan(plan(receipt.revision + 1), owner), receipt), false)
    for (const update of [{ idempotency_key: id(9) }, { operation: operation === 'CREATE' ? 'REVISION' : 'CREATE' }, { job_id: id(9) },
      { revision: raw.revision + 1 }, { input: { ...raw.input, title: '不同标题' } }, { plan_hash: 'broken' }, { extra: true }]) {
      assert.throws(() => parseManualClipSubmission({ ...raw, ...update }, expected), ManualClipError)
    }
  }
})

test('manual cryptographic checks cover complete immutable plan/source and exact operation owner/canonical input with trailing LF', async () => {
  const raw = plan(), content = { schema_version: '1.0', data_kind: 'manual_clip_plan_revision', source_kind: 'MANUAL_ASR', id: raw.id, source: raw.source,
    revision: raw.revision, title: raw.title, start_utterance_id: raw.start_utterance_id, end_utterance_id: raw.end_utterance_id, range: raw.range }
  raw.plan_hash = sha(manualClipCanonical(content)); assert.equal((await verifyManualClipPlan(parseManualClipPlan(raw, owner))).plan_hash, raw.plan_hash)
  const newerHead = { ...raw, latest_revision: 2, updated_at: '2026-11-11T11:00:00Z' }; assert.equal((await verifyManualClipPlan(parseManualClipPlan(newerHead, owner))).plan_hash, raw.plan_hash)
  await assert.rejects(verifyManualClipPlan(parseManualClipPlan({ ...raw, title: '修改' }, owner)), code('MANUAL_CLIP_SOURCE_INVALID'))
  for (const operation of ['CREATE', 'REVISION'] as const) {
    const receipt = submission(operation), ownerId = operation === 'CREATE' ? owner.job_id : id(5)
    receipt.request_hash = sha(`manual-clip-request-v1\n${operation}\n${ownerId}\n${manualClipCanonical(receipt.input)}\n`)
    const expected = { operation, job_id: owner.job_id, plan_id: operation === 'CREATE' ? null : id(5), key: id(6), input: JSON.stringify(receipt.input) }
    assert.equal((await verifyManualClipSubmission(receipt, expected)).request_hash, receipt.request_hash)
    await assert.rejects(verifyManualClipSubmission({ ...receipt, request_hash: sha(`manual-clip-request-v1\n${operation}\n${ownerId}\n${manualClipCanonical(receipt.input)}`) }, expected), code('MANUAL_CLIP_SUBMISSION_INVALID'))
  }
})

test('manual pending create/revision/export entries write and reread exact original body before any caller may POST', () => {
  for (const operation of ['CREATE', 'REVISION', 'EXPORT'] as const) {
    const { store, value } = storage(), body = operation === 'CREATE' ? input() : operation === 'REVISION' ? revisionInput() : { revision: 1, plan_hash: 'e'.repeat(64), profile: 'h264-aac-v1' as const }
    const pending = prepareManualClipPending(store, owner.job_id, owner.video_id, operation, operation === 'CREATE' ? null : id(5), body, () => id(6))
    assert.equal(value.writes, 1); assert.deepEqual(readManualClipPending(store, owner.job_id, owner.video_id), pending)
    assert.equal(pending.input, JSON.stringify(body)); assertManualClipPending(store, pending)
    assert.ok(manualClipWriteUrl(pending).startsWith('/api/')); assert.ok(manualClipSubmissionUrl(pending).endsWith(id(6)))
    assert.throws(() => prepareManualClipPending(store, owner.job_id, owner.video_id, operation, pending.plan_id, body, () => id(9)), code('MANUAL_CLIP_PENDING_EXISTS'))
    assert.equal(readManualClipPending(store, owner.job_id)!.key, id(6)); assert.equal(value.writes, 1)
    if (operation !== 'EXPORT') assert.equal(parseManualClipSubmission(submission(operation), pending).idempotency_key, pending.key)
  }
  assert.ok(manualClipReceiptSlot(owner.job_id).includes(owner.job_id))
})

test('manual pending storage refuses quota failure, lost readback, damage and different-owner records without deleting them', () => {
  const { store, value } = storage()
  assert.throws(() => prepareManualClipPending({ ...store, write: () => { throw new Error('quota') } }, owner.job_id, owner.video_id, 'CREATE', null, input(), () => id(6)), code('MANUAL_CLIP_STORAGE_UNAVAILABLE'))
  assert.throws(() => prepareManualClipPending({ ...store, write: () => {} }, owner.job_id, owner.video_id, 'CREATE', null, input(), () => id(6)), code('MANUAL_CLIP_STORAGE_UNAVAILABLE'))
  value.raw = '{broken'; assert.throws(() => readManualClipPending(store, owner.job_id), code('MANUAL_CLIP_LOCAL_RECEIPT_INVALID'))
  assert.throws(() => prepareManualClipPending(store, owner.job_id, owner.video_id, 'CREATE', null, input(), () => id(6)), code('MANUAL_CLIP_LOCAL_RECEIPT_INVALID')); assert.equal(value.raw, '{broken')
  value.raw = null; const entry = prepareManualClipPending(store, owner.job_id, owner.video_id, 'CREATE', null, input(), () => id(6))
  value.raw = JSON.stringify({ ...entry, video_id: id(9) }); assert.throws(() => readManualClipPending(store, owner.job_id, owner.video_id), code('MANUAL_CLIP_LOCAL_RECEIPT_INVALID'))
})

test('manual pending cleanup removes only the identical own entry and retains newer or malformed records', () => {
  const { store, value } = storage(), entry = prepareManualClipPending(store, owner.job_id, owner.video_id, 'CREATE', null, input(), () => id(6))
  value.raw = JSON.stringify({ ...entry, key: id(7) }); assert.equal(clearManualClipPending(store, entry), false); assert.equal(value.removes, 0)
  assert.throws(() => assertManualClipPending(store, entry), code('MANUAL_CLIP_STORAGE_UNAVAILABLE'))
  value.raw = '{broken'; assert.throws(() => clearManualClipPending(store, entry), ManualClipError); assert.equal(value.removes, 0)
  value.raw = JSON.stringify(entry); assert.equal(clearManualClipPending(store, entry), true); assert.equal(value.removes, 1)
})

test('manual reconciliation allows original replay only after the precise business 404 and keeps export identity separate', () => {
  assert.equal(manualClipSubmissionMissing({ status: 404, code: 'MANUAL_CLIP_SUBMISSION_NOT_FOUND' }), true)
  for (const value of [{ status: 404, code: 'MANUAL_CLIP_NOT_FOUND' }, { status: 500, code: 'MANUAL_CLIP_SUBMISSION_NOT_FOUND' },
    { status: '404', code: 'MANUAL_CLIP_SUBMISSION_NOT_FOUND' }, { code: 'NETWORK_UNAVAILABLE' }]) assert.equal(manualClipSubmissionMissing(value), false)
  const { store } = storage(); const entry = prepareManualClipPending(store, owner.job_id, owner.video_id, 'EXPORT', id(5), { revision: 1, plan_hash: 'e'.repeat(64), profile: 'h264-aac-v1' }, () => id(6))
  assert.equal(manualClipSubmissionUrl(entry), `/api/manual-clip-plans/${id(5)}/export-submissions/${id(6)}`)
  assert.throws(() => parseManualClipSubmission(submission(), entry), ManualClipError)
  assert.throws(() => parseManualClipExportInput({ revision: 0, plan_hash: 'e'.repeat(64), profile: 'h264-aac-v1' }), ManualClipError)
  assert.throws(() => parseManualClipPending({ ...entry, input: JSON.stringify({ ...input(), profile: 'h264-aac-v1' }) }), ManualClipError)
})

test('real localStorage factory retains both interleaved writers and cleanup only deletes its immutable physical key', () => {
  const data = new Map<string, string>(), browserStorage = { get length() { return data.size }, key: (index: number) => [...data.keys()][index] ?? null,
    getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, raw: string) => { data.set(key, raw) }, removeItem: (key: string) => { data.delete(key) } }
  const writerA = createManualClipReceiptStore(browserStorage, owner.job_id, owner.video_id), writerB = createManualClipReceiptStore(browserStorage, owner.job_id, owner.video_id)
  let b: ReturnType<typeof prepareManualClipPending> | undefined
  // A has observed an empty job. B finishes persistence before A writes its own request.
  const interleavedA = { ...writerA, read: (entry?: Parameters<typeof manualClipReceiptStorageKey>[0]) => {
    const raw = writerA.read(entry)
    if (!entry && !b) b = prepareManualClipPending(writerB, owner.job_id, owner.video_id, 'CREATE', null, { ...input(), title: '页面 B' }, () => id(7))
    return raw
  } }
  const a = prepareManualClipPending(interleavedA, owner.job_id, owner.video_id, 'CREATE', null, input(), () => id(6))
  assert.ok(b); assert.equal(data.size, 2); assert.notEqual(manualClipReceiptStorageKey(a), manualClipReceiptStorageKey(b))
  assertManualClipPending(writerA, a); assertManualClipPending(writerB, b)
  assert.deepEqual(scanManualClipPending(writerA, owner.job_id, owner.video_id).map(value => value.key), [id(6), id(7)])
  assert.equal(clearManualClipPending(writerA, a), true); assert.equal(data.size, 1); assertManualClipPending(writerB, b)
  assert.equal(clearManualClipPending(writerA, a), false); assert.equal(data.size, 1)
  assert.equal(clearManualClipPending(writerB, b), true); assert.equal(data.size, 0)
})
