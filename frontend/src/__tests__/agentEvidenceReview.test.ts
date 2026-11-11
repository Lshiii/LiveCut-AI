import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { h } from 'vue'
import * as review from '../agentEvidenceReview.ts'
import { reviewAgentEvidence, agentEvidenceReviewMessage, AGENT_EVIDENCE_REVIEW_BOUNDARY } from '../agentEvidenceReview.ts'
import * as evidence from '../agentEvidenceAnswer.ts'
import * as visual from '../agentVisualEvidence.ts'
import * as topics from '../topics.ts'
import { parseAgentTurn, type AgentSession, type AgentTurn } from '../videoAgent.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'

const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/agent-evidence-review.synthetic.json', import.meta.url), 'utf8'))
function verifiedCase(sample: any) {
  const result = structuredClone(sample.result), focused = Object.hasOwn(result, 'focus_timestamp_ms')
  const session: AgentSession = { id: result.session_id, job_id: result.job_id, video_id: result.video_id, created_at: '2026-11-11T00:00:00Z',
    source: { video_sha256: result.source_hash, transcript_hash: result.transcript_hash, duration_ms: sample.duration_ms }, turns: [] }
  const turn: AgentTurn = { id: result.turn_id, session_id: session.id, job_id: session.job_id, question: result.question, sequence: 1, visual_mode: 'frames', evidence_mode: 'structured',
    ...(focused ? { focus_timestamp_ms: result.focus_timestamp_ms } : {}), status: 'SUCCEEDED', state_version: 3, created_at: '2026-11-11T00:00:01Z', updated_at: '2026-11-11T00:00:02Z',
    budget: { max_text_calls: 8, text_calls_reserved: 3 }, stage: { id: '55555555-5555-4555-8555-555555555555', stage_type: 'AGENT_EVIDENCE', status: 'SUCCEEDED', attempt: 1, error_code: null }, result }
  return parseAgentTurn(turn, session)
}
assert.equal(fixture.data_kind, 'synthetic_fixture'); assert.ok(fixture.cases.length >= 14)
for (const sample of fixture.cases) test(`evidence review shared validated 1.3 case: ${sample.id}`, () => {
  const turn = verifiedCase(sample), before = JSON.stringify(turn), derived = reviewAgentEvidence(turn.result)
  assert.deepEqual(derived, sample.expected_review); assert.ok(Object.isFrozen(derived)); assert.equal(JSON.stringify(turn), before)
})

test('only verified structured 1.3 has a review; old versions do not gain inferred claims or fields', () => {
  for (const schema_version of ['1.0', '1.1', '1.2']) assert.equal(reviewAgentEvidence({ schema_version, get tool_calls() { throw Error('Old trace must not be inspected') } } as any), null)
  assert.equal(reviewAgentEvidence(null), null); assert.equal(reviewAgentEvidence(undefined), null)
})

test('review ignores all model prose and separates citations used only for inference from SPEECH claims', () => {
  const source = fixture.cases.find((sample: any) => sample.id === 'inference_citation_is_not_a_speech_block'), result = structuredClone(verifiedCase(source).result!)
  assert.equal(reviewAgentEvidence(result)!.citation_count, 1); assert.equal(reviewAgentEvidence(result)!.inference_utterance_count, 1); assert.equal(reviewAgentEvidence(result)!.speech_utterance_count, 0)
  const original = reviewAgentEvidence(result); result.answer = '已经搜索完整视频，没有任何原句。'; result.answer_blocks![0]!.text = '未检索到'
  assert.deepEqual(reviewAgentEvidence(result), original, 'The pure projection never interprets natural-language assertions')
})

test('program messages do not infer success, no-match or semantic proof from a trace or citation', () => {
  const find = (id: string) => reviewAgentEvidence(verifiedCase(fixture.cases.find((sample: any) => sample.id === id)).result)!
  assert.equal(agentEvidenceReviewMessage(find('no_tools_no_claims')), '本轮未请求原句检索或上下文。')
  assert.equal(agentEvidenceReviewMessage(find('empty_topics_are_only_topic_trace')), '原句相关轨迹仅来自话题工具，未请求原句检索或上下文；返回话题锚点 0 条。')
  assert.match(agentEvidenceReviewMessage(find('empty_search_is_not_proof_of_no_match')), /不能据此判断无匹配或全片不存在/)
  assert.match(agentEvidenceReviewMessage(find('observed_but_not_cited')), /没有讲者原句分项/)
  assert.match(agentEvidenceReviewMessage(find('speech_from_context')), /不证明文字准确回应问题或构成语义支持/)
  assert.match(AGENT_EVIDENCE_REVIEW_BOUNDARY, /工具轨迹未记录可区分的执行结果/)
})

