import { intentOutputValidation, intentPartIds, type IntentPart, type IntentOutput, type IntentPlan, type IntentUtterance, type IntentSnapshot } from './intentClips.ts'

export interface ComponentChoice {
  reason: string
  parts: IntentPart[]
  evidence_utterance_ids: string[]
}
export interface MontageComponent {
  component_id: string
  title: string
  choices: ComponentChoice[]
}
export interface ComponentSelection {
  request: string
  revision: number
  hash: string
  component: string
  choice: number
}
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const hasFields = (value: unknown, fields: string[]): value is Record<string, unknown> => object(value)
  && Object.keys(value).length === fields.length && fields.every(key => Object.hasOwn(value, key))
const samePart = (a: IntentPart, b: IntentPart | undefined) => b !== undefined && a.start_utterance_id === b.start_utterance_id
  && a.end_utterance_id === b.end_utterance_id && a.start_ms === b.start_ms && a.end_ms === b.end_ms
const sameParts = (a: IntentPart[], b: IntentPart[]) => a.length === b.length && a.every((part, i) => samePart(part, b[i]))

/** The cache is a read-only part of the source-bound plan. Validate before offering any choice. */
export function validComponentCache(plan: IntentPlan, source: IntentUtterance[], initial: boolean): boolean {
  const groups = plan.component_candidates, output = plan.outputs[0]
  if (groups === undefined) return true
  if (!Array.isArray(groups) || !groups.length || groups.length > 6 || plan.provenance.prompt_version !== 'montage-clips-v1'
    || plan.outputs.length !== 1 || output?.status !== 'FOUND') return false
  const names = new Set<string>()
  for (const g of groups) {
    if (!hasFields(g, ['component_id', 'title', 'choices']) || typeof g.component_id !== 'string'
      || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/.test(g.component_id) || names.has(g.component_id)
      || typeof g.title !== 'string' || !g.title.trim() || g.title.length > 120
      || !Array.isArray(g.choices) || !g.choices.length || g.choices.length > 4) return false
    names.add(g.component_id)
    for (const [i, c] of g.choices.entries()) {
      if (!hasFields(c, ['reason', 'parts', 'evidence_utterance_ids']) || typeof c.reason !== 'string' || !c.reason.trim()
        || !Array.isArray(c.parts) || c.parts.some(p => !hasFields(p, ['start_ms', 'end_ms', 'start_utterance_id', 'end_utterance_id']))
        || !Array.isArray(c.evidence_utterance_ids) || intentOutputValidation([{ ...output, ...c, excluded: initial ? output.excluded : [] }], source, plan.duration_ms)
        || g.choices.slice(0, i).some(other => sameParts(other.parts, c.parts))) return false
    }
  }
  for (const [i, g] of groups.entries()) {
    const min = groups[i - 1]?.choices[0]?.parts.at(-1)?.end_ms ?? 0
    const max = groups[i + 1]?.choices[0]?.parts[0]?.start_ms ?? plan.duration_ms
    if (g.choices.some(c => c.parts[0]!.start_ms < min || c.parts.at(-1)!.end_ms > max)) return false
  }
  const initialEvidence = groups.flatMap(g => g.choices[0]!.evidence_utterance_ids)
  return !initial || (sameParts(groups.flatMap(g => g.choices[0]!.parts), output.parts)
    && initialEvidence.length === output.evidence_utterance_ids.length
    && initialEvidence.every((id, i) => id === output.evidence_utterance_ids[i]))
}

/** Only exact, unambiguous group boundaries can be replaced. Freehand edits remain usable in the normal editor. */
export function selectedComponents(output: IntentOutput, groups: MontageComponent[]): number[] | null {
  const matches: number[][] = []
  function match(group: number, start: number, selected: number[]) {
    if (matches.length > 1) return
    if (group === groups.length) {
      if (start === output.parts.length) matches.push(selected)
      return
    }
    for (const [i, choice] of groups[group]!.choices.entries()) {
      if (choice.parts.every((part, j) => samePart(part, output.parts[start + j]))) {
        match(group + 1, start + choice.parts.length, [...selected, i])
      }
    }
  }
  match(0, 0, [])
  return matches.length === 1 ? matches[0]! : null
}

export function replaceComponent(snapshot: IntentSnapshot, output: IntentOutput, pick: ComponentSelection): IntentOutput | null {
  const plan = snapshot.plan, groups = plan?.component_candidates
  if (!groups || !plan || snapshot.id !== pick.request || pick.revision !== snapshot.revision || pick.hash !== snapshot.plan_hash
    || output.output_id !== plan.outputs[0]?.output_id || output.status !== 'FOUND') return null
  const selected = selectedComponents(output, groups), index = groups.findIndex(g => g.component_id === pick.component)
  if (!selected || index < 0 || !Number.isInteger(pick.choice) || selected[index] === pick.choice) return null
  const group = groups[index]!, next = group.choices[pick.choice], previous = group.choices[selected[index]!]!
  if (!next) return null
  const offset = selected.slice(0, index).reduce((total, choice, i) => total + groups[i]!.choices[choice]!.parts.length, 0)
  const parts = [...output.parts]
  parts.splice(offset, previous.parts.length, ...next.parts.map(part => ({ ...part })))
  const replacedIds = new Set(previous.parts.flatMap(p => intentPartIds(p, snapshot.utterances)))
  const evidence = new Set([...output.evidence_utterance_ids.filter(id => !replacedIds.has(id)), ...next.evidence_utterance_ids])
  selected[index] = pick.choice
  const reason = groups.map((g, i) => {
    const c = selected[i]!
    return ((c === 0 ? '已定位' : '已选备选') + '「' + g.title.slice(0, 45) + '」 ' + g.choices[c]!.reason).slice(0, Math.floor(960 / groups.length))
  }).join('\n')
  const updated = { ...output, parts, reason, evidence_utterance_ids: snapshot.utterances.map(u => u.utterance_id).filter(id => evidence.has(id)) }
  return intentOutputValidation([updated], snapshot.utterances, snapshot.source.duration_ms) ? null : updated
}
