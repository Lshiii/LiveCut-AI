import test from 'node:test'
import assert from 'node:assert/strict'
import { IntentHistoryError, IntentHistoryPager, cacheLegacyIntentDetails, compareIntentHistory, intentHistoryFromDetail,
  intentHistoryRouteMissing, intentHistoryTimeKey, mergeVerifiedIntentDetail, parseIntentHistoryPage, parseIntentHistorySummary,
  parseLegacyIntentHistoryDetails, readIntentHistory, type IntentHistoryPage } from '../intentHistory.ts'
import { IntentReadScope, IntentResponseError, intentPayload, type IntentSnapshot } from '../intentClips.ts'
import { intentSaveRecovered } from '../intentSaveRecovery.ts'
import { IntentRefreshScheduler, intentHasActiveWork, intentHistoryHasActiveWork } from '../intentRefreshScheduler.ts'

const JOB = '11111111-1111-4111-8111-111111111111', VIDEO = '22222222-2222-4222-8222-222222222222'
const OTHER_JOB = '99999999-9999-4999-8999-999999999999'
const id = (n: number) => `33333333-3333-4333-8333-${String(n).padStart(12, '0')}`
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function detail(n = 1): IntentSnapshot {
  const request = id(n), instruction = `完整保留视频解释 ${n}`, source = { video_sha256: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 3000 }
  return { id: request, job_id: JOB, video_id: VIDEO, instruction, status: 'PLAN_READY', state_version: 3, revision: 0,
    created_at: new Date(Date.parse('2026-11-07T12:00:00Z') - n * 1000).toISOString(), updated_at: '2026-11-07T12:01:00Z',
    budget: { max_text_calls: 6, text_calls_reserved: 1 }, stage: { id: '44444444-4444-4444-8444-444444444444', stage_type: 'SEGMENT', status: 'SUCCEEDED', attempt: 1, error_code: null },
    source, source_answer: null, plan_hash: 'c'.repeat(64), confirmed_revisions: [],
    utterances: ['u1', 'u2', 'u3'].map((utterance_id, index) => ({ utterance_id, start_ms: index * 1000, end_ms: (index + 1) * 1000, text: `完整私有ASR ${n}-${index}` })),
    plan: { schema_version: '1.0', request_id: request, job_id: JOB, video_id: VIDEO, instruction, source_hash: source.video_sha256,
      transcript_hash: source.transcript_hash, duration_ms: 3000, goals: [{ goal_id: 'explain', description: '解释' }], constraints: { keep_explanation_complete: true, exclude: [] },
      outputs: [{ output_id: 'explain-output', goal_id: 'explain', title: '解释片段', status: 'FOUND', reason: '引用原句', enabled: false,
        parts: [{ start_utterance_id: 'u1', end_utterance_id: 'u3', start_ms: 0, end_ms: 3000 }], excluded: [], evidence_utterance_ids: ['u1', 'u2', 'u3'] }],
      provenance: { text_model_id: 'synthetic', prompt_version: 'intent-clips-v1', retrieval_mode: 'bm25', index_hash: 'd'.repeat(64) } } }
}
function page(start = 1, count = 20, next: string | null = 'opaque_next-first') : IntentHistoryPage {
  return { schema_version: '1.0', job_id: JOB, items: Array.from({ length: count }, (_, index) => intentHistoryFromDetail(detail(start + index), JOB)),
    limit: 20, next_cursor: next, active_count: 0 }
}
const error = (status: number, code: string) => Object.assign(new Error(code), { status, code })

