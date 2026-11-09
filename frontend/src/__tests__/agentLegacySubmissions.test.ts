import test from 'node:test'
import assert from 'node:assert/strict'
import { AgentLegacySubmissionContext, AgentSubmissionError, MAX_AGENT_LEGACY_SLOTS, agentLegacyTurnSlots,
  createAgentSubmission, scanAgentSubmissions } from '../agentSubmissionRecovery.ts'
import { agentSessionSummaryFromDetail } from '../agentSessionHistory.ts'
import { clone, MemoryStorage, owner, session } from './support/agentSubmissionFixture.ts'

const code = (expected: string) => (error: unknown) => error instanceof AgentSubmissionError && error.code === expected
function setup() {
  const storage = new MemoryStorage(), full = session(), context = new AgentLegacySubmissionContext(owner)
  const storageKey = `livecut-agent-turn-${full.id}`, original = { key: 'legacy-original-key', input: JSON.stringify({ question: '旧问题，不应因翻页丢失', max_text_calls: 4 }) }
  storage.values.set(storageKey, JSON.stringify(original))
  return { storage, full, context, storageKey, original, slot: agentLegacyTurnSlots(storage)[0]! }
}
function foreignSession() {
  const full = session(); full.job_id = '99999999-9999-4999-8999-999999999999'; full.video_id = '88888888-8888-4888-8888-888888888888'
  return full
}

test('an offpage legacy slot blocks new submissions until a complete same-source session establishes its exact ownership', () => {
  const h = setup(); let keys = 0
  const pending = scanAgentSubmissions(h.storage, owner, [], h.context)
  assert.equal(pending.entries.length, 0); assert.equal(pending.issues[0]!.code, 'AGENT_SUBMISSION_LEGACY_OWNER_UNVERIFIED')
  assert.throws(() => createAgentSubmission(h.storage, owner, 'SESSION', '{}', () => { keys++; return 'new' }, [], undefined, [], h.context), code('AGENT_SUBMISSION_LEGACY_OWNER_UNVERIFIED'))
  assert.equal(keys, 0); assert.equal(h.storage.getItem(h.storageKey), h.slot.raw)
  h.context.classify(h.storage, h.slot, h.full, () => true)
  const migrated = scanAgentSubmissions(h.storage, owner, [], h.context)
  assert.equal(migrated.issues.length, 0); assert.equal(migrated.entries.length, 1); assert.equal(h.storage.getItem(h.storageKey), null)
  const entry = migrated.entries[0]!
  assert.equal(entry.key, h.original.key); assert.equal(entry.input, h.original.input); assert.deepEqual(entry.source, h.full.source)
  assert.equal(entry.session_id, h.full.id); assert.throws(() => createAgentSubmission(h.storage, owner, 'SESSION', '{}', () => 'new', [], undefined, [], h.context), code('AGENT_SUBMISSION_PENDING'))
})

test('a strictly verified foreign job is ignored only in this owner context and never migrated or deleted', () => {
  const h = setup(), full = foreignSession(), before = h.slot.raw
  assert.equal(h.context.classify(h.storage, h.slot, full, () => true), null)
  const scan = scanAgentSubmissions(h.storage, owner, [], h.context)
  assert.deepEqual(scan.entries, []); assert.deepEqual(scan.issues, []); assert.equal(h.storage.getItem(h.storageKey), before)
  const entry = createAgentSubmission(h.storage, owner, 'SESSION', '{}', () => 'allowed-after-classification', [], undefined, [], h.context)
  assert.equal(entry.kind, 'SESSION'); assert.equal(h.storage.getItem(h.storageKey), before)
  assert.equal(h.context.inspect(h.slot, { ...owner, job_id: full.job_id }), null)
})

test('metadata or wrong-id/key/source responses cannot become a legacy ownership proof', () => {
  const h = setup()
  assert.throws(() => h.context.classify(h.storage, h.slot, agentSessionSummaryFromDetail(h.full), () => true))
  assert.throws(() => h.context.classify(h.storage, { ...h.slot, storage_key: `livecut-agent-turn-${owner.video_id}` }, h.full, () => true), code('AGENT_SUBMISSION_LEGACY_OWNER_INVALID'))
  assert.throws(() => h.context.classify(h.storage, { ...h.slot, session_id: owner.video_id }, h.full, () => true), code('AGENT_SUBMISSION_LEGACY_OWNER_INVALID'))
  const other = clone(h.full); other.id = owner.video_id
  assert.throws(() => h.context.classify(h.storage, h.slot, other, () => true))
  for (const replacement of [{ ...h.full, video_id: owner.job_id }, { ...h.full, source: { ...h.full.source, video_sha256: 'd'.repeat(64) } }]) {
    assert.throws(() => h.context.classify(h.storage, h.slot, replacement, () => true), code('AGENT_SUBMISSION_SOURCE_MISMATCH'))
  }
  assert.equal(h.context.inspect(h.slot, owner), null); assert.equal(h.storage.getItem(h.storageKey), h.slot.raw)
})

test('slot proof binds complete raw bytes and becomes unverified after another page changes body or serialization', () => {
  const h = setup(); h.context.classify(h.storage, h.slot, foreignSession(), () => true)
  for (const raw of [JSON.stringify({ ...h.original, key: 'another-key' }), JSON.stringify({ input: h.original.input, key: h.original.key })]) {
    h.storage.values.set(h.storageKey, raw)
    assert.equal(h.context.unresolved(h.storage, owner).length, 1)
    assert.throws(() => createAgentSubmission(h.storage, owner, 'SESSION', '{}', () => 'new', [], undefined, [], h.context), code('AGENT_SUBMISSION_LEGACY_OWNER_UNVERIFIED'))
    assert.equal(h.storage.getItem(h.storageKey), raw)
  }
})

