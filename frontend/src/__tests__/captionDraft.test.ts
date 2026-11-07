import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { assessCaptionDraft, captionDraftValue, captionSaveCheck, restoreCaptionDraft, type CaptionDraftValue } from '../captionDraft.ts'
import { captionPayload, captionValidation, copyCaptionCues, type CaptionSnapshot } from '../captions.ts'

function snapshot(): CaptionSnapshot {
  const exportId = '11111111-1111-4111-8111-111111111111', clipId = '22222222-2222-4222-8222-222222222222'
  return { export_id: exportId, clip_id: clipId, clip_title: '字幕备份合成样本',
    content_url: `/api/exports/${exportId}/clips/${clipId}/content`, revision: 0, status: 'DRAFT', confirmed_revisions: [], content_hash: 'a'.repeat(64),
    source: { snapshot_hash: 'b'.repeat(64), clip_sha256: 'c'.repeat(64), transcript_hash: 'd'.repeat(64), duration_ms: 4000,
      media_duration_ms: 4017, timing_basis: 'SOURCE_EDL_SENTENCE_MS', parts: [{ start_ms: 10000, end_ms: 14000 }] },
    cues: Array.from({ length: 3 }, (_, index) => ({ id: `c00000${index + 1}`, part_index: 0, source_utterance_id: `u${index}`,
      source_start_ms: index ? 10000 + index * 1000 : 9000, source_end_ms: 11000 + index * 1000,
      retained_start_ms: 10000 + index * 1000, retained_end_ms: 11000 + index * 1000,
      start_ms: index * 1000, end_ms: (index + 1) * 1000, text: `字幕 ${index}`,
      source_text: `ONLY_FROM_FRESH_SOURCE_${index}`, boundary_partial: index === 0, reviewed: false, requires_review: index === 0 })) }
}
function edited() {
  const source = snapshot(), cues = copyCaptionCues(source.cues)
  cues[0]!.text = '修改后的字幕'
  return { source, cues, value: captionDraftValue(source, cues, {}) }
}
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value) }
  return value
}

test('caption backups contain editable data and source identity without original text or approval state', () => {
  const { source, cues } = edited(), value = captionDraftValue(reactive(source), reactive(cues), {})
  assert.deepEqual(Object.keys(value.cues[0]!).sort(), ['end_ms', 'id', 'reviewed', 'start_ms', 'text'])
  assert.equal(JSON.stringify(value).includes('source_text'), false)
  assert.equal(JSON.stringify(value).includes('ONLY_FROM_FRESH_SOURCE'), false)
  assert.equal(JSON.stringify(value).includes('acknowledged'), false)
  cues[0]!.text = '之后再次编辑'; source.source.parts[0]!.start_ms = 1
  assert.equal(value.cues[0]!.text, '修改后的字幕'); assert.equal(value.source.parts[0]!.start_ms, 10000)
})

test('history descriptions distinguish editable text with a bounded literal preview while preserving warnings', () => {
  const { source, value } = edited(), another = structuredClone(value)
  value.cues[0]!.text = '<script>字面文本</script>\n第二行\t  第三行'
  value.time_drafts.c000001 = { start_ms: '00:' }; value.needs_check = true
  another.cues[0]!.text = '另一份字幕草稿'
  const before = JSON.stringify(value), assessment = assessCaptionDraft(value, source)
  assert.notEqual(assessment.description, assessCaptionDraft(another, source).description)
  assert.match(assessment.description, /3 条保留字幕，0 条删除记录，含待修正时间，保存结果待核对/)
  assert.ok(assessment.description.includes('首条字幕：<script>字面文本</script> 第二行 第三行'))
  assert.equal(assessment.compatible, true)
  assert.equal(JSON.stringify(value), before)
  value.cues[0]!.text = ' \n\t '
  const empty = assessCaptionDraft(value, source).description
  assert.match(empty, /首条字幕含空文本/)
  assert.equal(empty.includes(source.cues[0]!.source_text), false, 'never substitute original transcript text')
  value.cues[0]!.text = '字'.repeat(100)
  assert.ok(assessCaptionDraft(value, source).description.endsWith('字'.repeat(72) + '…'))
  assert.equal(value.cues[0]!.text.length, 100, 'only the display excerpt is shortened')
})

