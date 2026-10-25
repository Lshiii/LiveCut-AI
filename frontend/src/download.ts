import { ApiError } from './api'

/** Preserve the server's exact JSON bytes, including its immutable document hash. */
export async function downloadJson(path: string): Promise<void> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(path, { cache: 'no-store', signal: controller.signal })
    if (!response.ok) {
      const failure = await response.json().catch(() => null)
      throw new ApiError(failure?.error_code ?? `HTTP_${response.status}`, failure?.request_id)
    }
    const disposition = response.headers.get('Content-Disposition') ?? ''
    const filename = /^attachment;\s*filename="([a-z0-9-]{1,150}\.json)"$/i.exec(disposition)?.[1]
    if (!filename || !/^application\/json(?:;|$)/i.test(response.headers.get('Content-Type') ?? '')) {
      throw new ApiError('INVALID_SERVER_RESPONSE')
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = filename
    document.body.append(link)
    try { link.click() } finally {
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(controller.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_UNAVAILABLE')
  } finally { window.clearTimeout(timer) }
}
