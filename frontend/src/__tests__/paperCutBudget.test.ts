import test from 'node:test'
import assert from 'node:assert/strict'
import { compilePaperCut, type PaperCutSource } from '../paperCut.ts'
import { suggestPaperCutBudget, type PaperCutBudgetSuggestion } from '../paperCutBudget.ts'

interface Line { length: number; gap?: number; part?: number; anchor?: boolean; evidence?: boolean; text?: string }
function source(lines: Line[]): PaperCutSource {
  let now = 0
  const rows = lines.map((line, i) => {
    const start = now + (line.gap ?? 0)
    now = start + line.length
    return { utterance: { utterance_id: 'u' + i, start_ms: start, end_ms: now, text: line.text ?? '句子' },
      partIndex: line.part ?? 0, evidence: line.evidence ?? false, anchor: line.anchor ?? false }
  })
  const candidate = { rows, originalDurationMs: 0 }
  candidate.originalDurationMs = compilePaperCut(candidate, new Set()).durationMs
  return candidate
}
function usable(result: PaperCutBudgetSuggestion) {
  assert.ok(result.status === 'already_fits' || result.status === 'suggested')
  return result
}
const empty = new Set<string>()

test('reuses an already short edit and never restores an earlier manual removal', () => {
  const s = source([{ length: 400, evidence: true }, { length: 350 }, { length: 500, evidence: true }])
  const old = new Set(['u1'])
  const enough = usable(suggestPaperCutBudget(s, old, 900, ''))
  assert.equal(enough.status, 'already_fits')
  assert.deepEqual(enough.removedIds, old)
  const shorter = usable(suggestPaperCutBudget(s, old, 450, ''))
  assert.deepEqual(shorter.removedIds, new Set(['u1', 'u2']))
  assert.equal(shorter.cut.durationMs, 400)
  assert.deepEqual(old, new Set(['u1']))
})

test('one mandatory quote plus one separate evidence sentence can make tiny targets impossible', () => {
  const s = source([{ length: 160, anchor: true }, { length: 450 }, { length: 550, evidence: true }])
  assert.deepEqual(suggestPaperCutBudget(s, empty, 1, ''), { status: 'infeasible', minimumMs: 710 })
  const fit = usable(suggestPaperCutBudget(s, empty, 710, ''))
  assert.equal(fit.keptAnchorCount, 1)
  assert.equal(fit.keptEvidenceCount, 1)
  assert.equal(fit.cut.durationMs, 710)
  assert.deepEqual(fit.removedIds, new Set(['u1']))
  assert.equal(suggestPaperCutBudget(s, new Set(['u0']), 710, '').status, 'invalid')
})

test('Chinese bigrams and whole Latin words prioritize the requested explanation', () => {
  const s = source([{ length: 100, evidence: true, text: '先介绍本期嘉宾。' },
    { length: 100, evidence: true, text: 'RERANK排序能给文档打分。' },
    { length: 100, evidence: true, text: 'Reranking is another word.' }])
  const result = usable(suggestPaperCutBudget(s, empty, 100, '为什么要用 Rerank排序'))
  assert.deepEqual(result.cut.evidenceIds, ['u1'])
  assert.ok(result.matchedTerms.includes('rerank') && result.matchedTerms.includes('排序'))
  assert.equal(result.approximate, false)
})

test('several short generic evidence lines do not routinely displace the requested technical explanation', () => {
  const s = source([{ length: 1800, evidence: true, text: '向量召回和精排决定检索顺序' },
    { length: 500, evidence: true }, { length: 500, evidence: true }, { length: 500, evidence: true }])
  const result = usable(suggestPaperCutBudget(s, empty, 1800, '向量召回 精排 检索'))
  assert.deepEqual(result.cut.evidenceIds, ['u0'])
})

test('equally relevant cuts prefer connected sentences and use more of a tied duration budget', () => {
  const s = source([{ length: 240, evidence: true }, { length: 240, evidence: true },
    { length: 230, part: 1, evidence: true }])
  assert.deepEqual(usable(suggestPaperCutBudget(s, empty, 490, '')).cut.evidenceIds, ['u0', 'u1'])
  const separate = source([{ length: 400, evidence: true }, { length: 450, part: 1, evidence: true }])
  assert.deepEqual(usable(suggestPaperCutBudget(separate, empty, 450, '')).cut.evidenceIds, ['u1'])
})