test('restore uses fresh immutable fields and derives review requirement from the restored reviewed flag', () => {
  const { source, value } = edited()
  value.cues[0]!.reviewed = true
  const restored = restoreCaptionDraft(value, source)!
  assert.equal(restored.cues[0]!.reviewed, true); assert.equal(restored.cues[0]!.requires_review, false)
  assert.equal(restored.cues[0]!.boundary_partial, true)
  assert.equal(restored.cues[0]!.source_text, source.cues[0]!.source_text)
  restored.cues[0]!.text = '恢复后独立编辑'
  assert.equal(source.cues[0]!.text, '字幕 0'); assert.equal(value.cues[0]!.text, '修改后的字幕')
})

test('empty text and incomplete raw time remain recoverable drafts and still fail ordinary save validation', () => {
  const source = snapshot(), cues = copyCaptionCues(source.cues)
  cues[0]!.text = ''; cues[1]!.start_ms = 1500
  const value = captionDraftValue(source, cues, { c000001: { start_ms: '00:', end_ms: '' }, c000002: { start_ms: ' 00:01.500 ' } })
  assert.equal(assessCaptionDraft(value, source).compatible, true)
  const restored = restoreCaptionDraft(value, source)!
  assert.equal(restored.cues[0]!.text, '')
  assert.deepEqual(restored.timeDrafts, value.time_drafts)
  assert.equal(restored.cues[0]!.start_ms, source.cues[0]!.start_ms)
  assert.ok(captionValidation(restored.cues, 4000, restored.timeDrafts))
  const whitespace = captionDraftValue(source, cues, { c000001: { start_ms: '   ' } })
  assert.equal(restoreCaptionDraft(whitespace, source)!.timeDrafts.c000001!.start_ms, '   ')
})

test('out of range or overlapping draft times remain pending correction without receiving server permission', () => {
  const { source, value } = edited()
  value.cues[0]!.end_ms = 6000; value.cues[1]!.start_ms = 100
  const restored = restoreCaptionDraft(value, source)!
  assert.equal(restored.cues[0]!.end_ms, 6000)
  assert.ok(captionValidation(restored.cues, source.source.duration_ms))
  assert.deepEqual(source.confirmed_revisions, [])
})

test('deletion stack restores editable values and undo positions against this base revision, not original r0', () => {
  const source = snapshot(), cues = copyCaptionCues(source.cues)
  const first = cues.splice(1, 1)[0]!; first.text = '删除前编辑'; first.reviewed = true
  const second = cues.splice(0, 1)[0]!
  const value = captionDraftValue(source, cues, {}, [{ cue: first, index: 1 }, { cue: second, index: 0 }])
  const restored = restoreCaptionDraft(value, source)!
  assert.equal(restored.deleted[0]!.cue.text, '删除前编辑')
  while (restored.deleted.length) { const item = restored.deleted.pop()!; restored.cues.splice(item.index, 0, item.cue) }
  assert.deepEqual(restored.cues.map(cue => cue.id), source.cues.map(cue => cue.id))
  const laterBase = snapshot(); laterBase.revision = 3; laterBase.cues.splice(1, 1)
  const changed = copyCaptionCues(laterBase.cues); changed[0]!.text = '当前r3上的草稿'
  assert.ok(restoreCaptionDraft(captionDraftValue(laterBase, changed, {}), laterBase))
})

