import test from 'node:test'
import assert from 'node:assert/strict'
import { newestAgentSession, parseAgentSession } from '../videoAgent.ts'
import { AGENT_SUBMISSION_PREFIX, AgentSubmissionError, agentDraftAfterSubmission, agentSubmissionStorageKey, assertAgentSubmissionStored,
  createAgentSubmission, filterAcknowledgedAgentSubmissions, inspectAgentSubmissionLimit, mergeAgentSubmissionResult, parseAgentSubmissionEntry, parseAgentSubmissionResponse, removeAgentSubmission,
  resolveAgentSubmission, restoreAgentSubmission, retainAgentSubmissions, sameAgentSubmission, scanAgentSubmissions } from '../agentSubmissionRecovery.ts'
import { clone, entry, JOB, MemoryStorage, owner, SESSION, session, turn } from './support/agentSubmissionFixture.ts'

const failure = (code: string, status = 404) => Object.assign(new Error(code), { code, status })
const code = (wanted: string) => (error: unknown) => error instanceof AgentSubmissionError && error.code === wanted
function persisted(value = entry()) { const store = new MemoryStorage(); restoreAgentSubmission(store, value); return { store, value } }

test('entries freeze full request identity and reject extra fields, bad input, kind and source mismatches', () => {
  assert.ok(parseAgentSubmissionEntry(entry())); assert.ok(parseAgentSubmissionEntry(entry('SESSION')))
  for (const mutate of [(v: any) => { v.extra = 1 }, (v: any) => { v.kind = 'CAPTION' }, (v: any) => { v.source.transcript_hash = 'bad' },
    (v: any) => { v.source.video_sha256 = 'f'.repeat(64) }, (v: any) => { v.session_id = null }, (v: any) => { v.key = 'https://private' },
    (v: any) => { v.input = JSON.stringify({ question: 'A', max_text_calls: 0 }) }, (v: any) => { v.input = JSON.stringify({ question: 'A', max_text_calls: 8, extra: true }) }]) {
    const value = clone(entry()); mutate(value); assert.equal(parseAgentSubmissionEntry(value), null)
  }
})

test('new requests require durable write and exact read-back before any POST can run', () => {
  const store = new MemoryStorage(), wanted = entry(); let posts = 0
  const created = createAgentSubmission(store, owner, 'TURN', wanted.input, () => wanted.key, [session()], session())
  assert.equal(store.getItem(agentSubmissionStorageKey(created)), JSON.stringify(created)); assert.equal(store.writes, 1)
  assertAgentSubmissionStored(store, created); posts++
  assert.equal(posts, 1)
  for (const mode of ['quota', 'readback'] as const) {
    const failed = new MemoryStorage(); if (mode === 'quota') failed.failWrite = true
    else failed.onWrite = (key, raw) => failed.values.set(key, raw.replace('原问题A', '另一页问题'))
    assert.throws(() => { createAgentSubmission(failed, owner, 'TURN', wanted.input, () => wanted.key, [session()], session()); posts++ })
    assert.equal(posts, 1)
  }
})

test('scan always reads all job entries; edits to question or budget cannot replace another pending key', () => {
  const { store, value } = persisted(), second = entry('SESSION', 'other-page-key')
  restoreAgentSubmission(store, second)
  const scan = scanAgentSubmissions(store, owner)
  assert.equal(scan.entries.length, 2); assert.equal(scan.issues.length, 0)
  let keys = 0
  assert.throws(() => createAgentSubmission(store, owner, 'TURN', JSON.stringify({ question: '草稿B', max_text_calls: 4 }), () => { keys++; return 'replacement' }, [session()], session()), code('AGENT_SUBMISSION_PENDING'))
  assert.equal(keys, 0); assert.equal(store.getItem(agentSubmissionStorageKey(value)), JSON.stringify(value)); assert.equal(store.getItem(agentSubmissionStorageKey(second)), JSON.stringify(second))
})

test('a concurrent page entry during persistence stops the new POST and preserves both independent records', () => {
  const store = new MemoryStorage(), other = entry('SESSION', 'other-page')
  store.onWrite = () => store.values.set(agentSubmissionStorageKey(other), JSON.stringify(other))
  let posts = 0
  assert.throws(() => { createAgentSubmission(store, owner, 'TURN', entry().input, () => 'this-page', [session()], session()); posts++ }, code('AGENT_SUBMISSION_PENDING'))
  assert.equal(posts, 0); assert.equal(scanAgentSubmissions(store, owner).entries.length, 2)
})

