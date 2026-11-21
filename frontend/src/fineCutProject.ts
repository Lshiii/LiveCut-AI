import { editSpans, type EditSpan } from './editTimeline.ts'
import type { IntentPart } from './intentClips.ts'
import type { SequencePreviewPart } from './sequencePreview.ts'

export interface FineCutOwner {
  requestId: string
  videoId: string
  sourceHash: string
  transcriptHash: string
  revision: number
  planHash: string
  outputId: string
  goalId: string
  durationMs: number
  evidenceIds: readonly string[]
  manualReviewHash?: string
}

type Node = Record<string, unknown>
const invalid = () => new Error('原工程的音画轨、媒体引用或毫秒时间码不一致，请重新载入已确认的时间线。')
const mismatch = () => new Error('原工程与当前已确认的来源、版本或保留区间不符，请重新核对。')
function object(value: unknown): Node {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid()
  return value as Node
}
function schema(value: unknown, expected: string) {
  const node = object(value)
  if (node.OTIO_SCHEMA !== expected) throw invalid()
  return node
}
function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw invalid()
  return value
}
function integer(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 }
function text(value: unknown): value is string { return typeof value === 'string' && value.trim().length > 0 }
function hash(value: unknown): value is string { return typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value) }
function time(value: unknown) {
  const clock = schema(value, 'RationalTime.1')
  if (clock.rate !== 1000 || !integer(clock.value)) throw invalid()
  return clock.value
}
function range(value: unknown): SequencePreviewPart {
  const node = schema(value, 'TimeRange.1')
  const start = time(node.start_time), length = time(node.duration)
  if (!length || !Number.isSafeInteger(start + length)) throw invalid()
  return { start_ms: start, end_ms: start + length }
}
function trimRange(existing: unknown, start: number, end: number) {
  const node = object(existing)
  object(node.start_time).value = start
  object(node.duration).value = end - start
}
function sameRange(a: SequencePreviewPart, b: SequencePreviewPart) { return a.start_ms === b.start_ms && a.end_ms === b.end_ms }
function coordinates(part: EditSpan) {
  return { source_start_ms: part.start_ms, source_end_ms: part.end_ms, edit_start_ms: part.editStartMs, edit_end_ms: part.editEndMs }
}
function summary(parts: EditSpan[]) {
  return { duration_ms: parts.at(-1)!.editEndMs, edit_points_ms: parts.slice(1).map(part => part.editStartMs) }
}
function checkOwner(proof: Node, owner: FineCutOwner) {
  const ids = array(proof.evidence_utterance_ids)
  const exportOrigin = ['export_id', 'clip_id', 'snapshot_hash'].some(key => Object.hasOwn(proof, key))
  if (exportOrigin && (!text(proof.export_id) || !text(proof.clip_id) || !hash(proof.snapshot_hash))) throw mismatch()
  if (![owner.requestId,owner.videoId,owner.outputId,owner.goalId].every(text)
    || ![owner.sourceHash,owner.planHash,owner.transcriptHash].every(hash)
    || !integer(owner.revision) || !owner.revision || !integer(owner.durationMs) || !owner.durationMs
    || proof.approved_revision !== owner.revision || proof.plan_hash !== owner.planHash
    || proof.clip_request_id !== owner.requestId || proof.video_id !== owner.videoId || proof.output_id !== owner.outputId
    || proof.goal_id !== owner.goalId || proof.source_sha256 !== owner.sourceHash || proof.transcript_hash !== owner.transcriptHash
    || proof.manual_review_hash !== owner.manualReviewHash || (owner.manualReviewHash !== undefined && !hash(owner.manualReviewHash))
    || !ids.length || ids.length !== owner.evidenceIds.length || ids.some((id,i) => !text(id) || id !== owner.evidenceIds[i])
    || new Set(ids).size !== ids.length || proof.edit_kind !== undefined || proof.approved_source !== undefined) throw mismatch()
}
function checkReference(raw: unknown, filename: string, owner: FineCutOwner) {
  const ref = schema(raw, 'ExternalReference.1'), available = range(ref.available_range)
  if (ref.name !== filename || !text(ref.target_url) || !ref.target_url.startsWith('./') || /[?#\\]/.test(ref.target_url)
    || available.start_ms !== 0 || available.end_ms !== owner.durationMs
    || ref.available_image_bounds !== null || object(object(ref.metadata).livecut).source_sha256 !== owner.sourceHash) throw invalid()
  let target: string
  try { target = decodeURIComponent(ref.target_url.slice(2)) } catch { throw invalid() }
  if (target !== filename || !/\.mp4$/i.test(filename) || filename.startsWith('.') || /[/\\%\u0000-\u001f]/u.test(filename)) throw invalid()
  return ref.target_url
}
function children(raw: unknown, type: 'Stack.1' | 'Track.1', name: string, kind?: string) {
  const node = schema(raw, type)
  if (node.name !== name || node.enabled !== true || node.source_range !== null || (kind !== undefined && node.kind !== kind)
    || array(node.effects).length || array(node.markers).length) throw invalid()
  return array(node.children)
}

/** Produce a local derivative only from the two synchronized hard-cut tracks emitted by the Java OTIO exporter. */
export function buildFineCutProject(raw: unknown, owner: FineCutOwner, base: readonly IntentPart[],
  trimmed: readonly SequencePreviewPart[]): Record<string, unknown> {
  const original = editSpans(base), cuts = editSpans(trimmed)
  if (!original.length || original.at(-1)!.end_ms > owner.durationMs
    || base.some(part => !text(part.start_utterance_id) || !text(part.end_utterance_id))) throw mismatch()
  if (original.length !== cuts.length || cuts.some((part, i) => part.start_ms < original[i]!.start_ms || part.end_ms > original[i]!.end_ms)) {
    throw new Error('每段只能在原粗剪区间内收紧，并至少保留 1 毫秒。')
  }
  // The wire document is JSON. This also accepts a reactive Vue view of the response.
  let copy: unknown
  try { copy = JSON.parse(JSON.stringify(raw)) } catch { throw invalid() }
  const project = schema(copy, 'Timeline.1'), metadata = object(project.metadata), proof = object(metadata.livecut)
  checkOwner(proof, owner)
  if (!text(project.name) || !text(proof.source_filename) || time(project.global_start_time) !== 0) throw invalid()
  const stack = children(project.tracks, 'Stack.1', 'tracks')
  if (stack.length !== 2) throw invalid()
  const video = children(stack[0], 'Track.1', 'V1', 'Video'), audio = children(stack[1], 'Track.1', 'A1', 'Audio')
  if (video.length !== base.length || audio.length !== base.length) throw mismatch()
  const expectedEvidence = new Set(owner.evidenceIds), seen = new Set<string>(), retainedEvidence = new Set<string>()
  let mediaUrl: string | undefined
  for (const [i, cut] of cuts.entries()) {
    const before = original[i]!, bounds = base[i]!
    for (const track of [video, audio]) {
      const clip = schema(track[i], 'Clip.1'), clipMeta = object(clip.metadata), clipProof = object(clipMeta.livecut)
      const reference = checkReference(clip.media_reference, proof.source_filename, owner)
      if (mediaUrl !== undefined && mediaUrl !== reference) throw invalid()
      mediaUrl = reference
      if (clip.name !== project.name + ' · ' + (i + 1) || clip.enabled !== true || array(clip.effects).length || !sameRange(range(clip.source_range), before)
        || clipProof.start_utterance_id !== bounds.start_utterance_id || clipProof.end_utterance_id !== bounds.end_utterance_id) throw mismatch()
      const markers = array(clip.markers)
      if (track === audio && markers.length) throw invalid()
      clip.markers = markers.filter(rawMarker => {
        const marker = schema(rawMarker, 'Marker.2'), markerMeta = object(marker.metadata), markerProof = object(markerMeta.livecut)
        const id = markerProof.utterance_id, markerRange = range(marker.marked_range)
        if (!text(id) || !expectedEvidence.has(id) || seen.has(id) || markerRange.start_ms < before.start_ms
          || markerRange.end_ms > before.end_ms) throw mismatch()
        seen.add(id)
        const start = Math.max(markerRange.start_ms, cut.start_ms), end = Math.min(markerRange.end_ms, cut.end_ms)
        if (end <= start) return false
        retainedEvidence.add(id)
        trimRange(marker.marked_range, start, end)
        markerMeta.livecut = { utterance_id: id, source_start_ms: start, source_end_ms: end,
          edit_start_ms: cut.editStartMs + (start - cut.start_ms), edit_end_ms: cut.editStartMs + (end - cut.start_ms),
          partial_utterance: start !== markerRange.start_ms || end !== markerRange.end_ms,
          approved_source: { ...markerProof, source_start_ms: markerRange.start_ms, source_end_ms: markerRange.end_ms,
            edit_start_ms: before.editStartMs + (markerRange.start_ms - before.start_ms),
            edit_end_ms: before.editStartMs + (markerRange.end_ms - before.start_ms) } }
        return true
      })
      trimRange(clip.source_range, cut.start_ms, cut.end_ms)
      clipMeta.livecut = { ...coordinates(cut), approved_source: { ...clipProof, ...coordinates(before) } }
    }
  }
  if (seen.size !== expectedEvidence.size) throw mismatch()
  project.name = project.name + ' · 人工精剪'
  metadata.livecut = { edit_kind: 'manual_fine_cut', clip_request_id: owner.requestId, output_id: owner.outputId, goal_id: owner.goalId,
    video_id: owner.videoId, source_sha256: owner.sourceHash, source_filename: proof.source_filename, transcript_hash: owner.transcriptHash,
    evidence_utterance_ids: owner.evidenceIds.filter(id => retainedEvidence.has(id)), ...summary(cuts),
    approved_source: { ...proof, ...summary(original) } }
  return project
}
