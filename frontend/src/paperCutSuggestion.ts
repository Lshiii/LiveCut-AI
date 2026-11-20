import { compilePaperCut, type PaperCutSource } from './paperCut.ts'

export interface PaperCutContext {
  requestId: string
  revision: number
  planHash: string
  outputId: string
}
export interface SuggestionRow {
  utterance_id: string
  part_index: number
  evidence: boolean
  anchor: boolean
}
export interface SuggestionInput {
  base_revision: number
  plan_hash: string
  output_id: string
  rows: SuggestionRow[]
  instruction: string
  target_ms?: number
}
export interface SuggestionReceipt {
  key: string
  requestId: string
  input: SuggestionInput
}
export interface SuggestionResult {
  remove_ids: string[]
  reason: string
  target_ms: number | null
}
export type SuggestionStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export interface SuggestionView {
  id: string
  status: SuggestionStatus
  result: SuggestionResult | null
  error_code: string | null
}

export function suggestionInput(context: PaperCutContext, source: PaperCutSource, removed: ReadonlySet<string>,
  instruction: string, targetMs?: number): SuggestionInput {
  const text = instruction.trim()
  if (!text || text.length > 1000) throw new Error('请输入 1 到 1000 字的改剪要求。')
  if (targetMs !== undefined && (!Number.isSafeInteger(targetMs) || targetMs < 1)) throw new Error('请填写有效的时长上限。')
  const { parts } = compilePaperCut(source, removed)
  const starts = new Set(parts.map(part => part.start_utterance_id))
  const rows: SuggestionRow[] = []
  let partIndex = -1, units = 0
  for (const { utterance, evidence, anchor } of source.rows) {
    if (removed.has(utterance.utterance_id)) continue
    if (starts.has(utterance.utterance_id)) partIndex++
    rows.push({ utterance_id: utterance.utterance_id, part_index: partIndex, evidence, anchor })
    units += utterance.text.length
  }
  if (rows.length > 128 || units > 24000) throw new Error('当前草稿超过单次建议的范围，请先缩小至 128 句、24000 字以内。')
  return { base_revision: context.revision, plan_hash: context.planHash, output_id: context.outputId, rows, instruction: text,
    ...(targetMs === undefined ? {} : { target_ms: targetMs }) }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const invalid = () => new Error('建议回执与本次草稿不符，请重新核对。')

export function restoredSuggestion(raw: unknown, context: PaperCutContext, source: PaperCutSource, removed: ReadonlySet<string>): SuggestionReceipt | null {
  if (!record(raw) || typeof raw.key !== 'string' || !uuid.test(raw.key) || raw.requestId !== context.requestId
    || !record(raw.input) || typeof raw.input.instruction !== 'string') return null
  try {
    if (raw.input.target_ms !== undefined && typeof raw.input.target_ms !== 'number') return null
    const input = suggestionInput(context, source, removed, raw.input.instruction, raw.input.target_ms)
    return JSON.stringify(raw.input) === JSON.stringify(input) ? { key: raw.key, requestId: context.requestId, input } : null
  } catch { return null }
}

export function parseSuggestion(raw: unknown, receipt: SuggestionReceipt, expectedId?: string): SuggestionView {
  if (!record(raw) || typeof raw.id !== 'string' || !uuid.test(raw.id) || (expectedId !== undefined && raw.id !== expectedId)
    || raw.idempotency_key !== receipt.key || raw.clip_request_id !== receipt.requestId
    || raw.base_revision !== receipt.input.base_revision || raw.plan_hash !== receipt.input.plan_hash || raw.output_id !== receipt.input.output_id
    || !['QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'].includes(String(raw.status))
    || !(raw.error_code === null || typeof raw.error_code === 'string')) throw invalid()
  const result = raw.result
  if (raw.status === 'SUCCEEDED') {
    if (!record(result) || !Array.isArray(result.remove_ids) || result.remove_ids.some(id => typeof id !== 'string')
      || new Set(result.remove_ids).size !== result.remove_ids.length || result.remove_ids.length > receipt.input.rows.length
      || typeof result.reason !== 'string' || !result.reason.trim() || (receipt.input.target_ms !== undefined && result.target_ms !== receipt.input.target_ms)
      || !(result.target_ms === null || (typeof result.target_ms === 'number' && Number.isSafeInteger(result.target_ms) && result.target_ms > 0))) throw invalid()
  } else if (result !== null) throw invalid()
  return { id: raw.id, status: raw.status as SuggestionStatus, result: result as SuggestionResult | null, error_code: raw.error_code }
}

export function previewSuggestion(source: PaperCutSource, removed: ReadonlySet<string>, result: SuggestionResult) {
  const deletions = new Set(result.remove_ids)
  if (deletions.size !== result.remove_ids.length || result.remove_ids.some(id => removed.has(id))) throw invalid()
  const cut = compilePaperCut(source, new Set([...removed, ...deletions]))
  if (result.target_ms !== null && cut.durationMs > result.target_ms && deletions.size > 0)
    throw new Error('建议保留的区间超过目标时长，请调整要求后重试。')
  return { ids: deletions, cut }
}
