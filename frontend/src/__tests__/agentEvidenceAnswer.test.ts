import test from 'node:test'
import assert from 'node:assert/strict'
import { h } from 'vue'
import { assembleAgentEvidenceAnswer, evidenceTextValid, parseAgentEvidenceCapability } from '../agentEvidenceAnswer.ts'
import * as evidence from '../agentEvidenceAnswer.ts'
import * as review from '../agentEvidenceReview.ts'
import * as speech from '../agentSpeechLookup.ts'
import * as visual from '../agentVisualEvidence.ts'
import * as topics from '../topics.ts'
import { newestAgentTurn, parseAgentSession, parseAgentTurn } from '../videoAgent.ts'
import { agentDraftAfterSubmission, agentDraftMatchesSubmission, createAgentSubmission, parseAgentSubmissionResponse, agentSubmissionInput } from '../agentSubmissionRecovery.ts'
import { agentNoteOwner, parseAgentNotePreview } from '../agentNote.ts'
import { agentFrameFollowupTarget } from '../agentFrameFollowup.ts'
import { MemoryStorage } from './support/agentSubmissionFixture.ts'
import { evidenceSample, cloneEvidence } from './support/agentEvidenceFixture.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'

function rebuild(value: ReturnType<typeof evidenceSample>) { value.turn.result!.answer = assembleAgentEvidenceAnswer(value.turn.result!.answer_blocks!); return value }
test('shared 1.3 focus and no-focus goldens preserve full prior source/PTS/tools and exactly generated labels/body/unions', () => {
  for (const focus of [false, true]) {
    const { session, turn } = evidenceSample(focus), checked = parseAgentSession(session, session.job_id, session.video_id, session.source.video_sha256).turns[0]!
    assert.equal(checked.stage.stage_type, 'AGENT_EVIDENCE'); assert.equal(checked.result!.answer_blocks!.length, 6); assert.equal(checked.result!.answer, turn.result!.answer)
    assert.equal(checked.result!.evidence_mode, 'structured'); assert.equal(checked.result!.provenance.prompt_version, 'video-agent-evidence-v1')
    assert.equal(Object.hasOwn(checked.result!, 'focus_timestamp_ms'), focus); assert.equal(Object.hasOwn(checked.result!.tool_calls[0]!, 'origin'), focus)
  }
})
test('capability is exact true only and structured request identity never silently becomes an old visual/text mode', () => {
  assert.equal(parseAgentEvidenceCapability({ structured_evidence_supported: true }), true)
  for (const bad of [null, {}, { structured_evidence_supported: false }, { structured_evidence_supported: true, other: 1 }]) assert.throws(() => parseAgentEvidenceCapability(bad))
  for (const evidence_mode of [null, false, 'other', 'structured']) assert.equal(agentSubmissionInput('TURN', { question: '问题', max_text_calls: 8, evidence_mode }), null)
  assert.deepEqual(agentSubmissionInput('TURN', { question: '问题', max_text_calls: 8 }), { question: '问题', max_text_calls: 8 })
  assert.deepEqual(agentSubmissionInput('TURN', { question: '问题', max_text_calls: 8, visual_mode: 'frames', evidence_mode: 'structured', focus_timestamp_ms: 0 }), { question: '问题', max_text_calls: 8, visual_mode: 'frames', focus_timestamp_ms: 0, evidence_mode: 'structured' })
})
test('1.3 refuses extra fields, mismatched mode/stage/focus/schema or free independent answer before adoption', () => {
  const mutations = [(v: any) => { v.evidence_mode = 'none' }, (v: any) => { delete v.evidence_mode }, (v: any) => { v.visual_mode = 'none' }, (v: any) => { v.stage.stage_type = 'AGENT_VISUAL' },
    (v: any) => { v.result.schema_version = '1.1' }, (v: any) => { delete v.result.evidence_mode }, (v: any) => { v.result.evidence_mode = null }, (v: any) => { v.result.provenance.prompt_version = 'video-agent-visual-v1' },
    (v: any) => { v.result.answer += '\n自由结论' }, (v: any) => { v.result.other = true }, (v: any) => { v.result.focus_timestamp_ms = 0 }, (v: any) => { v.result.tool_calls[0].origin = 'MODEL' }]
  for (const mutate of mutations) { const { session, turn } = evidenceSample(); mutate(turn); assert.throws(() => parseAgentTurn(turn, session)) }
})
test('each block rejects gaps, duplicate/unknown IDs, wrong evidence modality, absent facts and extra model-created fields', () => {
  const mutations = [(v: any) => { delete v.kind }, (v: any) => { v.block_id = 'b02' }, (v: any) => { v.kind = 'FACT' }, (v: any) => { v.utterance_ids = [] },
    (v: any) => { v.utterance_ids = ['foreign'] }, (v: any) => { v.utterance_ids = ['u1', 'u1'] }, (v: any) => { v.frame_ids = ['f0001'] }, (v: any) => { v.url = 'https://fake/' }]
  for (const mutate of mutations) { const value = evidenceSample(); mutate(value.turn.result!.answer_blocks![0]); rebuild(value); assert.throws(() => parseAgentTurn(value.turn, value.session)) }
  for (const kind of ['FRAME', 'SAMPLED_CHANGE', 'GENERAL', 'INSUFFICIENT']) { const value = evidenceSample(); value.turn.result!.answer_blocks![0]!.kind = kind as any; rebuild(value); assert.throws(() => parseAgentTurn(value.turn, value.session)) }
})
test('program body preserves original UTF16 text whitespace and denies C0/C1, isolated surrogates and aggregate overflow', () => {
  for (const text of ['  原文\t\r\n保留  ', '\uFEFF正文\uFEFF', '😀'.repeat(500)]) { const v = evidenceSample(); v.turn.result!.answer_blocks![0]!.text = text; rebuild(v); assert.equal(parseAgentTurn(v.turn, v.session).result!.answer_blocks![0]!.text, text) }
  for (const text of ['', '   ', '\uFEFF', '\u00A0', '\uFEFF\u00A0', 'x'.repeat(1001), '😀'.repeat(501), '\ud800', '\udc00', 'x\u0085', 'x\u0000', 'x\u007f']) { assert.equal(evidenceTextValid(text, 1000), false); const v = evidenceSample(); v.turn.result!.answer_blocks![0]!.text = text; rebuild(v); assert.throws(() => parseAgentTurn(v.turn, v.session)) }
  const v = evidenceSample(); for (const block of v.turn.result!.answer_blocks!) block.text = '字'.repeat(1000); rebuild(v); assert.throws(() => parseAgentTurn(v.turn, v.session))
})
test('speech and visual top-level citations must equal complete block unions with exact generated explanations', () => {
  for (const mutate of [(v: any) => { v.citations = [] }, (v: any) => { v.visual_citations.pop() }, (v: any) => { v.visual_citations[0].explanation = '模型解释' },
    (v: any) => { v.answer_blocks[1].frame_ids = ['f0002', 'f0001'] }, (v: any) => { v.answer_blocks[1].frame_ids = ['f0001', 'f0001'] }, (v: any) => { v.answer_blocks[1].frame_ids = ['f0032'] }]) {
    const v = evidenceSample(); mutate(v.turn.result); rebuild(v); assert.throws(() => parseAgentTurn(v.turn, v.session))
  }
})
test('speech IDs follow source order and block-local budgets do not permit an oversized top-level union', () => {
  const v = evidenceSample(), result = v.turn.result!
  result.citations.push({ utterance_id: 'u2', start_ms: 2000, end_ms: 2500, text: '第二句' }); result.tool_calls[0]!.result_utterance_ids.push('u2')
  result.answer_blocks![0]!.utterance_ids = ['u1', 'u2']; rebuild(v); assert.ok(parseAgentTurn(v.turn, v.session))
  result.answer_blocks![0]!.utterance_ids.reverse(); rebuild(v); assert.throws(() => parseAgentTurn(v.turn, v.session))
  const large = evidenceSample(), r = large.turn.result!; large.session.source.duration_ms = 10000
  r.citations = Array.from({ length: 25 }, (_, index) => ({ utterance_id: `u${index}`, start_ms: index * 100, end_ms: index * 100 + 99, text: '原句' }))
  r.tool_calls[0]!.result_utterance_ids = r.citations.map(c => c.utterance_id); r.visual_citations = []
  r.answer_blocks = Array.from({ length: 4 }, (_, index) => ({ block_id: `b0${index + 1}`, kind: 'SPEECH', text: '分项', utterance_ids: r.citations.slice(index * 8, index * 8 + 8).map(c => c.utterance_id), frame_ids: [] }))
  rebuild(large); assert.throws(() => parseAgentTurn(large.turn, large.session))
})
test('change requires two exact increasing-PTS frames of the same successful inspect_clip, not arbitrary screenshots', () => {
  for (const mutate of [(v: any) => { v.answer_blocks[2].frame_ids = ['f0001'] }, (v: any) => { v.answer_blocks[2].frame_ids.reverse() },
    (v: any) => { v.tool_calls[1].tool_name = 'inspect_frames'; v.tool_calls[1].arguments = { timestamps_ms: [1000, 1500] } },
    (v: any) => { v.visual_evidence[1].source_pts = 1000; v.visual_evidence[1].timestamp_ms = 1000 },
    (v: any) => { v.tool_calls[1].tool_name = 'inspect_frames'; v.tool_calls[1].arguments = { timestamps_ms: [1000] }; v.tool_calls[1].result_frame_ids = ['f0001']; v.tool_calls.push({ step: 3, tool_name: 'inspect_frames', arguments: { timestamps_ms: [1500] }, result_utterance_ids: [], result_frame_ids: ['f0002'] }) }]) {
    const value = evidenceSample(); mutate(value.turn.result); rebuild(value); assert.throws(() => parseAgentTurn(value.turn, value.session))
  }
})
test('GENERAL/INSUFFICIENT require their own block types and inference alone cannot establish VIDEO facts', () => {
  for (const scope of ['GENERAL', 'INSUFFICIENT'] as const) { const v = evidenceSample(); v.turn.result!.answer_scope = scope; v.turn.result!.answer_blocks = [{ block_id: 'b01', kind: scope, text: '明确边界', utterance_ids: [], frame_ids: [] }]; v.turn.result!.citations = []; v.turn.result!.visual_citations = []; rebuild(v); assert.equal(parseAgentTurn(v.turn, v.session).result!.answer_scope, scope) }
  const v = evidenceSample(); v.turn.result!.answer_blocks = [{ block_id: 'b01', kind: 'INFERENCE', text: '只是推断', utterance_ids: ['u1'], frame_ids: [] }]; v.turn.result!.visual_citations = []; rebuild(v); assert.throws(() => parseAgentTurn(v.turn, v.session))
})
test('immutable submission and draft matching bind evidence mode independently of question/budget and recover without current capabilities', () => {
  const { session, turn } = evidenceSample(), storage = new MemoryStorage(), input = { question: turn.question, max_text_calls: 8, visual_mode: 'frames', evidence_mode: 'structured' }, owner = { job_id: session.job_id, video_id: session.video_id, video_sha256: session.source.video_sha256 }
  const entry = createAgentSubmission(storage, owner, 'TURN', JSON.stringify(input), () => 'evidence-original', [session], session)
  assert.equal(parseAgentSubmissionResponse(turn, entry, session).kind, 'TURN')
  const draft = { session_id: session.id, question: turn.question, budget: 8, visual_mode: 'frames' as const }
  assert.equal(agentDraftMatchesSubmission(entry, draft), false); assert.equal(agentDraftAfterSubmission(entry, draft), turn.question)
  assert.equal(agentDraftAfterSubmission(entry, { ...draft, evidence_mode: 'structured' }), '')
  const old = { ...entry, input: JSON.stringify({ question: turn.question, max_text_calls: 8, visual_mode: 'frames' }) }; assert.throws(() => parseAgentSubmissionResponse(turn, old, session))
  assert.throws(() => newestAgentTurn(turn, { ...turn, evidence_mode: undefined }))
})
test('1.3 note identity and cited-frame followup preserve actual images/labels and focus mode stays independent', () => {
  const { session, turn } = evidenceSample(true), owner = agentNoteOwner(session, turn)
  assert.equal(owner.citation_count, 1); assert.equal(owner.image_count, 2)
  const note = parseAgentNotePreview({ schema_version: 1, ...owner, result_hash: 'e'.repeat(64), source_filename: '合成.mp4', markdown: turn.result!.answer, download_filename: `video-note-${turn.id}.zip` }, owner)
  assert.match(note.markdown, /【前后采样 b03】/); assert.match(note.markdown, /不证明采样间隙或因果/)
  const target = agentFrameFollowupTarget(session, turn.id, 'f0002', { owner: { job_id: session.job_id, video_id: session.video_id, source_hash: session.source.video_sha256, duration_ms: session.source.duration_ms }, session_id: session.id, transcript_hash: session.source.transcript_hash, active: true, visible: true, pending: false, visual_available: true, focus_supported: true })
  assert.equal(target.hint.timestamp_ms, turn.result!.visual_evidence![1]!.timestamp_ms); assert.ok(!Object.hasOwn(target.hint, 'evidence_mode'))
})
test('production evidence cards render pure text and two actual before/after evidence IDs/times with fixed safety labels', async () => {
  const sample = evidenceSample(); sample.turn.result!.answer_blocks![0]!.text = '<img src=x onerror=alert(1)> **原文**'; rebuild(sample); sample.turn = parseAgentTurn(sample.turn, sample.session)
  const component = compileHostComponent(new URL('../AgentEvidenceAnswer.vue', import.meta.url), { './agentEvidenceAnswer': evidence, './agentEvidenceReview': review, './agentSpeechLookup': speech, './agentVisualEvidence': visual, './topics': topics }), root = hostNode('root'), located: number[] = []
  hostRenderer.render(h(component, { turn: sample.turn, active: true, onLocate: (value: number) => located.push(value) }), root); await flushHost()
  try { const nodes = allHostNodes(root); assert.match(hostText(root), /<img src=x onerror=alert\(1\)> \*\*原文\*\*/); assert.equal(nodes.filter(v=>v.type==='img').length,2); assert.equal(nodes.filter(v=>v.type==='script').length,0); assert.match(hostText(root), /前侧采样 · f0001/); assert.match(hostText(root), /后侧采样 · f0002/); assert.match(hostText(root), /来源与结构核对不代表每项语义已被规则证明/); nodes.find(v=>v.type==='button'&&hostText(v)==='定位 f0002 画面时间')!.props.onClick(); assert.deepEqual(located,[1500]) }
  finally { hostRenderer.render(null, root) }
})
