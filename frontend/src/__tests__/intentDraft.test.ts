import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { intentPayload, type IntentSnapshot } from '../intentClips.ts'
import { assessIntentDraft, captureIntentDraft, parseIntentDraft, restoreIntentDraft } from '../intentDraft.ts'

const JOB = '11111111-1111-4111-8111-111111111111', REQUEST = '22222222-2222-4222-8222-222222222222'
function server(): IntentSnapshot {
  const video = '33333333-3333-4333-8333-333333333333', instruction = '保留完整解释，跳过引导'
  return { id: REQUEST, job_id: JOB, video_id: video, instruction, status: 'PLAN_READY', revision: 0, state_version: 3,
    plan_hash: 'c'.repeat(64), confirmed_revisions: [], created_at: '2026-11-07T00:00:00Z', updated_at: '2026-11-07T00:00:10Z',
    budget: { max_text_calls: 6, text_calls_reserved: 1 }, stage: { id: '44444444-4444-4444-8444-444444444444', stage_type: 'SEGMENT', status: 'SUCCEEDED', attempt: 1, error_code: null },
    source: { video_sha256: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 3000 },
    utterances: [{ utterance_id: 'u1', start_ms: 0, end_ms: 1000, text: '私有原句一不进入备份' },
      { utterance_id: 'u2', start_ms: 1000, end_ms: 2000, text: '被排除的私有原句不进入备份' },
      { utterance_id: 'u3', start_ms: 2000, end_ms: 3000, text: '私有原句三不进入备份' }],
    plan: { schema_version: '1.0', request_id: REQUEST, job_id: JOB, video_id: video, instruction,
      source_hash: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 3000,
      goals: [{ goal_id: 'explanation', description: '完整解释' }], constraints: { keep_explanation_complete: true, exclude: ['引导'] },
      provenance: { text_model_id: 'model', prompt_version: 'intent-clips-v1', retrieval_mode: 'bm25', index_hash: 'd'.repeat(64) },
      outputs: [{ output_id: 'explanation-output', goal_id: 'explanation', title: '完整解释', status: 'FOUND', reason: '只使用服务端说明', enabled: false,
        parts: [{ start_utterance_id: 'u1', end_utterance_id: 'u1', start_ms: 0, end_ms: 1000 }, { start_utterance_id: 'u3', end_utterance_id: 'u3', start_ms: 2000, end_ms: 3000 }],
        excluded: [{ utterance_id: 'u2', reason: '只使用服务端排除原因' }], evidence_utterance_ids: ['u1'] }] } }
}
function captured(needsCheck = false) {
  const current = server(), outputs = intentPayload(current.plan!.outputs).outputs
  outputs[0]!.title = ''; outputs[0]!.enabled = true
  outputs[0]!.parts.push({ start_utterance_id: '', end_utterance_id: '', start_ms: -1, end_ms: -1 })
  return { current, outputs, raw: captureIntentDraft(JOB, '下一个尚未提交的新要求', '', {
    snapshot: current, outputs, baseRevision: 0, basePlanHash: current.plan_hash, needsCheck,
  }) }
}
function freeze<T>(value: T): T {
  if (value !== null && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value) }
  return value
}

test('capture contains only editable fields and source identities, never full transcript, permissions, confirmations or pending state', () => {
  const { raw } = captured(true), json = JSON.stringify(raw)
  for (const sensitive of ['utterances', '私有原句', '只使用服务端说明', '只使用服务端排除原因', 'confirmed_revisions', 'status', 'budget', 'pending', 'content_url']) assert.equal(json.includes(sensitive), false)
  const parsed = parseIntentDraft(raw, JOB)!
  assert.equal(parsed.plan!.base_revision, 0); assert.equal(parsed.plan!.base_plan_hash, 'c'.repeat(64)); assert.equal(parsed.plan!.needs_check, true)
  assert.match(assessIntentDraft(raw, JOB, null).description, /前次保存结果仍需核对/)
  assert.match(assessIntentDraft(raw, JOB, server()).description, /前次保存结果仍需核对/)
})