test('history DTOs are exact lightweight metadata and never become editable details or save acknowledgements', () => {
  const saved = detail(), summary = intentHistoryFromDetail(saved, JOB), parsed = parseIntentHistoryPage(page(), JOB)
  assert.equal(parsed.items.length, 20); assert.equal(JSON.stringify(summary).includes('完整私有ASR'), false)
  for (const name of ['utterances', 'plan', 'stage', 'source_answer', 'confirmed_revisions', 'resumed_from_request_id']) assert.equal(Object.hasOwn(summary, name), false)
  assert.throws(() => mergeVerifiedIntentDetail(saved, summary, JOB, saved.id), IntentResponseError)
  assert.equal(intentSaveRecovered(saved, summary as unknown as IntentSnapshot, intentPayload(saved.plan!.outputs).outputs, 0), false)
  const forged = { ...summary, plan: saved.plan, utterances: saved.utterances }
  assert.throws(() => parseIntentHistorySummary(forged, JOB), IntentHistoryError)
})

test('history order preserves UTC microseconds and ASCII ID ties without using updated_at', () => {
  const newer = intentHistoryFromDetail(detail(1), JOB), older = intentHistoryFromDetail(detail(2), JOB)
  newer.created_at = '2026-11-07T12:00:00.123900Z'; newer.id = id(1)
  older.created_at = '2026-11-07T12:00:00.123100Z'; older.id = id(99)
  older.updated_at = '2026-11-08T12:00:00Z'
  assert.equal(Date.parse(newer.created_at), Date.parse(older.created_at))
  assert.equal(compareIntentHistory(newer, older), -1)
  assert.equal(intentHistoryTimeKey('2026-11-07T12:00:00.1Z'), '2026-11-07T12:00:00.100000000Z')
  assert.deepEqual(parseIntentHistoryPage({ ...page(1, 2, null), items: [newer, older] }, JOB).items.map(item => item.id), [id(1), id(99)])
  assert.throws(() => parseIntentHistoryPage({ ...page(1, 2, null), items: [older, newer] }, JOB), IntentHistoryError)
  older.created_at = newer.created_at
  assert.equal(compareIntentHistory(older, newer), -1)
  for (const invalid of ['2026-02-30T00:00:00Z', '2026-11-07T12:00:00+08:00', 'yesterday', '2026-11-07T12:00:00.1234567890Z'])
    assert.throws(() => intentHistoryTimeKey(invalid), IntentHistoryError)
})

test('invalid history ownership, schema, count, cursor loops and unsorted pages preserve previous good data', () => {
  const pager = new IntentHistoryPager(); pager.acceptModern(parseIntentHistoryPage(page(), JOB), 'latest', null)
  const before = clone(pager.items)
  for (const mutate of [(v: any) => { v.job_id = OTHER_JOB }, (v: any) => { v.extra = true }, (v: any) => { v.limit = 30 },
    (v: any) => { v.items = {} }, (v: any) => { v.items.push(v.items[0]) }, (v: any) => { v.items[0].job_id = OTHER_JOB },
    (v: any) => { v.items[0].source_mode = 'CAPTION' }, (v: any) => { v.items[0].stage = {} },
    (v: any) => { v.items[0].budget.text_calls_reserved = 7 }, (v: any) => { v.active_count = -1 }, (v: any) => { v.items[0].status = 'RUNNING' },
    (v: any) => { v.next_cursor = '' }, (v: any) => { v.next_cursor = 'a'.repeat(1025) }]) {
    const value = clone(page()); mutate(value); assert.throws(() => parseIntentHistoryPage(value, JOB), IntentHistoryError)
  }
  assert.throws(() => parseIntentHistoryPage(page(), JOB, 20, 'opaque_next-first'), IntentHistoryError)
  assert.deepEqual(pager.items, before)
})

