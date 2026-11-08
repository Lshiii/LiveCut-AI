import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { intentCanConfirm, intentCanExport, intentPayload, newestIntentSnapshot, parseIntentSnapshot, type IntentSnapshot } from '../intentClips.ts'
import { cacheLegacyIntentDetails, intentHistoryFromDetail, mergeVerifiedIntentDetail } from '../intentHistory.ts'
import { intentSaveRecovered } from '../intentSaveRecovery.ts'
import { captureIntentDraft, parseIntentDraft, restoreIntentDraft } from '../intentDraft.ts'
import { assertStoredManualReviewReceipt, effectiveIntentSelection, inspectManualReviewRevisionConflict, IntentManualReviewError,
  manualReviewCanonical, manualReviewConfirmation, manualReviewFreshConfirmation, manualReviewInput, manualReviewSelectedIds,
  manualReviewVerified, parseIntentManualReview, parseIntentManualReviewInput, parseIntentManualReviewReceipt, persistManualReviewReceipt,
  prepareManualReviewReceipt, recoverManualReviewReceipt, removeManualReviewReceipt, restoreManualReviewReceipt, sameManualReviewReceipt,
  validManualReviewNote, verifyIntentManualReviewReceipt, verifyIntentManualReviewSnapshot } from '../intentManualReview.ts'
import { clone, hash, manualFixture } from './support/manualReviewFixture.ts'

const failure = (code: string, status = 404) => Object.assign(new Error(code), { code, status })
const rejectedCode = (code: string) => (error: unknown) => error instanceof IntentManualReviewError && error.code === code
function storage(raw: string | null = null) {
  let value = raw, writes = 0, removes = 0
  return { read: () => value, write: (next: string) => { writes++; value = next }, remove: () => { removes++; value = null },
    get value() { return value }, get writes() { return writes }, get removes() { return removes } }
}

test('a manual version remains NEEDS_REVIEW and disabled until exact plan/output/review hashes are verified', async () => {
  const { reviewed } = manualFixture(), parsed = parseIntentSnapshot(reviewed, reviewed.job_id, reviewed.id)
  assert.equal(manualReviewVerified(parsed), false)
  assert.equal(intentCanConfirm(parsed, parsed.plan!.outputs, false, false), false)
  assert.throws(() => manualReviewConfirmation(parsed), IntentManualReviewError)
  await verifyIntentManualReviewSnapshot(parsed)
  assert.equal(manualReviewVerified(reactive(parsed)), true, 'Vue wrapping preserves content-bound verification')
  assert.deepEqual(manualReviewSelectedIds(parsed), ['output-cause', 'output-solution'])
  assert.equal(intentCanConfirm(parsed, parsed.plan!.outputs, false, false), true)
  assert.deepEqual(parsed.plan!.outputs.map(output => [output.status, output.enabled]), [['NEEDS_REVIEW', false], ['NEEDS_REVIEW', false]])
  assert.deepEqual(manualReviewConfirmation(parsed), { manual_review_hash: parsed.manual_review!.review_hash })
  assert.equal(intentCanExport(parsed, false, false), false)
  parsed.confirmed_revisions.push(2)
  assert.equal(intentCanExport(parsed, false, false), true)
})

test('manual review rejects unknown fields, invalid ownership, dates, revisions, duplicates and non-reviewable outputs', () => {
  for (const mutate of [
    (v: any) => { v.manual_review.extra = true }, (v: any) => { v.manual_review.outputs[0].time = 10 },
    (v: any) => { v.manual_review.id = v.manual_review.id.toUpperCase().replace('5555', 'ABCD') },
    (v: any) => { v.manual_review.job_id = v.video_id }, (v: any) => { v.manual_review.source_hash = 'f'.repeat(64) },
    (v: any) => { v.manual_review.revision++ }, (v: any) => { v.manual_review.base_plan_hash = 'f'.repeat(64) },
    (v: any) => { v.manual_review.created_at = '2026-02-30T00:00:00Z' }, (v: any) => { v.manual_review.created_at = '2026-11-08T00:00:00.1234567Z' },
    (v: any) => { v.manual_review.outputs.reverse() }, (v: any) => { v.manual_review.outputs.push(v.manual_review.outputs[0]) },
    (v: any) => { v.manual_review.outputs[0].accept_sentence_boundaries = false }, (v: any) => { v.manual_review.outputs[0].goal_id = 'other' },
    (v: any) => { v.plan.outputs[0].status = 'FOUND' }, (v: any) => { v.plan.outputs[0].parts = []; v.plan.outputs[0].evidence_utterance_ids = [] },
  ]) { const { reviewed } = manualFixture(); mutate(reviewed); assert.throws(() => parseIntentSnapshot(reviewed, reviewed.job_id)) }
})

