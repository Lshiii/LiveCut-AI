import type { AgentCitation, AgentToolCall } from './videoAgent.ts'
import { evidenceTextValid } from './agentEvidenceAnswer.ts'

export const SPEECH_LOOKUP_POLICY = 'speech_lookup_v1' as const
export interface AgentSpeechLookup {
  tool_step: number; strategy: 'FOCUS_CONTEXT' | 'QUESTION_SEARCH'; status: 'RETURNED' | 'EMPTY_RETURN' | 'CONTEXT_LIMIT'
  query_truncated: boolean; anchor_distance_ms: number | null; candidate_utterance_count: number; returned_utterance_ids: string[]
}
export class AgentSpeechLookupError extends Error { code = 'INVALID_AGENT_RESPONSE'; constructor() { super('INVALID_AGENT_RESPONSE') } }
const row = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const exact = (value: unknown, fields: string[]): value is Record<string, unknown> => row(value) && Object.keys(value).length === fields.length && fields.every(key => Object.hasOwn(value, key))
const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number => Number.isSafeInteger(value) && (value as number) >= min && (value as number) <= max
const same = (left: unknown, right: unknown) => JSON.stringify(left) === JSON.stringify(right)
function invalid(): never { throw new AgentSpeechLookupError() }
export function parseAgentSpeechLookupCapability(raw: unknown): true {
  if (!exact(raw, ['speech_lookup_supported', 'worker_protocol']) || raw.speech_lookup_supported !== true || raw.worker_protocol !== 2) invalid()
  return true
}
export function speechLookupQuery(question: string): { query: string; truncated: boolean } {
  if (!evidenceTextValid(question, 2000)) invalid()
  const trimmed = question.trim(); let query = trimmed.slice(0, 300)
  const last = query.charCodeAt(query.length - 1)
  if (last >= 0xd800 && last <= 0xdbff) query = query.slice(0, -1)
  return { query: query.trimEnd(), truncated: trimmed.length > 300 }
}
export function speechAnchorDistance(focus: number, sentence: Pick<AgentCitation, 'start_ms' | 'end_ms'>): number {
  return focus < sentence.start_ms ? sentence.start_ms - focus : focus >= sentence.end_ms ? focus - sentence.end_ms + 1 : 0
}
/** Only checks the public program record against its exact trace; it cannot recompute full-ASR nearest/ranking. */
export function parseAgentSpeechLookup(raw: unknown, owner: { question: string; focus_timestamp_ms?: number }, calls: readonly AgentToolCall[], citations: readonly AgentCitation[]): AgentSpeechLookup {
  if (!evidenceTextValid(owner.question, 2000)) invalid()
  if (!exact(raw, ['tool_step', 'strategy', 'status', 'query_truncated', 'anchor_distance_ms', 'candidate_utterance_count', 'returned_utterance_ids'])
    || !['FOCUS_CONTEXT', 'QUESTION_SEARCH'].includes(String(raw.strategy)) || !['RETURNED', 'EMPTY_RETURN', 'CONTEXT_LIMIT'].includes(String(raw.status))
    || typeof raw.query_truncated !== 'boolean' || !(raw.anchor_distance_ms === null || integer(raw.anchor_distance_ms))
    || !integer(raw.candidate_utterance_count, 0, 4) || !Array.isArray(raw.returned_utterance_ids) || raw.returned_utterance_ids.length > 4
    || raw.returned_utterance_ids.some(id => typeof id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(id))
    || new Set(raw.returned_utterance_ids).size !== raw.returned_utterance_ids.length) invalid()
  const focus = owner.focus_timestamp_ms, policyIndex = focus === undefined ? 0 : 1, policy = calls[policyIndex]
  if (!policy || raw.tool_step !== policyIndex + 1 || calls.some((call, index) => call.origin !== (focus !== undefined && index === 0 ? 'USER_FOCUS' : index === policyIndex ? 'EVIDENCE_POLICY' : 'MODEL'))
    || calls.filter(call => call.origin === 'EVIDENCE_POLICY').length !== 1 || policy.result_frame_ids?.length || !same(raw.returned_utterance_ids, policy.result_utterance_ids)) invalid()
  const returned = raw.returned_utterance_ids.length, candidates = raw.candidate_utterance_count
  if (candidates < returned || raw.status !== (candidates > returned ? 'CONTEXT_LIMIT' : returned ? 'RETURNED' : 'EMPTY_RETURN')) invalid()
  if (raw.strategy === 'FOCUS_CONTEXT') {
    if (focus === undefined || !integer(raw.anchor_distance_ms, 0, 15000) || raw.query_truncated || candidates < 1 || candidates > 3 || returned < 1
      || policy.tool_name !== 'read_context' || !exact(policy.arguments, ['utterance_id', 'before', 'after']) || policy.arguments.before !== 1 || policy.arguments.after !== 1) invalid()
    const anchor = citations.find(citation => citation.utterance_id === policy.arguments.utterance_id)
    if (anchor && speechAnchorDistance(focus, anchor) !== raw.anchor_distance_ms) invalid()
  } else {
    const expected = speechLookupQuery(owner.question)
    if (policy.tool_name !== 'search_transcript' || !exact(policy.arguments, ['query', 'top_k']) || policy.arguments.top_k !== 4
      || policy.arguments.query !== expected.query || raw.query_truncated !== expected.truncated
      || (focus === undefined ? raw.anchor_distance_ms !== null : raw.anchor_distance_ms !== null && raw.anchor_distance_ms <= 15000)
      || focus !== undefined && candidates > 0 && raw.anchor_distance_ms === null) invalid()
  }
  // Where public citations expose returned sentence times, they must preserve the same ASR order.
  let previous = -1
  for (const id of raw.returned_utterance_ids) { const citation = citations.find(value => value.utterance_id === id); if (citation) { if (citation.start_ms < previous) invalid(); previous = citation.end_ms } }
  return Object.freeze({ tool_step: raw.tool_step as number, strategy: raw.strategy as AgentSpeechLookup['strategy'], status: raw.status as AgentSpeechLookup['status'],
    query_truncated: raw.query_truncated, anchor_distance_ms: raw.anchor_distance_ms, candidate_utterance_count: candidates,
    returned_utterance_ids: Object.freeze([...raw.returned_utterance_ids]) as unknown as string[] })
}
export function speechLookupMessage(receipt: AgentSpeechLookup): string {
  if (receipt.status === 'EMPTY_RETURN') return '这一次有限预查没有返回原句，不表示全片没有相关讲解。'
  if (receipt.status === 'CONTEXT_LIMIT') return `程序预查有 ${receipt.candidate_utterance_count} 条候选，实际向首个模型输入提供 ${receipt.returned_utterance_ids.length} 条完整原句；超出上下文容量的原句未入模。`
  return `程序预查已向首个模型输入提供 ${receipt.returned_utterance_ids.length} 条完整原句。`
}
export function agentToolOriginLabel(origin: AgentToolCall['origin']): string {
  return origin === 'USER_FOCUS' ? '用户位置触发' : origin === 'EVIDENCE_POLICY' ? '程序按预查策略读取' : '模型选择'
}