test('instruction and bounded raw budget drafts round-trip without silently replacing invalid form input', () => {
  for (const maxCalls of ['', 0, 25, -2, 1.5, '1e', '  ']) {
    const raw = captureIntentDraft(JOB, '尚未提交的长要求', maxCalls, null), result = restoreIntentDraft(raw, JOB, null)
    assert.equal(result.ok, true)
    if (result.ok) { assert.equal(result.draft.max_calls, maxCalls); assert.equal(result.draft.instruction, '尚未提交的长要求'); assert.equal(result.outputs, null) }
  }
  const invalid = captureIntentDraft(JOB, '仍需保留下载的要求', Number.POSITIVE_INFINITY, null)
  assert.notEqual(invalid, null, 'invalid capture is not a clean signal')
  assert.equal(parseIntentDraft(invalid, JOB), null)
})

test('empty title, incomplete IDs and negative-one pending times survive local restoration untouched', () => {
  const { current, raw, outputs } = captured(), result = restoreIntentDraft(raw, JOB, current)
  assert.equal(result.ok, true)
  if (!result.ok) return
  assert.deepEqual(result.outputs, outputs)
  assert.equal(result.draft.instruction, '下一个尚未提交的新要求'); assert.equal(result.draft.max_calls, '')
  assert.deepEqual(result.outputs![0]!.parts.at(-1), { start_utterance_id: '', end_utterance_id: '', start_ms: -1, end_ms: -1 })
  assert.deepEqual(result.outputs![0]!.evidence_utterance_ids, ['u1'], 'an original evidence subset is preserved, not regenerated from every retained sentence')
  assert.equal(current.plan!.outputs[0]!.title, '完整解释')
})

test('unknown-save backup remains protected even if visible fields were undone to the old baseline', () => {
  const current = server(), raw = captureIntentDraft(JOB, '', 6, { snapshot: current, outputs: current.plan!.outputs,
    baseRevision: 0, basePlanHash: current.plan_hash, needsCheck: true })
  const result = restoreIntentDraft(raw, JOB, current)
  assert.equal(result.ok, true)
  if (result.ok) { assert.equal(result.draft.plan!.needs_check, true); assert.deepEqual(result.outputs, current.plan!.outputs) }
  assert.deepEqual(current.confirmed_revisions, [])
})

test('frozen base hash is not replaced by a later polled revision hash during capture', () => {
  const current = server(); current.revision = 2; current.state_version = 5; current.plan_hash = 'e'.repeat(64)
  const raw = captureIntentDraft(JOB, '', 6, { snapshot: current, outputs: current.plan!.outputs, baseRevision: 0, basePlanHash: 'c'.repeat(64), needsCheck: true })
  const parsed = parseIntentDraft(raw, JOB)!
  assert.equal(parsed.plan!.base_revision, 0); assert.equal(parsed.plan!.base_plan_hash, 'c'.repeat(64))
  assert.equal(restoreIntentDraft(raw, JOB, current).ok, false)
})

test('new server revision or changed hash is download-only even when editable values happen to match', () => {
  for (const onlyHash of [false, true]) {
    const current = server(), raw = captureIntentDraft(JOB, '', 6, { snapshot: current, outputs: current.plan!.outputs, baseRevision: 0, basePlanHash: current.plan_hash, needsCheck: true })
    current.plan_hash = 'e'.repeat(64)
    if (!onlyHash) { current.revision = 1; current.state_version = 4; current.confirmed_revisions = [1] }
    const assessment = assessIntentDraft(raw, JOB, current)
    assert.equal(assessment.compatible, false); assert.match(assessment.reason!, /版本已变化/)
    assert.equal(restoreIntentDraft(raw, JOB, current).ok, false)
    assert.equal(parseIntentDraft(raw, JOB)!.plan!.needs_check, true)
  }
})

