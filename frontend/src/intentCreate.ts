import { parseAnswerClipBinding, sameAnswerClipBinding, type AnswerClipBinding } from './answerClip.ts'
import type { IntentSnapshot } from './intentClips.ts'
export interface IntentCreatePayload { instruction: string; max_text_calls: number; source_answer?: { turn_id: string; utterance_ids: string[] } }
export interface IntentCreateReceipt { input: string; key: string; source_answer?: AnswerClipBinding }
export class IntentCreateError extends Error {
  code: 'INVALID_CLIP_REQUEST' | 'CLIP_SUBMISSION_UNKNOWN' | 'CLIP_SUBMISSION_OTHER_PAGE' | 'CLIP_RECEIPT_STORAGE_UNAVAILABLE'
  constructor(code: IntentCreateError['code']) { super(code); this.code = code }
}
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const id = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
export function parseIntentCreatePayload(raw: unknown): IntentCreatePayload | null {
  if (!row(raw) || !Object.hasOwn(raw, 'instruction') || !Object.hasOwn(raw, 'max_text_calls')
    || Object.keys(raw).some(key => !['instruction', 'max_text_calls', 'source_answer'].includes(key))
    || typeof raw.instruction !== 'string' || !raw.instruction.trim() || raw.instruction !== raw.instruction.trim() || raw.instruction.length > 2000
    || !Number.isInteger(raw.max_text_calls) || (raw.max_text_calls as number) < 1 || (raw.max_text_calls as number) > 24) return null
  if (raw.source_answer !== undefined && (!row(raw.source_answer) || Object.keys(raw.source_answer).length !== 2
    || typeof raw.source_answer.turn_id !== 'string' || !uuid.test(raw.source_answer.turn_id)
    || !Array.isArray(raw.source_answer.utterance_ids) || !raw.source_answer.utterance_ids.length || raw.source_answer.utterance_ids.length > 24
    || raw.source_answer.utterance_ids.some(value => typeof value !== 'string' || !id.test(value))
    || new Set(raw.source_answer.utterance_ids).size !== raw.source_answer.utterance_ids.length)) return null
  return { instruction: raw.instruction, max_text_calls: raw.max_text_calls as number,
    ...(raw.source_answer ? { source_answer: { turn_id: raw.source_answer.turn_id as string, utterance_ids: [...raw.source_answer.utterance_ids as string[]] } } : {}) }
}
export function intentCreatePayload(instruction: string, maxCalls: number, binding?: AnswerClipBinding | null): IntentCreatePayload {
  const anchor = binding ? parseAnswerClipBinding(binding) : null
  const payload = parseIntentCreatePayload({ instruction: instruction.trim(), max_text_calls: maxCalls,
    ...(anchor ? { source_answer: { turn_id: anchor.turn_id, utterance_ids: anchor.utterance_ids } } : {}) })
  if (!payload) throw new IntentCreateError('INVALID_CLIP_REQUEST')
  return payload
}
export function parseIntentCreateReceipt(raw: unknown): IntentCreateReceipt | null {
  if (!row(raw) || !Object.hasOwn(raw, 'input') || !Object.hasOwn(raw, 'key') || Object.keys(raw).some(key => !['input', 'key', 'source_answer'].includes(key))
    || typeof raw.key !== 'string' || !uuid.test(raw.key) || typeof raw.input !== 'string' || raw.input.length > 10000) return null
  try {
    const payload = parseIntentCreatePayload(JSON.parse(raw.input))
    if (!payload || !!payload.source_answer !== !!raw.source_answer) return null
    const binding = raw.source_answer ? parseAnswerClipBinding(raw.source_answer) : null
    if (binding && (payload.source_answer!.turn_id !== binding.turn_id || JSON.stringify(payload.source_answer!.utterance_ids) !== JSON.stringify(binding.utterance_ids))) return null
    return { input: raw.input, key: raw.key, ...(binding ? { source_answer: binding } : {}) }
  } catch { return null }
}
export function intentCreateReceipt(previous: IntentCreateReceipt | null, payload: IntentCreatePayload, newKey: () => string, binding: AnswerClipBinding | null = null): IntentCreateReceipt {
  const parsed = parseIntentCreatePayload(payload)
  if (!parsed) throw new IntentCreateError('INVALID_CLIP_REQUEST')
  const input = JSON.stringify(parsed)
  if (previous) {
    const saved = parseIntentCreateReceipt(previous)
    if (!saved || JSON.stringify(parseIntentCreatePayload(JSON.parse(saved.input))) !== input || !sameAnswerClipBinding(saved.source_answer, binding)) throw new IntentCreateError('CLIP_SUBMISSION_UNKNOWN')
    return saved
  }
  const receipt = parseIntentCreateReceipt({ input, key: newKey(), ...(binding ? { source_answer: binding } : {}) })
  if (!receipt) throw new IntentCreateError('INVALID_CLIP_REQUEST')
  return receipt
}
export function intentCreateResponseMatches(value: IntentSnapshot, payload: IntentCreatePayload, binding: AnswerClipBinding | null): boolean {
  return value.instruction === payload.instruction && value.budget.max_text_calls === payload.max_text_calls
    && sameAnswerClipBinding(value.source_answer, binding)
}
/** Existing frozen snapshots may fail ANSWER_SOURCE_INVALID after creation: keep that UNKNOWN receipt. */
export function intentCreateRejectedBeforeInsert(code: unknown): boolean {
  return typeof code === 'string' && ['INVALID_CLIP_REQUEST', 'INVALID_ANSWER_SOURCE', 'ANSWER_SOURCE_UNAVAILABLE'].includes(code)
}
/** Call after all asynchronous preparation, immediately before reserving/writing a key. */
export function prepareIntentCreateReceipt(readCurrent: () => string | null, original: IntentCreateReceipt | null,
  payload: IntentCreatePayload, newKey: () => string, binding: AnswerClipBinding | null = null): IntentCreateReceipt {
  let raw: string | null
  try { raw = readCurrent() } catch { throw new IntentCreateError('CLIP_RECEIPT_STORAGE_UNAVAILABLE') }
  let current: IntentCreateReceipt | null = null
  try { current = raw ? parseIntentCreateReceipt(JSON.parse(raw)) : null } catch { /* Invalid old storage is never replayed. */ }
  if (current && (!original || current.key !== original.key || current.input !== original.input || !sameAnswerClipBinding(current.source_answer, original.source_answer)))
    throw new IntentCreateError('CLIP_SUBMISSION_OTHER_PAGE')
  return intentCreateReceipt(original, payload, newKey, binding)
}
