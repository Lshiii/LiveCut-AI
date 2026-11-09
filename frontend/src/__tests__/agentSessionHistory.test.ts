import test from 'node:test'
import assert from 'node:assert/strict'
import { AgentSessionHistoryError, AgentSessionHistoryPager, agentHistoryTimeKey, agentSessionHistoryHasActiveWork,
  agentSessionHistoryRouteMissing, agentSessionSummaryFromDetail, compareAgentSessionHistory, mergeLegacyAgentSessions,
  parseAgentSessionHistoryPage, parseAgentSessionSummary, parseLegacyAgentSessions, readAgentSessionHistory, type AgentSessionHistoryPage } from '../agentSessionHistory.ts'
import { parseAgentSession, type AgentSession } from '../videoAgent.ts'
import { createAgentSubmission } from '../agentSubmissionRecovery.ts'
import { clone, MemoryStorage, session, turn, owner } from './support/agentSubmissionFixture.ts'
const id = (n: number) => `33333333-3333-4333-8333-${String(n).padStart(12, '0')}`
function detail(n = 1): AgentSession {
  const value = session(), answer = turn(); value.id = id(n); value.created_at = `2026-11-09T12:00:00.123${String(999 - n).padStart(3, '0')}Z`
  answer.session_id = value.id; answer.result!.session_id = value.id; answer.question = `完整问题 ${n}`; answer.result!.question = answer.question
  value.turns = [answer]; return value
}
function page(start = 1, count = 20, next: string | null = `cursor_${start + 20}`): AgentSessionHistoryPage {
  return { schema_version: '1.0', job_id: owner.job_id, limit: 20, next_cursor: next, active_count: 1,
    items: Array.from({ length: count }, (_, index) => agentSessionSummaryFromDetail(detail(start + index))) }
}
const failure = (status: number, code: string) => Object.assign(Error(code), { status, code })

test('strict metadata has no full-session authority or raw answer/history payload', () => {
  const summary = agentSessionSummaryFromDetail(detail()), parsed = parseAgentSessionHistoryPage(page(), owner)
  assert.equal(parsed.items.length, 20)
  for (const key of ['turns', 'answer', 'result', 'history_json', 'stage', 'tools']) assert.equal(Object.hasOwn(summary, key), false)
  assert.throws(() => parseAgentSession(summary, owner.job_id, owner.video_id, owner.video_sha256))
  const storage = new MemoryStorage(); let generatedKeys = 0
  assert.throws(() => createAgentSubmission(storage, owner, 'TURN', JSON.stringify({ question: '不能由摘要发起提问', max_text_calls: 4 }),
    () => { generatedKeys++; return 'new-key' }, [], summary as unknown as AgentSession))
  assert.equal(generatedKeys, 0); assert.equal(storage.length, 0)
  assert.equal(JSON.stringify(summary).includes('已有视频回答'), false)
})

test('sorting retains microseconds and ASCII UUID order within the same JavaScript millisecond', () => {
  const newer = agentSessionSummaryFromDetail(detail(1)), older = agentSessionSummaryFromDetail(detail(2))
  newer.id = id(1); older.id = id(99)
  assert.equal(Date.parse(newer.created_at), Date.parse(older.created_at)); assert.equal(compareAgentSessionHistory(newer, older), -1)
  older.created_at = newer.created_at; assert.equal(compareAgentSessionHistory(older, newer), -1)
  assert.equal(agentHistoryTimeKey('2026-11-09T12:00:00.1Z'), '2026-11-09T12:00:00.100000000Z')
  for (const value of ['2026-02-30T12:00:00Z', '2026-11-09T12:00:00+00:00', '2026-11-09T12:00:00.1234567890Z']) assert.throws(() => agentHistoryTimeKey(value))
})

