import type { VideoAsset } from './api'

export const MAX_UPLOAD_BYTES = 1_073_741_824
const HASH = /^[a-f0-9]{64}$/
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const STATUSES = new Set(['UPLOADING', 'MERGING', 'COMPLETED', 'FAILED', 'EXPIRED'])
export interface UploadIdentity { original_filename: string; size_bytes: number; content_hash: string }
export interface UploadPart { index: number; size_bytes: number; sha256: string }
export interface UploadSnapshot extends UploadIdentity {
  id: string; status: string; chunk_size: number; total_parts: number; received_parts: UploadPart[]
  received_bytes: number; state_version: number; expires_at: string
  video_id: string | null; video: VideoAsset | null; error_code: string | null
}
export type UploadPhase = 'idle' | 'hashing' | 'checking' | 'uploading' | 'merging' | 'paused' | 'completed' | 'error' | 'discarding'
export interface UploadState {
  phase: UploadPhase; filename: string; size: number; hashedBytes: number; snapshot: UploadSnapshot | null
  resumedParts: number; retryAttempt: number; error: string; storageAvailable: boolean
}
interface ResumeRecord { key: string; uploadId?: string }
interface StorageLike { getItem(key: string): string | null; setItem(key: string, value: string): void; removeItem(key: string): void }
export interface UploadDependencies {
  hashFile(file: File, signal: AbortSignal, progress: (bytes: number) => void): Promise<string>
  hashPart(blob: Blob): Promise<string>
  fetch?: typeof fetch; storage?: StorageLike | null; randomUUID?: () => string
  sleep?: (milliseconds: number, signal: AbortSignal) => Promise<void>; pollMs?: number; requestTimeoutMs?: number
}
export class UploadError extends Error {
  readonly code: string
  readonly status?: number
  readonly requestId?: string
  constructor(code: string, status?: number, requestId?: string) {
    super(code); this.code = code; this.status = status; this.requestId = requestId
  }
}
export class UploadPaused extends Error { constructor() { super('UPLOAD_PAUSED') } }

