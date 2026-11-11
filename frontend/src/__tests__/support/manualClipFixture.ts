import { createHash } from 'node:crypto'
import { manualClipCanonical, type ManualClipInput, type ManualClipPlan, type ManualClipSubmission } from '../../manualClips.ts'
export const manualId = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
export const manualOwner = { job_id: manualId(1), video_id: manualId(2), generation: 3, source_video_sha256: 'a'.repeat(64) }
export const manualSource = { ...manualOwner, transcript_sha256: 'b'.repeat(64), analysis_sha256: 'c'.repeat(64), duration_ms: 10000, source_fingerprint: 'd'.repeat(64) }
export const manualUtterance = (n: number) => ({ utterance_id: `u${n}`, start_ms: n * 1000, end_ms: (n + 1) * 1000, text: `原始 ASR ${n} 缓存` })
export const manualHash = (value: string) => createHash('sha256').update(value, 'utf8').digest('hex')
export function manualPlan(input: ManualClipInput, revision = 1, latest = revision, confirmed = false): ManualClipPlan {
  const first = manualUtterance(Number(input.start_utterance_id.slice(1))), last = manualUtterance(Number(input.end_utterance_id.slice(1)))
  const range = { start_ms: first.start_ms, end_ms: last.end_ms, utterance_count: Number(input.end_utterance_id.slice(1)) - Number(input.start_utterance_id.slice(1)) + 1, start_text: first.text, end_text: last.text }
  const immutable = { schema_version: '1.0', data_kind: 'manual_clip_plan_revision', source_kind: 'MANUAL_ASR', id: manualId(10), source: manualSource,
    revision, title: input.title, start_utterance_id: input.start_utterance_id, end_utterance_id: input.end_utterance_id, range }
  return { ...immutable, schema_version: '1.0', data_kind: 'manual_clip_plan', source_kind: 'MANUAL_ASR', latest_revision: latest,
    plan_hash: manualHash(manualClipCanonical(immutable)), status: confirmed ? 'CONFIRMED' : 'DRAFT', confirmed_revisions: confirmed ? [revision] : [],
    created_at: '2026-11-11T10:00:00Z', updated_at: '2026-11-11T10:00:00Z' }
}
export function manualSubmission(operation: 'CREATE' | 'REVISION', input: any, key: string, plan: ManualClipPlan): ManualClipSubmission {
  return { schema_version: '1.0', data_kind: 'manual_clip_submission', operation, job_id: manualOwner.job_id, plan_id: plan.id, idempotency_key: key, input,
    request_hash: manualHash(`manual-clip-request-v1\n${operation}\n${operation === 'CREATE' ? manualOwner.job_id : plan.id}\n${manualClipCanonical(input)}\n`), revision: plan.revision, plan_hash: plan.plan_hash }
}
export function manualExport(plan: ManualClipPlan) {
  const content = `/api/exports/${manualId(20)}/clips/${manualId(21)}/content`
  return { id: manualId(20), job_id: manualOwner.job_id, video_id: manualOwner.video_id, approved_revision: plan.revision, snapshot_hash: 'e'.repeat(64), manual_clip_plan_id: plan.id,
    profile: 'h264-aac-v1', status: 'SUCCEEDED', state_version: 2, created_at: '2026-11-11T10:01:00Z', updated_at: '2026-11-11T10:01:01Z', clips: [{ id: manualId(21),
      segment_id: 'manual-clip', product_id: null, product_name: null, title: plan.title, start_ms: plan.range.start_ms, end_ms: plan.range.end_ms, stage_id: manualId(22),
      status: 'SUCCEEDED', attempt: 1, error_code: null, size_bytes: 128, content_url: content, download_url: `${content}?download=true` }] }
}
