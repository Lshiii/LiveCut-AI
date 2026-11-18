import { compilePaperCut, type PaperCutResult, type PaperCutSource } from './paperCut.ts'

export type PaperCutBudgetSuggestion =
  | { status: 'suggested' | 'already_fits'; cut: PaperCutResult; removedIds: ReadonlySet<string>
      keptEvidenceCount: number; keptAnchorCount: number; matchedTerms: string[]; approximate: boolean }
  | { status: 'infeasible'; minimumMs: number }
  | { status: 'invalid' | 'limit'; message: string }

const MAX_ROWS = 256
const FRONTIER_SIZE = 32
const MAX_PARTS = 12

// Match Chinese bigrams and whole Latin/numeric words. Bound lexical work even on malformed long text.
function terms(text: string): Set<string> {
  const result = new Set<string>()
  const normalized = text.slice(0, 2000).normalize('NFKC').toLowerCase()
  for (const token of normalized.match(/\p{Script=Han}+|[\p{Script=Latin}\p{Number}]+/gu) ?? []) {
    if (/^\p{Script=Han}/u.test(token)) {
      const chars = [...token]
      if (chars.length === 1) result.add(token)
      else for (let i = 1; i < chars.length; i++) result.add(chars[i - 1]! + chars[i]!)
    } else result.add(token)
  }
  return result
}

interface Path {
  parts: number
  lastKept: boolean
  evidence: boolean
  ms: number
  score: number
  kept: bigint
}

function store(groups: Map<number, Path[]>, path: Path) {
  const key = path.parts * 4 + (path.lastKept ? 2 : 0) + (path.evidence ? 1 : 0)
  const list = groups.get(key)
  if (list) list.push(path)
  else groups.set(key, [path])
}

// All paths in a group have identical future moves and incremental durations. Always preserve its
// shortest path, including when it already exceeds the target. This makes the final feasible minimum
// exact even if some longer, editorially preferable options are dropped at the frontier ceiling.
function compact(paths: Path[], targetMs: number) {
  paths.sort((a, b) => a.ms - b.ms || b.score - a.score)
  const frontier: Path[] = []
  let bestScore = -Infinity, previousMs = -1
  for (const path of paths) {
    if (path.ms > targetMs && frontier.length) break
    if (path.ms === previousMs || path.score < bestScore) continue
    frontier.push(path)
    previousMs = path.ms
    bestScore = path.score
  }
  if (frontier.length <= FRONTIER_SIZE) return { paths: frontier, pruned: false }
  const sampled = Array.from({ length: FRONTIER_SIZE }, (_, i) =>
    frontier[Math.floor(i * (frontier.length - 1) / (FRONTIER_SIZE - 1))]!)
  return { paths: sampled, pruned: true }
}

