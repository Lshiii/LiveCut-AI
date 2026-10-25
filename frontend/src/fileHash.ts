import { UploadError, UploadPaused } from './upload'

export function hashFileInWorker(file: File, signal: AbortSignal, progress: (bytes: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new UploadPaused()); return }
    let worker: Worker
    try { worker = new Worker(new URL('./fileHash.worker.ts', import.meta.url), { type: 'module' }) }
    catch { reject(new UploadError('FILE_HASH_FAILED')); return }
    const cleanup = () => { signal.removeEventListener('abort', abort); worker.onmessage = null; worker.onerror = null; worker.terminate() }
    const abort = () => { cleanup(); reject(new UploadPaused()) }
    signal.addEventListener('abort', abort, { once: true })
    worker.onmessage = (event: MessageEvent<{ bytes?: number; hash?: string; error?: string }>) => {
      if (signal.aborted) return
      if (event.data.error) { cleanup(); reject(new UploadError('FILE_HASH_FAILED')) }
      else if (event.data.hash) { cleanup(); resolve(event.data.hash) }
      else if (typeof event.data.bytes === 'number') progress(event.data.bytes)
    }
    worker.onerror = () => { cleanup(); reject(new UploadError('FILE_HASH_FAILED')) }
    try { worker.postMessage(file) } catch { cleanup(); reject(new UploadError('FILE_HASH_FAILED')) }
  })
}
