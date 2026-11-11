import type { AgentResult } from './videoAgent.ts'
import type { AgentVisualFrame } from './agentVisualEvidence.ts'

export type AgentEvidenceKind = 'SPEECH' | 'FRAME' | 'SAMPLED_CHANGE' | 'INFERENCE' | 'GENERAL' | 'INSUFFICIENT'
export interface AgentEvidenceBlock { block_id: string; kind: AgentEvidenceKind; text: string; utterance_ids: string[]; frame_ids: string[] }
export const evidenceLabels: Record<AgentEvidenceKind, string> = { SPEECH: '原句依据', FRAME: '画面观察', SAMPLED_CHANGE: '前后采样', INFERENCE: '推断', GENERAL: '通用解释', INSUFFICIENT: '证据不足' }
export const evidenceWarnings: Partial<Record<AgentEvidenceKind, string>> = {
  SAMPLED_CHANGE: '（仅比较已取样画面，不证明采样间隙或因果。）', INFERENCE: '（属于推断，不能当作讲者原话或已观察事实。）',
}
export class AgentEvidenceError extends Error { code = 'INVALID_AGENT_RESPONSE'; constructor() { super('INVALID_AGENT_RESPONSE') } }
const row = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function invalid(): never { throw new AgentEvidenceError() }
export function parseAgentEvidenceCapability(raw: unknown): true {
  if (!row(raw) || Object.keys(raw).length !== 1 || raw.structured_evidence_supported !== true) invalid()
  return true
}
/** UTF-16 budget, original whitespace, no C0/C1 controls other than TAB/CR/LF or isolated surrogates. */
export function evidenceTextValid(value: unknown, maximum: number): value is string {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(value)) return false
  for (let i = 0; i < value.length; i++) {
    const unit = value.charCodeAt(i)
    if (unit >= 0xd800 && unit <= 0xdbff) { const next = value.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) return false }
    else if (unit >= 0xdc00 && unit <= 0xdfff) return false
  }
  return true
}
export function assembleAgentEvidenceAnswer(blocks: readonly AgentEvidenceBlock[]): string {
  return blocks.map(block => `【${evidenceLabels[block.kind]} ${block.block_id}】\n${block.text}${evidenceWarnings[block.kind] ? `\n${evidenceWarnings[block.kind]}` : ''}`).join('\n\n')
}
function compareFrameTime(a: AgentVisualFrame, b: AgentVisualFrame): bigint {
  const [an, ad] = a.source_time_base.split('/').map(BigInt) as [bigint, bigint], [bn, bd] = b.source_time_base.split('/').map(BigInt) as [bigint, bigint]
  return BigInt(a.source_pts) * an * bd - BigInt(b.source_pts) * bn * ad
}
/** Called only after the full legacy visual/source/PTS/tool validation, then rechecks the 1.3-specific binding. */
export function parseAgentEvidenceBlocks(raw: unknown, result: AgentResult): AgentEvidenceBlock[] {
  if (!Array.isArray(raw) || raw.length < 1 || raw.length > 8) invalid()
  const frames = new Map((result.visual_evidence ?? []).map(frame => [frame.frame_id, frame])), utteranceOrder = new Map(result.citations.map((citation, index) => [citation.utterance_id, index]))
  const blocks: AgentEvidenceBlock[] = raw.map((value, index) => {
    if (!row(value) || Object.keys(value).length !== 5 || !['block_id', 'kind', 'text', 'utterance_ids', 'frame_ids'].every(key => Object.hasOwn(value, key))
      || value.block_id !== `b${String(index + 1).padStart(2, '0')}` || typeof value.kind !== 'string' || !Object.hasOwn(evidenceLabels, value.kind)
      || !evidenceTextValid(value.text, 1000) || !Array.isArray(value.utterance_ids) || value.utterance_ids.length > 8 || !Array.isArray(value.frame_ids) || value.frame_ids.length > 4) invalid()
    const ids = value.utterance_ids, fids = value.frame_ids, kind = value.kind as AgentEvidenceKind
    let previous = -1
    for (const id of ids) { const order = typeof id === 'string' ? utteranceOrder.get(id) : undefined; if (order === undefined || order <= previous) invalid(); previous = order }
    if (fids.some(id => typeof id !== 'string' || !frames.has(id)) || new Set(fids).size !== fids.length) invalid()
    if (kind !== 'SAMPLED_CHANGE' && fids.some((id, i) => i > 0 && id <= fids[i - 1])) invalid()
    if (kind === 'SPEECH' && (!ids.length || fids.length) || kind === 'FRAME' && (ids.length || !fids.length)
      || kind === 'SAMPLED_CHANGE' && (ids.length || fids.length !== 2) || kind === 'INFERENCE' && !ids.length && !fids.length
      || ['GENERAL', 'INSUFFICIENT'].includes(kind) && (ids.length || fids.length)) invalid()
    if (kind === 'SAMPLED_CHANGE' && (compareFrameTime(frames.get(fids[0])!, frames.get(fids[1])!) >= 0
      || !result.tool_calls.some(call => call.tool_name === 'inspect_clip' && fids.every(id => call.result_frame_ids?.includes(id))))) invalid()
    return Object.freeze({ block_id: value.block_id, kind, text: value.text, utterance_ids: Object.freeze([...ids]), frame_ids: Object.freeze([...fids]) }) as unknown as AgentEvidenceBlock
  })
  if (result.answer_scope === 'VIDEO' ? !blocks.some(block => ['SPEECH', 'FRAME', 'SAMPLED_CHANGE'].includes(block.kind))
    : blocks.some(block => block.kind !== (result.answer_scope === 'GENERAL' ? 'GENERAL' : 'INSUFFICIENT'))) invalid()
  const ids = new Set(blocks.flatMap(block => block.utterance_ids)), fids = [...new Set(blocks.flatMap(block => block.frame_ids))].sort()
  if (ids.size > 24 || fids.length > 12 || ids.size !== result.citations.length || result.citations.some(citation => !ids.has(citation.utterance_id))
    || fids.length !== result.visual_citations?.length || result.visual_citations.some((citation, index) => citation.frame_id !== fids[index]
      || citation.explanation !== `关联分项：${blocks.filter(block => block.frame_ids.includes(citation.frame_id)).map(block => block.block_id).join('、')}`)) invalid()
  const answer = assembleAgentEvidenceAnswer(blocks)
  if (!evidenceTextValid(answer, 6000) || result.answer !== answer) invalid()
  return Object.freeze(blocks) as unknown as AgentEvidenceBlock[]
}