test('empty and nonempty session counts, owner/source fields and exact DTO keys are cross-checked', () => {
  const base = agentSessionSummaryFromDetail(detail())
  for (const patch of [{ first_question: null }, { first_question: ' ' }, { turn_count: 0 }, { turn_count: 101 },
    { active_turn_count: 2 }, { active_turn_count: -1 }, { active_turn_count: true }, { created_at: 'bad' },
    { job_id: owner.video_id }, { video_id: owner.job_id }, { turns: [] }, { source: { ...base.source, video_sha256: 'd'.repeat(64) } },
    { source: { ...base.source, duration_ms: 0 } }, { source: { ...base.source, transcript_hash: 'B'.repeat(64) } }]) {
    assert.throws(() => parseAgentSessionSummary({ ...base, ...patch }, owner), AgentSessionHistoryError)
  }
  const empty = { ...base, turn_count: 0, active_turn_count: 0, first_question: null }
  assert.equal(parseAgentSessionSummary(empty, owner).turn_count, 0)
  assert.equal(parseAgentSessionSummary({ ...base, turn_count: 3, active_turn_count: 3 }, owner).active_turn_count, 3)
})

test('page size, repeated cursor, ordering, duplicate ids and global activity constraints fail closed', () => {
  for (const patch of [{ schema_version: 1 }, { limit: 19 }, { active_count: -1 }, { active_count: Number.MAX_SAFE_INTEGER + 1 },
    { items: [page().items[0], page().items[0]], next_cursor: null }, { items: [...page().items].reverse() }, { future: true }]) {
    assert.throws(() => parseAgentSessionHistoryPage({ ...page(), ...patch }, owner), AgentSessionHistoryError)
  }
  assert.throws(() => parseAgentSessionHistoryPage(page(), owner, 20, page().next_cursor), AgentSessionHistoryError)
  assert.throws(() => parseAgentSessionHistoryPage(page(1, 1, 'still_more'), owner), AgentSessionHistoryError)
  const activity = page(1, 1, null); activity.items[0]!.active_turn_count = 1; activity.active_count = 0
  assert.throws(() => parseAgentSessionHistoryPage(activity, owner), AgentSessionHistoryError)
})

test('107 sessions page 20/20/20/20/20/7 with reversible cursors and an explicit latest reset', () => {
  const pager = new AgentSessionHistoryPager(), ids: string[] = []
  let start = 1
  for (let index = 0; index < 6; index++) {
    const count = index === 5 ? 7 : 20, navigation = index === 0 ? 'refresh' : 'older', cursor = pager.cursorFor(navigation)
    const value = parseAgentSessionHistoryPage(page(start, count, index === 5 ? null : `cursor_${start + 20}`), owner, 20, cursor)
    pager.acceptModern(value, navigation, cursor); ids.push(...pager.items.map(value => value.id)); assert.equal(pager.pageNumber, index + 1)
    start += count
  }
  assert.equal(new Set(ids).size, 107); assert.equal(pager.canOlder, false); assert.equal(pager.canNewer, true)
  const prior = pager.cursorFor('newer'); pager.acceptModern(page(81), 'newer', prior)
  assert.equal(pager.pageNumber, 5); assert.equal(pager.items[0]!.id, id(81))
  pager.acceptModern(page(), 'latest', pager.cursorFor('latest')); assert.equal(pager.pageNumber, 1); assert.equal(pager.canNewer, false)
})

test('metadata activity outside the page and selected full turns drive refresh without stale nonselected cache activity', () => {
  const pager = new AgentSessionHistoryPager(); pager.acceptModern(page(), 'refresh', null)
  assert.equal(agentSessionHistoryHasActiveWork(pager, detail()), true)
  pager.activeCount = 0; const selected = detail(), staleNonselected = detail(2); staleNonselected.turns[0]!.status = 'RUNNING'
  assert.equal(agentSessionHistoryHasActiveWork(pager, selected), false, 'unrefreshed old cache is deliberately not an input')
  selected.turns[0]!.status = 'QUEUED'; assert.equal(agentSessionHistoryHasActiveWork(pager, selected), true)
  pager.activeCount = null; pager.legacyKnownActiveCount = 1; assert.equal(agentSessionHistoryHasActiveWork(pager, null), true)
})

