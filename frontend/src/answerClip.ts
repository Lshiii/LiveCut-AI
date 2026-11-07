import { sha256 } from 'hash-wasm'
import { parseAgentTurn, type AgentCitation, type AgentSessionIdentity, type AgentTurn, type AgentSource } from './videoAgent.ts'
import { parseExecutorSnapshot } from './executors.ts'

export interface AnswerClipBinding {
  turn_id: string; session_id: string; answer_hash: string; job_id: string; video_id: string
  source_hash: string; transcript_hash: string; duration_ms: number; utterance_ids: string[]
}
export interface AnswerClipSource extends AnswerClipBinding {
  schema_version: '1.0'; question: string; answer: string; citations: AgentCitation[]; context_utterance_ids: string[]
}
export interface AnswerClipSelection { instruction: string; binding: AnswerClipBinding; question: string; citations: AgentCitation[] }
export class AnswerClipError extends Error { constructor() { super('INVALID_ANSWER_CLIP_SOURCE') } }
/** Protocol support is a declared queue, independent of an online worker or available slot. */
export function supportsAnswerClipProtocol(raw: unknown): boolean { return parseExecutorSnapshot(raw).stages.some(stage => stage.stage_type === 'ANCHOR_CLIP') }
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i, HASH = /^[a-f0-9]{64}$/, ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const fields = (value: Record<string, unknown>, names: string[]) => Object.keys(value).length === names.length && names.every(key => Object.hasOwn(value, key))
const text = (value: unknown, max: number): value is string => typeof value === 'string' && !!value.trim() && value.length <= max
const ids = (value: unknown, max: number): value is string[] => Array.isArray(value) && value.length > 0 && value.length <= max
  && value.every(id => typeof id === 'string' && ID.test(id)) && new Set(value).size === value.length
