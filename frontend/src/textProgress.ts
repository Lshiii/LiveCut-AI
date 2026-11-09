/** Public recovery state is optional; never render arbitrary provider metadata. */
export interface TextProgress {
  state: 'REQUESTING' | 'RETRY_WAIT' | 'SWITCHING_PROVIDER' | 'SUCCEEDED' | 'EXHAUSTED'
  attempt_number: number; max_attempts: number; provider: string; model_id: string
  timeout_seconds: number; elapsed_seconds: number; retry_in_seconds: number; error_code?: string
}
const states = new Set(['REQUESTING', 'RETRY_WAIT', 'SWITCHING_PROVIDER', 'SUCCEEDED', 'EXHAUSTED'])
/** Polling recreates objects; only a changed event payload resets the clock. */
export function textProgressSignature(progress: TextProgress | undefined): string {
  return progress ? JSON.stringify([progress.state, progress.attempt_number, progress.max_attempts, progress.provider,
    progress.model_id, progress.timeout_seconds, progress.elapsed_seconds, progress.retry_in_seconds, progress.error_code ?? null]) : ''
}
/** Monotonic local time supplements an active event; terminal counts stay authoritative. */
export function advanceTextProgress(progress: TextProgress, anchoredAt: number, now: number): TextProgress {
  const seconds = Number.isFinite(anchoredAt) && Number.isFinite(now) ? Math.max(0, Math.floor((now - anchoredAt) / 1000)) : 0
  if (progress.state === 'REQUESTING') return { ...progress, elapsed_seconds: progress.elapsed_seconds + seconds }
  if (progress.state === 'RETRY_WAIT') return { ...progress, retry_in_seconds: Math.max(0, progress.retry_in_seconds - seconds) }
  return progress
}
function integer(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
}
export function parseTextProgress(raw: unknown): TextProgress | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const value = raw as Record<string, unknown>
  if (typeof value.state !== 'string' || !states.has(value.state)
    || !integer(value.attempt_number, 1, 3) || !integer(value.max_attempts, value.attempt_number, 3)
    || typeof value.provider !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(value.provider)
    || typeof value.model_id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(value.model_id)
    || !integer(value.timeout_seconds, 1, 300) || !integer(value.elapsed_seconds, 0, 1800)
    || !integer(value.retry_in_seconds, 0, 60)
    || !(value.error_code === undefined || value.error_code === null
      || (typeof value.error_code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.error_code)))) return undefined
  return { state: value.state as TextProgress['state'], attempt_number: value.attempt_number,
    max_attempts: value.max_attempts, provider: value.provider, model_id: value.model_id,
    timeout_seconds: value.timeout_seconds, elapsed_seconds: value.elapsed_seconds, retry_in_seconds: value.retry_in_seconds,
    ...(typeof value.error_code === 'string' ? { error_code: value.error_code } : {}) }
}
export function textProgressCopy(progress: TextProgress): { title: string; detail: string } {
  const attempt = `第 ${progress.attempt_number} / ${progress.max_attempts} 次尝试`
  if (progress.state === 'EXHAUSTED' && progress.error_code === 'PROVIDER_QUOTA_EXHAUSTED') {
    const failure = textRecoveryFailureCopy(progress.error_code)!
    return { title: failure.title, detail: `${attempt} · 本轮已用 ${progress.elapsed_seconds} 秒。${failure.detail}` }
  }
  switch (progress.state) {
    case 'RETRY_WAIT': return { title: '短暂异常，正在自动恢复', detail: `${progress.retry_in_seconds} 秒后重试 · ${attempt}。已完成的语音转写和分析进度会保留。` }
    case 'SWITCHING_PROVIDER': return { title: '正在切换备用文本服务', detail: `${attempt} · 单次上限 ${progress.timeout_seconds} 秒。将继续处理当前内容。` }
    case 'SUCCEEDED': return { title: '本次文本请求已完成', detail: `${attempt} · 本轮已用 ${progress.elapsed_seconds} 秒，继续处理后续内容。` }
    case 'EXHAUSTED': return { title: '本轮自动恢复已结束', detail: `${attempt} · 本轮已用 ${progress.elapsed_seconds} 秒。请查看下方失败原因，核对后再重试；已完成的语音转写会保留。` }
    default: return { title: '正在请求文本服务', detail: `${attempt} · 单次上限 ${progress.timeout_seconds} 秒 · 本轮已用 ${progress.elapsed_seconds} 秒。短暂错误会在剩余次数内自动恢复。` }
  }
}
export function textRecoveryFailureCopy(errorCode: string | null): { title: string; detail: string } | undefined {
  if (errorCode === 'PROVIDER_QUOTA_EXHAUSTED') {
    return { title: '模型服务额度不足', detail: '模型平台的免费额度可能已用完，或账户可用额度不足。请先到模型平台核对额度与计费限制，再决定是否调整配置后重试。' }
  }
  if (errorCode === 'TEXT_RETRIES_EXHAUSTED' || errorCode === 'TEXT_RETRY_DEADLINE_EXCEEDED') {
    return { title: errorCode === 'TEXT_RETRIES_EXHAUSTED' ? '文本服务自动尝试次数已用尽' : '文本服务自动恢复已达时间上限',
      detail: '请稍后再试或检查备用服务配置。已完成的语音转写会保留，重试仍受本任务原调用预算限制。' }
  }
  if (['INVALID_TEXT_RETRY_CONFIGURATION', 'TEXT_RETRY_POLICY_CONFLICT', 'TEXT_FALLBACK_NOT_ALLOWED'].includes(errorCode ?? '')) {
    return { title: '文本服务恢复配置需要核对', detail: '请先检查主服务、备用服务与本任务允许的模型配置，再继续处理。已完成的语音转写会保留。' }
  }
  return undefined
}
