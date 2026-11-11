import type { AgentToolCall } from './videoAgent.ts'
import type { AgentEvidenceBlock } from './agentEvidenceAnswer.ts'

export type AgentSpeechReviewStatus = 'NOT_REQUESTED' | 'TOPIC_ANCHORS_ONLY' | 'NO_UTTERANCES_RETURNED' | 'SPEECH_NOT_CITED' | 'SPEECH_CITED'
/** A projection of an already validated 1.3 answer. This is not a network parser or semantic verifier. */
export interface AgentEvidenceReviewInput {
  schema_version: string; evidence_mode?: string
  tool_calls: readonly Pick<AgentToolCall, 'tool_name' | 'result_utterance_ids' | 'result_frame_ids' | 'origin'>[]
  citations: readonly { utterance_id: string }[]
  visual_evidence?: readonly { frame_id: string }[]; visual_citations?: readonly { frame_id: string }[]
  answer_blocks?: readonly Pick<AgentEvidenceBlock, 'kind' | 'utterance_ids'>[]
}
export interface AgentEvidenceReview {
  speech_status: AgentSpeechReviewStatus
  search_transcript_calls: number; read_context_calls: number; list_topics_calls: number
  returned_utterance_count: number; search_context_utterance_count: number; topic_anchor_utterance_count: number
  citation_count: number; speech_block_count: number; speech_utterance_count: number; inference_utterance_count: number
  user_focus_tool_calls: number; model_visual_tool_calls: number
  user_focus_frame_count: number; model_frame_count: number; observed_frame_count: number; visual_citation_count: number
  sampled_change_block_count: number; insufficient_block_count: number
}
export const AGENT_EVIDENCE_REVIEW_BOUNDARY = '本摘要仅由已有工具轨迹和引用派生，不新增检索，不判断回答正确性。工具轨迹未记录可区分的执行结果，空返回不能解释为查无匹配。'
const distinct = (ids: readonly string[]) => new Set(ids).size
export function reviewAgentEvidence(input: AgentEvidenceReviewInput | null | undefined): Readonly<AgentEvidenceReview> | null {
  if (!input || input.schema_version !== '1.3' || input.evidence_mode !== 'structured' || !input.answer_blocks || !input.visual_evidence || !input.visual_citations) return null
  const calls = input.tool_calls, blocks = input.answer_blocks
  const search = calls.filter(call => call.tool_name === 'search_transcript'), context = calls.filter(call => call.tool_name === 'read_context')
  const topics = calls.filter(call => call.tool_name === 'list_topics'), lookup = [...search, ...context]
  const speech = blocks.filter(block => block.kind === 'SPEECH'), inference = blocks.filter(block => block.kind === 'INFERENCE')
  const visual = calls.filter(call => call.tool_name === 'inspect_frames' || call.tool_name === 'inspect_clip')
  const focus = visual.filter(call => call.origin === 'USER_FOCUS'), model = visual.filter(call => call.origin !== 'USER_FOCUS')
  const searchContextCount = distinct(lookup.flatMap(call => call.result_utterance_ids))
  const speechStatus: AgentSpeechReviewStatus = speech.length ? 'SPEECH_CITED' : searchContextCount ? 'SPEECH_NOT_CITED'
    : lookup.length ? 'NO_UTTERANCES_RETURNED' : topics.length ? 'TOPIC_ANCHORS_ONLY' : 'NOT_REQUESTED'
  return Object.freeze({ speech_status: speechStatus, search_transcript_calls: search.length, read_context_calls: context.length, list_topics_calls: topics.length,
    returned_utterance_count: distinct(calls.flatMap(call => call.result_utterance_ids)), search_context_utterance_count: searchContextCount,
    topic_anchor_utterance_count: distinct(topics.flatMap(call => call.result_utterance_ids)), citation_count: distinct(input.citations.map(citation => citation.utterance_id)),
    speech_block_count: speech.length, speech_utterance_count: distinct(speech.flatMap(block => block.utterance_ids)), inference_utterance_count: distinct(inference.flatMap(block => block.utterance_ids)),
    user_focus_tool_calls: focus.length, model_visual_tool_calls: model.length, user_focus_frame_count: distinct(focus.flatMap(call => call.result_frame_ids ?? [])),
    model_frame_count: distinct(model.flatMap(call => call.result_frame_ids ?? [])), observed_frame_count: distinct(input.visual_evidence.map(frame => frame.frame_id)),
    visual_citation_count: distinct(input.visual_citations.map(citation => citation.frame_id)), sampled_change_block_count: blocks.filter(block => block.kind === 'SAMPLED_CHANGE').length,
    insufficient_block_count: blocks.filter(block => block.kind === 'INSUFFICIENT').length })
}
export function agentEvidenceReviewMessage(review: AgentEvidenceReview): string {
  switch (review.speech_status) {
    case 'NOT_REQUESTED': return '本轮未请求原句检索或上下文。'
    case 'TOPIC_ANCHORS_ONLY': return `原句相关轨迹仅来自话题工具，未请求原句检索或上下文；返回话题锚点 ${review.topic_anchor_utterance_count} 条。`
    case 'NO_UTTERANCES_RETURNED': return '已有原句检索或上下文工具轨迹，但这些轨迹未记录可用原句；不能据此判断无匹配或全片不存在。'
    case 'SPEECH_NOT_CITED': return '本轮已取得原句，但没有讲者原句分项；这不表示相关问题已回答。'
    case 'SPEECH_CITED': return '本轮含讲者原句分项；可核对原句来源，但不证明文字准确回应问题或构成语义支持。'
  }
}
