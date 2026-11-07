import type { AnalysisJob } from './api'
import { newestSnapshot, parseAnalysisSnapshot, SnapshotError } from './snapshotStream.ts'

const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
function validBudget(value: number) { return Number.isSafeInteger(value) && value >= 1 && value <= 100 }
export interface AnalysisResumeReceipt { input: string; key: string }

/** An unknown POST outcome must retry with the same source, budget and key. */
export function analysisResumeReceipt(saved: unknown, sourceId: string, maxTextCalls: number, newKey: () => string): AnalysisResumeReceipt {
  if (!uuid.test(sourceId) || !validBudget(maxTextCalls)) throw new SnapshotError()
  const input = JSON.stringify({ source_job_id: sourceId, max_text_calls: maxTextCalls })
  if (saved !== null && typeof saved === 'object' && !Array.isArray(saved)) {
    const value = saved as Record<string, unknown>
    if (value.input === input && typeof value.key === 'string' && uuid.test(value.key)) return { input, key: value.key }
  }
  const key = newKey()
  if (!uuid.test(key)) throw new SnapshotError()
  return { input, key }
}

export function analysisCanResume(source: AnalysisJob | null, dirty: boolean, pending: boolean): boolean {
  return source?.actions?.can_resume_from_asr === true && !dirty && !pending
}

/** The server may already have advanced this new job before its POST response arrives. */
export function parseResumedAnalysisSnapshot(raw: unknown, source: AnalysisJob, maxTextCalls: number): AnalysisJob {
  if (raw === null || typeof raw !== 'object' || !('id' in raw) || typeof raw.id !== 'string' || !validBudget(maxTextCalls)) throw new SnapshotError()
  const value = parseAnalysisSnapshot(raw, raw.id)
  if (value.id === source.id || value.video_id !== source.video_id || value.resumed_from_job_id !== source.id
    || value.generation <= source.generation || value.config?.analysis_mode !== 'topics'
    || value.budget.max_text_calls !== maxTextCalls || value.budget.max_asr_submissions !== 0
    || value.budget.asr_calls_reserved !== 0) throw new SnapshotError()
  return value
}

/** Same-job updates cannot change the immutable source or accounting contract. */
export function newestAnalysisSnapshot(known: AnalysisJob | null | undefined, incoming: AnalysisJob): AnalysisJob {
  if (known?.id === incoming.id && (known.video_id !== incoming.video_id || known.generation !== incoming.generation
    || known.resumed_from_job_id !== incoming.resumed_from_job_id || known.budget.max_text_calls !== incoming.budget.max_text_calls
    || known.budget.max_asr_submissions !== incoming.budget.max_asr_submissions)) throw new SnapshotError()
  return newestSnapshot(known, incoming)
}

export function analysisRetryExplanation(source: AnalysisJob | null): string {
  if (source?.actions?.retry_block_reason === 'TEXT_RECOVERY_EXHAUSTED') {
    return '本次文本请求的自动恢复次数或等待窗口已耗尽。可使用“复用转写重新分析”，以新的文本请求预算继续，原任务与调用记录保留。'
  }
  if (source?.actions?.retry_block_reason === 'ATTEMPTS_EXHAUSTED') {
    return `失败阶段已达到 ${source.actions.max_attempts} 次执行上限。${source.actions.can_resume_from_asr ? '可使用“复用转写重新分析”，新任务使用独立的文本请求预算。' : '可新建一次分析，新任务使用独立的模型调用预算，并会重新提交 ASR。'}`
  }
  return '当前没有可重试的失败阶段，请刷新任务状态，或新建一次分析。'
}