test('opaque cursor navigation changes only the history page, never selected detail, draft, or unknown request', async () => {
  const pager = new IntentHistoryPager(), selected = detail(40), draft = intentPayload(selected.plan!.outputs).outputs
  draft[0]!.title = '用户尚未保存的标题'
  const workspace = { selected, draft, unknown: { input: '原body和source_answer', key: id(91) }, saveNeedsCheck: true }, before = clone(workspace)
  pager.acceptModern(parseIntentHistoryPage(page(), JOB), 'latest', null)
  const requested = pager.cursorFor('older'), calls: string[] = []
  const result = await readIntentHistory({ pager, jobId: JOB, cursor: requested, probe: false, current: () => true,
    fetch: async path => { calls.push(path); return page(21, 20, 'opaque-last') } })
  assert.equal(new URL(calls[0]!, 'http://fixture').searchParams.get('cursor'), requested)
  assert.equal(result!.kind, 'modern'); if (result!.kind === 'modern') pager.acceptModern(result!.page, 'older', requested)
  assert.equal(pager.pageNumber, 2); assert.equal(pager.items[0]!.id, id(21)); assert.equal(pager.cursorFor('newer'), null)
  pager.acceptModern(parseIntentHistoryPage(page(), JOB), 'newer', null)
  assert.equal(pager.pageNumber, 1)
  pager.acceptModern(parseIntentHistoryPage(page(0), JOB), 'latest', null)
  assert.deepEqual(workspace, before)
})

test('whole-job active_count keeps fast polling even when this history page and selected request are terminal', () => {
  const raw = page(21, 4, null); raw.active_count = 3
  const parsed = parseIntentHistoryPage(raw, JOB)
  assert.equal(intentHasActiveWork([detail()], [], parsed.active_count), true)
  assert.equal(intentHasActiveWork([detail()], [], 0), false)
  assert.equal(intentHasActiveWork([detail()], [{ status: 'QUEUED', clips: [{ status: 'READY' }] }], 0), true)
})

test('only an actual missing-route 404 falls back, and legacy capability is cached until explicit reprobe', async () => {
  const pager = new IntentHistoryPager(), calls: string[] = [], rawLegacy = { items: Array.from({ length: 45 }, (_, index) => detail(index + 1)) }
  let upgraded = false
  const fetch = async (path: string) => {
    calls.push(path)
    if (path.includes('clip-request-history')) { if (!upgraded) throw error(404, 'RESOURCE_NOT_FOUND'); return page() }
    return rawLegacy
  }
  let result = await readIntentHistory({ pager, jobId: JOB, cursor: null, probe: false, fetch, current: () => true })
  assert.equal(result!.kind, 'legacy'); if (result!.kind === 'legacy') pager.acceptLegacy(result!.rows, 'refresh')
  assert.equal(calls.length, 2)
  result = await readIntentHistory({ pager, jobId: JOB, cursor: null, probe: false, fetch, current: () => true })
  assert.equal(calls.length, 3); assert.equal(calls[2], `/api/jobs/${JOB}/clip-requests`)
  pager.stepLegacy('older'); const oldPage = clone(pager.items)
  upgraded = true
  result = await readIntentHistory({ pager, jobId: JOB, cursor: null, probe: true, fetch, current: () => true })
  if (result!.kind === 'modern') pager.acceptModern(result!.page, 'refresh', null)
  assert.equal(pager.upgradeAvailable, true); assert.equal(pager.pageNumber, 2); assert.deepEqual(pager.items, oldPage)
  result = await readIntentHistory({ pager, jobId: JOB, cursor: null, probe: false, fetch, current: () => true })
  assert.ok(calls.at(-1)!.includes('clip-request-history'))
  if (result!.kind === 'modern') pager.acceptModern(result!.page, 'latest', null)
  assert.equal(pager.view, 'modern'); assert.equal(pager.pageNumber, 1)
})