test('deleting every cue is a valid local draft while undo metadata stays source bound', () => {
  const source = snapshot(), cues = copyCaptionCues(source.cues), deleted = []
  while (cues.length) deleted.push({ cue: cues.shift()!, index: 0 })
  const restored = restoreCaptionDraft(captionDraftValue(source, cues, {}, deleted), source)!
  assert.deepEqual(restored.cues, []); assert.equal(restored.deleted.length, 3)
  assert.equal(captionValidation(restored.cues, 4000), '')
})

test('source hashes, logical and media duration, parts, owner and exact base hash bind restoration', () => {
  const changes: ((value: CaptionDraftValue) => void)[] = [
    value => { value.export_id = '99999999-9999-4999-8999-999999999999' },
    value => { value.clip_id = '99999999-9999-4999-8999-999999999999' },
    value => { value.source.clip_sha256 = 'e'.repeat(64) }, value => { value.source.transcript_hash = 'e'.repeat(64) },
    value => { value.source.snapshot_hash = 'e'.repeat(64) }, value => { value.source.media_duration_ms++ },
    value => { value.source.duration_ms++; value.source.parts[0]!.end_ms++ },
    value => { value.source.parts[0]!.start_ms++; value.source.parts[0]!.end_ms++ },
    value => { value.base_revision++ }, value => { value.content_hash = 'e'.repeat(64) },
  ]
  for (const change of changes) {
    const { source, value } = edited(); change(value)
    assert.equal(assessCaptionDraft(value, source).compatible, false)
    assert.equal(restoreCaptionDraft(value, source), null)
  }
})

test('newer matching saved content is reported as already saved without restoring or upgrading its base', () => {
  const { source, value } = edited(), saved = structuredClone(source)
  saved.revision = 1; saved.content_hash = 'f'.repeat(64); saved.cues[0]!.text = value.cues[0]!.text
  assert.match(assessCaptionDraft(value, saved).reason!, /无需恢复/)
  assert.equal(restoreCaptionDraft(value, saved), null)
  assert.equal(value.base_revision, 0)
  saved.cues[0]!.text = '另一客户端不同内容'
  assert.match(assessCaptionDraft(value, saved).reason!, /版本已变化/)
  assert.equal(restoreCaptionDraft(value, saved), null)
})

test('already saved deletion can be recognized even though removed IDs no longer exist in the latest revision', () => {
  const source = snapshot(), cues = copyCaptionCues(source.cues), removed = cues.splice(1, 1)[0]!
  const value = captionDraftValue(source, cues, {}, [{ cue: removed, index: 1 }]), saved = structuredClone(source)
  saved.cues.splice(1, 1); saved.revision = 1; saved.content_hash = 'e'.repeat(64)
  assert.match(assessCaptionDraft(value, saved).reason!, /无需恢复/)
  assert.equal(restoreCaptionDraft(value, saved), null)
})

test('already saved comparison uses editable values rather than JSON object property insertion order', () => {
  const { source, value } = edited(), saved = structuredClone(source)
  saved.revision = 1; saved.content_hash = 'f'.repeat(64); saved.cues[0]!.text = value.cues[0]!.text
  value.cues = value.cues.map(cue => Object.fromEntries(Object.entries(cue).reverse()) as typeof cue)
  value.source = Object.fromEntries(Object.entries(value.source).reverse()) as typeof value.source
  assert.match(assessCaptionDraft(value, saved).reason!, /无需恢复/)
  assert.equal(restoreCaptionDraft(value, saved), null)
})

test('unknown, duplicate, reordered, omitted and forged deletion IDs or insertion indexes are rejected', () => {
  const changes: ((value: CaptionDraftValue) => void)[] = [
    value => { value.cues[0]!.id = 'unknown' }, value => { value.cues[0]!.id = value.cues[1]!.id },
    value => { value.cues.reverse() }, value => { value.cues.pop() },
    value => { value.deleted.push({ cue: { ...value.cues[0]! }, index: 0 }) },
    value => { value.deleted.push({ cue: value.cues.shift()!, index: 2 }) },
    value => { value.deleted.push({ cue: value.cues.shift()!, index: 100 }) },
  ]
  for (const change of changes) { const { source, value } = edited(); change(value); assert.equal(restoreCaptionDraft(value, source), null) }
})