test('corrupt, wrong-source and unreadable entries block new requests rather than disappearing', () => {
  for (const raw of ['{bad', JSON.stringify({ ...entry(), video_sha256: 'f'.repeat(64) })]) {
    const store = new MemoryStorage(); store.values.set(agentSubmissionStorageKey(entry()), raw)
    const result = scanAgentSubmissions(store, owner)
    assert.equal(result.issues.length, 1)
    assert.throws(() => createAgentSubmission(store, owner, 'SESSION', '{}', () => 'new'))
    assert.equal(store.getItem(agentSubmissionStorageKey(entry())), raw)
  }
  const unreadable = new MemoryStorage(); unreadable.failRead = true
  assert.ok(scanAgentSubmissions(unreadable, owner).issues.length)
  assert.throws(() => createAgentSubmission(unreadable, owner, 'SESSION', '{}', () => 'new'))
})

test('legacy session and known turn slots migrate original key/body only after copy and full read-back', () => {
  const store = new MemoryStorage(), oldSession = entry('SESSION', 'old-session'), oldTurn = entry('TURN', 'old-turn')
  store.values.set(`livecut-agent-session-${JOB}`, JSON.stringify({ input: oldSession.input, key: oldSession.key }))
  store.values.set(`livecut-agent-turn-${SESSION}`, JSON.stringify({ input: oldTurn.input, key: oldTurn.key }))
  const result = scanAgentSubmissions(store, owner, [session()])
  assert.equal(result.issues.length, 0); assert.equal(result.entries.length, 2)
  for (const wanted of [oldSession, oldTurn]) assert.ok(result.entries.some(value => sameAgentSubmission(value, wanted)))
  assert.equal(store.getItem(`livecut-agent-session-${JOB}`), null); assert.equal(store.getItem(`livecut-agent-turn-${SESSION}`), null)
})

test('legacy copy failure, corrupt data and mid-copy full-slot changes retain original information', () => {
  const legacyKey = `livecut-agent-session-${JOB}`, original = JSON.stringify({ key: 'old', input: '{}' })
  const failed = new MemoryStorage(); failed.values.set(legacyKey, original); failed.failWrite = true
  assert.ok(scanAgentSubmissions(failed, owner).issues.length); assert.equal(failed.getItem(legacyKey), original)
  const changed = new MemoryStorage(); changed.values.set(legacyKey, original)
  changed.onWrite = () => changed.values.set(legacyKey, JSON.stringify({ key: 'later', input: '{}' }))
  const scan = scanAgentSubmissions(changed, owner)
  assert.equal(scan.entries[0]?.key, 'old'); assert.ok(scan.issues.length); assert.notEqual(changed.getItem(legacyKey), null)
  changed.onWrite = undefined
  assert.equal(scanAgentSubmissions(changed, owner).entries.length, 2)
  const bad = new MemoryStorage(); bad.values.set(legacyKey, '{bad')
  assert.ok(scanAgentSubmissions(bad, owner).issues.length); assert.equal(bad.getItem(legacyKey), '{bad')
})

test('external deletion is never acknowledgement; original identity is re-persisted for reload without POST', () => {
  const { store, value } = persisted(); const known = scanAgentSubmissions(store, owner).entries
  store.removeItem(agentSubmissionStorageKey(value))
  const retained = retainAgentSubmissions(known, scanAgentSubmissions(store, owner), store)
  assert.equal(retained.entries.length, 1); assert.equal(retained.issues.length, 0)
  const reloaded = scanAgentSubmissions(store, owner)
  assert.equal(reloaded.entries[0]?.key, value.key); assert.equal(reloaded.entries[0]?.input, value.input)
  assert.throws(() => createAgentSubmission(store, owner, 'SESSION', '{}', () => 'new'), code('AGENT_SUBMISSION_PENDING'))
})