test('5xx, network errors, invalid 200, and a missing job never silently request the legacy list', async () => {
  for (const failure of [error(503, 'RESOURCE_NOT_FOUND'), error(404, 'JOB_NOT_FOUND'), error(500, 'INTERNAL_ERROR'), error(0, 'NETWORK_UNAVAILABLE'), error(0, 'REQUEST_TIMEOUT'), null]) {
    const pager = new IntentHistoryPager(); pager.acceptModern(parseIntentHistoryPage(page(), JOB), 'latest', null)
    let calls = 0
    await assert.rejects(readIntentHistory({ pager, jobId: JOB, cursor: null, probe: true, current: () => true,
      fetch: async () => { calls++; if (failure) throw failure; return { items: {} } } }))
    assert.equal(calls, 1); assert.equal(pager.items[0]!.id, id(1))
    assert.equal(pager.protocol, 'unknown')
  }
  assert.equal(intentHistoryRouteMissing(error(404, 'HTTP_404')), true)
  assert.equal(intentHistoryRouteMissing(error(404, 'RESOURCE_NOT_FOUND')), true)
  assert.equal(intentHistoryRouteMissing({ code: 'RESOURCE_NOT_FOUND' }), false)
})

test('a superseded history response or late 404 cannot switch pages, change capability, or initiate fallback', async () => {
  const pager = new IntentHistoryPager(), scope = new IntentReadScope(), parent = new AbortController()
  pager.acceptModern(parseIntentHistoryPage(page(), JOB), 'latest', null)
  const read = scope.begin(parent.signal); let reject!: (error: Error) => void, calls = 0
  const pending = readIntentHistory({ pager, jobId: JOB, cursor: null, probe: false, current: read.current,
    fetch: () => { calls++; return new Promise((_, fail) => { reject = fail }) } })
  parent.abort(); pager.acceptModern(parseIntentHistoryPage(page(21, 2, null), JOB), 'older', 'opaque_next-first')
  reject(error(404, 'RESOURCE_NOT_FOUND'))
  assert.equal(await pending, null); assert.equal(calls, 1); assert.equal(read.signal.aborted, true)
  assert.equal(pager.protocol, 'modern'); assert.equal(pager.pageNumber, 2); assert.equal(pager.items[0]!.id, id(21))
  read.release()
})

test('legacy whole-list snapshots remain an atomic validated cache for doc53, while modern summaries cannot acknowledge or discard', () => {
  const original = detail(), edited = intentPayload(original.plan!.outputs).outputs
  edited[0]!.title = '保存过但丢失回执'; edited[0]!.enabled = true
  const incoming = clone(original); incoming.revision = 1; incoming.state_version = 4; incoming.plan_hash = 'e'.repeat(64); incoming.plan!.outputs = edited
  const known = new Map([[original.id, original]]), before = clone([...known])
  const cache = cacheLegacyIntentDetails(known, [incoming], JOB)
  assert.deepEqual([...known], before); assert.equal(cache.get(original.id)!.revision, 1)
  assert.equal(intentSaveRecovered(original, cache.get(original.id)!, edited, 0), true)
  const metadata = intentHistoryFromDetail(incoming, JOB)
  assert.throws(() => cacheLegacyIntentDetails(known, [incoming, metadata], JOB), IntentResponseError)
  assert.deepEqual([...known], before)
  assert.equal(intentSaveRecovered(original, metadata as unknown as IntentSnapshot, edited, 0), false)
  const pager = new IntentHistoryPager(); pager.acceptModern(parseIntentHistoryPage({ ...page(1, 1, null), items: [metadata] }, JOB), 'latest', null)
  assert.equal(known.get(original.id)!.revision, 0)
})

test('legacy older pages keep their original key window when newer records arrive', () => {
  const pager = new IntentHistoryPager(), rows = Array.from({ length: 45 }, (_, index) => intentHistoryFromDetail(detail(index + 1), JOB))
  pager.acceptLegacy(rows, 'refresh'); pager.stepLegacy('older'); const oldIds = pager.items.map(item => item.id)
  const latest = [intentHistoryFromDetail(detail(0), JOB), ...rows.slice(0, 44)]
  pager.acceptLegacy(latest, 'refresh'); assert.deepEqual(pager.items.map(item => item.id), oldIds)
  pager.stepLegacy('newer'); assert.equal(pager.items[0]!.id, id(1))
  pager.acceptLegacy(latest, 'latest'); assert.equal(pager.items[0]!.id, id(0))
})