test('source, request, job or target changes cannot redirect a backup to another document', () => {
  const changes: ((current: IntentSnapshot) => void)[] = [
    current => { current.id = '55555555-5555-4555-8555-555555555555'; current.plan!.request_id = current.id },
    current => { current.job_id = '55555555-5555-4555-8555-555555555555'; current.plan!.job_id = current.job_id },
    current => { current.video_id = '55555555-5555-4555-8555-555555555555'; current.plan!.video_id = current.video_id },
    current => { current.source.video_sha256 = 'e'.repeat(64); current.plan!.source_hash = current.source.video_sha256 },
    current => { current.source.transcript_hash = 'e'.repeat(64); current.plan!.transcript_hash = current.source.transcript_hash },
    current => { current.source.duration_ms = 4000; current.plan!.duration_ms = 4000 },
    current => { current.plan!.outputs[0]!.output_id = 'different-output' },
  ]
  for (const change of changes) { const { current, raw } = captured(); change(current); assert.equal(restoreIntentDraft(raw, JOB, current).ok, false) }
})

test('authoritative status and reason come from the current server and a backup cannot enable an unverified target', () => {
  const { current, raw } = captured()
  current.plan!.outputs[0]!.status = 'NEEDS_REVIEW'
  assert.equal(restoreIntentDraft(raw, JOB, current).ok, false)
  const draft = parseIntentDraft(raw, JOB)!; draft.plan!.outputs[0]!.enabled = false
  const result = restoreIntentDraft(draft, JOB, current)
  assert.equal(result.ok, true)
  if (result.ok) { assert.equal(result.outputs![0]!.status, 'NEEDS_REVIEW'); assert.equal(result.outputs![0]!.reason, current.plan!.outputs[0]!.reason) }
  const injected = structuredClone(draft) as unknown as Record<string, unknown>
  ;((injected.plan as Record<string, unknown>).outputs as Record<string, unknown>[])[0]!.status = 'FOUND'
  assert.equal(parseIntentDraft(injected, JOB), null)
})

test('all nonempty original IDs are source-bound while incomplete local parts may remain invalid for later repair', () => {
  for (const field of ['evidence_ids', 'excluded_ids'] as const) {
    const { current, raw } = captured(), draft = parseIntentDraft(raw, JOB)!
    draft.plan!.outputs[0]![field] = ['foreign-id']
    assert.equal(restoreIntentDraft(draft, JOB, current).ok, false)
  }
  const { current, raw } = captured(), draft = parseIntentDraft(raw, JOB)!
  draft.plan!.outputs[0]!.parts[0]!.start_utterance_id = 'foreign-id'
  assert.equal(restoreIntentDraft(draft, JOB, current).ok, false)
  draft.plan!.outputs[0]!.parts[0]!.start_utterance_id = 'u3'
  assert.equal(restoreIntentDraft(draft, JOB, current).ok, true, 'a source-bound reversed/unfinished draft is preserved for validation, not silently repaired')
})

test('missing old-request cache still offers explicit retrieval, but an unavailable ready plan cannot be overwritten', () => {
  const { current, raw } = captured()
  assert.equal(assessIntentDraft(raw, JOB, null).compatible, true)
  assert.equal(restoreIntentDraft(raw, JOB, null).ok, false)
  current.plan = null; current.plan_hash = null; current.status = 'CANCELLED'
  assert.equal(restoreIntentDraft(raw, JOB, current).ok, false)
})

test('oversized, duplicate, malformed or authority-bearing backups are rejected without modifying the original', () => {
  const cases: ((value: Record<string, unknown>) => void)[] = [
    value => { value.instruction = '字'.repeat(2001) }, value => { value.max_calls = 'x'.repeat(33) }, value => { value.max_calls = 1e9 },
    value => { value.confirmed = true }, value => { (value.plan as Record<string, unknown>).base_revision = -1 },
    value => { (value.plan as Record<string, unknown>).base_plan_hash = null },
    value => { const plan = value.plan as Record<string, unknown>; plan.outputs = Array(7).fill((plan.outputs as unknown[])[0]) },
    value => { const output = ((value.plan as Record<string, unknown>).outputs as Record<string, unknown>[])[0]!; output.parts = Array(13).fill((output.parts as unknown[])[0]) },
    value => { const output = ((value.plan as Record<string, unknown>).outputs as Record<string, unknown>[])[0]!; output.evidence_ids = ['u1', 'u1'] },
  ]
  for (const change of cases) {
    const { raw } = captured(), value = raw as Record<string, unknown>; change(value)
    const before = JSON.stringify(value)
    assert.equal(parseIntentDraft(value, JOB), null); assert.equal(JSON.stringify(value), before)
  }
})