test('unknown or immutable metadata, approval and playback keys cannot enter restored data', () => {
  for (const [where, field, forged] of [
    ['cue', 'source_text', '伪造原句'], ['cue', 'boundary_partial', false], ['cue', 'requires_review', false],
    ['cue', 'source_utterance_id', 'uX'], ['root', 'acknowledged', true], ['root', 'pending', false], ['root', 'currentTime', 2],
  ] as const) {
    const { source, value } = edited()
    Object.assign(where === 'cue' ? value.cues[0]! : value, { [field]: forged })
    assert.equal(restoreCaptionDraft(value, source), null)
  }
})

test('prototype keys, inherited rows, accessors and sparse arrays are rejected without invoking getters', () => {
  const { source, value } = edited()
  const polluted = JSON.parse(JSON.stringify(value).replace('"schema_version":1', '"schema_version":1,"__proto__":{"polluted":true}'))
  assert.equal(restoreCaptionDraft(polluted, source), null)
  const inherited = Object.create(value)
  assert.equal(restoreCaptionDraft(inherited, source), null)
  const fields = structuredClone(value); fields.time_drafts = JSON.parse('{"constructor":{"start_ms":"bad"}}')
  assert.equal(restoreCaptionDraft(fields, source), null)
  let called = false
  Object.defineProperty(value.cues[0], 'text', { enumerable: true, get() { called = true; return 'bad' } })
  assert.equal(restoreCaptionDraft(value, source), null); assert.equal(called, false)
  const sparse = edited(); delete sparse.value.cues[1]
  assert.equal(restoreCaptionDraft(sparse.value, sparse.source), null)
})

test('unsafe numbers and oversized individual values, raw time or total live/deleted/raw UTF8 are bounded', () => {
  for (const number of [-1, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, 1.5]) {
    const { source, value } = edited(); value.cues[0]!.start_ms = number
    assert.equal(restoreCaptionDraft(value, source), null)
  }
  for (const field of ['text', 'time'] as const) {
    const { source, value } = edited()
    if (field === 'text') value.cues[0]!.text = 'x'.repeat(2001)
    else value.time_drafts.c000001 = { start_ms: 'x'.repeat(129) }
    assert.equal(restoreCaptionDraft(value, source), null)
  }
  const { source, value } = edited()
  value.cues = Array.from({ length: 600 }, (_, index) => ({ ...value.cues[0]!, id: `c${index}`, text: '字'.repeat(2000) }))
  assert.equal(restoreCaptionDraft(value, source), null)
  value.cues = [{ ...value.cues[0]!, text: '' }]
  value.deleted = Array.from({ length: 600 }, (_, index) => ({ index, cue: { ...value.cues[0]!, id: `d${index}`, text: '字'.repeat(2000) } }))
  assert.match(assessCaptionDraft(value, source).description, /范围/)
  value.deleted = []
  value.cues = Array.from({ length: 10000 }, (_, index) => ({ ...value.cues[0]!, id: `c${index}`, text: '' }))
  value.time_drafts = Object.fromEntries(value.cues.map(cue => [cue.id, { start_ms: 'x'.repeat(128), end_ms: 'x'.repeat(128) }]))
  assert.match(assessCaptionDraft(value, source).description, /范围/)
  value.cues.push({ ...value.cues[0]!, id: 'overflow' })
  assert.equal(restoreCaptionDraft(value, source), null)
})

test('assessment waits for the fresh server document and all helper operations leave inputs unchanged', () => {
  const { source, value } = edited(), before = JSON.stringify({ source, value })
  freeze(source); freeze(value)
  assert.equal(assessCaptionDraft(value, null).compatible, false)
  assert.equal(assessCaptionDraft(value, source).compatible, true)
  const restored = restoreCaptionDraft(value, source)!
  assert.deepEqual(captionPayload(restored.cues), { cues: value.cues })
  assert.equal(JSON.stringify({ source, value }), before)
  assert.equal(Object.hasOwn(restored, 'acknowledged'), false)
})