const bindingFields = ['turn_id', 'session_id', 'answer_hash', 'job_id', 'video_id', 'source_hash', 'transcript_hash', 'duration_ms', 'utterance_ids']
function invalid(): never { throw new AnswerClipError() }
export function parseAnswerClipBinding(raw: unknown): AnswerClipBinding {
  if (!row(raw) || !fields(raw, bindingFields) || !['turn_id', 'session_id', 'job_id', 'video_id'].every(key => typeof raw[key] === 'string' && UUID.test(raw[key] as string))
    || !['answer_hash', 'source_hash', 'transcript_hash'].every(key => typeof raw[key] === 'string' && HASH.test(raw[key] as string))
    || !Number.isSafeInteger(raw.duration_ms) || (raw.duration_ms as number) <= 0 || !ids(raw.utterance_ids, 24)) invalid()
  return { turn_id: raw.turn_id as string, session_id: raw.session_id as string, answer_hash: raw.answer_hash as string,
    job_id: raw.job_id as string, video_id: raw.video_id as string, source_hash: raw.source_hash as string,
    transcript_hash: raw.transcript_hash as string, duration_ms: raw.duration_ms as number, utterance_ids: [...raw.utterance_ids] }
}
export function answerClipBinding(source: AnswerClipBinding): AnswerClipBinding {
  return parseAnswerClipBinding(Object.fromEntries(bindingFields.map(key => [key, source[key as keyof AnswerClipBinding]])))
}
export function sameAnswerClipBinding(a: AnswerClipBinding | null | undefined, b: AnswerClipBinding | null | undefined): boolean {
  return !a && !b || !!a && !!b && JSON.stringify(answerClipBinding(a)) === JSON.stringify(answerClipBinding(b))
}
export function sameAnswerClipSource(a: AnswerClipSource | null | undefined, b: AnswerClipSource | null | undefined): boolean {
  return sameAnswerClipBinding(a, b) && (!a && !b || !!a && !!b && a.question === b.question && a.answer === b.answer
    && JSON.stringify(a.citations.map(citation => [citation.utterance_id, citation.start_ms, citation.end_ms, citation.text])) === JSON.stringify(b.citations.map(citation => [citation.utterance_id, citation.start_ms, citation.end_ms, citation.text]))
    && JSON.stringify(a.context_utterance_ids) === JSON.stringify(b.context_utterance_ids))
}
export function parseAnswerClipSource(raw: unknown, jobId: string, videoId: string, source: AgentSource): AnswerClipSource {
  if (!row(raw) || !fields(raw, [...bindingFields, 'schema_version', 'question', 'answer', 'citations', 'context_utterance_ids'])) invalid()
  const binding = parseAnswerClipBinding(Object.fromEntries(bindingFields.map(key => [key, raw[key]])))
  if (raw.schema_version !== '1.0' || binding.job_id !== jobId || binding.video_id !== videoId || binding.source_hash !== source.video_sha256
    || binding.transcript_hash !== source.transcript_hash || binding.duration_ms !== source.duration_ms || !text(raw.question, 2000) || !text(raw.answer, 6000)
    || !Array.isArray(raw.citations) || raw.citations.length !== binding.utterance_ids.length || !ids(raw.context_utterance_ids, 72)
    || binding.utterance_ids.some(id => !(raw.context_utterance_ids as string[]).includes(id))) invalid()
  let end = 0, units = 0
  const citations = raw.citations.map((value, index) => {
    if (!row(value) || !fields(value, ['utterance_id', 'start_ms', 'end_ms', 'text']) || value.utterance_id !== binding.utterance_ids[index]
      || !Number.isSafeInteger(value.start_ms) || (value.start_ms as number) < end || !Number.isSafeInteger(value.end_ms)
      || (value.end_ms as number) <= (value.start_ms as number) || (value.end_ms as number) > binding.duration_ms || !text(value.text, 12000)) invalid()
    end = value.end_ms as number; units += value.text.length
    return { utterance_id: value.utterance_id as string, start_ms: value.start_ms as number, end_ms: value.end_ms as number, text: value.text }
  })
  if (units > 12000) invalid()
  return { schema_version: '1.0', ...binding, question: raw.question, answer: raw.answer, citations, context_utterance_ids: [...raw.context_utterance_ids] }
}
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  return row(value) ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value
}
/** Hash the verified answer, retaining source order; no network or model work occurs here. */
export async function selectAnswerClip(session: AgentSessionIdentity, raw: AgentTurn, selectedIds: readonly string[]): Promise<AnswerClipSelection> {
  const turn = parseAgentTurn(raw, session, raw.id)
  if (turn.status !== 'SUCCEEDED' || turn.result?.answer_scope !== 'VIDEO' || !ids(selectedIds, 24)) invalid()
  const citations = turn.result.citations.filter(citation => selectedIds.includes(citation.utterance_id)).map(citation => ({ ...citation }))
  if (citations.length !== selectedIds.length || citations.reduce((sum, citation) => sum + citation.text.length, 0) > 12000) invalid()
  return { instruction: '将所选原句共同剪成一个片段，保留完整解释；允许扩展前后文，必须完整保留每条所选原句，跳过关注引导。', question: turn.question, citations,
    binding: { turn_id: turn.id, session_id: session.id, answer_hash: await sha256(JSON.stringify(canonical(turn.result))), job_id: session.job_id,
      video_id: session.video_id, source_hash: session.source.video_sha256, transcript_hash: session.source.transcript_hash,
      duration_ms: session.source.duration_ms, utterance_ids: citations.map(citation => citation.utterance_id) } }
}
/** Fresh recovery checks the original VIDEO turn, not a quoted answer supplied by a draft file. */
export async function verifyAnswerClipBinding(binding: AnswerClipBinding, rawTurn: unknown): Promise<AnswerClipSelection> {
  const expected = parseAnswerClipBinding(binding)
  const session = { id: expected.session_id, job_id: expected.job_id, video_id: expected.video_id,
    source: { video_sha256: expected.source_hash, transcript_hash: expected.transcript_hash, duration_ms: expected.duration_ms } }
  const turn = parseAgentTurn(rawTurn, session, expected.turn_id)
  const selection = await selectAnswerClip(session, turn, expected.utterance_ids)
  if (!sameAnswerClipBinding(selection.binding, expected)) invalid()
  return selection
}
