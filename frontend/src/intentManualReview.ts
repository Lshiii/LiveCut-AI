import { sha256 } from 'hash-wasm'
import type { IntentOutput, IntentPlan, IntentSnapshot } from './intentClips.ts'

export interface IntentManualReviewOutput {
  output_id: string; goal_id: string; output_hash: string; note: string; accept_sentence_boundaries: true
}
export interface IntentManualReview {
  schema_version: '1.0'; id: string; request_id: string; job_id: string; video_id: string
  source_hash: string; transcript_hash: string; duration_ms: number; automatic_plan_hash: string
  base_revision: number; base_plan_hash: string; revision: number; plan_hash: string
  outputs: IntentManualReviewOutput[]; created_at: string; review_hash: string
}
export interface IntentManualReviewInput {
  base_revision: number; plan_hash: string
  outputs: { output_id: string; note: string; accept_sentence_boundaries: true }[]
}
export interface IntentManualReviewReceipt {
  schema_version: 'intent-manual-review-receipt-v1'; key: string; input: string
  request_id: string; job_id: string; video_id: string; source: IntentSnapshot['source']
}
export interface ManualReviewReceiptStore { read: () => string | null; write: (value: string) => void; remove: () => void }
export class IntentManualReviewError extends Error {
  code: string
  constructor(code = 'INVALID_CLIP_MANUAL_REVIEW_RESPONSE') { super(code); this.code = code }
}
type Row = Record<string, unknown>
type Owner = Pick<IntentSnapshot, 'id' | 'job_id' | 'video_id' | 'source'>
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
const HASH = /^[a-f0-9]{64}$/, ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const row = (raw: unknown): raw is Row => raw !== null && typeof raw === 'object' && !Array.isArray(raw)
const exact = (raw: unknown, fields: string[]): raw is Row => row(raw) && Object.keys(raw).length === fields.length && fields.every(key => Object.hasOwn(raw, key))
const integer = (raw: unknown, minimum = 0): raw is number => Number.isSafeInteger(raw) && (raw as number) >= minimum
const digest = (raw: unknown): raw is string => typeof raw === 'string' && HASH.test(raw)
const identifier = (raw: unknown): raw is string => typeof raw === 'string' && ID.test(raw)
const uuid = (raw: unknown): raw is string => typeof raw === 'string' && UUID.test(raw)
function invalid(code?: string): never { throw new IntentManualReviewError(code) }
/** Match server UTF-16 limits and reject text that cannot survive JSON/database storage. */
export function validManualReviewNote(raw: unknown): raw is string {
  if (typeof raw !== 'string' || !raw.trim() || raw.length > 1000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(raw)) return false
  for (let i = 0; i < raw.length; i++) {
    const code = raw.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff) { const next = raw.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) return false }
    else if (code >= 0xdc00 && code <= 0xdfff) return false
  }
  return true
}
function utc(raw: unknown): raw is string {
  if (typeof raw !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(raw)) return false
  const milliseconds = raw.replace(/\.(\d{1,6})Z$/, (_, part: string) => `.${part.padEnd(3, '0').slice(0, 3)}Z`)
  return Number.isFinite(Date.parse(milliseconds)) && new Date(milliseconds).toISOString().slice(0, 19) === raw.slice(0, 19)
}
export function manualReviewCanonical(raw: unknown): string {
  const sorted = (value: unknown): unknown => Array.isArray(value) ? value.map(sorted)
    : row(value) ? Object.fromEntries(Object.keys(value).sort().map(key => [key, sorted(value[key])])) : value
  return JSON.stringify(sorted(raw))
}
export function manualReviewEligible(output: IntentOutput): boolean {
  return output.status === 'NEEDS_REVIEW' && output.enabled === false && output.parts.length > 0 && output.evidence_utterance_ids.length > 0
}
/** Structure and frozen ownership are synchronous; hashes are checked before granting any UI permission. */
export function parseIntentManualReview(raw: unknown, owner: Owner, current?: { revision: number; plan_hash: string | null; plan: IntentPlan | null }): IntentManualReview {
  if (!exact(raw, ['schema_version', 'id', 'request_id', 'job_id', 'video_id', 'source_hash', 'transcript_hash', 'duration_ms', 'automatic_plan_hash',
    'base_revision', 'base_plan_hash', 'revision', 'plan_hash', 'outputs', 'created_at', 'review_hash'])
    || raw.schema_version !== '1.0' || !uuid(raw.id) || !uuid(raw.request_id) || !uuid(raw.job_id) || !uuid(raw.video_id) || raw.request_id !== owner.id || raw.job_id !== owner.job_id || raw.video_id !== owner.video_id
    || raw.source_hash !== owner.source.video_sha256 || raw.transcript_hash !== owner.source.transcript_hash || raw.duration_ms !== owner.source.duration_ms
    || !digest(raw.automatic_plan_hash) || !integer(raw.base_revision) || !integer(raw.revision, 1) || raw.revision !== raw.base_revision + 1
    || !digest(raw.plan_hash) || raw.base_plan_hash !== raw.plan_hash || !digest(raw.review_hash) || !utc(raw.created_at)
    || !Array.isArray(raw.outputs) || raw.outputs.length > 6) invalid()
  const ids = new Set<string>(); let previous = -1
  for (const output of raw.outputs) {
    if (!exact(output, ['output_id', 'goal_id', 'output_hash', 'note', 'accept_sentence_boundaries'])
      || !identifier(output.output_id) || ids.has(output.output_id) || !identifier(output.goal_id) || !digest(output.output_hash)
      || !validManualReviewNote(output.note) || output.accept_sentence_boundaries !== true) invalid()
    ids.add(output.output_id)
    if (current) {
      const index = current.plan?.outputs.findIndex(value => value.output_id === output.output_id) ?? -1
      const target = current.plan?.outputs[index]
      if (index <= previous || !target || target.goal_id !== output.goal_id || !manualReviewEligible(target)) invalid()
      previous = index
    }
  }
  if (current && (!current.plan || current.revision !== raw.revision || current.plan_hash !== raw.plan_hash)) invalid()
  return raw as unknown as IntentManualReview
}
// A proof can be re-parsed or wrapped by Vue without losing verification. Exact content must still match.
const verified = new Map<string, { review: string; plan: string }>()
export async function verifyIntentManualReviewSnapshot<T extends IntentSnapshot>(snapshot: T): Promise<T> {
  if (!snapshot.manual_review) return snapshot
  const proof = parseIntentManualReview(snapshot.manual_review, snapshot, snapshot)
  const { review_hash: claimed, ...content } = proof
  const reviewJSON = manualReviewCanonical(content), proofJSON = manualReviewCanonical(proof), planJSON = manualReviewCanonical(snapshot.plan)
  const outputJSON = proof.outputs.map(item => ({ hash: item.output_hash,
    json: manualReviewCanonical(snapshot.plan!.outputs.find(value => value.output_id === item.output_id)) }))
  if (await sha256(reviewJSON) !== claimed || await sha256(planJSON) !== snapshot.plan_hash) invalid()
  for (const item of outputJSON) if (await sha256(item.json) !== item.hash) invalid()
  // Never certify an object that changed while a digest was in flight.
  if (manualReviewCanonical(snapshot.plan) !== planJSON || manualReviewCanonical(snapshot.manual_review) !== proofJSON) invalid()
  verified.set(claimed, { review: reviewJSON, plan: planJSON })
  if (verified.size > 256) verified.delete(verified.keys().next().value!)
  return snapshot
}
export function manualReviewVerified(snapshot: IntentSnapshot): boolean {
  if (!snapshot.manual_review) return false
  try {
    const proof = parseIntentManualReview(snapshot.manual_review, snapshot, snapshot), { review_hash, ...content } = proof
    const known = verified.get(review_hash)
    return !!known && known.review === manualReviewCanonical(content) && known.plan === manualReviewCanonical(snapshot.plan)
  } catch { return false }
}
export function manualReviewSelectedIds(snapshot: IntentSnapshot | null, dirty = false): string[] {
  return snapshot && !dirty && manualReviewVerified(snapshot) ? snapshot.manual_review!.outputs.map(value => value.output_id) : []
}
export function effectiveIntentSelection(snapshot: IntentSnapshot, outputs: IntentOutput[], dirty = false): IntentOutput[] {
  const manual = new Set(manualReviewSelectedIds(snapshot, dirty || manualReviewCanonical(outputs) !== manualReviewCanonical(snapshot.plan?.outputs)))
  return outputs.filter(output => output.status === 'FOUND' && output.enabled || manual.has(output.output_id))
}
export function manualReviewConfirmation(snapshot: IntentSnapshot): { manual_review_hash?: string } {
  if (!snapshot.manual_review) return {}
  if (!manualReviewVerified(snapshot)) invalid()
  return { manual_review_hash: snapshot.manual_review.review_hash }
}
/** A fresh read must still describe exactly the manual version the user reviewed. */
export function manualReviewFreshConfirmation(expected: IntentSnapshot, fresh: IntentSnapshot): { manual_review_hash?: string } {
  if (fresh.id !== expected.id || fresh.job_id !== expected.job_id || fresh.video_id !== expected.video_id
    || manualReviewCanonical(fresh.source) !== manualReviewCanonical(expected.source) || fresh.revision !== expected.revision
    || fresh.plan_hash !== expected.plan_hash || (fresh.manual_review?.review_hash ?? null) !== (expected.manual_review?.review_hash ?? null)) invalid('CLIP_MANUAL_REVIEW_MISMATCH')
  return manualReviewConfirmation(fresh)
}
export function parseIntentManualReviewInput(raw: unknown): IntentManualReviewInput | null {
  if (!exact(raw, ['base_revision', 'plan_hash', 'outputs']) || !integer(raw.base_revision) || !digest(raw.plan_hash) || !Array.isArray(raw.outputs) || raw.outputs.length > 6) return null
  const seen = new Set<string>()
  for (const item of raw.outputs) {
    if (!exact(item, ['output_id', 'note', 'accept_sentence_boundaries']) || !identifier(item.output_id) || seen.has(item.output_id)
      || !validManualReviewNote(item.note) || item.accept_sentence_boundaries !== true) return null
    seen.add(item.output_id)
  }
  return raw as unknown as IntentManualReviewInput
}
export function manualReviewInput(snapshot: IntentSnapshot, items: IntentManualReviewInput['outputs']): IntentManualReviewInput {
  if (!Object.hasOwn(snapshot, 'manual_review')) invalid('CLIP_MANUAL_REVIEW_UNAVAILABLE')
  const body = parseIntentManualReviewInput({ base_revision: snapshot.revision, plan_hash: snapshot.plan_hash, outputs: items })
  if (!body || snapshot.status !== 'PLAN_READY' || !snapshot.plan) invalid('INVALID_CLIP_MANUAL_REVIEW')
  let previous = -1
  for (const item of body.outputs) {
    const index = snapshot.plan.outputs.findIndex(output => output.output_id === item.output_id)
    if (index < 0 || index <= previous || !manualReviewEligible(snapshot.plan.outputs[index]!)) invalid('INVALID_CLIP_MANUAL_REVIEW')
    previous = index
  }
  return body
}
export function parseIntentManualReviewReceipt(raw: unknown): IntentManualReviewReceipt | null {
  if (!exact(raw, ['schema_version', 'key', 'input', 'request_id', 'job_id', 'video_id', 'source'])
    || raw.schema_version !== 'intent-manual-review-receipt-v1' || !uuid(raw.key) || !uuid(raw.request_id) || !uuid(raw.job_id) || !uuid(raw.video_id)
    || typeof raw.input !== 'string' || raw.input.length > 16000 || !exact(raw.source, ['video_sha256', 'transcript_hash', 'duration_ms'])
    || !digest(raw.source.video_sha256) || !digest(raw.source.transcript_hash) || !integer(raw.source.duration_ms, 1)) return null
  try { if (!parseIntentManualReviewInput(JSON.parse(raw.input))) return null } catch { return null }
  return raw as unknown as IntentManualReviewReceipt
}
export function sameManualReviewReceipt(a: IntentManualReviewReceipt | null, b: IntentManualReviewReceipt | null): boolean {
  return !a && !b || !!a && !!b && a.key === b.key && a.input === b.input && a.request_id === b.request_id && a.job_id === b.job_id
    && a.video_id === b.video_id && manualReviewCanonical(a.source) === manualReviewCanonical(b.source)
}
/** Run immediately before writing a receipt; async fresh reads must not overwrite another page's UNKNOWN. */
export function prepareManualReviewReceipt(read: () => string | null, original: IntentManualReviewReceipt | null,
  snapshot: IntentSnapshot, input: IntentManualReviewInput, newKey: () => string): IntentManualReviewReceipt {
  let stored: string | null
  try { stored = read() } catch { invalid('CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE') }
  let current: IntentManualReviewReceipt | null = null
  try { current = stored ? parseIntentManualReviewReceipt(JSON.parse(stored)) : null } catch { /* Untrusted data is never replayed. */ }
  if (stored && !current || current && !sameManualReviewReceipt(current, original)) invalid('CLIP_MANUAL_REVIEW_OTHER_PAGE')
  if (original) {
    if (!parseIntentManualReviewReceipt(original) || original.request_id !== snapshot.id || original.job_id !== snapshot.job_id || original.video_id !== snapshot.video_id
      || manualReviewCanonical(original.source) !== manualReviewCanonical(snapshot.source) || original.input !== JSON.stringify(input)) invalid('CLIP_MANUAL_REVIEW_UNKNOWN')
    return original
  }
  const result = parseIntentManualReviewReceipt({ schema_version: 'intent-manual-review-receipt-v1', key: newKey(), input: JSON.stringify(input),
    request_id: snapshot.id, job_id: snapshot.job_id, video_id: snapshot.video_id, source: { ...snapshot.source } })
  if (!result) invalid('INVALID_CLIP_MANUAL_REVIEW')
  return result
}
export function manualReviewReceiptMatchesOwner(receipt: IntentManualReviewReceipt, owner: Owner): boolean {
  return receipt.request_id === owner.id && receipt.job_id === owner.job_id && receipt.video_id === owner.video_id && manualReviewCanonical(receipt.source) === manualReviewCanonical(owner.source)
}
export function assertStoredManualReviewReceipt(store: ManualReviewReceiptStore, expected: IntentManualReviewReceipt): IntentManualReviewReceipt | null {
  let raw: string | null
  try { raw = store.read() } catch { invalid('CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE') }
  let stored: IntentManualReviewReceipt | null = null
  try { stored = raw ? parseIntentManualReviewReceipt(JSON.parse(raw)) : null } catch { /* Never overwrite an unrecognized receipt. */ }
  if (raw && !stored || stored && !sameManualReviewReceipt(stored, expected)) invalid('CLIP_MANUAL_REVIEW_OTHER_PAGE')
  return stored
}
export function persistManualReviewReceipt(store: ManualReviewReceiptStore, snapshot: IntentSnapshot, input: IntentManualReviewInput, newKey: () => string): IntentManualReviewReceipt {
  const receipt = prepareManualReviewReceipt(store.read, null, snapshot, input, newKey)
  try { store.write(JSON.stringify(receipt)) } catch { invalid('CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE') }
  return receipt
}
export function removeManualReviewReceipt(store: ManualReviewReceiptStore, expected: IntentManualReviewReceipt): void {
  // There is no await between comparison and removal. A different page's receipt is never removed.
  if (!assertStoredManualReviewReceipt(store, expected)) return
  try { store.remove() } catch { invalid('CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE') }
}
export function restoreManualReviewReceipt(store: ManualReviewReceiptStore, expected: IntentManualReviewReceipt, mounted: IntentManualReviewReceipt | null): IntentManualReviewReceipt {
  if (!parseIntentManualReviewReceipt(expected)) invalid()
  if (mounted && !sameManualReviewReceipt(mounted, expected)) invalid('CLIP_MANUAL_REVIEW_OTHER_PAGE')
  assertStoredManualReviewReceipt(store, expected)
  try { store.write(JSON.stringify(expected)) } catch { invalid('CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE') }
  return expected
}
export async function verifyIntentManualReviewReceipt(raw: unknown, receipt: IntentManualReviewReceipt): Promise<IntentManualReview> {
  const expected = parseIntentManualReviewReceipt(receipt)
  if (!expected) invalid()
  const proof = parseIntentManualReview(raw, { id: expected.request_id, job_id: expected.job_id, video_id: expected.video_id, source: expected.source })
  const input = parseIntentManualReviewInput(JSON.parse(expected.input))!
  if (proof.id !== expected.key || proof.base_revision !== input.base_revision || proof.base_plan_hash !== input.plan_hash
    || manualReviewCanonical(proof.outputs.map(item => ({ output_id: item.output_id, note: item.note, accept_sentence_boundaries: item.accept_sentence_boundaries }))) !== manualReviewCanonical(input.outputs)) invalid()
  const { review_hash, ...content } = proof
  if (await sha256(manualReviewCanonical(content)) !== review_hash) invalid()
  return proof
}