test('missing known entries are retained even when re-persistence fails, and foreign values are never overwritten', () => {
  const { store, value } = persisted(); store.removeItem(agentSubmissionStorageKey(value)); store.failWrite = true
  const retained = retainAgentSubmissions([value], scanAgentSubmissions(store, owner), store)
  assert.equal(retained.entries.length, 1); assert.ok(retained.issues.length)
  const changed = { ...clone(value), input: JSON.stringify({ question: 'B', max_text_calls: 4 }) }
  store.failWrite = false; store.values.set(agentSubmissionStorageKey(value), JSON.stringify(changed))
  assert.throws(() => removeAgentSubmission(store, value), code('AGENT_SUBMISSION_ENTRY_CONFLICT'))
  assert.throws(() => restoreAgentSubmission(store, value), code('AGENT_SUBMISSION_ENTRY_CONFLICT'))
  assert.equal(store.getItem(agentSubmissionStorageKey(value)), JSON.stringify(changed))
})

test('passively observed remote entries do not become owned and cannot cause storage delete/rewrite loops', () => {
  const { store, value } = persisted()
  const first = retainAgentSubmissions([], scanAgentSubmissions(store, owner), store)
  assert.equal(first.entries.length, 1)
  const writes = store.writes
  store.removeItem(agentSubmissionStorageKey(value))
  const afterOwnerAck = retainAgentSubmissions([], scanAgentSubmissions(store, owner), store)
  assert.equal(afterOwnerAck.entries.length, 0); assert.equal(store.writes, writes)
})

test('a strict-ACK page can submit after another page re-pins that exact entry, without deleting or re-pinning it', async () => {
  const { store, value } = persisted()
  // A owns R and remains UNKNOWN; B explicitly resolves the original key.
  const ownA = [value]
  const result = await resolveAgentSubmission(value, { current: () => true, get: async () => turn(), readSession: async () => session(), beforePost: () => {}, post: async () => { throw Error('unexpected') } })
  assert.equal(result?.kind, 'TURN')
  const acknowledgedB = [clone(value)]; removeAgentSubmission(store, value)
  retainAgentSubmissions(ownA, scanAgentSubmissions(store, owner), store)
  const writes = store.writes, removes = store.removes
  assert.equal(filterAcknowledgedAgentSubmissions(scanAgentSubmissions(store, owner), acknowledgedB).entries.length, 0)
  assert.equal(store.writes, writes); assert.equal(store.removes, removes, 'B does not fight A storage events')
  const fresh = createAgentSubmission(store, owner, 'TURN', JSON.stringify({ question: '新问题B', max_text_calls: 4 }), () => 'new-B', [session()], session(), acknowledgedB)
  assert.equal(fresh.key, 'new-B'); assert.notEqual(store.getItem(agentSubmissionStorageKey(value)), null, 'A original entry is preserved')
  for (const mutate of [(v: any) => { v.input = JSON.stringify({ question: 'different', max_text_calls: 8 }) }, (v: any) => { v.key = 'different' }, (v: any) => { v.source.transcript_hash = 'f'.repeat(64) }]) {
    const changed = clone(value); mutate(changed)
    assert.equal(filterAcknowledgedAgentSubmissions({ entries: [changed], issues: [] }, acknowledgedB).entries.length, 1)
  }
})

test('resolver GET success acknowledges the original session without invoking POST or another question', async () => {
  const value = entry('SESSION'), events: string[] = []
  const result = await resolveAgentSubmission(value, { current: () => true, get: async () => { events.push('GET original-key'); return session() },
    beforePost: () => { throw Error('must not retry') }, post: async () => { events.push('POST'); return session() } })
  assert.equal(result?.kind, 'SESSION'); assert.deepEqual(events, ['GET original-key'])
})

test('only exact kind-specific business404 allows the original key/body retry', async () => {
  for (const kind of ['SESSION', 'TURN'] as const) {
    const value = entry(kind), store = new MemoryStorage(), events: string[] = []; restoreAgentSubmission(store, value)
    const result = await resolveAgentSubmission(value, { current: () => true, get: async () => { events.push('GET'); throw failure(kind === 'SESSION' ? 'AGENT_SESSION_SUBMISSION_NOT_FOUND' : 'AGENT_TURN_SUBMISSION_NOT_FOUND') },
      readSession: async () => { events.push('GET session'); return session() }, beforePost: () => assertAgentSubmissionStored(store, value),
      post: async () => { events.push(`POST ${value.key} ${value.input}`); return kind === 'SESSION' ? session() : turn() } })
    assert.equal(result?.kind, kind); assert.equal(events[0], 'GET'); assert.equal(events.at(-1), `POST ${value.key} ${value.input}`)
  }
})

