import { createHash } from 'node:crypto'
import type { IntentSnapshot } from '../../intentClips.ts'
import type { IntentManualReview, IntentManualReviewReceipt } from '../../intentManualReview.ts'

let sequence = 0
const sorted = (raw: any): any => Array.isArray(raw) ? raw.map(sorted) : raw !== null && typeof raw === 'object'
  ? Object.fromEntries(Object.keys(raw).sort().map(key => [key, sorted(raw[key])])) : raw
export const hash = (raw: unknown) => createHash('sha256').update(JSON.stringify(sorted(raw)), 'utf8').digest('hex')
export const clone = <T>(raw: T): T => JSON.parse(JSON.stringify(raw))
export function manualFixture(count = 2) {
  const id = '11111111-1111-4111-8111-111111111111', job = '22222222-2222-4222-8222-222222222222', video = '33333333-3333-4333-8333-333333333333'
  const instruction = '保留完整缓存解释，接受当前整句边界'
  const snapshot: IntentSnapshot = { id, job_id: job, video_id: video, instruction, status: 'PLAN_READY', state_version: 4,
    created_at: '2026-11-08T00:00:00Z', updated_at: '2026-11-08T00:00:01Z', budget: { max_text_calls: 6, text_calls_reserved: 2 },
    stage: { id: '44444444-4444-4444-8444-444444444444', stage_type: 'SEGMENT', status: 'SUCCEEDED', attempt: 1, error_code: null },
    source: { video_sha256: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 4000 }, revision: 1, plan_hash: null,
    manual_review: null, confirmed_revisions: [], utterances: Array.from({ length: 4 }, (_, i) => ({ utterance_id: `u${i + 1}`, start_ms: i * 1000, end_ms: (i + 1) * 1000, text: `缓存原句${i + 1}，其中可能包含关注引导` })),
    plan: { schema_version: '1.0', request_id: id, job_id: job, video_id: video, instruction, source_hash: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 4000,
      goals: [{ goal_id: 'cause', description: '原因' }, { goal_id: 'solution', description: '解决方法' }], constraints: { keep_explanation_complete: true, exclude: [] },
      outputs: ['cause', 'solution'].map((goal_id, i) => ({ output_id: `output-${goal_id}`, goal_id, title: `缓存${goal_id}`, status: 'NEEDS_REVIEW', enabled: false,
        reason: '原句含完整解释和混合话术，需人工选择', parts: [{ start_utterance_id: `u${i * 2 + 1}`, end_utterance_id: `u${i * 2 + 2}`, start_ms: i * 2000, end_ms: (i + 1) * 2000 }],
        evidence_utterance_ids: [`u${i * 2 + 1}`, `u${i * 2 + 2}`], excluded: [] })),
      provenance: { text_model_id: 'synthetic', prompt_version: 'intent-clips-v1', retrieval_mode: 'bm25', index_hash: 'd'.repeat(64) } } }
  snapshot.plan_hash = hash(snapshot.plan)
  const input = { base_revision: snapshot.revision, plan_hash: snapshot.plan_hash,
    outputs: snapshot.plan!.outputs.slice(0, count).map(output => ({ output_id: output.output_id, note: '已核对原视频，接受整句内混合话术 😀', accept_sentence_boundaries: true as const })) }
  const receipt: IntentManualReviewReceipt = { schema_version: 'intent-manual-review-receipt-v1', key: `55555555-5555-4555-8555-${String(++sequence).padStart(12, '0')}`,
    request_id: id, job_id: job, video_id: video, source: { ...snapshot.source }, input: JSON.stringify(input) }
  const content: Omit<IntentManualReview, 'review_hash'> = { schema_version: '1.0', id: receipt.key, request_id: id, job_id: job, video_id: video,
    source_hash: snapshot.source.video_sha256, transcript_hash: snapshot.source.transcript_hash, duration_ms: 4000, automatic_plan_hash: 'e'.repeat(64),
    base_revision: 1, base_plan_hash: snapshot.plan_hash, revision: 2, plan_hash: snapshot.plan_hash,
    outputs: snapshot.plan!.outputs.slice(0, count).map((output, i) => ({ output_id: output.output_id, goal_id: output.goal_id, output_hash: hash(output),
      note: input.outputs[i]!.note, accept_sentence_boundaries: true })), created_at: '2026-11-08T00:00:02.123456Z' }
  const proof: IntentManualReview = { ...content, review_hash: hash(content) }
  const reviewed: IntentSnapshot = { ...clone(snapshot), revision: 2, state_version: 5, updated_at: '2026-11-08T00:00:02Z', manual_review: proof }
  return { snapshot, input, receipt, proof, reviewed }
}