/** GET a known immutable ID first. Only the exact business 404 permits an identical replay. */
export async function recoverManualReviewReceipt(receipt: IntentManualReviewReceipt, io: {
  get: () => Promise<unknown>; post: () => Promise<unknown>; current: () => boolean
}): Promise<IntentManualReview | null> {
  let raw: unknown
  try { raw = await io.get() }
  catch (error) {
    if (!io.current()) return null
    if (!manualReviewBusinessNotFound(error)) throw error
    raw = await io.post()
  }
  if (!io.current()) return null
  const proof = await verifyIntentManualReviewReceipt(raw, receipt)
  return io.current() ? proof : null
}
export function manualReviewBusinessNotFound(error: unknown): boolean {
  return row(error) && error.code === 'CLIP_MANUAL_REVIEW_NOT_FOUND' && error.status === 404
}
/** This is only a terminal-conflict inspection. It never removes a receipt or creates a new key. */
export async function inspectManualReviewRevisionConflict(receipt: IntentManualReviewReceipt, io: {
  get: () => Promise<unknown>; head: () => Promise<IntentSnapshot | null>; current: () => boolean
}): Promise<{ kind: 'completed'; proof: IntentManualReview } | { kind: 'superseded'; snapshot: IntentSnapshot } | null> {
  try {
    const raw = await io.get()
    if (!io.current()) return null
    const proof = await verifyIntentManualReviewReceipt(raw, receipt)
    return io.current() ? { kind: 'completed', proof } : null
  } catch (error) {
    if (!io.current()) return null
    if (!manualReviewBusinessNotFound(error)) throw error
  }
  const head = await io.head()
  if (!head || !io.current()) return null
  const input = parseIntentManualReviewInput(JSON.parse(receipt.input))
  if (!input || !manualReviewReceiptMatchesOwner(receipt, head) || !Object.hasOwn(head, 'manual_review') || !head.plan
    || head.status !== 'PLAN_READY' || head.revision <= input.base_revision || !digest(head.plan_hash)) invalid()
  await verifyIntentManualReviewSnapshot(head)
  const planJSON = manualReviewCanonical(head.plan)
  if (await sha256(planJSON) !== head.plan_hash || manualReviewCanonical(head.plan) !== planJSON) invalid()
  if (!manualReviewReceiptMatchesOwner(receipt, head) || head.revision <= input.base_revision) invalid()
  return io.current() ? { kind: 'superseded', snapshot: head } : null
}
