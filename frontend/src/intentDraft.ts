import type { DraftAssessment } from './browserDrafts.ts'
import { intentPayload, parseIntentSnapshot, type IntentOutput, type IntentPart, type IntentSnapshot } from './intentClips.ts'
import { answerClipBinding, parseAnswerClipBinding, sameAnswerClipBinding, type AnswerClipBinding, type AnswerClipSelection } from './answerClip.ts'
import { parseIntentCreateReceipt, parseIntentCreatePayload, type IntentCreateReceipt } from './intentCreate.ts'

export interface IntentDraftOutput {
  output_id: string
  goal_id: string
  title: string
  enabled: boolean
  parts: IntentPart[]
  excluded_ids: string[]
  evidence_ids: string[]
}
export interface IntentDraftPlan {
  request_id: string
  video_id: string
  source: { video_sha256: string; transcript_hash: string; duration_ms: number }
  base_revision: number
  base_plan_hash: string
  needs_check: boolean
  outputs: IntentDraftOutput[]
  source_answer?: AnswerClipBinding | null
}
export interface IntentDraft {
  schema_version: 'intent-draft-v1'
  job_id: string
  instruction: string
  max_calls: number | string
  plan: IntentDraftPlan | null
  source_answer?: AnswerClipBinding | null
  create_receipt?: IntentCreateReceipt | null
}
export type IntentDraftRestore = { ok: true; draft: IntentDraft; outputs: IntentOutput[] | null } | { ok: false; reason: string }
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const hash = /^[a-f0-9]{64}$/
const identifier = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown, minimum = 0): value is number => Number.isSafeInteger(value) && (value as number) >= minimum
const text = (value: unknown, max: number): value is string => typeof value === 'string' && [...value].length <= max
const id = (value: unknown): value is string => typeof value === 'string' && identifier.test(value)
function exact(value: unknown, fields: string[]): value is Record<string, unknown> {
  return row(value) && Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
}
function optional(value: unknown, required: string[], extra: string[]): value is Record<string, unknown> {
  return row(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => [...required, ...extra].includes(key))
}
function savedBinding(raw: unknown): AnswerClipBinding | null | undefined {
  if (raw === undefined || raw === null) return null
  try { return parseAnswerClipBinding(raw) } catch { return undefined }
}
const budget = (value: unknown): value is number | string => typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= 1e6
  || typeof value === 'string' && value.length <= 32
function ids(value: unknown, max: number): value is string[] {
  return Array.isArray(value) && value.length <= max && value.every(id) && new Set(value).size === value.length
}

/** Capture even unfinished input; a rejected backup must never be mistaken for a clean editor. */
export function captureIntentDraft(jobId: string, instruction: string, maxCalls: number | string,
  plan: { snapshot: IntentSnapshot; outputs: IntentOutput[]; baseRevision: number | null; basePlanHash: string | null; needsCheck: boolean } | null,
  sourceAnswer?: AnswerClipBinding | null, createReceipt?: IntentCreateReceipt | null): unknown {
  return { schema_version: 'intent-draft-v1', job_id: jobId, instruction, max_calls: maxCalls,
    ...(sourceAnswer ? { source_answer: answerClipBinding(sourceAnswer) } : {}), ...(createReceipt ? { create_receipt: { ...createReceipt, ...(createReceipt.source_answer ? { source_answer: answerClipBinding(createReceipt.source_answer) } : {}) } } : {}),
    plan: plan ? { request_id: plan.snapshot.id, video_id: plan.snapshot.video_id,
      source: { video_sha256: plan.snapshot.source.video_sha256, transcript_hash: plan.snapshot.source.transcript_hash, duration_ms: plan.snapshot.source.duration_ms },
      base_revision: plan.baseRevision, base_plan_hash: plan.basePlanHash, needs_check: plan.needsCheck,
      ...(plan.snapshot.source_answer ? { source_answer: answerClipBinding(plan.snapshot.source_answer) } : {}),
      outputs: plan.outputs.map(output => ({ output_id: output.output_id, goal_id: output.goal_id, title: output.title, enabled: output.enabled,
        parts: output.parts.map(part => ({ start_utterance_id: part.start_utterance_id, end_utterance_id: part.end_utterance_id, start_ms: part.start_ms, end_ms: part.end_ms })),
        excluded_ids: output.excluded.map(value => value.utterance_id), evidence_ids: [...output.evidence_utterance_ids] })) } : null }
}

