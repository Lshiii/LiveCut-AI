import test from 'node:test'
import assert from 'node:assert/strict'
import { isProxy, reactive } from 'vue'
import { copyCaptionCues, type CaptionCue } from '../captions.ts'
import { applyCaptionTextReplacement, captionTextMatches, previewCaptionTextReplacement, undoCaptionTextReplacement } from '../captionTextTools.ts'

function cue(text: string, index = 0): CaptionCue {
  return { id: `c${String(index + 1).padStart(6, '0')}`, part_index: 0, source_utterance_id: `u${index}`,
    source_start_ms: index * 1000, source_end_ms: (index + 1) * 1000,
    retained_start_ms: index * 1000, retained_end_ms: (index + 1) * 1000,
    start_ms: index * 1000, end_ms: (index + 1) * 1000, text, source_text: `ORIGINAL_ASR_${index}`,
    boundary_partial: index === 0, reviewed: false, requires_review: index === 0 }
}
const preview = (cues: CaptionCue[], query = 'cash', replacement = 'Cache', ignoreCase = false, ids = cues.map(cue => cue.id)) =>
  previewCaptionTextReplacement(cues, query, replacement, ignoreCase, ids)
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value) }
  return value
}

test('search finds literal non-overlapping matches and preserves whitespace within a nonblank query', () => {
  assert.equal(captionTextMatches('aaaaa', 'aa', false), 2)
  assert.equal(captionTextMatches('x x xx x ', ' x ', false), 2)
  for (const query of ['', ' ', '\t\n', 'x'.repeat(201)]) assert.equal(captionTextMatches('x x', query, false), 0)
  assert.equal(captionTextMatches('x'.repeat(200), 'x'.repeat(200), false), 1)
})

test('case-insensitive Unicode search keeps original offsets and uses explicit opt-in', () => {
  assert.equal(captionTextMatches('cash CASH Cash', 'cash', false), 1)
  assert.equal(captionTextMatches('cash CASH Cash', 'cash', true), 3)
  assert.equal(captionTextMatches('İi KKk 😀😀', 'k', true), 3)
  assert.equal(captionTextMatches('İi KKk 😀😀', '😀', false), 2)
  const cues = [cue('İi KKk 😀😀')], value = preview(cues, 'k', 'Cache', true)
  assert.equal(value.changes[0]!.after, 'İi CacheCacheCache 😀😀')
})

test('regular-expression metacharacters, dollar references, backslashes and markup stay literal', () => {
  const query = '.*+?^${}()|[]\\', replacement = '$& $1 $$ \\ <script>alert(1)</script> 😀\n第二行'
  const cues = [cue(`before ${query} ${query} after`)]
  assert.equal(captionTextMatches(cues[0]!.text, query, false), 2)
  const value = preview(cues, query, replacement)
  assert.equal(value.error, '')
  assert.equal(value.changes[0]!.after, `before ${replacement} ${replacement} after`)
  assert.equal(applyCaptionTextReplacement(cues, value).cues![0]!.text, `before ${replacement} ${replacement} after`)
})

test('a query containing a newline matches literally without matching another physical line', () => {
  const cues = [cue('one\ntwo one two')], value = preview(cues, 'one\ntwo', '第一行\n第二行')
  assert.equal(value.occurrences, 1)
  assert.equal(value.changes[0]!.after, '第一行\n第二行 one two')
})

test('preview covers a full 125-cue document rather than only a 50-cue page', () => {
  const cues = Array.from({ length: 125 }, (_, index) => cue(`cash cash ${index}`, index))
  const value = preview(cues)
  assert.equal(value.changes.length, 125)
  assert.equal(value.occurrences, 250)
  assert.equal(value.changes[124]!.id, cues[124]!.id)
  assert.equal(value.changes[124]!.after, 'Cache Cache 124')
  assert.equal(cues[124]!.text, 'cash cash 124')
})

test('explicit scope is independent of source order and cannot replace outside that scope', () => {
  const cues = [cue('cash', 0), cue('cash', 1), cue('cash', 2)]
  const ids = [cues[2]!.id, cues[0]!.id], value = preview(cues, 'cash', 'Cache', false, ids)
  assert.deepEqual(value.scopeIds, ids)
  assert.deepEqual(value.changes.map(change => change.id), [cues[0]!.id, cues[2]!.id])
  ids.pop()
  assert.equal(value.scopeIds.length, 2)
  const result = applyCaptionTextReplacement(cues, value).cues!
  assert.deepEqual(result.map(cue => cue.text), ['Cache', 'cash', 'Cache'])
})