test('Unicode notes use UTF-16 limits and preserve astral Unicode in canonical hashes', async () => {
  assert.equal(validManualReviewNote('😀'.repeat(500)), true)
  assert.equal(validManualReviewNote('😀'.repeat(500) + '字'), false)
  for (const note of ['', ' \t\n', '\ud800', '\udc00', '\u0000']) assert.equal(validManualReviewNote(note), false)
  assert.equal(validManualReviewNote('逐句\n核对\t😀'), true)
  assert.equal(manualReviewCanonical({ z: '😀', a: { z: 2, a: true } }), '{"a":{"a":true,"z":2},"z":"😀"}')
  const { reviewed, proof, receipt } = manualFixture()
  assert.equal((await verifyIntentManualReviewReceipt(proof, receipt)).review_hash, proof.review_hash)
  assert.equal(manualReviewVerified(await verifyIntentManualReviewSnapshot(reviewed)), true)
})

test('a matching-looking proof cannot bypass independently checked output, plan and review hashes', async () => {
  for (const mutate of [
    (v: IntentSnapshot) => { v.manual_review!.review_hash = 'f'.repeat(64) },
    (v: IntentSnapshot) => { v.manual_review!.outputs[0]!.output_hash = 'f'.repeat(64); const { review_hash: _, ...content } = v.manual_review!; v.manual_review!.review_hash = hash(content) },
    (v: IntentSnapshot) => { v.plan!.outputs[0]!.title += '篡改'; v.manual_review!.outputs[0]!.output_hash = hash(v.plan!.outputs[0]); const { review_hash: _, ...content } = v.manual_review!; v.manual_review!.review_hash = hash(content) },
  ]) { const { reviewed } = manualFixture(); mutate(reviewed); await assert.rejects(verifyIntentManualReviewSnapshot(reviewed), IntentManualReviewError); assert.equal(manualReviewVerified(reviewed), false) }
})

test('mutations during or after async hashing cannot reuse verified authority', async () => {
  const { reviewed } = manualFixture(), pending = verifyIntentManualReviewSnapshot(reviewed)
  reviewed.manual_review!.review_hash = 'f'.repeat(64)
  await assert.rejects(pending, IntentManualReviewError)
  const fresh = manualFixture().reviewed; await verifyIntentManualReviewSnapshot(fresh)
  const draft = intentPayload(fresh.plan!.outputs).outputs; draft[0]!.title += '修改'
  assert.equal(effectiveIntentSelection(fresh, draft).length, 0)
  assert.equal(intentCanConfirm(fresh, draft, false, false), false, 'caller cannot override changed outputs by passing a clean flag')
  assert.equal(manualReviewSelectedIds(fresh, true).length, 0)
  fresh.plan!.outputs[0]!.title += '直接变更'
  assert.equal(manualReviewVerified(fresh), false)
  assert.equal(intentCanConfirm(fresh, fresh.plan!.outputs, false, false), false)
})

test('batch input replaces the complete set, supports explicit zero clear and requires supported fresh field', () => {
  const { snapshot, input } = manualFixture()
  assert.deepEqual(manualReviewInput(snapshot, input.outputs), input)
  assert.deepEqual(manualReviewInput(snapshot, []), { base_revision: 1, plan_hash: snapshot.plan_hash, outputs: [] })
  const legacy = clone(snapshot); delete legacy.manual_review
  assert.equal(parseIntentSnapshot(legacy, legacy.job_id).manual_review, undefined, 'legacy snapshots remain readable')
  assert.throws(() => manualReviewInput(legacy, []), rejectedCode('CLIP_MANUAL_REVIEW_UNAVAILABLE'))
  for (const outputs of [[...input.outputs].reverse(), [input.outputs[0]!, input.outputs[0]!], [{ ...input.outputs[0]!, output_id: 'missing' }]])
    assert.throws(() => manualReviewInput(snapshot, outputs), rejectedCode('INVALID_CLIP_MANUAL_REVIEW'))
  assert.equal(parseIntentManualReviewInput({ ...input, outputs: [{ ...input.outputs[0], output_hash: 'a'.repeat(64) }] }), null)
})

