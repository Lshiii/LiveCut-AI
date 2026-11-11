import { sameTranscriptSource, type TranscriptOwner, type TranscriptSource, type TranscriptUtterance } from './transcriptSearch.ts'
import { sha256 } from 'hash-wasm'

export interface ManualClipInput { source_fingerprint: string; title: string; start_utterance_id: string; end_utterance_id: string }
export interface ManualClipRevisionInput extends ManualClipInput { base_revision: number; base_plan_hash: string }
export interface ManualClipRange { start_ms: number; end_ms: number; utterance_count: number; start_text: string; end_text: string }
export interface ManualClipRangeResult {
  schema_version: '1.0'; data_kind: 'transcript_range'; source: TranscriptSource; start_utterance_id: string; end_utterance_id: string; range: ManualClipRange
}
export interface ManualClipPlan {
  schema_version: '1.0'; data_kind: 'manual_clip_plan'; source_kind: 'MANUAL_ASR'; id: string; source: TranscriptSource
  revision: number; latest_revision: number; plan_hash: string; status: 'DRAFT' | 'CONFIRMED'; confirmed_revisions: number[]
  created_at: string; updated_at: string; title: string; start_utterance_id: string; end_utterance_id: string; range: ManualClipRange
}
export interface ManualClipSummary {
  id: string; job_id: string; video_id: string; title: string; revision: number; plan_hash: string
  status: 'DRAFT' | 'CONFIRMED'; created_at: string; updated_at: string
}
export interface ManualClipList { schema_version: '1.0'; data_kind: 'manual_clip_plan_list'; job_id: string; items: ManualClipSummary[]; total: number; limit: number; offset: number }
export interface ManualClipSubmission {
  schema_version: '1.0'; data_kind: 'manual_clip_submission'; operation: 'CREATE' | 'REVISION'; job_id: string; plan_id: string
  idempotency_key: string; input: ManualClipInput | ManualClipRevisionInput; request_hash: string; revision: number; plan_hash: string
}
export interface ManualClipSubmissionExpected { operation: 'CREATE' | 'REVISION' | 'EXPORT'; job_id: string; plan_id?: string | null; key: string; input: string | ManualClipInput | ManualClipRevisionInput }
export class ManualClipError extends Error { code: string; constructor(code = 'MANUAL_CLIP_RESPONSE_INVALID') { super(code); this.code = code } }
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, HASH = /^[a-f0-9]{64}$/, ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const SOURCE_FIELDS = ['job_id', 'video_id', 'generation', 'source_video_sha256', 'transcript_sha256', 'analysis_sha256', 'duration_ms', 'source_fingerprint']
const INPUT_FIELDS = ['source_fingerprint', 'title', 'start_utterance_id', 'end_utterance_id']
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown, min = 0): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value)
const hash = (value: unknown): value is string => typeof value === 'string' && HASH.test(value)
const identifier = (value: unknown): value is string => typeof value === 'string' && ID.test(value)
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
function fail(code?: string): never { throw new ManualClipError(code) }
function fields(value: unknown, expected: string[]): asserts value is Record<string, unknown> {
  if (!object(value) || Object.keys(value).length !== expected.length || expected.some(key => !Object.hasOwn(value, key))) fail()
}
function validText(value: unknown, maximum: number): value is string {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(value)) return false
  for (let index = 0; index < value.length; index++) {
    const unit = value.charCodeAt(index)
    if (unit >= 0xd800 && unit <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return false }
    else if (unit >= 0xdc00 && unit <= 0xdfff) return false
  }
  return true
}
export function manualClipCanonical(value: unknown): string {
  const sorted = (input: unknown): unknown => Array.isArray(input) ? input.map(sorted)
    : object(input) ? Object.fromEntries(Object.keys(input).sort().map(key => [key, sorted(input[key])])) : input
  return JSON.stringify(sorted(value))
}
export function parseManualClipSource(raw: unknown, owner?: TranscriptOwner, fixed?: TranscriptSource): TranscriptSource {
  fields(raw, SOURCE_FIELDS)
  if (!uuid(raw.job_id) || !uuid(raw.video_id) || !integer(raw.generation, 1) || raw.generation > 2147483647
    || !hash(raw.source_video_sha256) || !hash(raw.transcript_sha256) || !hash(raw.analysis_sha256) || !hash(raw.source_fingerprint) || !integer(raw.duration_ms, 1)) fail()
  if (owner && (raw.job_id !== owner.job_id || raw.video_id !== owner.video_id || raw.generation !== owner.generation || raw.source_video_sha256 !== owner.source_video_sha256)) fail('MANUAL_CLIP_SOURCE_CHANGED')
  const source = Object.freeze({ ...raw }) as unknown as TranscriptSource
  if (fixed && !sameTranscriptSource(source, fixed)) fail('MANUAL_CLIP_SOURCE_CHANGED')
  return source
}
export function parseManualClipInput(raw: unknown, revision = false): ManualClipInput | ManualClipRevisionInput {
  fields(raw, [...(revision ? ['base_revision', 'base_plan_hash'] : []), ...INPUT_FIELDS])
  if (!hash(raw.source_fingerprint) || !validText(raw.title, 120) || !identifier(raw.start_utterance_id) || !identifier(raw.end_utterance_id)
    || revision && (!integer(raw.base_revision, 1) || raw.base_revision >= Number.MAX_SAFE_INTEGER || !hash(raw.base_plan_hash))) fail('MANUAL_CLIP_INVALID')
  return Object.freeze({ ...(revision ? { base_revision: raw.base_revision as number, base_plan_hash: raw.base_plan_hash as string } : {}),
    source_fingerprint: raw.source_fingerprint, title: raw.title, start_utterance_id: raw.start_utterance_id, end_utterance_id: raw.end_utterance_id }) as ManualClipInput | ManualClipRevisionInput
}
export function manualClipDraftError(source: TranscriptSource | null, title: string, start: TranscriptUtterance | null, end: TranscriptUtterance | null): string {
  if (!source) return '请先从已核对的原句中明确选择起句和止句。'
  try { parseManualClipSource(source) } catch { return '原句来源无效，请重新核对来源。' }
  if (!validText(title, 120)) return '请填写非空白标题，最多 120 个 UTF-16 单元。'
  if (!start || !end) return '请明确选择起句和止句。'
  for (const item of [start, end]) if (!identifier(item.utterance_id) || !integer(item.start_ms) || !integer(item.end_ms, item.start_ms + 1)
    || item.end_ms > source.duration_ms || !validText(item.text, 100000)) return '起止原句与来源无效，请重新选择。'
  if (start.start_ms > end.start_ms || start.end_ms > end.end_ms) return '止句不能早于起句，请明确调整；不会自动交换。'
  if (start.utterance_id === end.utterance_id && (start.start_ms !== end.start_ms || start.end_ms !== end.end_ms || start.text !== end.text)
    || start.utterance_id !== end.utterance_id && start.end_ms > end.start_ms) return '起止原句的来源或时间冲突，请重新选择。'
  return ''
}
export function manualClipInput(source: TranscriptSource, title: string, start: TranscriptUtterance | null, end: TranscriptUtterance | null): ManualClipInput {
  if (manualClipDraftError(source, title, start, end)) fail('MANUAL_CLIP_INVALID_RANGE')
  return parseManualClipInput({ source_fingerprint: source.source_fingerprint, title, start_utterance_id: start!.utterance_id, end_utterance_id: end!.utterance_id }) as ManualClipInput
}
export function manualClipRevisionInput(plan: ManualClipPlan, input: ManualClipInput): ManualClipRevisionInput {
  if (plan.revision !== plan.latest_revision || plan.source.source_fingerprint !== input.source_fingerprint) fail('MANUAL_CLIP_REVISION_CONFLICT')
  return parseManualClipInput({ base_revision: plan.revision, base_plan_hash: plan.plan_hash, ...input }, true) as ManualClipRevisionInput
}
export function manualClipRangeUrl(source: TranscriptSource, startId: string, endId: string): string {
  parseManualClipSource(source)
  if (!identifier(startId) || !identifier(endId)) fail('MANUAL_CLIP_INVALID_RANGE')
  return `/api/jobs/${source.job_id}/transcript/range?${new URLSearchParams({ source_fingerprint: source.source_fingerprint, start_utterance_id: startId, end_utterance_id: endId })}`
}
export function parseManualClipRange(raw: unknown, owner: TranscriptOwner, source: TranscriptSource, start: TranscriptUtterance, end: TranscriptUtterance): ManualClipRangeResult {
  fields(raw, ['schema_version', 'data_kind', 'source', 'start_utterance_id', 'end_utterance_id', 'range'])
  if (raw.schema_version !== '1.0' || raw.data_kind !== 'transcript_range' || raw.start_utterance_id !== start.utterance_id || raw.end_utterance_id !== end.utterance_id) fail()
  const checked = parseManualClipSource(raw.source, owner, source)
  if (manualClipDraftError(checked, '人工区间', start, end)) fail('MANUAL_CLIP_INVALID_RANGE')
  fields(raw.range, ['start_ms', 'end_ms', 'utterance_count', 'start_text', 'end_text'])
  const range = raw.range
  if (!integer(range.start_ms) || !integer(range.end_ms, range.start_ms + 1) || range.end_ms > checked.duration_ms
    || range.start_ms !== start.start_ms || range.end_ms !== end.end_ms || range.start_text !== start.text || range.end_text !== end.text
    || !integer(range.utterance_count, 1) || range.utterance_count > 100000 || !validText(range.start_text, 100000) || !validText(range.end_text, 100000)
    || start.utterance_id === end.utterance_id && range.utterance_count !== 1 || start.utterance_id !== end.utterance_id && range.utterance_count < 2) fail()
  return Object.freeze({ schema_version: '1.0', data_kind: 'transcript_range', source: checked, start_utterance_id: start.utterance_id,
    end_utterance_id: end.utterance_id, range: Object.freeze({ ...range }) as unknown as ManualClipRange })
}
export function parseManualClipPlan(raw: unknown, owner?: TranscriptOwner, expected: { id?: string; revision?: number; source?: TranscriptSource } = {}): ManualClipPlan {
  fields(raw, ['schema_version', 'data_kind', 'source_kind', 'id', 'source', 'revision', 'latest_revision', 'plan_hash', 'status', 'confirmed_revisions',
    'created_at', 'updated_at', 'title', 'start_utterance_id', 'end_utterance_id', 'range'])
  if (raw.schema_version !== '1.0' || raw.data_kind !== 'manual_clip_plan' || raw.source_kind !== 'MANUAL_ASR' || !uuid(raw.id)
    || expected.id !== undefined && expected.id !== raw.id || !integer(raw.revision, 1) || !integer(raw.latest_revision, raw.revision)
    || expected.revision !== undefined && expected.revision !== raw.revision || !hash(raw.plan_hash) || !['DRAFT', 'CONFIRMED'].includes(String(raw.status))
    || !Array.isArray(raw.confirmed_revisions) || !date(raw.created_at) || !date(raw.updated_at) || Date.parse(raw.updated_at) < Date.parse(raw.created_at)
    || !validText(raw.title, 120) || !identifier(raw.start_utterance_id) || !identifier(raw.end_utterance_id)) fail()
  let previous = 0
  for (const value of raw.confirmed_revisions) { if (!integer(value, 1) || value <= previous || value > raw.latest_revision) fail(); previous = value }
  if ((raw.status === 'CONFIRMED') !== raw.confirmed_revisions.includes(raw.revision)) fail()
  const source = parseManualClipSource(raw.source, owner, expected.source)
  fields(raw.range, ['start_ms', 'end_ms', 'utterance_count', 'start_text', 'end_text'])
  const range = raw.range
  if (!integer(range.start_ms) || !integer(range.end_ms, range.start_ms + 1) || range.end_ms > source.duration_ms
    || !integer(range.utterance_count, 1) || range.utterance_count > 100000 || !validText(range.start_text, 100000) || !validText(range.end_text, 100000)
    || raw.start_utterance_id === raw.end_utterance_id && (range.utterance_count !== 1 || range.start_text !== range.end_text)
    || raw.start_utterance_id !== raw.end_utterance_id && range.utterance_count < 2) fail()
  return Object.freeze({ ...raw, source, range: Object.freeze({ ...range }), confirmed_revisions: Object.freeze([...raw.confirmed_revisions]) }) as unknown as ManualClipPlan
}
export function parseManualClipList(raw: unknown, owner: TranscriptOwner, expected: { limit: number; offset: number }): ManualClipList {
  fields(raw, ['schema_version', 'data_kind', 'job_id', 'items', 'total', 'limit', 'offset'])
  if (raw.schema_version !== '1.0' || raw.data_kind !== 'manual_clip_plan_list' || raw.job_id !== owner.job_id || !uuid(raw.job_id)
    || !integer(raw.total) || !integer(raw.offset) || raw.offset !== expected.offset || !integer(raw.limit, 1) || raw.limit > 50 || raw.limit !== expected.limit
    || !Array.isArray(raw.items) || raw.items.length > raw.limit || raw.items.length > Math.max(0, raw.total - raw.offset)) fail()
  const ids = new Set<string>(), items = raw.items.map(value => {
    fields(value, ['id', 'job_id', 'video_id', 'title', 'revision', 'plan_hash', 'status', 'created_at', 'updated_at'])
    if (!uuid(value.id) || ids.has(value.id) || value.job_id !== owner.job_id || value.video_id !== owner.video_id || !validText(value.title, 120)
      || !integer(value.revision, 1) || !hash(value.plan_hash) || !['DRAFT', 'CONFIRMED'].includes(String(value.status))
      || !date(value.created_at) || !date(value.updated_at) || Date.parse(value.updated_at) < Date.parse(value.created_at)) fail()
    ids.add(value.id); return Object.freeze({ ...value }) as unknown as ManualClipSummary
  })
  return Object.freeze({ ...raw, items: Object.freeze(items) }) as unknown as ManualClipList
}
export function parseManualClipSubmission(raw: unknown, expected: ManualClipSubmissionExpected): ManualClipSubmission {
  fields(raw, ['schema_version', 'data_kind', 'operation', 'job_id', 'plan_id', 'idempotency_key', 'input', 'request_hash', 'revision', 'plan_hash'])
  if (!['CREATE', 'REVISION'].includes(expected.operation)) fail()
  if (expected.operation === 'REVISION' && !uuid(expected.plan_id)) fail()
  let expectedInput: ManualClipInput | ManualClipRevisionInput
  try { expectedInput = parseManualClipInput(typeof expected.input === 'string' ? JSON.parse(expected.input) : expected.input, expected.operation === 'REVISION') }
  catch { return fail() }
  const input = parseManualClipInput(raw.input, expected.operation === 'REVISION')
  if (raw.schema_version !== '1.0' || raw.data_kind !== 'manual_clip_submission' || raw.operation !== expected.operation || !uuid(raw.job_id)
    || raw.job_id !== expected.job_id || !uuid(raw.plan_id) || expected.plan_id != null && raw.plan_id !== expected.plan_id
    || raw.idempotency_key !== expected.key || !uuid(raw.idempotency_key) || !hash(raw.request_hash) || !hash(raw.plan_hash)
    || !integer(raw.revision, 1) || raw.revision !== (expected.operation === 'CREATE' ? 1 : (expectedInput as ManualClipRevisionInput).base_revision + 1)
    || manualClipCanonical(input) !== manualClipCanonical(expectedInput)) fail('MANUAL_CLIP_SUBMISSION_INVALID')
  return Object.freeze({ ...raw, input }) as unknown as ManualClipSubmission
}
export function manualClipSubmissionPlanMatches(plan: ManualClipPlan, receipt: ManualClipSubmission): boolean {
  return plan.id === receipt.plan_id && plan.source.job_id === receipt.job_id && plan.revision === receipt.revision && plan.plan_hash === receipt.plan_hash
    && plan.source.source_fingerprint === receipt.input.source_fingerprint && plan.title === receipt.input.title
    && plan.start_utterance_id === receipt.input.start_utterance_id && plan.end_utterance_id === receipt.input.end_utterance_id
}
/** Only immutable revision content participates; mutable head/status/confirmation fields are excluded. */
export async function verifyManualClipPlan(value: ManualClipPlan): Promise<ManualClipPlan> {
  const plan = parseManualClipPlan(value)
  const body = { schema_version: '1.0', data_kind: 'manual_clip_plan_revision', source_kind: 'MANUAL_ASR', id: plan.id, source: plan.source,
    revision: plan.revision, title: plan.title, start_utterance_id: plan.start_utterance_id, end_utterance_id: plan.end_utterance_id, range: plan.range }
  if (await sha256(manualClipCanonical(body)) !== plan.plan_hash) fail('MANUAL_CLIP_SOURCE_INVALID')
  return plan
}
export async function verifyManualClipSubmission(value: unknown, expected: ManualClipSubmissionExpected): Promise<ManualClipSubmission> {
  const receipt = parseManualClipSubmission(value, expected)
  const ownerId = receipt.operation === 'CREATE' ? receipt.job_id : receipt.plan_id
  const material = `manual-clip-request-v1\n${receipt.operation}\n${ownerId}\n${manualClipCanonical(receipt.input)}\n`
  if (await sha256(material) !== receipt.request_hash) fail('MANUAL_CLIP_SUBMISSION_INVALID')
  return receipt
}
export function manualClipDetailUrl(id: string, revision?: number): string {
  if (!uuid(id) || revision !== undefined && !integer(revision, 1)) fail('MANUAL_CLIP_INVALID')
  return `/api/manual-clip-plans/${id}${revision === undefined ? '' : `?revision=${revision}`}`
}
export function manualClipListUrl(jobId: string, offset = 0, limit = 20): string {
  if (!uuid(jobId) || !integer(offset) || !integer(limit, 1) || limit > 50) fail('MANUAL_CLIP_INVALID')
  return `/api/jobs/${jobId}/manual-clip-plans?${new URLSearchParams({ limit: String(limit), offset: String(offset) })}`
}
export function manualClipErrorCopy(error: unknown): string {
  const code = object(error) && typeof error.code === 'string' ? error.code : ''
  const messages: Record<string, string> = {
    MANUAL_CLIP_INVALID: '人工方案参数无效，请核对标题与起止原句。', MANUAL_CLIP_INVALID_RANGE: '起止原句范围无效，请重新核对；不会自动交换或漏句。',
    MANUAL_CLIP_SOURCE_CHANGED: '人工片段的原句来源已变化，请重新核对来源；当前草稿和原回执保留。', MANUAL_CLIP_SOURCE_INVALID: '人工片段来源暂时无法核验，请稍后重试；当前草稿和原回执保留。',
    MANUAL_CLIP_REVISION_CONFLICT: '人工方案已有不同的新版本。请核对当前版本后明确采用，未覆盖本页草稿。',
    MANUAL_CLIP_CONFIRMATION_REQUIRED: '请先明确确认该人工方案的当前版本，再生成 MP4。',
    MANUAL_CLIP_SUBMISSION_INVALID: '原提交回执未通过身份核对，未认领任何新版本；原标识保留。',
    MANUAL_CLIP_NOT_FOUND: '人工方案不存在，请重新读取历史。', MANUAL_CLIP_REVISION_NOT_FOUND: '该人工修订不存在，原提交标识仍保留。',
    IDEMPOTENCY_CONFLICT: '该原请求标识属于不同内容，已停止提交并保留原记录。',
    NETWORK_UNAVAILABLE: '暂时无法连接服务，当前草稿与原提交标识保留。', REQUEST_TIMEOUT: '请求结果尚未确认，请先核对原回执；不会自动换标识重发。',
    MANUAL_CLIP_PENDING_EXISTS: '已有未核对的人工提交，请先核对原回执；未替换原标识。',
    MANUAL_CLIP_LOCAL_RECEIPT_INVALID: '本机人工提交记录无法核对，已保留原记录，请先处理该记录。',
    MANUAL_CLIP_STORAGE_UNAVAILABLE: '本机暂时无法保存或核对原提交记录，当前草稿与原标识保留。',
  }
  return messages[code] ?? '人工方案响应无法核对，请重试只读核对；当前草稿与原提交标识保留。'
}
export type ManualPlan = ManualClipPlan
export type ManualPlanList = ManualClipList
export type ManualInput = ManualClipInput
export type RevisionInput = ManualClipRevisionInput
export type ManualSubmission = ManualClipSubmission
export const parseManualPlan = parseManualClipPlan
export const parseManualList = parseManualClipList
export const parseManualSubmission = parseManualClipSubmission