test('generic404, other-kind404, bad response and network failure never trigger fallback POST', async () => {
  const value = entry(); let posts = 0
  for (const error of [failure('HTTP_404'), failure('RESOURCE_NOT_FOUND'), failure('AGENT_SESSION_NOT_FOUND'), failure('AGENT_SESSION_SUBMISSION_NOT_FOUND'), failure('AGENT_TURN_SUBMISSION_NOT_FOUND', 500), failure('NETWORK_UNAVAILABLE', 0)]) {
    await assert.rejects(resolveAgentSubmission(value, { current: () => true, get: async () => { throw error }, readSession: async () => session(), beforePost: () => {}, post: async () => { posts++; return turn() } }))
  }
  await assert.rejects(resolveAgentSubmission(value, { current: () => true, get: async () => ({ ...turn(), question: 'mismatch' }), readSession: async () => session(), beforePost: () => {}, post: async () => { posts++; return turn() } }))
  assert.equal(posts, 0)
})

test('successful, failed, cancelled and active original turns all resolve without creating another turn', async () => {
  for (const status of ['SUCCEEDED', 'FAILED', 'CANCELLED', 'QUEUED', 'RUNNING'] as const) {
    let posts = 0
    const result = await resolveAgentSubmission(entry(), { current: () => true, get: async () => turn(status), readSession: async () => session(), beforePost: () => {}, post: async () => { posts++; return turn() } })
    assert.equal(result?.kind === 'TURN' && result.turn.status, status); assert.equal(posts, 0)
  }
})

test('original turn owner, frozen source, question and budget must all match before acknowledgement or retry', async () => {
  const value = entry()
  for (const mutate of [(v: any) => { v.question = 'B' }, (v: any) => { v.budget.max_text_calls = 4 }, (v: any) => { v.session_id = JOB }, (v: any) => { v.result.source_hash = 'f'.repeat(64) }]) {
    const raw = turn(); mutate(raw); assert.throws(() => parseAgentSubmissionResponse(raw, value, session()))
  }
  const changed = session(); changed.source.transcript_hash = 'f'.repeat(64); let posts = 0
  await assert.rejects(resolveAgentSubmission(value, { current: () => true, get: async () => { throw failure('AGENT_TURN_SUBMISSION_NOT_FOUND') }, readSession: async () => changed, beforePost: () => {}, post: async () => { posts++; return turn() } }))
  assert.equal(posts, 0)
})

test('unknown-session turn recovery preserves real session metadata and merges the next refresh', async () => {
  const original = session()
  const result = await resolveAgentSubmission(entry(), { current: () => true, get: async () => turn(), readSession: async () => original, beforePost: () => {}, post: async () => { throw Error('unexpected') } })
  assert.equal(result?.session.created_at, original.created_at)
  assert.notEqual(result?.session.created_at, turn().created_at)
  const verified = parseAgentSession(result!.session, owner.job_id, owner.video_id, owner.video_sha256)
  assert.doesNotThrow(() => newestAgentSession(verified, original))
})

test('structurally valid responses with cached immutable identity conflicts preserve the original receipt before ACK', () => {
  const { store, value } = persisted(), known = session(); known.turns = [turn()]
  const changed = turn(); changed.sequence = 2
  const response = parseAgentSubmissionResponse(changed, value, session())
  assert.throws(() => { mergeAgentSubmissionResult(known, response); removeAgentSubmission(store, value) })
  assert.equal(store.getItem(agentSubmissionStorageKey(value)), JSON.stringify(value)); assert.equal(store.removes, 0)
  const otherSession = clone(known); otherSession.created_at = '2026-11-08T00:00:05Z'
  assert.throws(() => mergeAgentSubmissionResult(known, { kind: 'SESSION', session: otherSession }))
  assert.equal(store.removes, 0)
})

test('recovery clears only the unchanged submitted draft in its original conversation', () => {
  const value = entry(), original = { session_id: SESSION, question: '原问题A', budget: 8 }
  assert.equal(agentDraftAfterSubmission(value, original), '')
  assert.equal(agentDraftAfterSubmission(value, { ...original, question: '草稿B' }), '草稿B')
  assert.equal(agentDraftAfterSubmission(value, { ...original, budget: 4 }), '原问题A')
  assert.equal(agentDraftAfterSubmission(value, { ...original, session_id: JOB }), '原问题A')
  assert.equal(agentDraftAfterSubmission(entry('SESSION'), original), '原问题A')
})