export function parseIntentDraft(raw: unknown, jobId: string): IntentDraft | null {
  if (!uuid.test(jobId) || !optional(raw, ['schema_version', 'job_id', 'instruction', 'max_calls', 'plan'], ['source_answer', 'create_receipt'])
    || raw.schema_version !== 'intent-draft-v1' || raw.job_id !== jobId || !text(raw.instruction, 2000) || !budget(raw.max_calls)) return null
  const sourceAnswer = savedBinding(raw.source_answer), receipt = raw.create_receipt === undefined || raw.create_receipt === null ? null : parseIntentCreateReceipt(raw.create_receipt)
  if (sourceAnswer === undefined || sourceAnswer && sourceAnswer.job_id !== jobId || raw.create_receipt != null && !receipt) return null
  if (receipt) {
    const payload = parseIntentCreatePayload(JSON.parse(receipt.input))!
    if (payload.instruction !== raw.instruction.trim() || payload.max_text_calls !== raw.max_calls || !!payload.source_answer !== !!sourceAnswer || !sameAnswerClipBinding(receipt.source_answer, sourceAnswer)
      || sourceAnswer && (payload.source_answer!.turn_id !== sourceAnswer.turn_id || JSON.stringify(payload.source_answer!.utterance_ids) !== JSON.stringify(sourceAnswer.utterance_ids))) return null
  }
  const extras = { ...(sourceAnswer ? { source_answer: sourceAnswer } : {}), ...(receipt ? { create_receipt: receipt } : {}) }
  if (raw.plan === null) return { schema_version: 'intent-draft-v1', job_id: jobId, instruction: raw.instruction, max_calls: raw.max_calls, plan: null, ...extras }
  const plan = raw.plan
  if (!optional(plan, ['request_id', 'video_id', 'source', 'base_revision', 'base_plan_hash', 'needs_check', 'outputs'], ['source_answer'])
    || typeof plan.request_id !== 'string' || !uuid.test(plan.request_id) || typeof plan.video_id !== 'string' || !uuid.test(plan.video_id)
    || !exact(plan.source, ['video_sha256', 'transcript_hash', 'duration_ms'])
    || typeof plan.source.video_sha256 !== 'string' || !hash.test(plan.source.video_sha256)
    || typeof plan.source.transcript_hash !== 'string' || !hash.test(plan.source.transcript_hash) || !integer(plan.source.duration_ms, 1)
    || !integer(plan.base_revision) || typeof plan.base_plan_hash !== 'string' || !hash.test(plan.base_plan_hash)
    || typeof plan.needs_check !== 'boolean' || !Array.isArray(plan.outputs) || !plan.outputs.length || plan.outputs.length > 6) return null
  const planAnswer = savedBinding(plan.source_answer)
  if (planAnswer === undefined || planAnswer && (planAnswer.job_id !== jobId || planAnswer.video_id !== plan.video_id || planAnswer.source_hash !== plan.source.video_sha256
    || planAnswer.transcript_hash !== plan.source.transcript_hash || planAnswer.duration_ms !== plan.source.duration_ms)) return null
  const outputs: IntentDraftOutput[] = []
  for (const output of plan.outputs) {
    if (!exact(output, ['output_id', 'goal_id', 'title', 'enabled', 'parts', 'excluded_ids', 'evidence_ids'])
      || !id(output.output_id) || !id(output.goal_id) || !text(output.title, 120) || typeof output.enabled !== 'boolean'
      || !Array.isArray(output.parts) || output.parts.length > 12 || !ids(output.excluded_ids, 100) || !ids(output.evidence_ids, 200)) return null
    const parts: IntentPart[] = []
    for (const part of output.parts) {
      if (!exact(part, ['start_utterance_id', 'end_utterance_id', 'start_ms', 'end_ms'])
        || !(part.start_utterance_id === '' || id(part.start_utterance_id)) || !(part.end_utterance_id === '' || id(part.end_utterance_id))
        || !integer(part.start_ms, -1) || !integer(part.end_ms, -1) || part.start_ms > plan.source.duration_ms || part.end_ms > plan.source.duration_ms) return null
      parts.push({ start_utterance_id: part.start_utterance_id, end_utterance_id: part.end_utterance_id, start_ms: part.start_ms, end_ms: part.end_ms })
    }
    outputs.push({ output_id: output.output_id, goal_id: output.goal_id, title: output.title, enabled: output.enabled,
      parts, excluded_ids: [...output.excluded_ids], evidence_ids: [...output.evidence_ids] })
  }
  if (new Set(outputs.map(value => value.output_id)).size !== outputs.length || new Set(outputs.map(value => value.goal_id)).size !== outputs.length) return null
  return { schema_version: 'intent-draft-v1', job_id: jobId, instruction: raw.instruction, max_calls: raw.max_calls, ...extras,
    plan: { request_id: plan.request_id, video_id: plan.video_id,
      source: { video_sha256: plan.source.video_sha256, transcript_hash: plan.source.transcript_hash, duration_ms: plan.source.duration_ms },
      base_revision: plan.base_revision, base_plan_hash: plan.base_plan_hash, needs_check: plan.needs_check, outputs,
      ...(planAnswer ? { source_answer: planAnswer } : {}) } }
}