function activeDetail(n: number): IntentSnapshot {
  const value = detail(n); value.status = 'QUEUED'; value.plan = null; value.plan_hash = null; value.stage.status = 'READY'; value.stage.attempt = 0; value.budget.text_calls_reserved = 0
  return value
}

test('modern older-page downgrade retains its page but counts newly read legacy work and the selected full detail', async () => {
  const pager = new IntentHistoryPager(), cache = new Map<string, IntentSnapshot>()
  pager.acceptModern(parseIntentHistoryPage(page(), JOB), 'latest', null)
  pager.acceptModern(parseIntentHistoryPage(page(21, 4, null), JOB), 'older', 'opaque_next-first')
  const oldPage = clone(pager.items), selected = activeDetail(40)
  pager.markLegacy()
  const result = await readIntentHistory({ pager, jobId: JOB, cursor: pager.currentCursor, probe: false, current: () => true,
    fetch: async () => ({ items: [activeDetail(0), detail(1)] }) })
  assert.equal(result!.kind, 'legacy')
  if (result!.kind === 'legacy') { cacheLegacyIntentDetails(cache, result!.details, JOB); pager.acceptLegacy(result!.rows, 'refresh') }
  assert.deepEqual(pager.items, oldPage); assert.equal(pager.legacyRows.length, 0)
  assert.equal(pager.activeCount, null); assert.equal(pager.legacyKnownActiveCount, 1)
  assert.equal(intentHistoryHasActiveWork(pager, detail(40), []), true)
  pager.acceptLegacy([intentHistoryFromDetail(detail(1), JOB)], 'refresh')
  assert.equal(pager.legacyKnownActiveCount, 0)
  assert.equal(intentHistoryHasActiveWork(pager, selected, []), true)
  assert.deepEqual(pager.items, oldPage)
})

test('legacy older-page windows stay frozen while an external new queued request keeps fast activity checks', async () => {
  const pager = new IntentHistoryPager(), rows = Array.from({ length: 45 }, (_, index) => intentHistoryFromDetail(detail(index + 1), JOB))
  pager.acceptLegacy(rows, 'refresh'); pager.stepLegacy('older'); const oldIds = pager.items.map(item => item.id)
  assert.equal(intentHistoryHasActiveWork(pager, detail(40), []), false)
  const result = await readIntentHistory({ pager, jobId: JOB, cursor: null, probe: false, current: () => true,
    fetch: async () => ({ items: [activeDetail(0), ...Array.from({ length: 44 }, (_, index) => detail(index + 1))] }) })
  if (result!.kind === 'legacy') { cacheLegacyIntentDetails(new Map(), result!.details, JOB); pager.acceptLegacy(result!.rows, 'refresh') }
  assert.deepEqual(pager.items.map(item => item.id), oldIds)
  assert.equal(pager.legacyRows.some(item => item.id === id(0)), false)
  assert.equal(pager.legacyKnownActiveCount, 1); assert.equal(intentHistoryHasActiveWork(pager, detail(40), []), true)
})