test('zero selection is still an immutable manual version and FOUND selection still requires its exact hash', async () => {
  const { reviewed } = manualFixture(0)
  await verifyIntentManualReviewSnapshot(reviewed)
  assert.equal(manualReviewVerified(reviewed), true)
  assert.equal(intentCanConfirm(reviewed, reviewed.plan!.outputs, false, false), false)
  assert.deepEqual(manualReviewConfirmation(reviewed), { manual_review_hash: reviewed.manual_review!.review_hash })
})

test('ordinary new revisions do not inherit proof and same-plan manual revisions cannot acknowledge a lost ordinary PUT', async () => {
  const { snapshot, reviewed } = manualFixture(); await verifyIntentManualReviewSnapshot(reviewed)
  assert.equal(snapshot.plan_hash, reviewed.plan_hash)
  assert.equal(intentSaveRecovered(snapshot, reviewed, reviewed.plan!.outputs, 1), false)
  const ordinary = { ...clone(reviewed), revision: 3, state_version: 6, manual_review: null }
  assert.equal(intentSaveRecovered(reviewed, ordinary, ordinary.plan!.outputs, 2), true)
  assert.equal(effectiveIntentSelection(ordinary, ordinary.plan!.outputs).length, 0)
  assert.deepEqual(manualReviewConfirmation(ordinary), {})
})

test('same revision late responses cannot erase or replace an observed immutable batch', async () => {
  const { reviewed } = manualFixture(); await verifyIntentManualReviewSnapshot(reviewed)
  const late = { ...clone(reviewed), state_version: 6, manual_review: null }
  assert.equal(newestIntentSnapshot(reviewed, late), reviewed)
  const missing = clone(late); delete missing.manual_review
  assert.equal(newestIntentSnapshot(reviewed, missing), reviewed)
  const other = clone(reviewed); other.manual_review!.id = '66666666-6666-4666-8666-666666666666'
  assert.equal(newestIntentSnapshot(reviewed, other), reviewed)
})

test('full cache ingestion refuses unverified proof and legacy batches publish atomically only after all hashes pass', async () => {
  const a = manualFixture().reviewed, b = manualFixture().reviewed, known = new Map<string, IntentSnapshot>()
  assert.throws(() => mergeVerifiedIntentDetail(null, a, a.job_id, a.id), IntentManualReviewError)
  assert.throws(() => cacheLegacyIntentDetails(known, [a], a.job_id), IntentManualReviewError)
  assert.equal(known.size, 0)
  b.manual_review!.review_hash = 'f'.repeat(64)
  await assert.rejects(Promise.all([a, b].map(value => verifyIntentManualReviewSnapshot(value))), IntentManualReviewError)
  assert.equal(known.size, 0)
  await verifyIntentManualReviewSnapshot(a)
  const next = cacheLegacyIntentDetails(known, [a], a.job_id)
  assert.equal(next.size, 1); assert.equal(known.size, 0)
  const summary = intentHistoryFromDetail(a, a.job_id)
  assert.equal(Object.hasOwn(summary, 'manual_review'), false)
  assert.throws(() => mergeVerifiedIntentDetail(null, summary, a.job_id, a.id))
})

test('confirm and export reject a fresh different manual head even when plan hashes are identical', async () => {
  const { reviewed } = manualFixture(); await verifyIntentManualReviewSnapshot(reviewed)
  assert.deepEqual(manualReviewFreshConfirmation(reviewed, clone(reviewed)), { manual_review_hash: reviewed.manual_review!.review_hash })
  const advanced = { ...clone(reviewed), revision: 3, state_version: 6, manual_review: null }
  assert.throws(() => manualReviewFreshConfirmation(reviewed, advanced), rejectedCode('CLIP_MANUAL_REVIEW_MISMATCH'))
  const missing = clone(reviewed); delete missing.manual_review
  assert.throws(() => manualReviewFreshConfirmation(reviewed, missing), rejectedCode('CLIP_MANUAL_REVIEW_MISMATCH'))
})