export function restoreIntentDraft(raw: unknown, jobId: string, server: IntentSnapshot | null, verifiedAnswers: readonly AnswerClipSelection[] = []): IntentDraftRestore {
  const draft = parseIntentDraft(raw, jobId)
  if (!draft) return { ok: false, reason: '这份备份格式或来源标识无效，可下载留存，不能覆盖当前内容。' }
  for (const binding of [draft.source_answer, draft.plan?.source_answer]) if (binding && !verifiedAnswers.some(value => sameAnswerClipBinding(value.binding, binding)))
    return { ok: false, reason: '锚定回答尚未经过最新原对话核对；只能下载保留，不能退回普通文字要求。' }
  if (!draft.plan) return { ok: true, draft, outputs: null }
  const plan = draft.plan
  if (!server) return { ok: false, reason: '需要重新读取原剪辑请求后核对，当前方案未改变。' }
  let current: IntentSnapshot
  try { current = parseIntentSnapshot(server, jobId, plan.request_id) }
  catch { return { ok: false, reason: '原请求未通过来源校验，可下载备份，不能覆盖当前方案。' } }
  if (!current.plan || current.status !== 'PLAN_READY') return { ok: false, reason: '原请求当前没有可编辑方案，可下载备份，不能覆盖当前内容。' }
  if (current.video_id !== plan.video_id || current.source.video_sha256 !== plan.source.video_sha256
    || current.source.transcript_hash !== plan.source.transcript_hash || current.source.duration_ms !== plan.source.duration_ms
    || !sameAnswerClipBinding(current.source_answer, plan.source_answer)) {
    return { ok: false, reason: '视频或转写来源已经变化，这份备份只能下载，不能恢复到当前方案。' }
  }
  if (current.revision !== plan.base_revision || current.plan_hash !== plan.base_plan_hash) {
    return { ok: false, reason: '服务端方案版本已变化，这份备份只能下载。请下载保留后人工核对，避免覆盖其他修改。' }
  }
  if (current.plan.outputs.length !== plan.outputs.length) return { ok: false, reason: '剪辑目标已变化，这份备份只能下载。' }
  const source = new Set(current.utterances.map(value => value.utterance_id)), outputs: IntentOutput[] = []
  for (const [index, saved] of plan.outputs.entries()) {
    const target = current.plan.outputs[index]!
    if (target.output_id !== saved.output_id || target.goal_id !== saved.goal_id || saved.enabled && target.status !== 'FOUND') {
      return { ok: false, reason: '剪辑目标身份或可选权限不一致，这份备份不能应用。' }
    }
    const exclusions = new Map(target.excluded.map(value => [value.utterance_id, value]))
    if (saved.parts.some(part => part.start_utterance_id !== '' && !source.has(part.start_utterance_id)
        || part.end_utterance_id !== '' && !source.has(part.end_utterance_id))
      || saved.excluded_ids.some(id => !source.has(id) || !exclusions.has(id)) || saved.evidence_ids.some(id => !source.has(id))) {
      return { ok: false, reason: '备份含有不属于原来源的原句或排除项，只能下载，不能覆盖方案。' }
    }
    const restored = intentPayload([target]).outputs[0]!
    restored.title = saved.title; restored.enabled = saved.enabled
    restored.parts = saved.parts.map(part => ({ ...part }))
    restored.excluded = saved.excluded_ids.map(id => ({ ...exclusions.get(id)! }))
    restored.evidence_utterance_ids = [...saved.evidence_ids]
    outputs.push(restored)
  }
  return { ok: true, draft, outputs }
}