test('malformed or source-conflicting legacy lists cannot partially update known activity or validated detail cache', async () => {
  const pager = new IntentHistoryPager(), original = detail(1), cache = new Map([[original.id, original]])
  pager.acceptLegacy([intentHistoryFromDetail(activeDetail(0), JOB), intentHistoryFromDetail(original, JOB)], 'refresh')
  const before = clone(pager.items), cacheBefore = clone([...cache])
  const foreign = activeDetail(2); foreign.job_id = OTHER_JOB
  for (const raw of [{ items: {} }, { items: [activeDetail(0), foreign] }, { items: [activeDetail(0), activeDetail(0)] }]) {
    await assert.rejects(readIntentHistory({ pager, jobId: JOB, cursor: null, probe: false, current: () => true, fetch: async () => raw }))
    assert.equal(pager.legacyKnownActiveCount, 1); assert.deepEqual(pager.items, before)
  }
  const conflict = detail(1); conflict.source.video_sha256 = 'f'.repeat(64); conflict.plan!.source_hash = conflict.source.video_sha256
  const result = await readIntentHistory({ pager, jobId: JOB, cursor: null, probe: false, current: () => true,
    fetch: async () => ({ items: [activeDetail(0), conflict, activeDetail(2)] }) })
  assert.throws(() => { if (result!.kind === 'legacy') { cacheLegacyIntentDetails(cache, result!.details, JOB); pager.acceptLegacy(result!.rows, 'refresh') } }, IntentResponseError)
  assert.equal(pager.legacyKnownActiveCount, 1); assert.deepEqual(pager.items, before); assert.deepEqual([...cache], cacheBefore)
})

test('modern bad 200 pages reject zero versions, uppercase UUIDs and non-base64url cursors without replacing the good page', async () => {
  const good = page(), uppercase = 'ABCDEFAB-3333-4333-8333-000000000001'
  const mutations = [(value: any) => { value.items[0].state_version = 0 }, (value: any) => { value.items[0].id = uppercase },
    (value: any) => { value.items[0].video_id = uppercase }, (value: any) => { value.items[0].job_id = uppercase },
    ...['opaque/next', 'opaque+next', 'opaque?next', 'abc=', 'bad cursor'].map(token => (value: any) => { value.next_cursor = token })]
  for (const mutate of mutations) {
    const pager = new IntentHistoryPager(); pager.acceptModern(parseIntentHistoryPage(good, JOB), 'latest', null)
    const before = clone(pager.items), bad = clone(good); mutate(bad); let calls = 0
    await assert.rejects(readIntentHistory({ pager, jobId: JOB, cursor: null, probe: true, current: () => true,
      fetch: async () => { calls++; return bad } }), IntentHistoryError)
    assert.equal(calls, 1); assert.deepEqual(pager.items, before); assert.equal(pager.protocol, 'unknown')
  }
  assert.throws(() => parseIntentHistoryPage({ ...page(1, 0, null), job_id: uppercase }, uppercase), IntentHistoryError)
})

test('legacy full snapshots keep their previous validation rules instead of inheriting the new summary-only constraints', () => {
  const old = detail(1), uppercase = 'ABCDEFAB-3333-4333-8333-000000000001'
  old.id = uppercase; old.plan!.request_id = uppercase; old.state_version = 0; old.budget.max_text_calls = 100
  old.created_at = '2026-11-07T12:00:01+00:00'
  const validated = parseLegacyIntentHistoryDetails({ items: [detail(2), old] }, JOB)
  assert.equal(validated[0]!.id, uppercase); assert.equal(validated[0]!.state_version, 0); assert.equal(validated[0]!.budget.max_text_calls, 100)
  const cache = cacheLegacyIntentDetails(new Map(), validated, JOB)
  assert.equal(cache.get(uppercase)!.state_version, 0)
  assert.throws(() => parseIntentHistorySummary(intentHistoryFromDetail(old, JOB), JOB), IntentHistoryError)
})