test('backups contain editable data only; forged review fields and NEEDS_REVIEW.enabled cannot restore authority', async () => {
  const { reviewed } = manualFixture(); await verifyIntentManualReviewSnapshot(reviewed)
  const raw = captureIntentDraft(reviewed.job_id, '', 6, { snapshot: reviewed, outputs: reviewed.plan!.outputs, baseRevision: 2, basePlanHash: reviewed.plan_hash, needsCheck: false })
  assert.equal(JSON.stringify(raw).includes('review_hash'), false)
  assert.equal(JSON.stringify(raw).includes('manual_review'), false)
  const valid = restoreIntentDraft(raw, reviewed.job_id, reviewed)
  assert.equal(valid.ok, true)
  if (valid.ok) assert.deepEqual(valid.outputs?.map(value => value.enabled), [false, false])
  assert.equal(parseIntentDraft({ ...(raw as object), manual_review: reviewed.manual_review }, reviewed.job_id), null)
  const forged = clone(raw) as any; forged.plan.outputs[0].enabled = true
  assert.equal(restoreIntentDraft(forged, reviewed.job_id, reviewed).ok, false)
})

test('receipts bind exact owner, source, key, original body and human notes without restoring permission', async () => {
  const { receipt, proof, reviewed } = manualFixture()
  assert.ok(parseIntentManualReviewReceipt(receipt))
  assert.deepEqual(await verifyIntentManualReviewReceipt(proof, receipt), proof)
  assert.equal(manualReviewVerified(reviewed), false, 'an immutable receipt alone grants no full-snapshot permission')
  for (const mutate of [(v: any) => { v.id = '66666666-6666-4666-8666-666666666666' }, (v: any) => { v.outputs[0].note += '不同输入' }, (v: any) => { v.base_revision++ }, (v: any) => { v.video_id = v.job_id }]) {
    const changed = clone(proof); mutate(changed); await assert.rejects(verifyIntentManualReviewReceipt(changed, receipt), IntentManualReviewError)
  }
})

test('UNKNOWN reads its known immutable ID first and never POSTs after a successful GET', async () => {
  const { receipt, proof } = manualFixture(), events: string[] = []
  const result = await recoverManualReviewReceipt(receipt, { current: () => true,
    get: async () => { events.push(`GET ${receipt.key}`); return proof }, post: async () => { events.push('POST'); throw Error('must not post') } })
  assert.deepEqual(result, proof); assert.deepEqual(events, [`GET ${receipt.key}`])
})

test('only exact business HTTP404 permits replay of the same frozen key/body', async () => {
  const { receipt, proof } = manualFixture(), events: string[] = [], original = JSON.stringify(receipt)
  const result = await recoverManualReviewReceipt(receipt, { current: () => true,
    get: async () => { events.push('GET'); throw failure('CLIP_MANUAL_REVIEW_NOT_FOUND') },
    post: async () => { events.push(`POST ${receipt.key} ${receipt.input}`); return proof } })
  assert.deepEqual(result, proof); assert.deepEqual(events, ['GET', `POST ${receipt.key} ${receipt.input}`]); assert.equal(JSON.stringify(receipt), original)
  for (const value of [failure('RESOURCE_NOT_FOUND'), failure('HTTP_404'), failure('CLIP_MANUAL_REVIEW_NOT_FOUND', 500), failure('NETWORK_UNAVAILABLE', 0), new SyntaxError('bad JSON')]) {
    let posts = 0
    await assert.rejects(recoverManualReviewReceipt(receipt, { current: () => true, get: async () => { throw value }, post: async () => { posts++; return proof } }))
    assert.equal(posts, 0)
  }
})

test('bad immutable GET data and stale async completions preserve UNKNOWN without fallback writes', async () => {
  const { receipt, proof } = manualFixture(); let posts = 0, current = true
  await assert.rejects(recoverManualReviewReceipt(receipt, { current: () => true, get: async () => ({ ...proof, review_hash: 'f'.repeat(64) }), post: async () => { posts++; return proof } }))
  assert.equal(posts, 0)
  assert.equal(await recoverManualReviewReceipt(receipt, { current: () => current,
    get: async () => { current = false; throw failure('CLIP_MANUAL_REVIEW_NOT_FOUND') }, post: async () => { posts++; return proof } }), null)
  assert.equal(posts, 0)
})

test('CAS rechecks storage after async preflight and never overwrites a different page UNKNOWN', () => {
  const { snapshot, input, receipt } = manualFixture(), store = storage()
  const preparedBeforeAwait = null
  store.write(JSON.stringify(receipt))
  let keys = 0
  assert.throws(() => prepareManualReviewReceipt(store.read, preparedBeforeAwait, snapshot, input, () => { keys++; return receipt.key }), rejectedCode('CLIP_MANUAL_REVIEW_OTHER_PAGE'))
  assert.equal(keys, 0); assert.equal(store.value, JSON.stringify(receipt))
  assert.throws(() => persistManualReviewReceipt(store, snapshot, input, () => receipt.key), rejectedCode('CLIP_MANUAL_REVIEW_OTHER_PAGE'))
  assert.equal(store.writes, 1)
})