test('reactive and deeply frozen capture and restore do not clone proxies or share edited arrays with the backup or server', () => {
  const { current, outputs } = captured()
  const before = JSON.stringify({ current, outputs })
  const raw = captureIntentDraft(JOB, '本地要求', 6, { snapshot: reactive(current), outputs: reactive(outputs), baseRevision: 0, basePlanHash: current.plan_hash, needsCheck: false })
  freeze(raw); freeze(current)
  const result = restoreIntentDraft(raw, JOB, current)
  assert.equal(result.ok, true)
  if (result.ok) { result.outputs![0]!.parts[0]!.start_ms = 999; result.outputs![0]!.title = '后续修改' }
  assert.equal(JSON.stringify({ current, outputs }), before)
  assert.equal(parseIntentDraft(raw, JOB)!.plan!.outputs[0]!.parts[0]!.start_ms, 0)
})

test('backup descriptions distinguish editable titles and new instructions even before the old request is fetched', () => {
  const { current, raw } = captured(), a = parseIntentDraft(raw, JOB)!, b = parseIntentDraft(raw, JOB)!
  a.plan!.outputs[0]!.title = '草稿A：缓存原因'; a.instruction = '另剪解决方法A'
  b.plan!.outputs[0]!.title = '草稿B：缓存解决'; b.instruction = '另剪解决方法B'
  for (const serverSnapshot of [current, null]) {
    const first = assessIntentDraft(a, JOB, serverSnapshot), second = assessIntentDraft(b, JOB, serverSnapshot)
    assert.notEqual(first.description, second.description)
    assert.match(first.description, /草稿A：缓存原因/); assert.match(first.description, /另剪解决方法A/)
    assert.match(second.description, /草稿B：缓存解决/); assert.match(second.description, /另剪解决方法B/)
    assert.equal(first.description.includes('只使用服务端'), false)
    assert.equal(first.description.includes('私有原句'), false)
  }
})

test('instruction-only previews normalize physical whitespace and retain HTML as literal plain text', () => {
  const instruction = '  <script>alert("A")</script>\n\t保留   完整解释  '
  const a = captureIntentDraft(JOB, instruction, 6, null), b = captureIntentDraft(JOB, '另一份要求B', 6, null)
  const preview = assessIntentDraft(a, JOB, null)
  assert.ok(preview.description.startsWith('要求：<script>alert("A")</script> 保留 完整解释'))
  assert.equal(preview.description.includes('\n'), false); assert.equal(preview.description.includes('&lt;script&gt;'), false)
  assert.notEqual(preview.description, assessIntentDraft(b, JOB, null).description)
  assert.equal(parseIntentDraft(a, JOB)!.instruction, instruction, 'display normalization must not rewrite the backed-up input')
})

test('combined editable previews have a short Unicode-safe bound without changing the original long input', () => {
  const { current, raw } = captured(), value = parseIntentDraft(raw, JOB)!
  value.plan!.outputs[0]!.title = '🙂'.repeat(50); value.instruction = '中'.repeat(200)
  const preview = assessIntentDraft(value, JOB, current)
  const expected = `标题：${'🙂'.repeat(31)}…；新要求：${'中'.repeat(31)}…`
  assert.ok(preview.description.startsWith(expected))
  assert.ok([...expected].length >= 60 && [...expected].length <= 80)
  assert.equal(value.plan!.outputs[0]!.title, '🙂'.repeat(50)); assert.equal(value.instruction, '中'.repeat(200))
})

test('empty editable titles and budget-only raw input have explicit distinguishing descriptions', () => {
  const { current, raw } = captured(), value = parseIntentDraft(raw, JOB)!
  value.plan!.outputs[0]!.title = ' \n\t '
  assert.match(assessIntentDraft(value, JOB, current).description, /空标题待修/)
  assert.match(assessIntentDraft(captureIntentDraft(JOB, '', '', null), JOB, null).description, /请求上限原输入：（空）/)
  assert.match(assessIntentDraft(captureIntentDraft(JOB, '', 25, null), JOB, null).description, /请求上限原输入：25/)
})
