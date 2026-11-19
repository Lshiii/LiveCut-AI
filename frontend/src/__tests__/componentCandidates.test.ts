import assert from 'node:assert/strict'
import test from 'node:test'
import { replaceComponent, selectedComponents, validComponentCache, type ComponentChoice, type ComponentSelection } from '../componentCandidates.ts'
import type { IntentOutput, IntentPart, IntentPlan, IntentSnapshot } from '../intentClips.ts'

const utterances = [...'abcdefghij'].map((id, i) => ({ utterance_id: id, start_ms: i * 1000, end_ms: i * 1000 + 950, text: id }))
function part(i: number, j = i): IntentPart {
  return { start_utterance_id: utterances[i]!.utterance_id, end_utterance_id: utterances[j]!.utterance_id,
    start_ms: utterances[i]!.start_ms, end_ms: utterances[j]!.end_ms }
}
function choice(parts: IntentPart[]): ComponentChoice {
  return { parts, reason: '原句', evidence_utterance_ids: parts.map(p => p.start_utterance_id) }
}
function fixture() {
  const fish = [part(0), part(2, 3)], oil = [part(6, 7)], alternate = [part(5), part(8)]
  const output: IntentOutput = { output_id: 'out', goal_id: 'montage', title: '鱼和油', enabled: true,
    status: 'FOUND', reason: '原方案', excluded: [{ utterance_id: 'j', reason: '闲聊' }], parts: [...fish, ...oil],
    evidence_utterance_ids: ['a', 'c', 'g'] }
  const plan = { outputs: [output], duration_ms: 10000, provenance: { prompt_version: 'montage-clips-v1' },
    component_candidates: [{ component_id: 'fish', title: '鱼', choices: [choice(fish), choice([part(1)])] },
      { component_id: 'oil', title: '油', choices: [choice(oil), choice(alternate)] }] } as IntentPlan
  const snapshot = { id: 'req', revision: 3, plan_hash: 'a'.repeat(64), source: { duration_ms: 10000 }, plan, utterances } as IntentSnapshot
  const pick: ComponentSelection = { request: 'req', hash: snapshot.plan_hash!, revision: 3, component: 'oil', choice: 1 }
  return { snapshot, plan, pick, output, fish, alternate }
}
test('replace a multi-part component; keep the other product and its original evidence exactly; reload and switch back', () => {
  const { snapshot, plan, output, pick, fish, alternate } = fixture()
  const before = JSON.stringify(output)
  assert.ok(validComponentCache(plan, utterances, true))
  assert.deepEqual(selectedComponents(output, plan.component_candidates!), [0, 0])
  const edited = replaceComponent(snapshot, output, pick)!
  assert.deepEqual(edited.parts, [...fish, ...alternate])
  assert.deepEqual(edited.evidence_utterance_ids, ['a', 'c', 'f', 'i'])
  assert.deepEqual(edited.excluded, output.excluded)
  assert.equal(edited.enabled, true)
  assert.equal(JSON.stringify(output), before)
  const saved = structuredClone(snapshot)
  saved.revision++
  saved.plan_hash = 'b'.repeat(64)
  saved.plan!.outputs[0] = edited
  assert.deepEqual(selectedComponents(edited, saved.plan!.component_candidates!), [0, 1])
  assert.equal(replaceComponent(saved, edited, pick), null)
  const restored = replaceComponent(saved, edited, { ...pick, revision: saved.revision, hash: saved.plan_hash, choice: 0 })!
  assert.deepEqual(restored.parts, output.parts)
  assert.deepEqual(restored.evidence_utterance_ids, output.evidence_utterance_ids)
})
test('reject foreign, stale, excluded, freehand-edited or invalid alternatives without changing the draft', () => {
  const { snapshot, plan, pick, output } = fixture()
  for (const bad of [{ ...pick, hash: 'b'.repeat(64) }, { ...pick, request: 'other' }, { ...pick, choice: 12 }])
    assert.equal(replaceComponent(snapshot, output, bad), null)
  const removed = { ...output, parts: [output.parts[0]!, output.parts[2]!] }
  assert.equal(replaceComponent(snapshot, removed, pick), null)
  const excluded = { ...output, excluded: [...output.excluded, { utterance_id: 'f', reason: '手动排除' }] }
  assert.equal(replaceComponent(snapshot, excluded, pick), null)
  const forged = structuredClone(plan)
  forged.component_candidates![1]!.choices[1]!.parts[0]!.start_ms += 1
  assert.equal(validComponentCache(forged, utterances, true), false)
  const crossed = structuredClone(plan)
  crossed.component_candidates![1]!.choices[1] = choice([part(2)])
  assert.equal(validComponentCache(crossed, utterances, true), false)
  const legacy = { ...plan, component_candidates: undefined }
  assert.equal(validComponentCache(legacy, utterances, true), true)
})