test('durable persistence is required before submitting, and storage failure keeps new writes unsent', () => {
  const { snapshot, input, receipt } = manualFixture(), store = storage()
  const result = persistManualReviewReceipt(store, snapshot, input, () => receipt.key)
  assert.equal(result.key, receipt.key); assert.equal(store.value, JSON.stringify(result)); assert.equal(store.writes, 1)
  assert.throws(() => persistManualReviewReceipt({ ...store, read: () => null, write: () => { throw Error('quota') } }, snapshot, input, () => receipt.key), rejectedCode('CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE'))
  assert.throws(() => prepareManualReviewReceipt(() => { throw Error('disabled') }, null, snapshot, input, () => receipt.key), rejectedCode('CLIP_MANUAL_REVIEW_STORAGE_UNAVAILABLE'))
})

test('CAS removal and explicit receipt import preserve another page data and grant no permissions', () => {
  const own = manualFixture(), other = manualFixture(), store = storage(JSON.stringify(other.receipt))
  assert.throws(() => removeManualReviewReceipt(store, own.receipt), rejectedCode('CLIP_MANUAL_REVIEW_OTHER_PAGE'))
  assert.throws(() => restoreManualReviewReceipt(store, own.receipt, null), rejectedCode('CLIP_MANUAL_REVIEW_OTHER_PAGE'))
  assert.equal(store.removes, 0); assert.equal(store.writes, 0); assert.equal(store.value, JSON.stringify(other.receipt))
  const clean = storage(); const imported = restoreManualReviewReceipt(clean, own.receipt, null)
  assert.equal(sameManualReviewReceipt(imported, own.receipt), true)
  assert.equal(manualReviewVerified(own.reviewed), false)
  assert.ok(assertStoredManualReviewReceipt(clean, imported)); removeManualReviewReceipt(clean, imported)
  assert.equal(clean.value, null); assert.equal(clean.removes, 1)
})

test('a revision-conflict terminal candidate needs a second business404 and strictly advanced valid same-source head', async () => {
  const { receipt, snapshot } = manualFixture(), head = { ...clone(snapshot), revision: 3, state_version: 7 }
  const store = storage(JSON.stringify(receipt)), events: string[] = []
  const result = await inspectManualReviewRevisionConflict(receipt, { current: () => true,
    get: async () => { events.push('GET-known'); throw failure('CLIP_MANUAL_REVIEW_NOT_FOUND') }, head: async () => { events.push('GET-head'); return head } })
  assert.equal(result?.kind, 'superseded'); assert.deepEqual(events, ['GET-known', 'GET-head'])
  assert.equal(store.value, JSON.stringify(receipt), 'inspection never clears the receipt automatically')
  for (const mutate of [(v: any) => { v.revision = 1 }, (v: any) => { v.source.transcript_hash = 'f'.repeat(64) },
    (v: any) => { delete v.manual_review }, (v: any) => { v.plan_hash = 'f'.repeat(64) }]) {
    const changed = clone(head); mutate(changed)
    await assert.rejects(inspectManualReviewRevisionConflict(receipt, { current: () => true, get: async () => { throw failure('CLIP_MANUAL_REVIEW_NOT_FOUND') }, head: async () => changed }), IntentManualReviewError)
  }
})

test('conflict reinspection finds late successful records or retains unknown on generic404, network failure and stale lifetime', async () => {
  const { receipt, proof } = manualFixture(); let headReads = 0
  const result = await inspectManualReviewRevisionConflict(receipt, { current: () => true, get: async () => proof, head: async () => { headReads++; return null } })
  assert.equal(result?.kind, 'completed'); assert.equal(headReads, 0)
  for (const value of [failure('RESOURCE_NOT_FOUND'), failure('HTTP_404'), failure('NETWORK_UNAVAILABLE', 0)]) {
    await assert.rejects(inspectManualReviewRevisionConflict(receipt, { current: () => true, get: async () => { throw value }, head: async () => { headReads++; return null } }))
    assert.equal(headReads, 0)
  }
  let current = true
  assert.equal(await inspectManualReviewRevisionConflict(receipt, { current: () => current, get: async () => { current = false; throw failure('CLIP_MANUAL_REVIEW_NOT_FOUND') }, head: async () => { headReads++; return null } }), null)
  assert.equal(headReads, 0)
})
