import type { StageDiagnostic } from './diagnostics'
import type { TextProgress } from './textProgress'

export interface VideoAsset {
  id: string
  original_filename: string
  content_hash: string
  size_bytes: number
  content_url: string
  media_validation_status: string
  created_at: string
}
export interface Stage { id: string; stage_type: string; status: string; attempt: number; error_code: string | null; diagnostic?: StageDiagnostic; text_progress?: TextProgress }
export interface JobActions {
  can_cancel: boolean
  can_retry: boolean
  retry_block_reason: 'JOB_NOT_RETRYABLE' | 'ATTEMPTS_EXHAUSTED' | 'TEXT_RECOVERY_EXHAUSTED' | null
  can_resume_from_asr?: boolean
  resume_from_asr_block_reason?: 'RESUME_REQUIRES_TOPICS' | 'JOB_NOT_RESUMABLE' | 'PREPARATION_NOT_READY' | 'PREPARATION_SOURCE_INVALID' | null
  max_attempts: number
}
export interface AnalysisJob {
  config?: Record<string, unknown>
  resumed_from_job_id?: string
  id: string; video_id: string; generation: number; status: string; stages: Stage[]; created_at: string; state_version: number
  budget: { max_text_calls: number; text_calls_reserved: number; max_asr_submissions: number; asr_calls_reserved: number }
  actions: JobActions
}
export interface ExportClip {
  id: string; segment_id: string; product_id: string | null; product_name: string | null; title?: string
  start_ms: number; end_ms: number; stage_id: string; status: string; attempt: number
  error_code: string | null; size_bytes: number | null; content_url: string | null; download_url: string | null; diagnostic?: StageDiagnostic
}
export interface ExportJob {
  id: string; job_id: string; video_id: string; approved_revision: number; snapshot_hash: string
  profile: string; status: string; state_version: number; created_at: string; updated_at: string; clips: ExportClip[]
  clip_request_id?: string | null; manual_clip_plan_id?: string
}
export interface Page<T> { items: T[]; limit: number; offset: number }
export class ApiError extends Error {
  constructor(public code: string, public requestId?: string, public status?: number) { super(code) }
}
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (init.signal?.aborted) controller.abort()
  else init.signal?.addEventListener('abort', abort, { once: true })
  const timeout = window.setTimeout(() => controller.abort(), init.body instanceof FormData ? 300000 : 15000)
  try {
    const response = await fetch(path, { cache: 'no-store', ...init, signal: controller.signal })
    const body = await response.json().catch(error => { if (controller.signal.aborted) throw error; return null })
    if (!response.ok) throw new ApiError(body?.error_code ?? `HTTP_${response.status}`, body?.request_id, response.status)
    if (!body || typeof body !== 'object') throw new ApiError('INVALID_SERVER_RESPONSE')
    return body as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(controller.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_UNAVAILABLE')
  } finally { window.clearTimeout(timeout); init.signal?.removeEventListener('abort', abort) }
}
export function post<T>(path: string, payload?: unknown, headers: Record<string, string> = {}): Promise<T> {
  return api<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers },
    ...(payload === undefined ? {} : { body: JSON.stringify(payload) }) })
}