test('audible gaps count between adjacent retained sentences but not across original part boundaries', () => {
  const s = source([{ length: 400, anchor: true, evidence: true }, { length: 400, anchor: true, gap: 1700 },
    { length: 300, part: 1, gap: 5000 }])
  assert.deepEqual(suggestPaperCutBudget(s, empty, 2499, ''), { status: 'infeasible', minimumMs: 2500 })
  const result = usable(suggestPaperCutBudget(s, empty, 2500, ''))
  assert.equal(result.cut.durationMs, 2500)
  assert.equal(result.cut.parts.length, 1)
  assert.equal(usable(suggestPaperCutBudget(s, empty, 2800, '')).cut.durationMs, 2800)
})

test('13 disjoint quote runs require a bridge, and the cheapest previously cut bridge stays unavailable', () => {
  const lines = Array.from({ length: 25 }, (_, i) => i % 2 === 0
    ? { length: 100, anchor: true, evidence: i === 0 } : { length: i === 1 ? 220 : 310 })
  const s = source(lines), old = new Set(['u1'])
  assert.deepEqual(suggestPaperCutBudget(s, old, 1500, ''), { status: 'infeasible', minimumMs: 1610 })
  const fit = usable(suggestPaperCutBudget(s, old, 1610, ''))
  assert.equal(fit.cut.parts.length, 12)
  assert.equal(fit.keptAnchorCount, 13)
  assert.equal(fit.cut.durationMs, 1610)
  assert(fit.removedIds.has('u1'))
})

test('reported minimum always matches exhaustive valid cuts on small alternating fixtures', () => {
  for (let seed = 0; seed < 8; seed++) {
    const s = source(Array.from({ length: 8 }, (_, i) => ({ length: 151 + (i * 89 + seed * 31) % 700,
      gap: (i * 17 + seed * 19) % 250, part: i < 4 ? 0 : 1, evidence: i % 3 === 0, anchor: (i + seed) % 5 === 0 })))
    const feasible = []
    for (let mask = 0; mask < 256; mask++) {
      const removed = new Set(s.rows.flatMap((r, i) => mask & (1 << i) ? [r.utterance.utterance_id] : []))
      try { feasible.push(compilePaperCut(s, removed).durationMs) } catch { /* invalid cut */ }
    }
    const min = Math.min(...feasible)
    assert.deepEqual(suggestPaperCutBudget(s, empty, min - 1, ''), { status: 'infeasible', minimumMs: min })
    const fit = usable(suggestPaperCutBudget(s, empty, min, ''))
    assert.equal(fit.cut.durationMs, min)
    assert.deepEqual(compilePaperCut(s, fit.removedIds), fit.cut)
  }
})

test('bounded search marks ranking approximation and always recompiles a legal exact-duration edit', () => {
  const s = source(Array.from({ length: 86 }, (_, i) => ({ length: 200 + (i * 719) % 1300,
    gap: (i * 11) % 250, evidence: i % 2 === 0, anchor: i === 75, text: i % 3 ? '用户的问题' : '检索相关内容' })))
  const result = usable(suggestPaperCutBudget(s, empty, 30000, '检索'))
  assert.equal(result.approximate, true)
  assert.ok(result.cut.durationMs <= 30000)
  assert.deepEqual(compilePaperCut(s, result.removedIds), result.cut)
  assert.ok(result.cut.parts.length <= 12 && result.keptEvidenceCount > 0)
  assert.ok(!result.removedIds.has('u75'))
})
test('oversized input and invalid target are reported separately from an impossible edit', () => {
  const s = source(Array.from({ length: 257 }, () => ({ length: 100, evidence: true })))
  assert.equal(suggestPaperCutBudget(s, empty, 1500, '').status, 'limit')
  assert.equal(suggestPaperCutBudget(s, empty, NaN, '').status, 'invalid')
  assert.equal(suggestPaperCutBudget(s, empty, 0, '').status, 'invalid')
})