test('production cards display unrequested speech warning and focus/model distinction directly, without added image/API work', async () => {
  const turn = verifiedCase(fixture.cases.find((sample: any) => sample.id === 'user_focus_is_not_speech_lookup'))
  const component = compileHostComponent(new URL('../AgentEvidenceAnswer.vue', import.meta.url), { './agentEvidenceAnswer': evidence, './agentEvidenceReview': review, './agentVisualEvidence': visual, './topics': topics })
  const root = hostNode('root'), before = JSON.stringify(turn)
  const priorFetch = globalThis.fetch; let fetchCalls = 0; globalThis.fetch = (() => { fetchCalls++; throw Error('Summary must never request data') }) as any
  try {
    hostRenderer.render(h(component, { turn, active: true }), root); await flushHost()
    const section = allHostNodes(root).find(node => node.props['aria-label'] === '证据核查摘要')!; assert.ok(section); assert.equal(section.props['data-speech-status'], 'NOT_REQUESTED')
    assert.match(hostText(section), /本轮未请求原句检索或上下文/); assert.match(hostText(section), /实际画面 1 帧/); assert.match(hostText(section), /用户定位 1 次 · 模型选择 0 次/)
    assert.equal(allHostNodes(section).filter(node => ['img', 'video', 'a'].includes(node.type)).length, 0)
    const details = allHostNodes(section).find(node => node.type === 'details')!; assert.ok(!details.props.open)
    const warning = allHostNodes(section).find(node => node.props.role === 'status')!; assert.notEqual(warning.parent, details, 'The key warning is not hidden in disclosure')
    assert.equal(JSON.stringify(turn), before); assert.equal(fetchCalls, 0)
  } finally { hostRenderer.render(null, root); globalThis.fetch = priorFetch }
})

test('production summary updates only with result props, preserves raw answer and shows overlapping returned frame groups honestly', async () => {
  const turn = verifiedCase(fixture.cases.find((sample: any) => sample.id === 'focus_frame_reused_by_model_clip_not_added_twice'))
  const component = compileHostComponent(new URL('../AgentEvidenceAnswer.vue', import.meta.url), { './agentEvidenceAnswer': evidence, './agentEvidenceReview': review, './agentVisualEvidence': visual, './topics': topics }), root = hostNode('root')
  try {
    hostRenderer.render(h(component, { turn, active: false }), root); await flushHost(); const summary = allHostNodes(root).find(node => node.props['aria-label'] === '证据核查摘要')!
    assert.match(hostText(summary), /实际画面 2 帧/); assert.match(hostText(summary), /用户定位 \/ 模型取图返回帧1 \/ 2 帧/); assert.match(hostText(summary), /不能直接相加/)
    assert.ok(allHostNodes(root).filter(node => node.type === 'img').every(node => node.props.src === undefined))
    const raw = structuredClone(turn), answer = raw.result!.answer; hostRenderer.render(h(component, { turn: raw, active: true }), root); await flushHost(); assert.equal(raw.result!.answer, answer)
  } finally { hostRenderer.render(null, root) }
})

test('four FRAME blocks and one inference can reference one USER_FOCUS image without becoming five observations or a model-selected image', () => {
  const sample = structuredClone(fixture.cases.find((value: any) => value.id === 'user_focus_is_not_speech_lookup')), result = sample.result
  result.answer_blocks = Array.from({ length: 6 }, (_, index) => ({ block_id: `b0${index + 1}`, kind: index < 4 ? 'FRAME' : index === 4 ? 'INFERENCE' : 'INSUFFICIENT', text: '合成结构计数示例。', utterance_ids: [], frame_ids: index < 5 ? ['f0001'] : [] }))
  result.visual_citations = [{ frame_id: 'f0001', explanation: '关联分项：b01、b02、b03、b04、b05' }]; result.answer = evidence.assembleAgentEvidenceAnswer(result.answer_blocks)
  const derived = reviewAgentEvidence(verifiedCase(sample).result)!
  assert.equal(derived.observed_frame_count, 1); assert.equal(derived.visual_citation_count, 1); assert.equal(derived.user_focus_tool_calls, 1); assert.equal(derived.model_visual_tool_calls, 0)
  assert.equal(derived.user_focus_frame_count, 1); assert.equal(derived.model_frame_count, 0); assert.equal(derived.speech_status, 'NOT_REQUESTED'); assert.equal(derived.insufficient_block_count, 1)
})