test('unknown-save state survives undo to baseline and an explicit local draft restore', () => {
  const source = snapshot(), value = captionDraftValue(source, source.cues, {}, [], true)
  assert.equal(value.needs_check, true)
  assert.equal(assessCaptionDraft(value, source).compatible, true, 'equal old baseline does not discard an unknown-save backup')
  const restored = restoreCaptionDraft(value, source)!
  assert.equal(restored.needsCheck, true)
  assert.deepEqual(captionPayload(restored.cues), captionPayload(source.cues))
  assert.equal(Object.hasOwn(restored, 'pending'), false)
  const saved = structuredClone(source); saved.revision = 1; saved.content_hash = 'e'.repeat(64); saved.cues[0]!.text = '已提交但丢回执的新文字'
  assert.equal(captionSaveCheck(source, saved, restored.cues, restored.timeDrafts), 'conflict')
  assert.equal(assessCaptionDraft(value, saved).compatible, false)
})

test('a lost save is only acknowledged by a same-source current or newer full payload match', () => {
  const { source, cues } = edited(), saved = structuredClone(source)
  saved.revision = 1; saved.content_hash = 'e'.repeat(64); saved.cues[0]!.text = cues[0]!.text
  assert.equal(captionSaveCheck(source, saved, cues, {}), 'saved')
  const different = structuredClone(saved); different.cues[1]!.reviewed = true
  assert.equal(captionSaveCheck(source, different, cues, {}), 'conflict')
  const another = copyCaptionCues(cues); another[0]!.text = '等待期间再次编辑'
  assert.equal(captionSaveCheck(source, saved, another, {}), 'conflict')
  const deleted = copyCaptionCues(cues); deleted.pop()
  assert.equal(captionSaveCheck(source, saved, deleted, {}), 'conflict')
})

test('an unchanged old head permits an explicit retry observation while preserving editable input and raw strings', () => {
  const { source, cues } = edited(), raw = { c000001: { start_ms: '00:' } }, before = JSON.stringify({ source, cues, raw })
  assert.equal(captionSaveCheck(source, structuredClone(source), cues, raw), 'retry')
  assert.equal(JSON.stringify({ source, cues, raw }), before)
  assert.equal(captionSaveCheck(source, source, source.cues, raw), 'retry', 'invalid raw input can never be considered clean')
  const saved = structuredClone(source); saved.revision = 1; saved.content_hash = 'e'.repeat(64); saved.cues[0]!.text = cues[0]!.text
  assert.equal(captionSaveCheck(source, saved, cues, raw), 'conflict', 'same numeric times do not replace an incomplete text input')
})

test('save-result checks reject changed source, stale heads, same-revision content changes and lost confirmation history', () => {
  const source = snapshot(), incoming = structuredClone(source)
  incoming.source.clip_sha256 = 'f'.repeat(64)
  assert.equal(captionSaveCheck(source, incoming, source.cues, {}), 'conflict')
  const known = structuredClone(source); known.revision = 1; known.content_hash = 'e'.repeat(64)
  assert.equal(captionSaveCheck(known, source, source.cues, {}), 'conflict')
  const wrongHash = structuredClone(source); wrongHash.content_hash = 'f'.repeat(64)
  assert.equal(captionSaveCheck(source, wrongHash, source.cues, {}), 'conflict')
  const wrongPayload = structuredClone(source); wrongPayload.cues[0]!.text = '相同修订伪造内容'
  assert.equal(captionSaveCheck(source, wrongPayload, wrongPayload.cues, {}), 'conflict')
  const confirmed = structuredClone(source); confirmed.confirmed_revisions = [0]
  assert.equal(captionSaveCheck(confirmed, source, source.cues, {}), 'conflict')
})