test('only a genuine route HTTP404 permits the full legacy fallback; failures and malformed modern200 do not', async () => {
  for (const error of [failure(500, 'SERVER_ERROR'), failure(404, 'JOB_NOT_FOUND'), failure(0, 'NETWORK_UNAVAILABLE')]) {
    const paths: string[] = []
    await assert.rejects(readAgentSessionHistory({ pager: new AgentSessionHistoryPager(), owner, cursor: null, probe: false, current: () => true,
      fetch: async path => { paths.push(path); throw error } }))
    assert.equal(paths.length, 1)
  }
  const paths: string[] = []
  const result = await readAgentSessionHistory({ pager: new AgentSessionHistoryPager(), owner, cursor: null, probe: false, current: () => true,
    fetch: async path => { paths.push(path); if (paths.length === 1) throw failure(404, 'RESOURCE_NOT_FOUND'); return { items: [detail()] } } })
  assert.equal(result?.kind, 'legacy'); assert.equal(paths[1], `/api/jobs/${owner.job_id}/agent-sessions`)
  assert.equal(agentSessionHistoryRouteMissing(failure(404, 'HTTP_404')), true)
  const badPaths: string[] = []
  await assert.rejects(readAgentSessionHistory({ pager: new AgentSessionHistoryPager(), owner, cursor: null, probe: false, current: () => true,
    fetch: async path => { badPaths.push(path); return { ...page(), result_json: 'private' } } }))
  assert.equal(badPaths.length, 1)
})

test('upgrade discovery preserves the old page and both old-page navigation directions until explicit latest', () => {
  const pager = new AgentSessionHistoryPager(), rows = Array.from({ length: 65 }, (_, index) => agentSessionSummaryFromDetail(detail(index + 1)))
  pager.acceptLegacy(rows, 'latest'); pager.navigateLocally('older'); assert.equal(pager.pageNumber, 2)
  const before = pager.items.map(value => value.id); pager.acceptModern(page(), 'refresh', null)
  assert.equal(pager.upgradeAvailable, true); assert.deepEqual(pager.items.map(value => value.id), before)
  assert.equal(pager.navigateLocally('older'), true); assert.equal(pager.view, 'legacy'); assert.equal(pager.pageNumber, 3)
  assert.equal(pager.items[0]!.id, id(41)); pager.navigateLocally('newer'); assert.equal(pager.items[0]!.id, id(21))
  assert.equal(pager.navigateLocally('latest'), false); pager.acceptModern(page(), 'latest', null)
  assert.equal(pager.view, 'modern'); assert.equal(pager.pageNumber, 1); assert.equal(pager.items[0]!.id, id(1))
})

test('compatibility full cache is validated and merged atomically, retaining offpage details and rejecting bad bodies', () => {
  const known = [detail(77)], before = JSON.stringify(known)
  const bad = detail(77); bad.created_at = '2026-11-09T13:00:00Z'
  assert.throws(() => mergeLegacyAgentSessions(known, [detail(1), bad], owner)); assert.equal(JSON.stringify(known), before)
  const result = mergeLegacyAgentSessions(known, parseLegacyAgentSessions({ items: [detail(1)] }, owner), owner)
  assert.equal(result.length, 2); assert.equal(result.find(value => value.id === id(77))!.turns[0]!.result!.answer, '已有视频回答')
  assert.throws(() => parseLegacyAgentSessions({ items: [agentSessionSummaryFromDetail(detail())] }, owner))
  assert.throws(() => parseLegacyAgentSessions({ items: [detail(), detail()] }, owner))
})

test('late history responses do not publish a page or trigger a legacy downgrade after owner/lifecycle expiry', async () => {
  let current = true, finish!: (value: unknown) => void
  const pager = new AgentSessionHistoryPager(); pager.acceptModern(page(), 'refresh', null); const before = JSON.stringify(pager)
  const promise = readAgentSessionHistory({ pager, owner, cursor: null, probe: false, current: () => current,
    fetch: () => new Promise(resolve => { finish = resolve }) })
  current = false; finish(page(21)); assert.equal(await promise, null); assert.equal(JSON.stringify(pager), before)
  current = true; let reject!: (value: unknown) => void, calls = 0
  const late404 = readAgentSessionHistory({ pager, owner, cursor: null, probe: false, current: () => current,
    fetch: () => { calls++; return new Promise((_resolve, fail) => { reject = fail }) } })
  current = false; reject(failure(404, 'RESOURCE_NOT_FOUND')); assert.equal(await late404, null); assert.equal(calls, 1); assert.equal(pager.protocol, 'modern')
})