function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function integer(value: unknown, min: number, max = Number.MAX_SAFE_INTEGER): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
}
function invalid(): never { throw new UploadError('INVALID_SERVER_RESPONSE') }
export function uploadStorageKey(identity: UploadIdentity) {
  return 'livecut-upload-v1:' + JSON.stringify([identity.original_filename, identity.size_bytes, identity.content_hash])
}
export function validateUploadFile(file: Pick<File, 'name' | 'size'>) {
  const base = file.name.replaceAll('\\', '/').split('/').at(-1) ?? ''
  // Match Java String.strip, which deliberately differs from JS trim for NBSP.
  const whitespace = (value: number) => (value >= 9 && value <= 13) || (value >= 28 && value <= 32)
    || value === 0x1680 || (value >= 0x2000 && value <= 0x2006) || (value >= 0x2008 && value <= 0x200a)
    || value === 0x2028 || value === 0x2029 || value === 0x205f || value === 0x3000
  const characters = [...base]
  while (characters.length && whitespace(characters[0]!.codePointAt(0)!)) characters.shift()
  while (characters.length && whitespace(characters.at(-1)!.codePointAt(0)!)) characters.pop()
  const name = characters.join('')
  if (!name.toLowerCase().endsWith('.mp4') || !integer(file.size, 1, MAX_UPLOAD_BYTES) || characters.length > 255
      || characters.some(value => { const cp = value.codePointAt(0)!; return cp < 32 || (cp >= 127 && cp <= 159) })) throw new UploadError('INVALID_UPLOAD_FILE')
  return name
}
export function parseUploadSnapshot(raw: unknown, identity: UploadIdentity, uploadId?: string): UploadSnapshot {
  if (!object(raw) || typeof raw.id !== 'string' || !UUID.test(raw.id) || (uploadId && raw.id !== uploadId)
      || typeof raw.status !== 'string' || !STATUSES.has(raw.status)
      || raw.original_filename !== identity.original_filename || raw.size_bytes !== identity.size_bytes || raw.content_hash !== identity.content_hash
      || !integer(raw.chunk_size, 16, 8_388_608) || !integer(raw.total_parts, 1, 4096)
      || raw.total_parts !== Math.ceil(identity.size_bytes / raw.chunk_size)
      || !integer(raw.state_version, 1) || typeof raw.expires_at !== 'string' || !Number.isFinite(Date.parse(raw.expires_at))
      || !Array.isArray(raw.received_parts) || !integer(raw.received_bytes, 0, identity.size_bytes)
      || !(raw.error_code === null || (typeof raw.error_code === 'string' && /^[A-Z0-9_]{1,64}$/.test(raw.error_code)))) invalid()
  const seen = new Set<number>(), parts: UploadPart[] = []
  let bytes = 0
  for (const part of raw.received_parts) {
    if (!object(part) || !integer(part.index, 0, raw.total_parts - 1) || seen.has(part.index)
        || part.size_bytes !== Math.min(raw.chunk_size, identity.size_bytes - part.index * raw.chunk_size)
        || typeof part.sha256 !== 'string' || !HASH.test(part.sha256)) invalid()
    seen.add(part.index); bytes += part.size_bytes as number
    parts.push({ index: part.index, size_bytes: part.size_bytes as number, sha256: part.sha256 })
  }
  if (bytes !== raw.received_bytes || (['MERGING', 'COMPLETED'].includes(raw.status) && parts.length !== raw.total_parts)) invalid()
  let video: VideoAsset | null = null
  if (raw.status === 'COMPLETED') {
    const asset = raw.video
    if (!object(asset) || typeof asset.id !== 'string' || !UUID.test(asset.id) || raw.video_id !== asset.id
        || asset.content_hash !== identity.content_hash || asset.size_bytes !== identity.size_bytes
        || asset.original_filename !== identity.original_filename || asset.content_url !== `/api/videos/${asset.id}/content`
        || asset.media_validation_status !== 'HEADER_CHECKED' || typeof asset.created_at !== 'string'
        || !Number.isFinite(Date.parse(asset.created_at))) invalid()
    video = asset as unknown as VideoAsset
  } else if (raw.video_id !== null || raw.video !== null) invalid()
  return { ...identity, id: raw.id, status: raw.status, chunk_size: raw.chunk_size, total_parts: raw.total_parts,
    received_parts: parts.sort((a, b) => a.index - b.index), received_bytes: bytes, state_version: raw.state_version,
    expires_at: raw.expires_at, video_id: video?.id ?? null, video, error_code: raw.error_code as string | null }
}
export function newestUploadSnapshot(current: UploadSnapshot | null, incoming: UploadSnapshot) {
  if (current?.id === incoming.id) {
    if (current.chunk_size !== incoming.chunk_size || current.total_parts !== incoming.total_parts) invalid()
    if (current.state_version > incoming.state_version
        || (current.state_version === incoming.state_version && (current.received_bytes > incoming.received_bytes || current.status !== incoming.status))) return current
    if (current.status === 'COMPLETED' && (incoming.status !== 'COMPLETED' || current.video_id !== incoming.video_id)) invalid()
  }
  return incoming
}
export function uploadErrorText(error: unknown) {
  const code = error instanceof UploadError ? error.code : 'UPLOAD_CLIENT_ERROR'
  const messages: Record<string, string> = {
    INVALID_UPLOAD_FILE: '请选择非空 MP4 视频，大小不超过 1 GiB。',
    INVALID_SERVER_RESPONSE: '上传服务返回了不完整或不匹配的数据。续传记录已保留。',
    NETWORK_UNAVAILABLE: '网络暂时不可用。已保留续传进度，恢复网络后可继续。',
    REQUEST_TIMEOUT: '上传请求超时。进度已保留，继续时会重新核对服务端状态。',
    UPLOAD_CAPACITY_EXCEEDED: '上传暂存空间或会话数已满，请稍后继续，或放弃不再需要的上传。',
    UPLOAD_MERGE_BUSY: '合并服务暂时繁忙。进度已保留，请稍后继续。',
    PART_CONFLICT: '服务端同一分片存在不同内容，请放弃本次上传后重新选择原文件。',
    PART_HASH_MISMATCH: '分片内容校验不一致，请检查原文件是否被修改。',
    PART_SIZE_MISMATCH: '分片长度校验未通过，请重新选择原文件。',
    UPLOAD_EXPIRED: '本次上传已过期，重新选择原文件后可建立新上传。',
    UPLOAD_FAILED: '合并未完成，请查看错误提示，必要时放弃本次上传后重新开始。',
    UPLOAD_ALREADY_COMPLETED: '视频已完成上传，请继续查询完成结果。',
    UPLOAD_LOCAL_STATE_INVALID: '本地续传记录无效，请放弃此记录后重新开始。',
    FILE_HASH_FAILED: '无法读取并校验文件，请重新选择原文件。',
    UPLOAD_BUSY: '请先暂停当前上传，再选择其他文件。',
  }
  const text = messages[code] ?? `上传未完成（${code}）。续传记录已保留。`
  return error instanceof UploadError && error.requestId ? `${text}（请求 ${error.requestId}）` : text
}
export function uploadSleep(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new UploadPaused()); return }
    const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', abort); resolve() }
    const abort = () => { clearTimeout(timer); signal.removeEventListener('abort', abort); reject(new UploadPaused()) }
    const timer = setTimeout(finish, milliseconds)
    signal.addEventListener('abort', abort, { once: true })
  })
}
export class ResumableUploader {
  state: UploadState = { phase: 'idle', filename: '', size: 0, hashedBytes: 0, snapshot: null, resumedParts: 0, retryAttempt: 0, error: '', storageAvailable: true }
  private controller: AbortController | null = null
  private epoch = 0
  private destroyed = false
  private file: File | null = null
  private identity: UploadIdentity | null = null
  private record: ResumeRecord | null = null
  private records = new Map<string, ResumeRecord>()
  private fetcher: typeof fetch
  private sleep: (ms: number, signal: AbortSignal) => Promise<void>
  private changed: (state: UploadState) => void
  private dependencies: UploadDependencies
  constructor(changed: (state: UploadState) => void, dependencies: UploadDependencies) {
    this.changed = changed; this.dependencies = dependencies
    // Browser fetch is a host function; calling an unbound copy as an uploader
    // method changes its receiver and can throw before any request is sent.
    this.fetcher = dependencies.fetch ?? ((input, init) => fetch(input, init))
    this.sleep = dependencies.sleep ?? uploadSleep
  }
  get busy() { return ['hashing', 'checking', 'uploading', 'merging', 'discarding'].includes(this.state.phase) }
  get canResume() { return this.file !== null && ['paused', 'error'].includes(this.state.phase) }
  private emit(patch: Partial<UploadState>) {
    this.state = { ...this.state, ...patch }
    if (!this.destroyed) this.changed(this.state)
  }
  private active(epoch: number, signal: AbortSignal) { return !this.destroyed && this.epoch === epoch && !signal.aborted }
  private check(epoch: number, signal: AbortSignal) { if (!this.active(epoch, signal)) throw new UploadPaused() }
  pause() {
    if (this.destroyed || !this.busy) return
    this.epoch++; this.controller?.abort(); this.controller = null
    this.emit({ phase: 'paused', retryAttempt: 0, error: '' })
  }
  destroy() { this.destroyed = true; this.epoch++; this.controller?.abort(); this.controller = null; this.file = null }
  async resume() { if (this.file) return this.start(this.file) }
  async start(file: File): Promise<VideoAsset | null> {
    if (this.destroyed) return null
    if (this.busy) throw new UploadError('UPLOAD_BUSY')
    const filename = validateUploadFile(file)
    const epoch = ++this.epoch, controller = new AbortController(), signal = controller.signal
    this.controller = controller
    if (this.file !== file) { this.identity = null; this.record = null }
    this.file = file
    this.emit({ phase: 'hashing', filename, size: file.size, hashedBytes: 0, snapshot: null, resumedParts: 0, retryAttempt: 0, error: '' })
    try {
      const hash = await this.dependencies.hashFile(file, signal, bytes => {
        if (this.active(epoch, signal)) this.emit({ hashedBytes: Math.min(file.size, Math.max(0, bytes)) })
      })
      this.check(epoch, signal)
      if (!HASH.test(hash)) throw new UploadError('FILE_HASH_FAILED')
      const identity = { original_filename: filename, size_bytes: file.size, content_hash: hash }
      this.identity = identity; this.emit({ phase: 'checking', hashedBytes: file.size })
      let snapshot = await this.resolve(identity, epoch, signal)
      this.check(epoch, signal)
      this.emit({ resumedParts: snapshot.received_parts.length })
      if (snapshot.status === 'UPLOADING') {
        this.emit({ phase: 'uploading' })
        await this.parts(file, snapshot, identity, epoch, signal)
        this.check(epoch, signal)
        snapshot = this.state.snapshot ?? snapshot
        if (snapshot.status === 'UPLOADING') {
          this.emit({ phase: 'merging' })
          snapshot = await this.snapshotRequest(`/api/uploads/${snapshot.id}/complete`, { method: 'POST' }, identity, snapshot.id, epoch, signal)
        }
      }
      while (snapshot.status === 'MERGING') {
        this.emit({ phase: 'merging' })
        await this.sleep(this.dependencies.pollMs ?? 1000, signal)
        snapshot = await this.snapshotRequest(`/api/uploads/${snapshot.id}`, {}, identity, snapshot.id, epoch, signal)
      }
      this.check(epoch, signal)
      if (snapshot.status !== 'COMPLETED' || !snapshot.video) throw new UploadError(snapshot.status === 'EXPIRED' ? 'UPLOAD_EXPIRED' : snapshot.error_code ?? 'UPLOAD_FAILED')
      this.forget(identity); this.emit({ phase: 'completed', snapshot, retryAttempt: 0 })
      return snapshot.video
    } catch (error) {
      if (this.active(epoch, signal)) this.emit({ phase: 'error', error: uploadErrorText(error), retryAttempt: 0 })
      return null
    } finally { if (this.controller === controller) this.controller = null }
  }
  private readRecord(identity: UploadIdentity): ResumeRecord | null {
    const key = uploadStorageKey(identity), memory = this.records.get(key)
    if (memory) return memory
    let value: string | null = null
    try { value = this.dependencies.storage?.getItem(key) ?? null }
    catch { this.emit({ storageAvailable: false }) }
    if (!value) return null
    try {
      const parsed = JSON.parse(value)
      if (!object(parsed) || typeof parsed.key !== 'string' || !UUID.test(parsed.key)
          || !(parsed.uploadId === undefined || (typeof parsed.uploadId === 'string' && UUID.test(parsed.uploadId)))) throw new Error()
      const record = parsed as unknown as ResumeRecord; this.records.set(key, record); return record
    } catch { throw new UploadError('UPLOAD_LOCAL_STATE_INVALID') }
  }
  private remember(identity: UploadIdentity, record: ResumeRecord) {
    this.record = record; const key = uploadStorageKey(identity); this.records.set(key, record)
    try { if (this.dependencies.storage) this.dependencies.storage.setItem(key, JSON.stringify(record)); else this.emit({ storageAvailable: false }) }
    catch { this.emit({ storageAvailable: false }) }
  }
  private forget(identity: UploadIdentity) {
    const key = uploadStorageKey(identity); this.records.delete(key)
    try { this.dependencies.storage?.removeItem(key) } catch { this.emit({ storageAvailable: false }) }
    this.record = null
  }
  private adopt(snapshot: UploadSnapshot, epoch: number, signal: AbortSignal) {
    this.check(epoch, signal)
    const newest = newestUploadSnapshot(this.state.snapshot, snapshot)
    this.emit({ snapshot: newest }); return newest
  }
  private async resolve(identity: UploadIdentity, epoch: number, signal: AbortSignal, allowExpiredRestart = true): Promise<UploadSnapshot> {
    let record = this.readRecord(identity)
    if (record?.uploadId) {
      this.record = record
      try {
        const found = await this.snapshotRequest(`/api/uploads/${record.uploadId}`, {}, identity, record.uploadId, epoch, signal)
        if (found.status !== 'EXPIRED') return found
      } catch (error) { if (!(error instanceof UploadError) || error.code !== 'UPLOAD_EXPIRED') throw error }
      this.forget(identity); record = null; this.emit({ snapshot: null })
    }
    if (!record) record = { key: (this.dependencies.randomUUID ?? (() => crypto.randomUUID()))() }
    this.remember(identity, record) // Persist before POST; a lost response can reuse this exact key.
    let snapshot: UploadSnapshot | null = null
    try {
      snapshot = await this.snapshotRequest('/api/uploads', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': record.key }, body: JSON.stringify(identity) }, identity, undefined, epoch, signal)
    } catch (error) {
      // A lost initialization response can leave only its idempotency key in
      // local storage. Java returns 410 when that key's session has expired.
      if (!(error instanceof UploadError) || error.code !== 'UPLOAD_EXPIRED' || !allowExpiredRestart) throw error
    }
    if (snapshot === null || (snapshot.status === 'EXPIRED' && allowExpiredRestart)) {
      this.forget(identity); this.emit({ snapshot: null })
      return this.resolve(identity, epoch, signal, false)
    }
    this.remember(identity, { ...record, uploadId: snapshot.id })
    return snapshot
  }
  private async parts(file: File, snapshot: UploadSnapshot, identity: UploadIdentity, epoch: number, signal: AbortSignal) {
    const received = new Set(snapshot.received_parts.map(part => part.index))
    const queue = Array.from({ length: snapshot.total_parts }, (_, index) => index).filter(index => !received.has(index))
    const group = new AbortController(), abort = () => group.abort()
    signal.addEventListener('abort', abort, { once: true })
    let cursor = 0, failure: unknown = null
    const worker = async () => {
      while (failure === null && cursor < queue.length && this.state.snapshot?.status === 'UPLOADING') {
        this.check(epoch, signal)
        const index = queue[cursor++]!
        try {
          const blob = file.slice(index * snapshot.chunk_size, Math.min(file.size, (index + 1) * snapshot.chunk_size))
          const digest = await this.dependencies.hashPart(blob)
          this.check(epoch, signal)
          if (!HASH.test(digest)) throw new UploadError('FILE_HASH_FAILED')
          await this.snapshotRequest(`/api/uploads/${snapshot.id}/parts/${index}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': digest }, body: blob,
          }, identity, snapshot.id, epoch, group.signal)
        } catch (error) { if (failure === null) { failure = error; group.abort() } }
      }
    }
    try {
      await Promise.all(Array.from({ length: Math.min(3, queue.length) }, () => worker()))
      if (failure !== null) throw failure
      this.check(epoch, signal)
    } finally { signal.removeEventListener('abort', abort); group.abort() }
  }
  private async snapshotRequest(path: string, init: RequestInit, identity: UploadIdentity, uploadId: string | undefined, epoch: number, signal: AbortSignal) {
    const raw = await this.retry(() => this.request(path, init, signal), epoch, signal)
    return this.adopt(parseUploadSnapshot(raw, identity, uploadId), epoch, signal)
  }
  private async retry<T>(operation: () => Promise<T>, epoch: number, signal: AbortSignal): Promise<T> {
    for (let attempt = 0; ; attempt++) {
      this.check(epoch, signal)
      try { return await operation() }
      catch (error) {
        this.check(epoch, signal)
        const retryable = error instanceof UploadError && (error.code === 'NETWORK_UNAVAILABLE' || error.code === 'REQUEST_TIMEOUT'
          || error.status === 408 || error.status === 429 || (error.status !== undefined && error.status >= 500))
        if (!retryable || attempt >= 3) throw error
        this.emit({ retryAttempt: attempt + 1 })
        await this.sleep(Math.min(8000, 800 * 2 ** attempt) * (0.8 + Math.random() * 0.4), signal)
      }
    }
  }
  private async request(path: string, init: RequestInit, signal: AbortSignal): Promise<unknown> {
    if (signal.aborted) throw new UploadPaused()
    const controller = new AbortController(), abort = () => controller.abort()
    signal.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(() => controller.abort(), this.dependencies.requestTimeoutMs ?? (init.method === 'PUT' ? 90_000 : 15_000))
    try {
      const response = await this.fetcher(path, { cache: 'no-store', ...init, signal: controller.signal })
      const raw: unknown = await response.json().catch(error => {
        if (controller.signal.aborted || signal.aborted) throw error
        return null
      })
      if (!response.ok) {
        const code = object(raw) && typeof raw.error_code === 'string' && /^[A-Z0-9_]{1,64}$/.test(raw.error_code) ? raw.error_code : `HTTP_${response.status}`
        const requestId = object(raw) && typeof raw.request_id === 'string' && UUID.test(raw.request_id) ? raw.request_id : undefined
        throw new UploadError(code, response.status, requestId)
      }
      return raw
    } catch (error) {
      if (signal.aborted) throw new UploadPaused()
      if (error instanceof UploadError) throw error
      throw new UploadError(controller.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_UNAVAILABLE')
    } finally { clearTimeout(timer); signal.removeEventListener('abort', abort) }
  }
  async discard() {
    if (this.destroyed) return
    this.pause()
    const identity = this.identity
    if (!identity) { this.file = null; this.emit({ phase: 'idle', filename: '', size: 0, snapshot: null, error: '' }); return }
    const epoch = ++this.epoch, controller = new AbortController(); this.controller = controller
    this.emit({ phase: 'discarding', error: '' })
    try {
      let record: ResumeRecord | null
      try { record = this.record ?? this.readRecord(identity) }
      catch (error) {
        if (!(error instanceof UploadError) || error.code !== 'UPLOAD_LOCAL_STATE_INVALID') throw error
        this.forget(identity); this.file = null; this.identity = null
        this.emit({ phase: 'idle', filename: '', size: 0, hashedBytes: 0, snapshot: null, error: '' }); return
      }
      // An initialization acknowledgement may be lost. Recover its original
      // receipt before abandoning it, rather than leaving a reserved session.
      if (record && !record.uploadId) {
        const snapshot = await this.resolve(identity, epoch, controller.signal)
        record = { ...record, uploadId: snapshot.id }
      }
      if (record?.uploadId) {
        try { await this.retry(() => this.request(`/api/uploads/${record!.uploadId}`, { method: 'DELETE' }, controller.signal), epoch, controller.signal) }
        catch (error) {
          if (!(error instanceof UploadError) || !['UPLOAD_EXPIRED', 'UPLOAD_NOT_FOUND'].includes(error.code)) throw error
        }
      }
      this.check(epoch, controller.signal); this.forget(identity); this.file = null; this.identity = null
      this.emit({ phase: 'idle', filename: '', size: 0, hashedBytes: 0, snapshot: null, resumedParts: 0, retryAttempt: 0 })
    } catch (error) {
      if (this.active(epoch, controller.signal)) this.emit({ phase: 'error', error: uploadErrorText(error) })
    } finally { if (this.controller === controller) this.controller = null }
  }
}
