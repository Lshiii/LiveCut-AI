import type { SequencePreviewPart } from './sequencePreview.ts'

export interface EditSpan extends SequencePreviewPart {
  editStartMs: number
  editEndMs: number
  partIndex: number
}

/** Both clocks are integer milliseconds. Every retained interval is half-open. */
export function editSpans(parts: readonly SequencePreviewPart[]): EditSpan[] {
  const spans: EditSpan[] = []
  let elapsed = 0, previousEnd = 0
  for (const [partIndex, part] of parts.entries()) {
    if (!part || !Number.isSafeInteger(part.start_ms) || !Number.isSafeInteger(part.end_ms)
      || part.start_ms < previousEnd || part.end_ms <= part.start_ms) return []
    const next = elapsed + (part.end_ms - part.start_ms)
    spans.push({ ...part, partIndex, editStartMs: elapsed, editEndMs: next })
    elapsed = next
    previousEnd = part.end_ms
  }
  return spans
}

export function editToSource(parts: readonly SequencePreviewPart[], editMs: number): { sourceMs: number; partIndex: number } | null {
  if (!Number.isSafeInteger(editMs) || editMs < 0) return null
  const spans = editSpans(parts), last = spans.at(-1)
  if (!last || editMs > last.editEndMs) return null
  // The endpoint is a valid scrubber position, but there is no playable sample at the exclusive end.
  if (editMs === last.editEndMs) return { sourceMs: last.end_ms - 1, partIndex: last.partIndex }
  const part = spans.find(span => editMs < span.editEndMs)!
  return { sourceMs: part.start_ms + (editMs - part.editStartMs), partIndex: part.partIndex }
}

export function sourceToEdit(parts: readonly SequencePreviewPart[], sourceMs: number): number | null {
  if (!Number.isSafeInteger(sourceMs) || sourceMs < 0) return null
  const spans = editSpans(parts), last = spans.at(-1)
  if (last && sourceMs === last.end_ms) return last.editEndMs
  const part = spans.find(span => span.start_ms <= sourceMs && sourceMs < span.end_ms)
  return part ? part.editStartMs + (sourceMs - part.start_ms) : null
}

export interface EditJoin {
  at_ms: number
  from_ms: number
  to_ms: number
  audition: [SequencePreviewPart, SequencePreviewPart]
}

/** Input is the already source-validated, ordered set of retained intervals. */
export function editJoins(parts: readonly SequencePreviewPart[]): EditJoin[] {
  const joins: EditJoin[] = []
  let elapsed = 0
  for (let index = 1; index < parts.length; index++) {
    const left = parts[index - 1]!, right = parts[index]!
    elapsed += left.end_ms - left.start_ms
    joins.push({
      at_ms: elapsed, from_ms: left.end_ms, to_ms: right.start_ms,
      audition: [
        { start_ms: Math.max(left.start_ms, left.end_ms - 1500), end_ms: left.end_ms },
        { start_ms: right.start_ms, end_ms: Math.min(right.end_ms, right.start_ms + 1500) },
      ],
    })
  }
  return joins
}