test('hidden, unmounted, switched-task and late-read lifetimes cannot POST or acknowledge', async () => {
  let current = true, posts = 0
  const result = await resolveAgentSubmission(entry(), { current: () => current, get: async () => { current = false; throw failure('AGENT_TURN_SUBMISSION_NOT_FOUND') }, readSession: async () => session(), beforePost: () => {}, post: async () => { posts++; return turn() } })
  assert.equal(result, null); assert.equal(posts, 0)
  current = true
  const later = await resolveAgentSubmission(entry(), { current: () => current, get: async () => turn(), readSession: async () => { current = false; return session() }, beforePost: () => {}, post: async () => { posts++; return turn() } })
  assert.equal(later, null); assert.equal(posts, 0)
})

test('strict read-back before retry and full CAS removal preserve unrelated independent entries', async () => {
  const { store, value } = persisted(), other = entry('TURN', 'other-key'); restoreAgentSubmission(store, other)
  removeAgentSubmission(store, value)
  assert.equal(store.getItem(agentSubmissionStorageKey(other)), JSON.stringify(other))
  restoreAgentSubmission(store, value)
  let posts = 0
  await assert.rejects(resolveAgentSubmission(value, { current: () => true, get: async () => { throw failure('AGENT_TURN_SUBMISSION_NOT_FOUND') }, readSession: async () => {
    store.values.set(agentSubmissionStorageKey(value), JSON.stringify({ ...value, input: JSON.stringify({ question: 'B', max_text_calls: 8 }) })); return session()
  }, beforePost: () => assertAgentSubmissionStored(store, value), post: async () => { posts++; return turn() } }), code('AGENT_SUBMISSION_ENTRY_CONFLICT'))
  assert.equal(posts, 0)
})

test('SESSION_LIMIT inspection requires another business404 and fresh same-source session with exactly 100 turns', async () => {
  const full = session(); full.turns = Array.from({ length: 100 }, (_, index) => turn(index % 2 ? 'CANCELLED' : 'FAILED', index + 1))
  const { store, value } = persisted(), events: string[] = []
  const result = await inspectAgentSubmissionLimit(value, { current: () => true, get: async () => { events.push('GET original'); throw failure('AGENT_TURN_SUBMISSION_NOT_FOUND') }, readSession: async () => { events.push('GET source'); return full } })
  assert.equal(result?.kind, 'session-full'); assert.deepEqual(events, ['GET original', 'GET source']); assert.notEqual(store.getItem(agentSubmissionStorageKey(value)), null, 'inspection never clears automatically')
  for (const bad of [session(), { ...clone(full), turns: full.turns.slice(0, 99) }, { ...clone(full), source: { ...full.source, transcript_hash: 'f'.repeat(64) } }])
    await assert.rejects(inspectAgentSubmissionLimit(value, { current: () => true, get: async () => { throw failure('AGENT_TURN_SUBMISSION_NOT_FOUND') }, readSession: async () => bad }))
})

test('limit reinspection handles late original success and never unlocks on generic404, BUSY or stale callbacks', async () => {
  const value = entry(); let sourceReads = 0
  const found = await inspectAgentSubmissionLimit(value, { current: () => true, get: async () => turn('CANCELLED'), readSession: async () => { sourceReads++; return session() } })
  assert.equal(found?.kind, 'completed')
  for (const error of [failure('HTTP_404'), failure('AGENT_SESSION_BUSY', 409), failure('NETWORK_UNAVAILABLE', 0)])
    await assert.rejects(inspectAgentSubmissionLimit(value, { current: () => true, get: async () => { throw error }, readSession: async () => { sourceReads++; return session() } }))
  assert.equal(sourceReads, 1)
  let current = true
  assert.equal(await inspectAgentSubmissionLimit(value, { current: () => current, get: async () => { current = false; throw failure('AGENT_TURN_SUBMISSION_NOT_FOUND') }, readSession: async () => { sourceReads++; return session() } }), null)
  assert.equal(sourceReads, 1)
})
