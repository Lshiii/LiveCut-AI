import { parseTextProgress, type TextProgress } from './textProgress.ts'

export interface IntentUtterance { utterance_id: string; start_ms: number; end_ms: number; text: string }
export interface IntentPart { start_utterance_id: string; end_utterance_id: string; start_ms: number; end_ms: number }
export interface IntentOutput {
  output_id: string; goal_id: string; title: string
  status: 'FOUND' | 'NOT_FOUND' | 'NEEDS_REVIEW'; reason: string; enabled: boolean
  parts: IntentPart[]; excluded: { utterance_id: string; reason: string }[]; evidence_utterance_ids: string[]
}
export interface IntentPlan {
  schema_version: '1.0'; request_id: string; job_id: string; video_id: string
  source_hash: string; transcript_hash: string; duration_ms: number; instruction: string
  goals: { goal_id: string; description: string }[]
  constraints: { keep_explanation_complete: boolean; exclude: string[] }
  outputs: IntentOutput[]
  provenance: { text_model_id: string; prompt_version: string; retrieval_mode: string; index_hash: string }
}
export interface IntentSnapshot {
  id: string; job_id: string; video_id: string; instruction: string
  resumed_from_request_id?: string
  status: 'QUEUED' | 'RUNNING' | 'PLAN_READY' | 'FAILED' | 'CANCELLED'; state_version: number
  created_at: string; updated_at: string
  budget: { max_text_calls: number; text_calls_reserved: number }
  stage: { id: string; stage_type: string; status: string; attempt: number; error_code: string | null; text_progress?: TextProgress }
  source: { video_sha256: string; transcript_hash: string; duration_ms: number }
  revision: number; plan_hash: string | null; confirmed_revisions: number[]
  plan: IntentPlan | null; utterances: IntentUtterance[]
}
export class IntentResponseError extends Error { constructor() { super('INVALID_CLIP_RESPONSE') } }
type Row = Record<string, unknown>
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const identifier = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const hash = /^[a-f0-9]{64}$/
const stageStates = new Set(['READY', 'BLOCKED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'])
function row(value: unknown): value is Row { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function integer(value: unknown, min = 0): value is number { return Number.isSafeInteger(value) && (value as number) >= min }
function text(value: unknown, max: number, empty = false): value is string { return typeof value === 'string' && (empty || !!value.trim()) && [...value].length <= max }
function id(value: unknown): value is string { return typeof value === 'string' && identifier.test(value) }
function digest(value: unknown): value is string { return typeof value === 'string' && hash.test(value) }
function date(value: unknown): value is string { return typeof value === 'string' && Number.isFinite(Date.parse(value)) }
function invalid(): never { throw new IntentResponseError() }

/** Evidence is mapped from source order, never by lexicographic ID order. */
export function intentPartIds(part: IntentPart, utterances: IntentUtterance[]): string[] {
  const first = utterances.findIndex(value => value.utterance_id === part.start_utterance_id)
  const last = utterances.findIndex(value => value.utterance_id === part.end_utterance_id)
  return first >= 0 && last >= first ? utterances.slice(first, last + 1).map(value => value.utterance_id) : []
}
export function intentOutputValidation(outputs: IntentOutput[], utterances: IntentUtterance[], duration: number,
  goals?: IntentPlan['goals']): string {
  if (!outputs.length || outputs.length > 6) return '方案应包含 1～6 个剪辑目标。'
  const outputIds = new Set<string>(), goalIds = new Set<string>()
  const source = new Map(utterances.map((value, index) => [value.utterance_id, { value, index }]))
  for (const output of outputs) {
    if (!id(output.output_id) || outputIds.has(output.output_id) || !id(output.goal_id) || goalIds.has(output.goal_id)) return '目标标识重复或无效，请重新读取方案。'
    outputIds.add(output.output_id); goalIds.add(output.goal_id)
    if (!text(output.title, 120) || !text(output.reason, 1000)) return '每个目标需填写标题（最多 120 字），说明最多 1000 字。'
    if (!['FOUND', 'NOT_FOUND', 'NEEDS_REVIEW'].includes(output.status) || typeof output.enabled !== 'boolean'
      || (output.enabled && output.status !== 'FOUND')) return '只有已找到证据的目标可以勾选导出。'
    if (!Array.isArray(output.parts) || output.parts.length > 12 || !Array.isArray(output.excluded) || output.excluded.length > 100 || !Array.isArray(output.evidence_utterance_ids) || output.evidence_utterance_ids.length > 200) return '片段或证据格式无效。'
    if ((output.status === 'FOUND' && (!output.parts.length || !output.evidence_utterance_ids.length))
      || (output.status === 'NOT_FOUND' && output.parts.length)) return '已找到的目标需保留片段和证据；未找到目标不能含有成片区间。'
    const kept = new Set<string>(); let previous = -1
    for (const part of output.parts) {
      const start = source.get(part.start_utterance_id), end = source.get(part.end_utterance_id)
      if (!start || !end || start.index > end.index || start.index <= previous) return '请选择有效且按原视频顺序排列、不重复的起止语句。'
      if (!integer(part.start_ms) || !integer(part.end_ms, part.start_ms + 1) || part.end_ms > duration
        || part.start_ms !== start.value.start_ms || part.end_ms !== end.value.end_ms) return '时间须对应所选原句，请重新选择起止语句。'
      previous = end.index
      for (const value of utterances.slice(start.index, end.index + 1)) kept.add(value.utterance_id)
    }
    if (new Set(output.evidence_utterance_ids).size !== output.evidence_utterance_ids.length
      || output.evidence_utterance_ids.some((value, index, all) => !id(value) || !kept.has(value) || (index > 0 && source.get(value)!.index <= source.get(all[index - 1]!)!.index))) return '证据语句必须按顺序包含在保留片段中，且不能重复。'
    const excluded = new Set<string>()
    for (const value of output.excluded) {
      if (!id(value.utterance_id) || !source.has(value.utterance_id) || excluded.has(value.utterance_id)
        || !text(value.reason, 300) || kept.has(value.utterance_id)) return '排除语句不能同时出现在保留片段中；如需保留，请先取消排除。'
      excluded.add(value.utterance_id)
    }
  }
  if (goals && (goals.length !== outputs.length || goals.some(goal => !goalIds.has(goal.goal_id)))) return '每个需求目标都应有对应结果，包括未找到的目标。'
  return ''
}

export function parseIntentSnapshot(raw: unknown, jobId: string, requestId?: string): IntentSnapshot {
  if (!row(raw) || typeof raw.id !== 'string' || !uuid.test(raw.id) || (requestId !== undefined && raw.id !== requestId)
    || raw.job_id !== jobId || !uuid.test(jobId) || typeof raw.video_id !== 'string' || !uuid.test(raw.video_id)
    || !text(raw.instruction, 2000) || !['QUEUED', 'RUNNING', 'PLAN_READY', 'FAILED', 'CANCELLED'].includes(raw.status as string)
    || !integer(raw.state_version) || !date(raw.created_at) || !date(raw.updated_at)
    || !row(raw.budget) || !integer(raw.budget.max_text_calls, 1) || !integer(raw.budget.text_calls_reserved)
    || raw.budget.text_calls_reserved > raw.budget.max_text_calls
    || !row(raw.stage) || typeof raw.stage.id !== 'string' || !uuid.test(raw.stage.id)
    || raw.stage.stage_type !== 'SEGMENT' || !stageStates.has(raw.stage.status as string) || !integer(raw.stage.attempt)
    || !(raw.stage.error_code === null || (typeof raw.stage.error_code === 'string' && /^[A-Z][A-Z0-9_]{0,79}$/.test(raw.stage.error_code)))
    || !row(raw.source) || !digest(raw.source.video_sha256) || !digest(raw.source.transcript_hash) || !integer(raw.source.duration_ms, 1)
    || !integer(raw.revision) || !(raw.plan_hash === null || digest(raw.plan_hash))
    || !Array.isArray(raw.confirmed_revisions) || raw.confirmed_revisions.some(value => !integer(value) || value > (raw.revision as number))
    || new Set(raw.confirmed_revisions).size !== raw.confirmed_revisions.length || !Array.isArray(raw.utterances)
    || !(raw.resumed_from_request_id === undefined || raw.resumed_from_request_id === null
      || (typeof raw.resumed_from_request_id === 'string' && uuid.test(raw.resumed_from_request_id) && raw.resumed_from_request_id !== raw.id))) invalid()
  const ids = new Set<string>(); let end = 0
  for (const value of raw.utterances) {
    if (!row(value) || !id(value.utterance_id) || ids.has(value.utterance_id) || !text(value.text, 20000)
      || !integer(value.start_ms) || !integer(value.end_ms, value.start_ms + 1) || value.start_ms < end || value.end_ms > raw.source.duration_ms) invalid()
    ids.add(value.utterance_id); end = value.end_ms
  }
  if (raw.plan === null) {
    if (raw.status === 'PLAN_READY' || raw.plan_hash !== null || raw.revision !== 0 || raw.confirmed_revisions.length) invalid()
  } else {
    const plan = raw.plan
    if (!row(plan) || plan.schema_version !== '1.0' || plan.request_id !== raw.id || plan.job_id !== jobId || plan.video_id !== raw.video_id
      || plan.source_hash !== raw.source.video_sha256 || plan.transcript_hash !== raw.source.transcript_hash
      || plan.duration_ms !== raw.source.duration_ms || plan.instruction !== raw.instruction || raw.plan_hash === null
      || !Array.isArray(plan.goals) || !plan.goals.length || plan.goals.length > 6
      || !row(plan.constraints) || typeof plan.constraints.keep_explanation_complete !== 'boolean'
      || !Array.isArray(plan.constraints.exclude) || plan.constraints.exclude.length > 8 || new Set(plan.constraints.exclude).size !== plan.constraints.exclude.length || plan.constraints.exclude.some(value => !text(value, 120))
      || !row(plan.provenance) || !text(plan.provenance.text_model_id, 200) || !text(plan.provenance.prompt_version, 200)
      || plan.provenance.prompt_version !== 'intent-clips-v1' || !['bm25', 'bm25_dense_rrf'].includes(plan.provenance.retrieval_mode as string) || !digest(plan.provenance.index_hash) || !Array.isArray(plan.outputs)) invalid()
    const goals = new Set<string>()
    for (const goal of plan.goals) {
      if (!row(goal) || !id(goal.goal_id) || goal.goal_id.length > 64 || goals.has(goal.goal_id) || !text(goal.description, 300)) invalid()
      goals.add(goal.goal_id)
    }
    for (const output of plan.outputs) {
      if (!row(output) || !Array.isArray(output.parts) || output.parts.some(value => !row(value)) || !Array.isArray(output.excluded) || output.excluded.some(value => !row(value))) invalid()
    }
    if (intentOutputValidation(plan.outputs as unknown as IntentOutput[], raw.utterances as unknown as IntentUtterance[], raw.source.duration_ms, plan.goals as IntentPlan['goals'])) invalid()
  }
  const { text_progress: rawProgress, ...stage } = raw.stage
  const progress = parseTextProgress(rawProgress)
  const { resumed_from_request_id: resumedFrom, ...value } = raw
  return { ...value, ...(typeof resumedFrom === 'string' ? { resumed_from_request_id: resumedFrom } : {}),
    stage: { ...stage, ...(progress ? { text_progress: progress } : {}) } } as unknown as IntentSnapshot
}

/** A continued request has new accounting, but exactly the same frozen media and instruction. */
export function parseResumedIntentSnapshot(raw: unknown, source: IntentSnapshot, maxTextCalls: number): IntentSnapshot {
  const value = parseIntentSnapshot(raw, source.job_id)
  if (value.id === source.id || value.resumed_from_request_id !== source.id || value.video_id !== source.video_id
    || value.instruction !== source.instruction || value.source.video_sha256 !== source.source.video_sha256
    || value.source.transcript_hash !== source.source.transcript_hash || value.source.duration_ms !== source.source.duration_ms
    || value.budget.max_text_calls !== maxTextCalls) invalid()
  return value
}

export interface IntentResumeReceipt { input: string; key: string }
export function intentResumeReceipt(saved: unknown, requestId: string, maxTextCalls: number, newKey: () => string): IntentResumeReceipt {
  if (!uuid.test(requestId) || !integer(maxTextCalls, 1) || maxTextCalls > 24) invalid()
  const input = JSON.stringify({ request_id: requestId, max_text_calls: maxTextCalls })
  if (row(saved) && saved.input === input && typeof saved.key === 'string' && uuid.test(saved.key)) return { input, key: saved.key }
  const key = newKey()
  if (!uuid.test(key)) invalid()
  return { input, key }
}

export function intentCanCancel(snapshot: IntentSnapshot | null, unsavedPlan: boolean, pending: boolean): boolean {
  return !!snapshot && ['QUEUED', 'RUNNING'].includes(snapshot.status) && !unsavedPlan && !pending
}
export function intentCanResume(snapshot: IntentSnapshot | null, unsavedInput: boolean, pending: boolean): boolean {
  return !!snapshot && ['FAILED', 'CANCELLED'].includes(snapshot.status) && !unsavedInput && !pending
}

export function newestIntentSnapshot(known: IntentSnapshot | null | undefined, incoming: IntentSnapshot): IntentSnapshot {
  if (!known || known.id !== incoming.id) return incoming
  if (known.job_id !== incoming.job_id || known.video_id !== incoming.video_id || known.instruction !== incoming.instruction
    || known.resumed_from_request_id !== incoming.resumed_from_request_id || known.source.duration_ms !== incoming.source.duration_ms
    || known.source.video_sha256 !== incoming.source.video_sha256 || known.source.transcript_hash !== incoming.source.transcript_hash) invalid()
  if (incoming.state_version < known.state_version || incoming.revision < known.revision
    || (incoming.state_version === known.state_version && incoming.status !== known.status)
    || known.confirmed_revisions.some(value => !incoming.confirmed_revisions.includes(value))) return known
  return incoming
}
export function intentPayload(outputs: IntentOutput[]): { outputs: IntentOutput[] } {
  return { outputs: outputs.map(value => ({ output_id: value.output_id, goal_id: value.goal_id, title: value.title,
    status: value.status, reason: value.reason, enabled: value.enabled,
    parts: value.parts.map(part => ({ start_utterance_id: part.start_utterance_id, end_utterance_id: part.end_utterance_id,
      start_ms: part.start_ms, end_ms: part.end_ms })),
    excluded: value.excluded.map(item => ({ utterance_id: item.utterance_id, reason: item.reason })),
    evidence_utterance_ids: [...value.evidence_utterance_ids] })) }
}
export function intentCanConfirm(snapshot: IntentSnapshot | null, outputs: IntentOutput[], dirty: boolean, pending: boolean): boolean {
  return !!snapshot?.plan && snapshot.status === 'PLAN_READY' && !dirty && !pending
    && !intentOutputValidation(outputs, snapshot.utterances, snapshot.source.duration_ms, snapshot.plan.goals)
    && outputs.some(value => value.enabled)
}
export function intentCanExport(snapshot: IntentSnapshot | null, dirty: boolean, pending: boolean): boolean {
  return !!snapshot?.plan && !dirty && !pending && snapshot.plan_hash !== null
    && snapshot.confirmed_revisions.includes(snapshot.revision) && snapshot.plan.outputs.some(value => value.enabled)
}
export function editIntentBoundary(output: IntentOutput, index: number, field: 'start_utterance_id' | 'end_utterance_id', value: string, utterances: IntentUtterance[]) {
  const part = output.parts[index]
  if (!part) return
  part[field] = value
  part.start_ms = utterances.find(row => row.utterance_id === part.start_utterance_id)?.start_ms ?? -1
  part.end_ms = utterances.find(row => row.utterance_id === part.end_utterance_id)?.end_ms ?? -1
  refreshIntentEvidence(output, utterances)
}
export function refreshIntentEvidence(output: IntentOutput, utterances: IntentUtterance[]) {
  output.evidence_utterance_ids = [...new Set(output.parts.flatMap(part => intentPartIds(part, utterances)))]
}

/** Each selection owns its GETs; a stale response cannot act on a newly selected request. */
export class IntentReadScope {
  private epoch = 0
  private controllers = new Set<AbortController>()
  private stopped = false
  begin(): { signal: AbortSignal; current(): boolean; release(): void } {
    const controller = new AbortController(), epoch = this.epoch
    if (this.stopped) controller.abort()
    this.controllers.add(controller)
    return { signal: controller.signal,
      current: () => !this.stopped && epoch === this.epoch && !controller.signal.aborted,
      release: () => { this.controllers.delete(controller) } }
  }
  invalidate() { this.epoch++; for (const controller of this.controllers) controller.abort(); this.controllers.clear() }
  dispose() { this.stopped = true; this.invalidate() }
}
