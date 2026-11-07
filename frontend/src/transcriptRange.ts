import type { IntentOutput, IntentPart, IntentUtterance } from './intentClips.ts'
import { topicTime } from './topics.ts'

const identifier = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const milliseconds = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0
type SourcePosition = { value: IntentUtterance; index: number }
type RangeResult = { part: IntentPart | null; error: string }
const invalid = (error: string): RangeResult => ({ part: null, error })

/** A local boundary edit only; status, exclusions, evidence and permissions stay with the caller. */
export function validateTranscriptRange(output: IntentOutput, partIndex: number, startId: string, endId: string,
  utterances: IntentUtterance[], durationMs: number): RangeResult {
  if (!output || !['FOUND', 'NEEDS_REVIEW'].includes(output.status) || !Array.isArray(output.parts)) return invalid('当前目标不能调整原句区间。')
  if (!Number.isSafeInteger(partIndex) || partIndex < 0 || partIndex >= output.parts.length) return invalid('要调整的保留区间已变化，请重新选择。')
  if (!Array.isArray(utterances) || !utterances.length || !milliseconds(durationMs) || !durationMs) return invalid('原句来源或时间无效，请重新读取后再试。')
  const source = new Map<string, SourcePosition>()
  let previousEnd = 0
  for (const [index, value] of utterances.entries()) {
    if (!value || typeof value.utterance_id !== 'string' || !identifier.test(value.utterance_id) || source.has(value.utterance_id)
      || !milliseconds(value.start_ms) || !milliseconds(value.end_ms) || value.end_ms <= value.start_ms
      || value.start_ms < previousEnd || value.end_ms > durationMs) return invalid('原句来源或时间无效，请重新读取后再试。')
    source.set(value.utterance_id, { value, index }); previousEnd = value.end_ms
  }
  if (!Array.isArray(output.excluded)) return invalid('排除原句来源无效，请重新读取后再试。')
  const excluded = new Set<string>()
  for (const value of output.excluded) {
    if (!value || typeof value.utterance_id !== 'string' || !source.has(value.utterance_id)) return invalid('排除原句来源无效，请重新读取后再试。')
    excluded.add(value.utterance_id)
  }
  const start = source.get(startId), end = source.get(endId)
  if (!start || !end) return invalid('请选择有效的起始原句和结束原句。')
  if (start.index > end.index) return invalid('结束原句不能早于起始原句。')
  function containsExcluded(first: number, last: number) {
    for (let index = first; index <= last; index++) if (excluded.has(utterances[index]!.utterance_id)) return true
    return false
  }
  if (containsExcluded(start.index, end.index)) return invalid('所选范围包含已排除原句，请调整起止原句。')

  for (const [index, part] of output.parts.entries()) {
    if (index === partIndex || !part) continue
    const otherStart = source.get(part.start_utterance_id), otherEnd = source.get(part.end_utterance_id)
    // Unfinished/invalid drafts remain untouched, so the user can repair one
    // range at a time. Every individually complete legal range is protected.
    if (!otherStart || !otherEnd || otherStart.index > otherEnd.index
      || !milliseconds(part.start_ms) || !milliseconds(part.end_ms) || part.end_ms <= part.start_ms
      || part.start_ms !== otherStart.value.start_ms || part.end_ms !== otherEnd.value.end_ms
      || containsExcluded(otherStart.index, otherEnd.index)) continue
    if (index < partIndex && (otherEnd.index >= start.index || part.end_ms > start.value.start_ms)) return invalid('所选范围与前面的保留区间顺序冲突或重叠。')
    if (index > partIndex && (end.index >= otherStart.index || end.value.end_ms > part.start_ms)) return invalid('所选范围与后面的保留区间顺序冲突或重叠。')
  }
  return { part: { start_utterance_id: startId, end_utterance_id: endId,
    start_ms: start.value.start_ms, end_ms: end.value.end_ms }, error: '' }
}

const normalized = (value: string) => value.normalize('NFKC').toLowerCase()

/** Literal AND terms, searched against text and the same time labels the picker displays. */
export function filterTranscriptIndexes(utterances: IntentUtterance[], query: string): number[] {
  if (!Array.isArray(utterances) || typeof query !== 'string') return []
  const terms = normalized(query).trim().split(/\s+/u).filter(Boolean), result: number[] = []
  for (const [index, value] of utterances.entries()) {
    if (!value || typeof value.text !== 'string') continue
    const haystack = normalized(`${value.text} ${topicTime(value.start_ms)} ${topicTime(value.end_ms)}`)
    if (terms.every(term => haystack.includes(term))) result.push(index)
  }
  return result
}

const safePageSize = (value: number) => Number.isSafeInteger(value) && value > 0 ? value : 20

export function transcriptPage(indexes: number[], page: number, pageSize = 20): { indexes: number[]; page: number; totalPages: number; total: number } {
  const values = Array.isArray(indexes) ? indexes : [], size = safePageSize(pageSize)
  const total = values.length, totalPages = Math.max(1, Math.ceil(total / size))
  const requested = typeof page === 'number' && Number.isFinite(page) ? Math.trunc(page) : 1
  const current = Math.max(1, Math.min(totalPages, requested)), offset = (current - 1) * size
  return { indexes: values.slice(offset, offset + size), page: current, totalPages, total }
}

export function transcriptPageForId(utterances: IntentUtterance[], indexes: number[], id: string, pageSize = 20): number {
  if (!Array.isArray(utterances) || !Array.isArray(indexes) || typeof id !== 'string') return 1
  const sourceIndex = utterances.findIndex(value => value?.utterance_id === id)
  if (sourceIndex < 0) return 1
  const position = indexes.indexOf(sourceIndex)
  return position < 0 ? 1 : Math.floor(position / safePageSize(pageSize)) + 1
}
