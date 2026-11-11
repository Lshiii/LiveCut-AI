import test from 'node:test'
import assert from 'node:assert/strict'
import { h } from 'vue'
import * as speech from '../agentSpeechLookup.ts'
import * as screenSearch from '../agentScreenSearch.ts'
import { parseAgentSpeechLookupCapability, speechLookupQuery, speechAnchorDistance, agentToolOriginLabel } from '../agentSpeechLookup.ts'
import * as evidence from '../agentEvidenceAnswer.ts'
import * as review from '../agentEvidenceReview.ts'
import * as visual from '../agentVisualEvidence.ts'
import * as topics from '../topics.ts'
import { parseAgentTurn, newestAgentTurn } from '../videoAgent.ts'
import { agentSubmissionInput, createAgentSubmission, parseAgentSubmissionResponse, agentDraftAfterSubmission, agentDraftMatchesSubmission } from '../agentSubmissionRecovery.ts'
import { agentNoteOwner } from '../agentNote.ts'
import { agentFrameFollowupTarget } from '../agentFrameFollowup.ts'
import { selectAnswerClip } from '../answerClip.ts'
import { MemoryStorage } from './support/agentSubmissionFixture.ts'
import { speechFixture, speechSample } from './support/agentSpeechLookupFixture.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'

assert.equal(speechFixture.data_kind, 'synthetic_fixture')
for (const source of speechFixture.cases) test(`shared 1.4 public record and full source/visual projection: ${source.id}`, () => {
  const { sample, session, turn } = speechSample(source.id), before = JSON.stringify(turn), parsed = parseAgentTurn(turn, session)
  assert.equal(parsed.result!.schema_version, '1.4'); assert.equal(parsed.result!.speech_lookup!.strategy, sample.expected_strategy); assert.equal(parsed.result!.speech_lookup!.status, sample.expected_status)
  assert.deepEqual(parsed.result!.tool_calls.map(call => call.origin), turn.result!.tool_calls.map(call => call.origin)); assert.deepEqual(parsed.result, turn.result)
  assert.equal(JSON.stringify(turn), before); assert.ok(Object.isFrozen(parsed.result!.speech_lookup)); assert.ok(review.reviewAgentEvidence(parsed.result))
})