function previewText(value: string, limit = 72) {
  const characters = [...value.replace(/\s+/gu, ' ').trim()]
  return characters.length > limit ? characters.slice(0, limit - 1).join('') + '…' : characters.join('')
}
function editablePreview(draft: IntentDraft) {
  const instruction = previewText(draft.instruction)
  if (draft.plan) {
    const title = previewText(draft.plan.outputs[0]!.title) || '空标题待修'
    return instruction ? `标题：${previewText(title, 32)}；新要求：${previewText(instruction, 32)}` : `标题：${title}`
  }
  if (instruction) return `要求：${instruction}`
  const calls = draft.max_calls === '' ? '（空）' : typeof draft.max_calls === 'string' && !draft.max_calls.trim() ? '（空白）' : previewText(String(draft.max_calls), 32)
  return `请求上限原输入：${calls}`
}
export function assessIntentDraft(raw: unknown, jobId: string, server: IntentSnapshot | null): DraftAssessment {
  const draft = parseIntentDraft(raw, jobId)
  if (!draft) return { title: '无法直接恢复的剪辑备份', description: '原记录仍保留，可下载后核对。', compatible: false, reason: '备份格式或来源标识不符合当前工作区。' }
  const title = draft.plan ? `剪辑方案 r${draft.plan.base_revision} 的本地修改` : '未提交的剪辑要求'
  const preview = editablePreview(draft)
  const checkHint = draft.plan?.needs_check ? ' 前次保存结果仍需核对。' : ''
  const description = preview + ' · ' + (draft.plan ? `${draft.plan.outputs.length} 个目标${draft.instruction.trim() ? '，另含未提交要求' : ''}；恢复不会自动保存或确认。`
    : '恢复到剪辑要求输入框，不创建查找请求。') + checkHint
  if (draft.source_answer || draft.plan?.source_answer) return { title, description: description + ' 锚定来源将在恢复时重新读取原回答核对；未知提交保留原标识。', compatible: true }
  if (draft.plan && !server) return { title, description: `${preview} · 恢复前会重新读取原请求，核对来源与版本；不会自动保存。${checkHint}`, compatible: true }
  const result = restoreIntentDraft(raw, jobId, server)
  return result.ok ? { title, description, compatible: true } : { title, description, compatible: false, reason: result.reason }
}
