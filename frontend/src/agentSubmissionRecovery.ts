import { newestAgentSession, newestAgentTurn, parseAgentSession, parseAgentTurn, type AgentSession, type AgentTurn } from './videoAgent.ts'

export interface AgentSubmissionOwner { job_id: string; video_id: string; video_sha256: string }
export interface AgentSubmissionEntry extends AgentSubmissionOwner {
  schema_version: 'agent-submission-v1'; kind: 'SESSION' | 'TURN'; key: string; session_id: string | null
  source: AgentSession['source'] | null; input: string
}
export interface AgentSubmissionStorage {
  readonly length: number; key(index: number): string | null
  getItem(key: string): string | null; setItem(key: string, value: string): void; removeItem(key: string): void
}
export interface AgentSubmissionIssue { storage_key: string; code: string }
export interface AgentSubmissionScan { entries: AgentSubmissionEntry[]; issues: AgentSubmissionIssue[] }
export class AgentSubmissionError extends Error {
  code: string
  constructor(code = 'INVALID_AGENT_SUBMISSION_RESPONSE') { super(code); this.code = code }
}
export const AGENT_SUBMISSION_PREFIX = 'livecut-agent-submission-v1:'
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i, KEY = /^[A-Za-z0-9_-]{1,100}$/, HASH = /^[a-f0-9]{64}$/
const row = (raw: unknown): raw is Record<string, unknown> => raw !== null && typeof raw === 'object' && !Array.isArray(raw)
const exact = (raw: unknown, keys: string[]): raw is Record<string, unknown> => row(raw) && Object.keys(raw).length === keys.length && keys.every(key => Object.hasOwn(raw, key))
const integer = (raw: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): raw is number => Number.isSafeInteger(raw) && (raw as number) >= min && (raw as number) <= max
const uuid = (raw: unknown): raw is string => typeof raw === 'string' && UUID.test(raw)
const key = (raw: unknown): raw is string => typeof raw === 'string' && KEY.test(raw)
const hash = (raw: unknown): raw is string => typeof raw === 'string' && HASH.test(raw)
const sameSource = (a: AgentSession['source'], b: AgentSession['source']) => a.video_sha256 === b.video_sha256 && a.transcript_hash === b.transcript_hash && a.duration_ms === b.duration_ms
function fail(code?: string): never { throw new AgentSubmissionError(code) }
function sameOwner(entry: AgentSubmissionOwner, owner: AgentSubmissionOwner) { return entry.job_id === owner.job_id && entry.video_id === owner.video_id && entry.video_sha256 === owner.video_sha256 }
export interface AgentTurnSubmissionInput { question: string; max_text_calls: number; visual_mode?: 'none' | 'frames'; focus_timestamp_ms?: number; evidence_mode?: 'none' | 'structured' }
export function agentSubmissionInput(kind: AgentSubmissionEntry['kind'], raw: unknown): {} | AgentTurnSubmissionInput | null {
  if (kind === 'SESSION') return exact(raw, []) ? {} : null
  if (!row(raw) || !Object.hasOwn(raw, 'question') || !Object.hasOwn(raw, 'max_text_calls')
    || !Object.keys(raw).every(key => ['question', 'max_text_calls', 'visual_mode', 'focus_timestamp_ms', 'evidence_mode'].includes(key))
    || !(raw.visual_mode === undefined || raw.visual_mode === 'none' || raw.visual_mode === 'frames') || typeof raw.question !== 'string' || !raw.question.trim() || raw.question !== raw.question.trim()
    || raw.question.length > 2000 || !integer(raw.max_text_calls, 1, 24)
    || Object.hasOwn(raw, 'focus_timestamp_ms') && (raw.visual_mode !== 'frames' || !integer(raw.focus_timestamp_ms))
    || Object.hasOwn(raw, 'evidence_mode') && (raw.evidence_mode !== 'none' && raw.evidence_mode !== 'structured' || raw.evidence_mode === 'structured' && raw.visual_mode !== 'frames')) return null
  return { question: raw.question, max_text_calls: raw.max_text_calls, ...(Object.hasOwn(raw, 'visual_mode') ? { visual_mode: raw.visual_mode as 'none' | 'frames' } : {}),
    ...(Object.hasOwn(raw, 'focus_timestamp_ms') ? { focus_timestamp_ms: raw.focus_timestamp_ms as number } : {}),
    ...(Object.hasOwn(raw, 'evidence_mode') ? { evidence_mode: raw.evidence_mode as 'none' | 'structured' } : {}) }
}
export function parseAgentSubmissionEntry(raw: unknown): AgentSubmissionEntry | null {
  if (!exact(raw, ['schema_version', 'kind', 'key', 'job_id', 'video_id', 'video_sha256', 'session_id', 'source', 'input'])
    || raw.schema_version !== 'agent-submission-v1' || !['SESSION', 'TURN'].includes(raw.kind as string) || !key(raw.key)
    || !uuid(raw.job_id) || !uuid(raw.video_id) || !hash(raw.video_sha256) || typeof raw.input !== 'string' || raw.input.length > 16000) return null
  if (raw.kind === 'SESSION') { if (raw.session_id !== null || raw.source !== null) return null }
  else if (!uuid(raw.session_id) || !exact(raw.source, ['video_sha256', 'transcript_hash', 'duration_ms'])
    || raw.source.video_sha256 !== raw.video_sha256 || !hash(raw.source.transcript_hash) || !integer(raw.source.duration_ms, 1)) return null
  try {
    const input = agentSubmissionInput(raw.kind as AgentSubmissionEntry['kind'], JSON.parse(raw.input)) as AgentTurnSubmissionInput | null
    if (!input || raw.kind === 'TURN' && input.focus_timestamp_ms !== undefined && input.focus_timestamp_ms >= (raw.source as { duration_ms: number }).duration_ms) return null
  } catch { return null }
  return raw as unknown as AgentSubmissionEntry
}
export function agentSubmissionStorageKey(entry: AgentSubmissionEntry): string {
  return `${AGENT_SUBMISSION_PREFIX}${entry.job_id}:${entry.kind}:${entry.session_id ?? 'new'}:${entry.key}`
}
export function sameAgentSubmission(a: AgentSubmissionEntry, b: AgentSubmissionEntry): boolean {
  return a.schema_version === b.schema_version && a.kind === b.kind && a.key === b.key && sameOwner(a, b) && a.session_id === b.session_id
    && a.input === b.input && (a.source === null ? b.source === null : b.source !== null && sameSource(a.source, b.source))
}
function decode(raw: string | null): AgentSubmissionEntry | null { try { return raw === null ? null : parseAgentSubmissionEntry(JSON.parse(raw)) } catch { return null } }
/** An entry is immutable. Success requires a full read-back, never a mount cache acknowledgement. */
function writeImmutable(storage: AgentSubmissionStorage, entry: AgentSubmissionEntry): void {
  const storageKey = agentSubmissionStorageKey(entry), previous = storage.getItem(storageKey)
  if (previous !== null) {
    const parsed = decode(previous)
    if (!parsed || !sameAgentSubmission(parsed, entry)) fail('AGENT_SUBMISSION_ENTRY_CONFLICT')
  } else storage.setItem(storageKey, JSON.stringify(entry))
  const saved = decode(storage.getItem(storageKey))
  if (!saved || !sameAgentSubmission(saved, entry)) fail('AGENT_SUBMISSION_STORAGE_UNAVAILABLE')
}
function migrateLegacy(storage: AgentSubmissionStorage, legacyKey: string, owner: AgentSubmissionOwner, session?: AgentSession, expectedRaw?: string): void {
  const raw = storage.getItem(legacyKey)
  if (raw === null) return
  if (expectedRaw !== undefined && raw !== expectedRaw) fail('AGENT_SUBMISSION_LEGACY_CHANGED')
  let old: unknown
  try { old = JSON.parse(raw) } catch { fail('AGENT_SUBMISSION_LEGACY_INVALID') }
  if (!exact(old, ['key', 'input']) || !key(old.key) || typeof old.input !== 'string') fail('AGENT_SUBMISSION_LEGACY_INVALID')
  const entry = parseAgentSubmissionEntry({ schema_version: 'agent-submission-v1', kind: session ? 'TURN' : 'SESSION',
    key: old.key, ...owner, session_id: session?.id ?? null, source: session ? { ...session.source } : null, input: old.input })
  if (!entry) fail('AGENT_SUBMISSION_LEGACY_INVALID')
  writeImmutable(storage, entry)
  // Copy/read-back must succeed before a fresh full comparison can remove the old slot.
  if (storage.getItem(legacyKey) !== raw) fail('AGENT_SUBMISSION_LEGACY_CHANGED')
  storage.removeItem(legacyKey)
}
export interface AgentLegacyTurnSlot { storage_key: string; session_id: string; raw: string }
export const MAX_AGENT_LEGACY_SLOTS = 128
/** Unknown legacy slots carry no job id; they cannot disappear from the new-submit gate merely through pagination. */
export function agentLegacyTurnSlots(storage: AgentSubmissionStorage): AgentLegacyTurnSlot[] {
  const keys: string[] = []
  for (let index = 0; index < storage.length; index++) {
    const name = storage.key(index)
    if (name?.startsWith('livecut-agent-turn-')) {
      if (!uuid(name.slice('livecut-agent-turn-'.length))) fail('AGENT_SUBMISSION_LEGACY_INVALID')
      keys.push(name)
    }
  }
  if (keys.length > MAX_AGENT_LEGACY_SLOTS) fail('AGENT_SUBMISSION_LEGACY_LIMIT')
  return [...new Set(keys)].sort().flatMap(storage_key => {
    const raw = storage.getItem(storage_key)
    return raw === null ? [] : [{ storage_key, session_id: storage_key.slice('livecut-agent-turn-'.length), raw }]
  })
}
/** A mount-local proof is made only from a complete parsed session and the exact durable slot bytes. */
export class AgentLegacySubmissionContext {
  private owner: AgentSubmissionOwner
  private proofs = new Map<string, { raw: string; session: AgentSession; foreign: boolean }>()
  private disposed = false
  constructor(owner: AgentSubmissionOwner) { this.owner = { ...owner } }
  classify(storage: AgentSubmissionStorage, slot: AgentLegacyTurnSlot, rawSession: unknown, current: () => boolean): AgentSession | null {
    if (!uuid(slot.session_id) || slot.storage_key !== `livecut-agent-turn-${slot.session_id}`) fail('AGENT_SUBMISSION_LEGACY_OWNER_INVALID')
    if (this.disposed || !current() || storage.getItem(slot.storage_key) !== slot.raw) return null
    if (!row(rawSession) || !uuid(rawSession.job_id) || !uuid(rawSession.video_id) || !row(rawSession.source) || !hash(rawSession.source.video_sha256)) fail('AGENT_SUBMISSION_LEGACY_OWNER_INVALID')
    const session = parseAgentSession(rawSession, rawSession.job_id, rawSession.video_id, rawSession.source.video_sha256, slot.session_id)
    const foreign = session.job_id !== this.owner.job_id
    if (!foreign && (session.video_id !== this.owner.video_id || session.source.video_sha256 !== this.owner.video_sha256)) fail('AGENT_SUBMISSION_SOURCE_MISMATCH')
    if (this.disposed || !current() || storage.getItem(slot.storage_key) !== slot.raw) return null
    this.proofs.set(slot.storage_key, { raw: slot.raw, session: JSON.parse(JSON.stringify(session)) as AgentSession, foreign })
    return foreign ? null : session
  }
  inspect(slot: AgentLegacyTurnSlot, owner: AgentSubmissionOwner): { foreign: boolean; session: AgentSession } | null {
    if (this.disposed || !sameOwner(this.owner, owner)) return null
    const proof = this.proofs.get(slot.storage_key)
    return proof && proof.raw === slot.raw ? { foreign: proof.foreign, session: JSON.parse(JSON.stringify(proof.session)) as AgentSession } : null
  }
  unresolved(storage: AgentSubmissionStorage, owner: AgentSubmissionOwner): AgentLegacyTurnSlot[] {
    return agentLegacyTurnSlots(storage).filter(slot => !this.inspect(slot, owner))
  }
  forget(slot: AgentLegacyTurnSlot) { if (this.proofs.get(slot.storage_key)?.raw === slot.raw) this.proofs.delete(slot.storage_key) }
  dispose() { this.disposed = true; this.proofs.clear() }
}
/** Scan every entry for this job, including independent entries created by another page. */
export function scanAgentSubmissions(storage: AgentSubmissionStorage, owner: AgentSubmissionOwner, sessions: readonly AgentSession[] = [], legacy?: AgentLegacySubmissionContext): AgentSubmissionScan {
  const result: AgentSubmissionScan = { entries: [], issues: [] }
  const attempt = (storageKey: string, run: () => void) => { try { run() } catch (error) { result.issues.push({ storage_key: storageKey, code: error instanceof AgentSubmissionError ? error.code : 'AGENT_SUBMISSION_STORAGE_UNAVAILABLE' }) } }
  const legacySession = `livecut-agent-session-${owner.job_id}`
  attempt(legacySession, () => migrateLegacy(storage, legacySession, owner))
  if (legacy) attempt('livecut-agent-turn-', () => {
    if (!(legacy instanceof AgentLegacySubmissionContext)) fail('AGENT_SUBMISSION_LEGACY_OWNER_UNVERIFIED')
    for (const slot of agentLegacyTurnSlots(storage)) attempt(slot.storage_key, () => {
      const proof = legacy.inspect(slot, owner)
      if (!proof) fail('AGENT_SUBMISSION_LEGACY_OWNER_UNVERIFIED')
      if (!proof.foreign) migrateLegacy(storage, slot.storage_key, owner, proof.session, slot.raw)
    })
  })
  else for (const rawSession of sessions) {
    const legacyTurn = `livecut-agent-turn-${rawSession.id}`
    attempt(legacyTurn, () => {
      const session = parseAgentSession(rawSession, owner.job_id, owner.video_id, owner.video_sha256, rawSession.id)
      migrateLegacy(storage, legacyTurn, owner, session)
    })
  }
  attempt(`${AGENT_SUBMISSION_PREFIX}${owner.job_id}:`, () => {
    const keys: string[] = []
    for (let index = 0; index < storage.length; index++) { const storageKey = storage.key(index); if (storageKey?.startsWith(`${AGENT_SUBMISSION_PREFIX}${owner.job_id}:`)) keys.push(storageKey) }
    for (const storageKey of keys.sort()) attempt(storageKey, () => {
      const raw = storage.getItem(storageKey)
      if (raw === null) return // Another page already acknowledged this immutable entry.
      const entry = decode(raw)
      if (!entry || agentSubmissionStorageKey(entry) !== storageKey) fail('AGENT_SUBMISSION_ENTRY_INVALID')
      if (!sameOwner(entry, owner)) fail('AGENT_SUBMISSION_SOURCE_MISMATCH')
      result.entries.push(entry)
    })
  })
  return result
}
/** Only this mount's prior strict server acknowledgement can exempt a reappearing exact entry. */
export function filterAcknowledgedAgentSubmissions(scan: AgentSubmissionScan, acknowledged: readonly AgentSubmissionEntry[]): AgentSubmissionScan {
  return { entries: scan.entries.filter(entry => !acknowledged.some(known => sameAgentSubmission(known, entry))), issues: [...scan.issues] }
}
export function createAgentSubmission(storage: AgentSubmissionStorage, owner: AgentSubmissionOwner, kind: AgentSubmissionEntry['kind'], input: string,
  newKey: () => string, sessions: readonly AgentSession[] = [], session?: AgentSession, acknowledged: readonly AgentSubmissionEntry[] = [], legacy?: AgentLegacySubmissionContext): AgentSubmissionEntry {
  try {
    const pending = filterAcknowledgedAgentSubmissions(scanAgentSubmissions(storage, owner, sessions, legacy), acknowledged)
    if (pending.issues.length) fail(pending.issues[0]!.code)
    if (pending.entries.length) fail('AGENT_SUBMISSION_PENDING')
    const validSession = kind === 'TURN' && session ? parseAgentSession(session, owner.job_id, owner.video_id, owner.video_sha256, session.id) : null
    const entry = parseAgentSubmissionEntry({ schema_version: 'agent-submission-v1', kind, key: newKey(), ...owner,
      session_id: validSession?.id ?? null, source: validSession ? { ...validSession.source } : null, input })
    if (!entry) fail('AGENT_SUBMISSION_ENTRY_INVALID')
    writeImmutable(storage, entry)
    const after = filterAcknowledgedAgentSubmissions(scanAgentSubmissions(storage, owner, sessions, legacy), acknowledged)
    if (after.issues.length || after.entries.length !== 1 || !sameAgentSubmission(after.entries[0]!, entry)) fail('AGENT_SUBMISSION_PENDING')
    return entry
  } catch (error) { if (error instanceof AgentSubmissionError) throw error; fail('AGENT_SUBMISSION_STORAGE_UNAVAILABLE') }
}
/** Immediately before a retry/delete, compare the actual durable record in full. */
export function assertAgentSubmissionStored(storage: AgentSubmissionStorage, entry: AgentSubmissionEntry): void {
  try {
    const saved = decode(storage.getItem(agentSubmissionStorageKey(entry)))
    if (!saved || !sameAgentSubmission(saved, entry)) fail('AGENT_SUBMISSION_ENTRY_CONFLICT')
  } catch (error) { if (error instanceof AgentSubmissionError) throw error; fail('AGENT_SUBMISSION_STORAGE_UNAVAILABLE') }
}
/** External deletion is not acknowledgement. A business-404 retry can re-persist this same identity. */
export function restoreAgentSubmission(storage: AgentSubmissionStorage, entry: AgentSubmissionEntry): void {
  if (!parseAgentSubmissionEntry(entry)) fail()
  try { writeImmutable(storage, entry) } catch (error) { if (error instanceof AgentSubmissionError) throw error; fail('AGENT_SUBMISSION_STORAGE_UNAVAILABLE') }
}
export function retainAgentSubmissions(known: readonly AgentSubmissionEntry[], scan: AgentSubmissionScan, storage?: AgentSubmissionStorage): AgentSubmissionScan {
  const entries = new Map(known.map(entry => [agentSubmissionStorageKey(entry), entry])), issues = [...scan.issues]
  for (const entry of scan.entries) {
    const storageKey = agentSubmissionStorageKey(entry), previous = entries.get(storageKey)
    if (previous && !sameAgentSubmission(previous, entry)) issues.push({ storage_key: storageKey, code: 'AGENT_SUBMISSION_ENTRY_CONFLICT' })
    else entries.set(storageKey, entry)
  }
  if (storage) for (const entry of known) {
    try { restoreAgentSubmission(storage, entry) }
    catch (error) { issues.push({ storage_key: agentSubmissionStorageKey(entry), code: error instanceof AgentSubmissionError ? error.code : 'AGENT_SUBMISSION_STORAGE_UNAVAILABLE' }) }
  }
  return { entries: [...entries.values()], issues }
}
export function removeAgentSubmission(storage: AgentSubmissionStorage, entry: AgentSubmissionEntry): void {
  try {
    const storageKey = agentSubmissionStorageKey(entry)
    if (storage.getItem(storageKey) === null) return
    assertAgentSubmissionStored(storage, entry)
    storage.removeItem(storageKey)
  } catch (error) { if (error instanceof AgentSubmissionError) throw error; fail('AGENT_SUBMISSION_STORAGE_UNAVAILABLE') }
}
export type AgentSubmissionResult = { kind: 'SESSION'; session: AgentSession } | { kind: 'TURN'; session: AgentSession; turn: AgentTurn }
/** Validate the entire adoption against cached immutable identities before any receipt can be cleared. */
export function mergeAgentSubmissionResult(known: AgentSession | undefined, result: AgentSubmissionResult): AgentSession {
  const merged = newestAgentSession(known, result.session)
  if (result.kind === 'SESSION') return merged
  const latest = newestAgentTurn(merged.turns.find(value => value.id === result.turn.id), result.turn)
  const turns = [...merged.turns.filter(value => value.id !== latest.id), latest].sort((a, b) => a.sequence - b.sequence)
  return parseAgentSession({ ...merged, turns }, merged.job_id, merged.video_id, merged.source.video_sha256, merged.id)
}
export function parseAgentSubmissionResponse(raw: unknown, entry: AgentSubmissionEntry, session?: AgentSession): AgentSubmissionResult {
  if (!parseAgentSubmissionEntry(entry)) fail()
  if (entry.kind === 'SESSION') return { kind: 'SESSION', session: parseAgentSession(raw, entry.job_id, entry.video_id, entry.video_sha256) }
  if (!session || session.id !== entry.session_id || session.job_id !== entry.job_id || session.video_id !== entry.video_id || !entry.source || !sameSource(session.source, entry.source)) fail()
  const verified = parseAgentSession(session, entry.job_id, entry.video_id, entry.video_sha256, entry.session_id!)
  const turn = parseAgentTurn(raw, verified), input = agentSubmissionInput('TURN', JSON.parse(entry.input)) as AgentTurnSubmissionInput
  if (turn.question !== input.question || turn.budget.max_text_calls !== input.max_text_calls || (turn.visual_mode ?? 'none') !== (input.visual_mode ?? 'none')
    || turn.focus_timestamp_ms !== input.focus_timestamp_ms || (turn.evidence_mode ?? 'none') !== (input.evidence_mode ?? 'none')) fail()
  return { kind: 'TURN', session: verified, turn }
}
export function agentSubmissionBusinessNotFound(error: unknown, kind: AgentSubmissionEntry['kind']): boolean {
  return row(error) && error.status === 404 && error.code === (kind === 'SESSION' ? 'AGENT_SESSION_SUBMISSION_NOT_FOUND' : 'AGENT_TURN_SUBMISSION_NOT_FOUND')
}
export async function resolveAgentSubmission(entry: AgentSubmissionEntry, io: {
  get: () => Promise<unknown>; post: () => Promise<unknown>; readSession?: () => Promise<unknown>; beforePost: () => void; current: () => boolean
}): Promise<AgentSubmissionResult | null> {
  if (!parseAgentSubmissionEntry(entry)) fail()
  let raw: unknown, missing = false, session: AgentSession | undefined
  try { raw = await io.get() }
  catch (error) { if (!io.current()) return null; if (!agentSubmissionBusinessNotFound(error, entry.kind)) throw error; missing = true }
  if (!io.current()) return null
  if (entry.kind === 'TURN') {
    if (!io.readSession) fail()
    const owner = await io.readSession()
    if (!io.current()) return null
    session = parseAgentSession(owner, entry.job_id, entry.video_id, entry.video_sha256, entry.session_id!)
    if (!entry.source || !sameSource(session.source, entry.source)) fail()
  }
  if (missing) {
    if (!io.current()) return null
    io.beforePost()
    raw = await io.post()
    if (!io.current()) return null
  }
  const result = parseAgentSubmissionResponse(raw, entry, session)
  return io.current() ? result : null
}
/** Only completed acknowledgement in the original session may clear the still-identical submitted draft. */
export interface AgentSubmissionDraft { session_id: string; question: string; budget: number; visual_mode?: 'none' | 'frames'; focus_timestamp_ms?: number; evidence_mode?: 'none' | 'structured' }
export function agentDraftMatchesSubmission(entry: AgentSubmissionEntry, draft: AgentSubmissionDraft): boolean {
  if (entry.kind !== 'TURN' || draft.session_id !== entry.session_id) return false
  const input = agentSubmissionInput('TURN', JSON.parse(entry.input)) as AgentTurnSubmissionInput
  return draft.question.trim() === input.question && draft.budget === input.max_text_calls && (draft.visual_mode ?? 'none') === (input.visual_mode ?? 'none') && draft.focus_timestamp_ms === input.focus_timestamp_ms && (draft.evidence_mode ?? 'none') === (input.evidence_mode ?? 'none')
}
export function agentDraftAfterSubmission(entry: AgentSubmissionEntry, draft: AgentSubmissionDraft): string {
  return agentDraftMatchesSubmission(entry, draft) ? '' : draft.question
}
/** Called only after this exact original POST returned AGENT_SESSION_LIMIT. Purely read-only inspection. */
export async function inspectAgentSubmissionLimit(entry: AgentSubmissionEntry, io: {
  get: () => Promise<unknown>; readSession: () => Promise<unknown>; current: () => boolean
}): Promise<{ kind: 'completed'; result: AgentSubmissionResult } | { kind: 'session-full'; session: AgentSession } | null> {
  if (!parseAgentSubmissionEntry(entry) || entry.kind !== 'TURN') fail()
  let raw: unknown, missing = false
  try { raw = await io.get() }
  catch (error) { if (!io.current()) return null; if (!agentSubmissionBusinessNotFound(error, 'TURN')) throw error; missing = true }
  if (!io.current()) return null
  const owner = await io.readSession()
  if (!io.current()) return null
  const session = parseAgentSession(owner, entry.job_id, entry.video_id, entry.video_sha256, entry.session_id!)
  if (!entry.source || !sameSource(session.source, entry.source)) fail()
  if (!missing) return { kind: 'completed', result: parseAgentSubmissionResponse(raw, entry, session) }
  if (session.turns.length !== 100) fail()
  return io.current() ? { kind: 'session-full', session } : null
}