test('late owner lookups after hidden/source cancellation cannot classify or clear a durable slot', () => {
  const h = setup(); const writes = h.storage.writes
  assert.equal(h.context.classify(h.storage, h.slot, h.full, () => false), null)
  assert.equal(h.context.inspect(h.slot, owner), null); assert.equal(h.storage.writes, writes)
  h.context.dispose(); assert.equal(h.context.classify(h.storage, h.slot, h.full, () => true), null)
  assert.equal(scanAgentSubmissions(h.storage, owner, [], h.context).issues[0]!.code, 'AGENT_SUBMISSION_LEGACY_OWNER_UNVERIFIED')
  const next = new AgentLegacySubmissionContext({ ...owner, video_sha256: 'd'.repeat(64) })
  assert.equal(next.inspect(h.slot, owner), null); assert.equal(h.storage.getItem(h.storageKey), h.slot.raw)
})

test('previously verified unchanged foreign proof survives hiding, while incomplete lookups remain unverified', () => {
  const h = setup(); h.context.classify(h.storage, h.slot, foreignSession(), () => true)
  assert.equal(h.context.classify(h.storage, h.slot, foreignSession(), () => false), null)
  assert.equal(h.context.unresolved(h.storage, owner).length, 0)
  assert.deepEqual(scanAgentSubmissions(h.storage, owner, [], h.context).issues, [])
  assert.equal(h.storage.getItem(h.storageKey), h.slot.raw)
})

test('copy/read-back migration preserves both receipts when another page replaces the original slot before deletion', () => {
  const h = setup(); h.context.classify(h.storage, h.slot, h.full, () => true)
  const nextRaw = JSON.stringify({ key: 'second-key', input: JSON.stringify({ question: '另一页更新的问题', max_text_calls: 4 }) })
  h.storage.onWrite = key => { if (key.startsWith('livecut-agent-submission-v1:')) h.storage.values.set(h.storageKey, nextRaw) }
  const result = scanAgentSubmissions(h.storage, owner, [], h.context)
  assert.equal(result.issues[0]!.code, 'AGENT_SUBMISSION_LEGACY_CHANGED'); assert.equal(result.entries[0]!.key, h.original.key)
  assert.equal(h.storage.getItem(h.storageKey), nextRaw)
  assert.throws(() => createAgentSubmission(h.storage, owner, 'SESSION', '{}', () => 'third-key', [], undefined, [], h.context), code('AGENT_SUBMISSION_LEGACY_OWNER_UNVERIFIED'))
})

test('fresh before-and-after creation gates detect a new offpage slot without receiving a storage event', () => {
  const h = setup(); h.context.classify(h.storage, h.slot, foreignSession(), () => true)
  const newSlotKey = 'livecut-agent-turn-77777777-7777-4777-8777-777777777777'
  h.storage.onWrite = key => {
    if (key.startsWith('livecut-agent-submission-v1:')) h.storage.values.set(newSlotKey, JSON.stringify({ key: 'peer-key', input: h.original.input }))
  }
  assert.throws(() => createAgentSubmission(h.storage, owner, 'SESSION', '{}', () => 'new-unposted-key', [], undefined, [], h.context), code('AGENT_SUBMISSION_PENDING'))
  const snapshot = scanAgentSubmissions(h.storage, owner, [], h.context)
  assert(snapshot.issues.some(value => value.storage_key === newSlotKey)); assert(snapshot.entries.some(value => value.key === 'new-unposted-key'))
  assert.equal(h.storage.getItem(h.storageKey), h.slot.raw)
})

test('another-page body change after the pre-scan blocks creation before it can return a sendable receipt', () => {
  const h = setup(); h.context.classify(h.storage, h.slot, foreignSession(), () => true)
  const changed = JSON.stringify({ key: 'peer-changed-key', input: h.original.input })
  h.storage.onWrite = key => { if (key.startsWith('livecut-agent-submission-v1:')) h.storage.values.set(h.storageKey, changed) }
  assert.throws(() => createAgentSubmission(h.storage, owner, 'SESSION', '{}', () => 'new-key', [], undefined, [], h.context), code('AGENT_SUBMISSION_PENDING'))
  assert.equal(h.storage.getItem(h.storageKey), changed)
})

test('proof snapshots do not share caller-mutable source objects and overflow remains a blocking retained condition', () => {
  const h = setup(); h.context.classify(h.storage, h.slot, h.full, () => true)
  h.full.source.transcript_hash = 'd'.repeat(64)
  const inspect = h.context.inspect(h.slot, owner)!
  inspect.session.source.transcript_hash = 'e'.repeat(64)
  assert.equal(h.context.inspect(h.slot, owner)!.session.source.transcript_hash, 'b'.repeat(64))
  h.context.forget(h.slot); assert.equal(h.context.inspect(h.slot, owner), null)
  const storage = new MemoryStorage(), context = new AgentLegacySubmissionContext(owner)
  for (let index = 0; index <= MAX_AGENT_LEGACY_SLOTS; index++) storage.values.set(`livecut-agent-turn-77777777-7777-4777-8777-${String(index).padStart(12, '0')}`, h.slot.raw)
  assert.throws(() => agentLegacyTurnSlots(storage), code('AGENT_SUBMISSION_LEGACY_LIMIT'))
  assert.throws(() => createAgentSubmission(storage, owner, 'SESSION', '{}', () => 'new-key', [], undefined, [], context), code('AGENT_SUBMISSION_LEGACY_LIMIT'))
  assert.equal(storage.length, MAX_AGENT_LEGACY_SLOTS + 1)
})
