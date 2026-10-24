export interface VideoAsset {
  id: string
  original_filename: string
  content_hash: string
  size_bytes: number
  content_url: string
  media_validation_status: string
  created_at: string
}
export interface Stage { id: string; stage_type: string; status: string; attempt: number; error_code: string | null }
export interface AnalysisJob {
  id: string; video_id: string; generation: number; status: string; stages: Stage[]; created_at: string
  budget: { max_text_calls: number; text_calls_reserved: number; max_asr_submissions: number; asr_calls_reserved: number }
}
export interface Page<T> { items: T[]; limit: number; offset: number }
export class ApiError extends Error {
  constructor(public code: string, public requestId?: string) { super(code) }
}
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), init.body instanceof FormData ? 300000 : 15000)
  try {
    const response = await fetch(path, { cache: 'no-store', ...init, signal: controller.signal })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new ApiError(body?.error_code ?? `HTTP_${response.status}`, body?.request_id)
    if (!body || typeof body !== 'object') throw new ApiError('INVALID_SERVER_RESPONSE')
    return body as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(controller.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_UNAVAILABLE')
  } finally { window.clearTimeout(timeout) }
}
export function post<T>(path: string, payload?: unknown, headers: Record<string, string> = {}): Promise<T> {
  return api<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers },
    ...(payload === undefined ? {} : { body: JSON.stringify(payload) }) })
}