test('empty scope, no matches and identical replacements do not report changed occurrences', () => {
  const cues = [cue('cash CASH')]
  for (const value of [preview(cues, 'missing'), preview(cues, 'cash', 'cash'), preview(cues, 'cash', 'Cache', false, [])]) {
    assert.equal(value.error, ''); assert.deepEqual(value.changes, []); assert.equal(value.occurrences, 0)
    assert.equal(applyCaptionTextReplacement(cues, value).cues, null)
  }
  assert.equal(preview(cues, 'cash', 'cash', true).occurrences, 2)
})

test('invalid queries and replacement settings fail without a usable patch', () => {
  const cues = [cue('cash')]
  for (const query of ['', ' ', 'x'.repeat(201)]) {
    const value = preview(cues, query)
    assert.ok(value.error); assert.deepEqual(value.changes, [])
  }
  assert.ok(preview(cues, 'cash', 'x'.repeat(2001)).error)
  assert.ok(previewCaptionTextReplacement(cues, 'cash', 'Cache', 'yes' as unknown as boolean, [cues[0]!.id]).error)
})

test('unknown or duplicate scope IDs and duplicate document IDs cannot generate a patch', () => {
  const cues = [cue('cash')]
  for (const ids of [['unknown'], [cues[0]!.id, cues[0]!.id]]) {
    const value = preview(cues, 'cash', 'Cache', false, ids)
    assert.ok(value.error); assert.deepEqual(value.changes, [])
  }
  assert.ok(preview([cue('cash'), cue('cash')]).error)
  assert.ok(preview([{ ...cue('cash'), id: '__proto__' }]).error)
})

test('removing a substring is allowed but an empty or blank resulting cue rejects the entire batch', () => {
  assert.equal(preview([cue('cash prefix')], 'cash', '').changes[0]!.after, ' prefix')
  const cues = [cue('cash prefix'), cue('cash cash', 1)]
  for (const replacement of ['', ' \t ']) {
    const value = preview(cues, 'cash', replacement)
    assert.ok(value.error); assert.deepEqual(value.changes, []); assert.equal(value.occurrences, 0)
    assert.equal(applyCaptionTextReplacement(cues, value).cues, null)
    assert.equal(cues[0]!.text, 'cash prefix')
  }
})

test('per-cue length is checked after literal replacement and rejects partial changes', () => {
  const cues = [cue('cash'), cue('cash'.repeat(500), 1)]
  const value = preview(cues, 'cash', 'Cache')
  assert.ok(value.error); assert.deepEqual(value.changes, [])
  assert.equal(preview([cue('a')], 'a', 'x'.repeat(2000)).error, '')
})

test('the aggregate limit counts UTF-8 bytes rather than JavaScript character count', () => {
  const cues = Array.from({ length: 350 }, (_, index) => cue('x'.repeat(2000), index))
  const tooLarge = preview(cues, 'x', '字')
  assert.ok(tooLarge.error.includes('2 MiB')); assert.deepEqual(tooLarge.changes, []); assert.equal(tooLarge.occurrences, 0)
  assert.equal(preview(cues.slice(0, 349), 'x', '字').error, '')
})

test('apply copies frozen or reactive cues and changes only their editable text', () => {
  const cues = freeze([cue('cash'), cue('cash', 1)]), value = preview(cues)
  const result = applyCaptionTextReplacement(cues, freeze(value)).cues!
  assert.equal(result[0]!.text, 'Cache'); assert.equal(cues[0]!.text, 'cash')
  result.forEach((item, index) => assert.deepEqual({ ...item, text: cues[index]!.text }, cues[index]))
  const reactiveCues = reactive(copyCaptionCues(cues)), copied = applyCaptionTextReplacement(reactiveCues, preview(reactiveCues)).cues!
  assert.equal(isProxy(copied[0]), false)
  copied[0]!.reviewed = true
  assert.equal(reactiveCues[0]!.reviewed, false)
})

test('apply refuses stale changed text, a new match in previously unchanged scope, or missing cues atomically', () => {
  const cues = [cue('cash'), cue('unrelated', 1)], value = preview(cues)
  const changed = copyCaptionCues(cues); changed[0]!.text = 'cash later'
  const newMatch = copyCaptionCues(cues); newMatch[1]!.text = 'new cash'
  for (const current of [changed, newMatch, cues.slice(1)]) {
    const before = JSON.stringify(current)
    assert.equal(applyCaptionTextReplacement(current, value).cues, null)
    assert.equal(JSON.stringify(current), before)
  }
})