test('a failed independent modern reprobe retains last-known active work and cannot downgrade the five-second timer', async () => {
  const pager = new IntentHistoryPager(), selected = detail(40), raw = page()
  raw.active_count = 3; pager.acceptModern(parseIntentHistoryPage(raw, JOB), 'latest', null)
  const scheduled: number[] = []
  const scheduler = new IntentRefreshScheduler({ refresh: async () => {}, hasActiveWork: () => intentHistoryHasActiveWork(pager, selected, []),
    clock: { setTimeout: (_, milliseconds) => { scheduled.push(milliseconds); return scheduled.length }, clearTimeout: () => {} } })
  scheduler.update({ active: true, visible: true, blocked: false })
  await Promise.resolve(); await Promise.resolve()
  assert.equal(scheduled.at(-1), 5000)
  const savedPage = clone(pager.items)
  await assert.rejects(readIntentHistory({ pager, jobId: JOB, cursor: null, probe: true, current: () => true,
    fetch: async () => { throw error(503, 'SERVICE_UNAVAILABLE') } }))
  assert.equal(pager.protocol, 'unknown'); assert.equal(pager.activeCount, 3); assert.equal(pager.legacyKnownActiveCount, 0)
  assert.equal(intentHistoryHasActiveWork(pager, selected, []), true)
  scheduler.reschedule(); assert.equal(scheduled.at(-1), 5000); assert.deepEqual(pager.items, savedPage)
  pager.markLegacy(); pager.legacyKnownActiveCount = 1; pager.probe()
  assert.equal(intentHistoryHasActiveWork(pager, selected, []), true)
  scheduler.dispose()
})

for (const scenario of ['malformed-200', 'source-conflict'] as const) test(`a missing modern route followed by legacy ${scenario} preserves activity, cache, page and fast scheduling`, async () => {
  const pager = new IntentHistoryPager(), selected = detail(40), original = detail(1), cache = new Map([[original.id, original]])
  const first = page(), older = page(21, 4, null); first.active_count = 3; older.active_count = 3
  pager.acceptModern(parseIntentHistoryPage(first, JOB), 'latest', null)
  pager.acceptModern(parseIntentHistoryPage(older, JOB), 'older', 'opaque_next-first')
  const savedPage = clone(pager.items), savedCache = clone([...cache]), scheduled: number[] = []
  const scheduler = new IntentRefreshScheduler({ refresh: async () => {}, hasActiveWork: () => intentHistoryHasActiveWork(pager, selected, []),
    clock: { setTimeout: (_, milliseconds) => { scheduled.push(milliseconds); return scheduled.length }, clearTimeout: () => {} } })
  scheduler.update({ active: true, visible: true, blocked: false }); await Promise.resolve(); await Promise.resolve()
  assert.equal(scheduled.at(-1), 5000)
  const conflicting = detail(1); conflicting.source.video_sha256 = 'f'.repeat(64); conflicting.plan!.source_hash = conflicting.source.video_sha256
  let calls = 0
  await assert.rejects(async () => {
    const result = await readIntentHistory({ pager, jobId: JOB, cursor: pager.currentCursor, probe: true, current: () => true,
      fetch: async path => {
        calls++
        if (path.includes('clip-request-history')) throw error(404, 'RESOURCE_NOT_FOUND')
        return scenario === 'malformed-200' ? { items: {} } : { items: [activeDetail(0), conflicting, activeDetail(2)] }
      } })
    if (result?.kind === 'legacy') { cacheLegacyIntentDetails(cache, result.details, JOB); pager.acceptLegacy(result.rows, 'refresh') }
  })
  assert.equal(calls, 2); assert.equal(pager.protocol, 'legacy')
  assert.equal(pager.activeCount, 3); assert.equal(pager.legacyKnownActiveCount, 0)
  assert.deepEqual(pager.items, savedPage); assert.deepEqual([...cache], savedCache)
  scheduler.reschedule(); assert.equal(scheduled.at(-1), 5000)
  const valid = await readIntentHistory({ pager, jobId: JOB, cursor: pager.currentCursor, probe: false, current: () => true,
    fetch: async path => { assert.equal(path.includes('clip-request-history'), false); return { items: [detail(1)] } } })
  if (valid?.kind === 'legacy') { cacheLegacyIntentDetails(cache, valid.details, JOB); pager.acceptLegacy(valid.rows, 'refresh') }
  assert.equal(pager.activeCount, null); assert.equal(pager.legacyKnownActiveCount, 0)
  assert.deepEqual(pager.items, savedPage)
  scheduler.reschedule(); assert.equal(scheduled.at(-1), 60000)
  scheduler.dispose()
})