/** Suggest a further cut of the current scratch edit. The returned set includes earlier manual cuts. */
export function suggestPaperCutBudget(source: PaperCutSource, removedIds: ReadonlySet<string>, targetMs: number,
  goalText: string): PaperCutBudgetSuggestion {
  if (!Number.isSafeInteger(targetMs) || targetMs < 1) return { status: 'invalid', message: '目标时长必须大于零。' }
  if (source.rows.length > MAX_ROWS) return { status: 'limit', message: `单次时长建议最多处理 ${MAX_ROWS} 句，请先缩小候选区间。` }
  let previousEnd = 0
  for (const { utterance } of source.rows) {
    if (!Number.isSafeInteger(utterance.start_ms) || !Number.isSafeInteger(utterance.end_ms)
      || utterance.start_ms < previousEnd || utterance.end_ms <= utterance.start_ms) {
      return { status: 'invalid', message: '原句时间缺失或交叠，请重新读取候选。' }
    }
    previousEnd = utterance.end_ms
  }
  let current: PaperCutResult
  try { current = compilePaperCut(source, removedIds) }
  catch (cause) { return { status: 'invalid', message: cause instanceof Error ? cause.message : '当前粗剪草稿无法编译。' } }
  const goalTerms = [...terms(goalText)].slice(0, 64)
  const matches = source.rows.map(({ utterance }) => {
    const tokens = terms(utterance.text)
    return goalTerms.filter(term => tokens.has(term))
  })
  function suggestion(status: 'already_fits' | 'suggested', cut: PaperCutResult, removed: ReadonlySet<string>,
    approximate: boolean): PaperCutBudgetSuggestion {
    const matchedTerms = new Set<string>()
    let keptAnchorCount = 0
    source.rows.forEach((row, index) => {
      if (removed.has(row.utterance.utterance_id)) return
      if (row.anchor) keptAnchorCount++
      for (const term of matches[index]!) matchedTerms.add(term)
    })
    return { status, cut, removedIds: removed, keptEvidenceCount: cut.evidenceIds.length, keptAnchorCount,
      matchedTerms: [...matchedTerms].slice(0, 12), approximate }
  }
  if (current.durationMs <= targetMs) return suggestion('already_fits', current, new Set(removedIds), false)

  // A short, generic evidence line should not routinely displace the sentence that names the
  // requested mechanism. Give more weight to terms which discriminate among the available rows.
  const availableCount = source.rows.length - removedIds.size
  const documentFrequency = new Map<string, number>()
  matches.forEach((rowTerms, i) => {
    if (!removedIds.has(source.rows[i]!.utterance.utterance_id))
      for (const term of rowTerms) documentFrequency.set(term, (documentFrequency.get(term) ?? 0) + 1)
  })
  const termWeight = new Map([...documentFrequency].map(([term, frequency]) =>
    [term, 10 + Math.round(18 * Math.log2(1 + availableCount / frequency))]))
  let paths: Path[] = [{ parts: 0, lastKept: false, evidence: false, ms: 0, score: 0, kept: 0n }]
  let approximate = false
  for (const [i, row] of source.rows.entries()) {
    const { utterance } = row, previous = source.rows[i - 1]
    const removed = removedIds.has(utterance.utterance_id), bit = 1n << BigInt(i)
    const strongest = matches[i]!.map(term => termWeight.get(term) ?? 0).sort((a, b) => b - a).slice(0, 4)
    const points = 3 + (row.evidence ? 24 : 0) + strongest.reduce((sum, weight) => sum + weight, 0)
    const groups = new Map<number, Path[]>()
    for (const path of paths) {
      if (!row.anchor) store(groups, { ...path, lastKept: false })
      if (removed) continue
      const continues = path.lastKept && previous?.partIndex === row.partIndex
      const parts = path.parts + (continues ? 0 : 1)
      if (parts > MAX_PARTS) continue
      // The gap between adjacent kept rows is audible and consumes budget.
      const start = continues ? previous!.utterance.end_ms : utterance.start_ms
      const continuity = continues && utterance.start_ms - start <= 1500 ? 6 : 0
      store(groups, { parts, lastKept: true, evidence: path.evidence || row.evidence,
        ms: path.ms + utterance.end_ms - start, score: path.score + points + continuity, kept: path.kept | bit })
    }
    paths = []
    for (const group of groups.values()) {
      const next = compact(group, targetMs)
      if (next.pruned) approximate = true
      paths.push(...next.paths)
    }
  }

  const withEvidence = paths.filter(path => path.evidence)
  const candidates = withEvidence.filter(path => path.ms <= targetMs)
  candidates.sort((a, b) => b.score - a.score || b.ms - a.ms || a.parts - b.parts)
  const best = candidates[0]
  if (!best) {
    // The input scratch cut was valid, so at least its shortest feasible counterpart survives.
    return { status: 'infeasible', minimumMs: Math.min(...withEvidence.map(path => path.ms)) }
  }
  const removed = new Set<string>()
  source.rows.forEach(({ utterance }, i) => {
    if ((best.kept & (1n << BigInt(i))) === 0n) removed.add(utterance.utterance_id)
  })
  const cut = compilePaperCut(source, removed)
  if (cut.durationMs !== best.ms || cut.durationMs > targetMs) return { status: 'invalid', message: '建议与原片时间不符，请重新核对。' }
  return suggestion('suggested', cut, removed, approximate)
}
