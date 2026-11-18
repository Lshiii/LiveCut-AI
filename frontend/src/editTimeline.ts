import type { SequencePreviewPart } from './sequencePreview.ts'

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
