import { intentOutputValidation, type IntentOutput, type IntentPart, type IntentUtterance } from './intentClips.ts'

export interface PaperCutRow {
  utterance: IntentUtterance
  partIndex: number
  evidence: boolean
  anchor: boolean
}
export interface PaperCutSource {
  rows: PaperCutRow[]
  originalDurationMs: number
}
export interface PaperCutResult {
  parts: IntentPart[]
  evidenceIds: string[]
  durationMs: number
}

/** Freeze only the originally kept rows. A restore can never introduce excluded or unseen material. */
export function preparePaperCut(output: IntentOutput, utterances: IntentUtterance[], durationMs: number,
  anchorIds: readonly string[] = []): PaperCutSource {
  if (output.status !== 'FOUND') throw new Error('仅已找到证据的目标支持逐句粗剪。')
  const error = intentOutputValidation([output], utterances, durationMs)
  if (error) throw new Error(error)
  const indexes = new Map(utterances.map((item, index) => [item.utterance_id, index]))
  const evidence = new Set(output.evidence_utterance_ids), anchors = new Set(anchorIds)
  const rows = output.parts.flatMap((part, partIndex) => {
    const first = indexes.get(part.start_utterance_id)!, last = indexes.get(part.end_utterance_id)!
    return utterances.slice(first, last + 1).map(utterance => ({ utterance: { ...utterance }, partIndex,
      evidence: evidence.has(utterance.utterance_id), anchor: anchors.has(utterance.utterance_id) }))
  })
  if (rows.filter(row => row.anchor).length !== anchors.size) throw new Error('请先恢复全部回答锚句。')
  return { rows, originalDurationMs: output.parts.reduce((sum, part) => sum + part.end_ms - part.start_ms, 0) }
}

/** A run continues only inside its original part. Gaps between retained sentences in that run keep their audio. */
export function compilePaperCut(source: PaperCutSource, removedIds: ReadonlySet<string>): PaperCutResult {
  const parts: IntentPart[] = [], evidenceIds: string[] = []
  let current: IntentPart | undefined, lastPartIndex = -1, matched = 0
  for (const row of source.rows) {
    const { utterance_id: id, start_ms, end_ms } = row.utterance
    if (removedIds.has(id)) {
      if (row.anchor) throw new Error('回答引用的原句必须保留。')
      matched++
      current = undefined
      continue
    }
    if (!current || lastPartIndex !== row.partIndex) {
      current = { start_utterance_id: id, end_utterance_id: id, start_ms, end_ms }
      parts.push(current)
    } else {
      current.end_utterance_id = id
      current.end_ms = end_ms
    }
    lastPartIndex = row.partIndex
    if (row.evidence) evidenceIds.push(id)
  }
  if (matched !== removedIds.size) throw new Error('所选原句不在当前候选中。')
  if (!evidenceIds.length) throw new Error('请至少保留一条原有依据原句。')
  if (parts.length > 12) throw new Error('每个成片最多保留 12 个区间，请调整选择。')
  return { parts, evidenceIds, durationMs: parts.reduce((sum, part) => sum + part.end_ms - part.start_ms, 0) }
}

interface PaperCutChange { remove: boolean; ids: string[] }
export interface PaperCutHistory {
  removed: ReadonlySet<string>
  past: PaperCutChange[]
  future: PaperCutChange[]
}
export function newPaperCutHistory(): PaperCutHistory { return { removed: new Set(), past: [], future: [] } }
function changeSet(previous: ReadonlySet<string>, ids: Iterable<string>, remove: boolean) {
  const next = new Set(previous)
  for (const id of ids) {
    if (remove) next.add(id)
    else next.delete(id)
  }
  return next
}
export function editPaperCut(source: PaperCutSource, history: PaperCutHistory, selected: ReadonlySet<string>, remove: boolean): PaperCutHistory {
  const ids = [...selected].filter(id => history.removed.has(id) !== remove)
  if (!ids.length) return history
  const removed = changeSet(history.removed, ids, remove)
  compilePaperCut(source, removed)
  return { removed, past: [...history.past.slice(-99), { remove, ids }], future: [] }
}
export function stepPaperCut(history: PaperCutHistory, direction: 'undo' | 'redo'): PaperCutHistory {
  const undo = direction === 'undo', stack = undo ? history.past : history.future
  const last = stack.at(-1)
  if (!last) return history
  const removed = changeSet(history.removed, last.ids, undo ? !last.remove : last.remove)
  return undo ? { removed, past: stack.slice(0, -1), future: [...history.future, last] }
    : { removed, past: [...history.past, last], future: stack.slice(0, -1) }
}