test('apply recomputes every patch field and refuses forged text, count, order or scope', () => {
  const cues = [cue('cash'), cue('cash', 1)], value = preview(cues)
  const forged = [
    { ...value, occurrences: 99 },
    { ...value, changes: [{ ...value.changes[0]!, after: 'unexpected' }, value.changes[1]!] },
    { ...value, changes: [{ ...value.changes[0]!, occurrences: 2 }, value.changes[1]!] },
    { ...value, changes: [...value.changes].reverse() },
    { ...value, scopeIds: [cues[0]!.id] },
    { ...value, changes: [...value.changes, value.changes[0]!] },
    { ...value, extra: true },
  ]
  for (const item of forged) assert.equal(applyCaptionTextReplacement(cues, item).cues, null)
})

test('apply preserves later nontext fields while UI ownership guards remain responsible for the whole draft', () => {
  const cues = [cue('cash')], value = preview(cues)
  cues[0]!.start_ms = 5; cues[0]!.reviewed = true; cues[0]!.requires_review = false
  const result = applyCaptionTextReplacement(cues, value).cues!
  assert.equal(result[0]!.text, 'Cache'); assert.equal(result[0]!.start_ms, 5)
  assert.equal(result[0]!.reviewed, true); assert.equal(result[0]!.requires_review, false)
})

test('undo restores every affected text while preserving unrelated text, timing and reviewed edits', () => {
  const cues = [cue('cash cash'), cue('cash', 1), cue('unrelated', 2)], value = preview(cues)
  const applied = applyCaptionTextReplacement(cues, value).cues!
  applied[0]!.start_ms = 7; applied[0]!.reviewed = true; applied[0]!.requires_review = false
  applied[2]!.text = 'later manual edit'
  const before = JSON.stringify(applied), undone = undoCaptionTextReplacement(applied, value.changes).cues!
  assert.deepEqual(undone.map(cue => cue.text), ['cash cash', 'cash', 'later manual edit'])
  assert.equal(undone[0]!.start_ms, 7); assert.equal(undone[0]!.reviewed, true); assert.equal(undone[0]!.requires_review, false)
  assert.equal(undone[0]!.source_text, 'ORIGINAL_ASR_0')
  assert.equal(JSON.stringify(applied), before)
})

test('undo refuses the whole operation when any affected cue was edited, removed or duplicated', () => {
  const cues = [cue('cash'), cue('cash', 1)], value = preview(cues)
  const applied = applyCaptionTextReplacement(cues, value).cues!
  const edited = copyCaptionCues(applied); edited[1]!.text = 'manual edit'
  for (const current of [edited, applied.slice(1), [applied[0]!, applied[0]!]]) {
    const before = JSON.stringify(current)
    assert.equal(undoCaptionTextReplacement(current, value.changes).cues, null)
    assert.equal(JSON.stringify(current), before)
  }
})

test('undo validates records and refuses duplicate IDs, blank/oversized text, invalid counts or no changes', () => {
  const cues = [cue('cash')], value = preview(cues), applied = applyCaptionTextReplacement(cues, value).cues!
  const item = value.changes[0]!
  for (const changes of [[], [item, item], [{ ...item, before: '' }], [{ ...item, before: ' ' }],
    [{ ...item, before: 'x'.repeat(2001) }], [{ ...item, occurrences: 0 }], [{ ...item, before: item.after }]])
    assert.equal(undoCaptionTextReplacement(applied, changes).cues, null)
})

test('undo checks total UTF-8 bytes after preserving later edits outside its patch', () => {
  const cues = Array.from({ length: 350 }, (_, index) => cue(index === 349 ? 'x'.repeat(2000) : '字'.repeat(2000), index))
  const value = preview(cues, '字', 'x', false, [cues[0]!.id])
  assert.equal(value.error, '')
  const applied = applyCaptionTextReplacement(cues, value).cues!
  applied[349]!.text = '字'.repeat(2000)
  assert.ok(undoCaptionTextReplacement(applied, value.changes).error.includes('2 MiB'))
  assert.equal(applied[0]!.text, 'x'.repeat(2000))
})

test('runtime malformed inputs return errors rather than throwing or partially mutating', () => {
  const cues = [cue('cash')]
  assert.equal(applyCaptionTextReplacement(cues, null as never).cues, null)
  assert.equal(undoCaptionTextReplacement(cues, null as never).cues, null)
  assert.ok(previewCaptionTextReplacement(null as never, 'cash', 'Cache', false, []).error)
  assert.equal(captionTextMatches(null as never, 'cash', false), 0)
})
