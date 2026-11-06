import type { IntentOutput, IntentPart, IntentUtterance } from './intentClips.ts'

const identifier = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const milliseconds = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0

/** Playback bounds only. This never supplies evidence or authorizes confirmation/export. */
export function intentPreviewParts(output: IntentOutput, utterances: IntentUtterance[], durationMs: number,
  partIndex?: number): Array<{ start_ms: number; end_ms: number }> | null {
  if (!output || typeof output !== 'object' || Array.isArray(output)
    || !['FOUND', 'NEEDS_REVIEW'].includes(output.status)
    || !Array.isArray(output.parts) || !output.parts.length || !Array.isArray(output.excluded)
    || !Array.isArray(utterances) || !milliseconds(durationMs) || durationMs === 0) return null
  const single = partIndex !== undefined
  if (single ? !Number.isSafeInteger(partIndex) || partIndex < 0 || partIndex >= output.parts.length : output.parts.length > 12) return null

  const source = new Map<string, { value: IntentUtterance; index: number }>()
  let previousSourceEnd = 0
  for (const [index, value] of utterances.entries()) {
    if (!value || typeof value.utterance_id !== 'string' || !identifier.test(value.utterance_id)
      || source.has(value.utterance_id) || !milliseconds(value.start_ms) || !milliseconds(value.end_ms)
      || value.end_ms <= value.start_ms || value.start_ms < previousSourceEnd || value.end_ms > durationMs) return null
    source.set(value.utterance_id, { value, index })
    previousSourceEnd = value.end_ms
  }
  const excluded = new Set<string>()
  for (const value of output.excluded) {
    if (!value || typeof value.utterance_id !== 'string' || !source.has(value.utterance_id)) return null
    excluded.add(value.utterance_id)
  }

  // A different part may be an unfinished draft. Single-part playback does not
  // inspect its fields or compare its order against this selected part.
  const parts: IntentPart[] = single ? [output.parts[partIndex]!] : output.parts
  const result: Array<{ start_ms: number; end_ms: number }> = []
  let previousIndex = -1, previousEnd = 0
  for (const part of parts) {
    if (!part || typeof part !== 'object') return null
    const start = source.get(part.start_utterance_id), end = source.get(part.end_utterance_id)
    if (!start || !end || start.index > end.index || start.index <= previousIndex
      || !milliseconds(part.start_ms) || !milliseconds(part.end_ms)
      || part.end_ms <= part.start_ms || part.end_ms > durationMs || part.start_ms < previousEnd
      || part.start_ms !== start.value.start_ms || part.end_ms !== end.value.end_ms) return null
    for (let index = start.index; index <= end.index; index++) {
      if (excluded.has(utterances[index]!.utterance_id)) return null
    }
    result.push({ start_ms: part.start_ms, end_ms: part.end_ms })
    previousIndex = end.index; previousEnd = part.end_ms
  }
  return result
}