test('speech capability is exact protocol2; old support and malformed answers cannot authorize the new policy', () => {
  assert.equal(parseAgentSpeechLookupCapability({ speech_lookup_supported: true, worker_protocol: 2 }), true)
  for (const value of [null, {}, { speech_lookup_supported: true }, { speech_lookup_supported: true, worker_protocol: 1 }, { speech_lookup_supported: false, worker_protocol: 2 }, { speech_lookup_supported: true, worker_protocol: 2, extra: 1 }, { speech_lookup_supported: true, worker_protocol: true }]) assert.throws(() => parseAgentSpeechLookupCapability(value))
})
test('new-policy query uses JS trim and at most300 UTF16 units without splitting an emoji; old question validation remains separate', () => {
  assert.deepEqual(speechLookupQuery('\uFEFF\u00a0查询\uFEFF\u00a0'), { query: '查询', truncated: false })
  assert.deepEqual(speechLookupQuery('a'.repeat(299) + '😀尾巴'), { query: 'a'.repeat(299), truncated: true })
  assert.deepEqual(speechLookupQuery('😀'.repeat(151)), { query: '😀'.repeat(150), truncated: true })
  assert.deepEqual(speechLookupQuery('a'.repeat(299) + ' ' + '后半问题'), { query: 'a'.repeat(299), truncated: true })
  for (const question of ['\uFEFF\u00a0', 'x\u0085', 'x\u0000', '\ud800', '\udc00', 'x'.repeat(2001)]) assert.throws(() => speechLookupQuery(question))
  const base = { question: '旧\u0085问题', max_text_calls: 8, visual_mode: 'frames', evidence_mode: 'structured' }
  assert.ok(agentSubmissionInput('TURN', base)); assert.equal(agentSubmissionInput('TURN', { ...base, evidence_policy: 'speech_lookup_v1' }), null)
})
test('half-open public anchor distance has zero/one millisecond edge behavior without pretending to compute full-ASR nearest', () => {
  const sentence = { start_ms: 1000, end_ms: 2000 }
  assert.equal(speechAnchorDistance(0, sentence), 1000); assert.equal(speechAnchorDistance(1000, sentence), 0); assert.equal(speechAnchorDistance(1999, sentence), 0)
  assert.equal(speechAnchorDistance(2000, sentence), 1); assert.equal(speechAnchorDistance(17000, sentence), 15001)
})
test('1.4 refuses owner/policy/schema/prompt/origin downgrade, missing fields and arbitrary new receipt fields', () => {
  for (const mutate of [(v: any) => { delete v.evidence_policy }, (v: any) => { v.evidence_policy = null }, (v: any) => { v.evidence_mode = 'none' }, (v: any) => { v.visual_mode = 'none' },
    (v: any) => { v.result.schema_version = '1.3' }, (v: any) => { v.result.evidence_policy = 'other' }, (v: any) => { delete v.result.speech_lookup }, (v: any) => { v.result.extra = 1 },
    (v: any) => { v.result.speech_lookup.extra = 1 }, (v: any) => { delete v.result.speech_lookup.query_truncated }, (v: any) => { v.result.provenance.prompt_version = 'video-agent-evidence-v1' },
    (v: any) => { delete v.result.tool_calls[1].origin }, (v: any) => { v.result.tool_calls[1].origin = 'MODEL' }, (v: any) => { v.result.tool_calls[0].origin = 'EVIDENCE_POLICY' },
    (v: any) => { v.result.tool_calls[2] = { ...v.result.tool_calls[1], step: 3 }; v.result.tool_calls[2].origin = 'EVIDENCE_POLICY' }]) {
    const { session, turn } = speechSample(); mutate(turn); assert.throws(() => parseAgentTurn(turn, session))
  }
})
test('context public receipt pins unique step, source-return IDs, threshold, exact before/after and candidate/status consistency', () => {
  for (const mutate of [(v: any) => { v.speech_lookup.tool_step = 1 }, (v: any) => { v.speech_lookup.anchor_distance_ms = 15001 }, (v: any) => { v.speech_lookup.query_truncated = true },
    (v: any) => { v.speech_lookup.candidate_utterance_count = 0 }, (v: any) => { v.speech_lookup.candidate_utterance_count = 4 }, (v: any) => { v.speech_lookup.candidate_utterance_count = 1.5 },
    (v: any) => { v.speech_lookup.status = 'EMPTY_RETURN' }, (v: any) => { v.speech_lookup.returned_utterance_ids.reverse() }, (v: any) => { v.speech_lookup.returned_utterance_ids = ['unknown'] },
    (v: any) => { v.tool_calls[1].arguments.before = 0 }, (v: any) => { v.tool_calls[1].arguments.after = 2 }, (v: any) => { v.speech_lookup.anchor_distance_ms = 1 }]) {
    const { session, turn } = speechSample(); mutate(turn.result); assert.throws(() => parseAgentTurn(turn, session))
  }
})
test('question-search public receipt binds actual normalized prefix/truncation and local BM25 while preserving explicit empty and context-limited results', () => {
  for (const mutate of [(v: any) => { v.speech_lookup.query_truncated = true }, (v: any) => { v.speech_lookup.anchor_distance_ms = 15001 },
    (v: any) => { v.tool_calls[0].arguments.query += '另一问题' }, (v: any) => { v.tool_calls[0].arguments.top_k = 3 }, (v: any) => { v.provenance.retrieval_mode = 'bm25_dense_rrf' },
    (v: any) => { v.provenance.retrieval_mode = 'none' }, (v: any) => { v.speech_lookup.status = 'CONTEXT_LIMIT' }, (v: any) => { v.speech_lookup.candidate_utterance_count = false }]) {
    const { session, turn } = speechSample('no_focus_local_search'); mutate(turn.result); assert.throws(() => parseAgentTurn(turn, session))
  }
  const { session, turn } = speechSample('far_focus_uses_question_search'); turn.result!.speech_lookup!.anchor_distance_ms = 15000; assert.throws(() => parseAgentTurn(turn, session))
})
test('new policy is part of durable original identity and cannot clear a differently selected current draft', () => {
  const { session, turn } = speechSample('no_focus_local_search'), store = new MemoryStorage(), input = { question: turn.question, max_text_calls: 8, visual_mode: 'frames', evidence_mode: 'structured', evidence_policy: 'speech_lookup_v1' }
  const owner = { job_id: session.job_id, video_id: session.video_id, video_sha256: session.source.video_sha256 }, entry = createAgentSubmission(store, owner, 'TURN', JSON.stringify(input), () => 'speech-original', [session], session)
  assert.equal(parseAgentSubmissionResponse(turn, entry, session).kind, 'TURN')
  const draft = { session_id: session.id, question: turn.question, budget: 8, visual_mode: 'frames' as const, evidence_mode: 'structured' as const }
  assert.equal(agentDraftMatchesSubmission(entry, draft), false); assert.equal(agentDraftAfterSubmission(entry, draft), turn.question)
  assert.equal(agentDraftAfterSubmission(entry, { ...draft, evidence_policy: 'speech_lookup_v1' }), '')
  assert.throws(() => parseAgentSubmissionResponse({ ...turn, evidence_policy: undefined }, entry, session)); assert.throws(() => newestAgentTurn(turn, { ...turn, evidence_policy: undefined }))
  for (const evidence_policy of [null, 'none', true, 'other']) assert.equal(agentSubmissionInput('TURN', { ...input, evidence_policy }), null)
  assert.equal(agentSubmissionInput('TURN', { ...input, evidence_mode: 'none' }), null)
  assert.equal(agentSubmissionInput('TURN', { ...input, visual_mode: 'none' }), null)
})
test('1.4 note/anchor/followup reuse verified source; continuing from a frame never inherits the source policy', async () => {
  const { session, turn } = speechSample('policy_and_model_sampled_change_have_distinct_origins'), parsed = parseAgentTurn(turn, session)
  assert.ok(agentNoteOwner(session, parsed)); assert.equal(agentToolOriginLabel(parsed.result!.tool_calls[1]!.origin), '程序按预查策略读取')
  const frame = parsed.result!.visual_citations![0]!.frame_id, target = agentFrameFollowupTarget(session, turn.id, frame, { owner: { job_id: session.job_id, video_id: session.video_id, source_hash: session.source.video_sha256, duration_ms: session.source.duration_ms }, session_id: session.id, transcript_hash: session.source.transcript_hash, active: true, visible: true, pending: false, visual_available: true, focus_supported: true })
  assert.ok(!Object.hasOwn(target.hint, 'evidence_policy')); assert.ok(!Object.hasOwn(target.hint, 'evidence_mode'))
  if (parsed.result!.citations.length) { const selected = await selectAnswerClip(session, parsed, [parsed.result!.citations[0]!.utterance_id]); assert.equal(selected.binding.turn_id, turn.id) }
})
test('policy applies strict text validation before pending turns can be acknowledged and works with original budget one', () => {
  const { session, turn } = speechSample(); turn.status = 'QUEUED'; turn.result = null
  for (const question of ['\uFEFF\u00a0', 'x\u0085', '\ud800']) assert.throws(() => parseAgentTurn({ ...turn, question }, session))
  turn.budget = { max_text_calls: 1, text_calls_reserved: 0 }; assert.equal(parseAgentTurn(turn, session).budget.max_text_calls, 1)
})
test('production card presents program scope/query truncation/record status separately from SPEECH without new requests', async () => {
  const component = compileHostComponent(new URL('../AgentEvidenceAnswer.vue', import.meta.url), { './agentEvidenceAnswer': evidence, './agentEvidenceReview': review, './agentSpeechLookup': speech, './agentScreenSearch': screenSearch, './agentVisualEvidence': visual, './topics': topics }), root = hostNode('root')
  try {
    for (const id of ['long_query_prefix_is_explicit', 'context_limit_keeps_anchor_before_neighbors', 'empty_search_is_not_full_video_absence']) {
      const { session, turn } = speechSample(id), parsed = parseAgentTurn(turn, session), before = JSON.stringify(parsed)
      hostRenderer.render(h(component, { turn: parsed, active: false }), root); await flushHost()
      const record = allHostNodes(root).find(node => node.props['aria-label'] === '程序原句预查')!; assert.ok(record); assert.equal(record.props['data-lookup-status'], parsed.result!.speech_lookup!.status)
      assert.match(hostText(record), /不是模型自主请求/); assert.match(hostText(record), /不保证相关性、全片覆盖或语义正确/)
      if (id.startsWith('long')) assert.match(hostText(record), /不代表已检索后半问题/)
      if (id.startsWith('context')) { assert.match(hostText(record), /候选 3 条 · 实际提供 1 条/); assert.match(hostText(record), /前 1 句 \/ 后 1 句/) }
      if (id.startsWith('empty')) assert.match(hostText(record), /不表示全片没有相关讲解/)
      assert.equal(allHostNodes(record).filter(node => ['img', 'video', 'a'].includes(node.type)).length, 0); assert.equal(JSON.stringify(parsed), before)
    }
  } finally { hostRenderer.render(null, root) }
})
