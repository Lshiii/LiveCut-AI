import type { VideoAsset } from './api'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SHA = /^[0-9a-f]{64}$/
const PREFIX = 'livecut-source-import-pending-v1:'
const statuses = ['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'] as const
export type SourceImportStatus = typeof statuses[number]
export interface SourceImportSnapshot {
  id: string; url: string; status: SourceImportStatus
  phase: 'QUEUED' | 'DOWNLOADING' | 'NORMALIZING' | 'VERIFYING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  progress_percent: number | null; downloaded_bytes: number; video_id: string | null; error_code: string | null
  attempt: number; created_at: string; updated_at: string
}
export interface SourceImportReceipt { version: 1; key: string; input: string; url: string; created_at: string }
export interface ImportStorage { readonly length: number; key(index: number): string | null; getItem(key: string): string | null; setItem(key: string, value: string): void; removeItem(key: string): void }
export class SourceImportError extends Error { code: string; constructor(code = 'INVALID_SOURCE_IMPORT_RESPONSE') { super(code); this.code = code } }
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0
const timestamp = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const timeKey = (value: string) => value.replace(/(?:\.(\d+))?Z$/, (_match, fraction: string | undefined) => `.${(fraction ?? '').padEnd(9, '0')}Z`)

export function sourceImportUrl(input: string): string {
  if (typeof input !== 'string' || input.length > 2048 || /[\x00-\x1f\x7f-\x9f]/.test(input)) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
  try { if (/[\x00-\x1f\x7f-\x9f]/.test(decodeURIComponent(input))) throw new Error('control') } catch { throw new SourceImportError('SOURCE_URL_UNSUPPORTED') }
  let url: URL
  try { url = new URL(input.trim()) } catch { throw new SourceImportError('SOURCE_URL_UNSUPPORTED') }
  if (url.protocol !== 'https:' || url.username || url.password || url.port) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
  const keys = [...url.searchParams.keys()]
  if (keys.some(key => !key) || new Set(keys).size !== keys.length) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
  if (url.searchParams.has('list') || url.searchParams.has('index') || url.searchParams.has('playlist')) throw new SourceImportError('SOURCE_PLAYLIST_UNSUPPORTED')
  const host = url.hostname.toLowerCase()
  if (['www.youtube.com', 'youtube.com', 'm.youtube.com', 'youtu.be', 'www.youtu.be'].includes(host)) {
    if (host.endsWith('youtu.be') && url.searchParams.has('v')) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
    const id = host.endsWith('youtu.be') ? /^\/([A-Za-z0-9_-]{11})\/?$/.exec(url.pathname)?.[1]
      : url.pathname === '/watch' && url.searchParams.getAll('v').length === 1 ? url.searchParams.get('v') : null
    if (!id || !/^[A-Za-z0-9_-]{11}$/.test(id)) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
    return `https://www.youtube.com/watch?v=${id}`
  }
  if (['www.bilibili.com', 'bilibili.com', 'm.bilibili.com'].includes(host)) {
    const id = /^\/video\/(BV[A-Za-z0-9]{10}|av[1-9]\d{0,19})\/?$/.exec(url.pathname)?.[1]
    if (!id) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
    if (url.searchParams.has('p') && (url.searchParams.getAll('p').length !== 1 || url.searchParams.get('p') !== '1')) throw new SourceImportError('SOURCE_MULTIPART_UNSUPPORTED')
    return `https://www.bilibili.com/video/${id}`
  }
  if (['www.douyin.com', 'douyin.com', 'm.douyin.com', 'v.douyin.com'].includes(host)
    && ['modal_id', 'aweme_id', 'item_id', 'item_ids', 'video_id'].some(key => url.searchParams.has(key))) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
  if (['www.douyin.com', 'douyin.com', 'm.douyin.com'].includes(host)) {
    const id = /^\/video\/(\d{15,22})\/?$/.exec(url.pathname)?.[1]
    if (!id) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
    return `https://www.douyin.com/video/${id}`
  }
  if (host === 'v.douyin.com') {
    const slug = /^\/([A-Za-z0-9_-]{1,64})\/?$/.exec(url.pathname)?.[1]
    if (!slug) throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
    return `https://v.douyin.com/${slug}/`
  }
  throw new SourceImportError('SOURCE_URL_UNSUPPORTED')
}

export function parseSourceImport(raw: unknown, expected?: { id?: string; url?: string }): SourceImportSnapshot {
  if (!object(raw) || typeof raw.id !== 'string' || !UUID.test(raw.id) || typeof raw.url !== 'string'
    || !statuses.includes(raw.status as SourceImportStatus) || !integer(raw.downloaded_bytes) || !integer(raw.attempt) || raw.attempt > 3
    || !timestamp(raw.created_at) || !timestamp(raw.updated_at) || Date.parse(raw.updated_at) < Date.parse(raw.created_at)
    || !(raw.progress_percent === null || typeof raw.progress_percent === 'number' && Number.isFinite(raw.progress_percent) && raw.progress_percent >= 0 && raw.progress_percent <= 100)
    || !(raw.error_code === null || typeof raw.error_code === 'string' && /^[A-Z0-9_]{1,100}$/.test(raw.error_code))) throw new SourceImportError()
  let canonical: string
  try { canonical = sourceImportUrl(raw.url) } catch { throw new SourceImportError() }
  if (canonical !== raw.url || expected?.id && raw.id !== expected.id || expected?.url && raw.url !== sourceImportUrl(expected.url)) throw new SourceImportError()
  if (raw.status === 'RUNNING' ? !['DOWNLOADING', 'NORMALIZING', 'VERIFYING'].includes(String(raw.phase)) : raw.phase !== raw.status) throw new SourceImportError()
  if (raw.status === 'COMPLETED' ? typeof raw.video_id !== 'string' || !UUID.test(raw.video_id) || raw.progress_percent !== 100 || raw.error_code !== null : raw.video_id !== null) throw new SourceImportError()
  if (raw.status !== 'FAILED' && raw.error_code !== null) throw new SourceImportError()
  return { id: raw.id, url: raw.url, status: raw.status as SourceImportStatus, phase: raw.phase as SourceImportSnapshot['phase'],
    progress_percent: raw.progress_percent, downloaded_bytes: raw.downloaded_bytes, video_id: raw.video_id as string | null, error_code: raw.error_code,
    attempt: raw.attempt, created_at: raw.created_at, updated_at: raw.updated_at }
}
export function parseSourceImports(raw: unknown): SourceImportSnapshot[] {
  if (!object(raw) || !Array.isArray(raw.items) || raw.items.length > 20) throw new SourceImportError()
  const items = raw.items.map(item => parseSourceImport(item))
  if (new Set(items.map(item => item.id)).size !== items.length) throw new SourceImportError()
  return items
}
export function sourceImportTerminal(value: SourceImportSnapshot) { return !['QUEUED', 'RUNNING'].includes(value.status) }
export function newestSourceImport(old: SourceImportSnapshot | undefined, next: SourceImportSnapshot, explicitRetry = false): SourceImportSnapshot {
  if (!old) return next
  if (old.id !== next.id || old.url !== next.url || old.created_at !== next.created_at) throw new SourceImportError()
  if (next.attempt < old.attempt) return old
  if (['COMPLETED', 'CANCELLED'].includes(old.status)) return old
  if (next.attempt > old.attempt) return next
  if (sourceImportTerminal(old)) {
    // FAILED can be explicitly retried by another page: it returns QUEUED
    // before the next claim increments attempt. Never revive it from an older read.
    if (old.status === 'FAILED' && next.status === 'QUEUED' && (explicitRetry || timeKey(next.updated_at) > timeKey(old.updated_at))) return next
    if (old.status !== next.status || old.video_id !== next.video_id) return old
    return timeKey(next.updated_at) < timeKey(old.updated_at) ? old : next
  }
  if (timeKey(next.updated_at) < timeKey(old.updated_at) || old.status === 'RUNNING' && next.status === 'QUEUED') return old
  const phases = ['QUEUED', 'DOWNLOADING', 'NORMALIZING', 'VERIFYING']
  if (!sourceImportTerminal(next) && phases.indexOf(next.phase) < phases.indexOf(old.phase)) return old
  return next
}
export function sourceImportVideo(raw: unknown, expectedId: string): VideoAsset {
  if (!object(raw) || raw.id !== expectedId || !UUID.test(expectedId) || typeof raw.original_filename !== 'string' || !raw.original_filename.trim() || raw.original_filename.length > 255
    || /[\x00-\x1f\x7f/\\]/.test(raw.original_filename) || typeof raw.content_hash !== 'string' || !SHA.test(raw.content_hash)
    || !integer(raw.size_bytes) || raw.size_bytes < 1 || raw.content_url !== `/api/videos/${expectedId}/content`
    || typeof raw.media_validation_status !== 'string' || !['HEADER_CHECKED', 'VERIFIED'].includes(raw.media_validation_status) || !timestamp(raw.created_at)) throw new SourceImportError()
  return raw as unknown as VideoAsset
}
export function sourceReceiptKey(key: string) { if (!UUID.test(key)) throw new SourceImportError('SOURCE_IMPORT_RECEIPT_INVALID'); return PREFIX + key }
function parseReceipt(raw: unknown): SourceImportReceipt {
  if (!object(raw) || raw.version !== 1 || typeof raw.key !== 'string' || !UUID.test(raw.key) || typeof raw.input !== 'string' || typeof raw.url !== 'string' || !timestamp(raw.created_at)
    || raw.url !== sourceImportUrl(raw.url) || raw.input !== JSON.stringify({ url: raw.url })) throw new SourceImportError('SOURCE_IMPORT_RECEIPT_INVALID')
  return raw as unknown as SourceImportReceipt
}
export function loadSourceReceipts(storage: ImportStorage): SourceImportReceipt[] {
  const values: SourceImportReceipt[] = []
  try {
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i)
      if (!key?.startsWith(PREFIX)) continue
      if (values.length >= 20) throw new SourceImportError('SOURCE_IMPORT_RECEIPT_LIMIT')
      const entry = parseReceipt(JSON.parse(storage.getItem(key) ?? 'null'))
      if (sourceReceiptKey(entry.key) !== key) throw new SourceImportError('SOURCE_IMPORT_RECEIPT_INVALID')
      values.push(entry)
    }
  } catch (error) { if (error instanceof SourceImportError) throw error; throw new SourceImportError('SOURCE_IMPORT_STORAGE_UNAVAILABLE') }
  return values.sort((a, b) => a.created_at.localeCompare(b.created_at) || a.key.localeCompare(b.key))
}
export function createSourceReceipt(storage: ImportStorage, input: string, newKey: () => string, now = new Date().toISOString()): SourceImportReceipt {
  if (loadSourceReceipts(storage).length) throw new SourceImportError('SOURCE_IMPORT_PENDING')
  const url = sourceImportUrl(input), entry = parseReceipt({ version: 1, key: newKey(), input: JSON.stringify({ url }), url, created_at: now })
  const key = sourceReceiptKey(entry.key), encoded = JSON.stringify(entry)
  try { if (storage.getItem(key) !== null) throw new Error('occupied'); storage.setItem(key, encoded); if (storage.getItem(key) !== encoded) throw new Error('not stored') }
  catch { throw new SourceImportError('SOURCE_IMPORT_STORAGE_UNAVAILABLE') }
  return entry
}
export function assertSourceReceipt(storage: ImportStorage, entry: SourceImportReceipt) {
  try { if (storage.getItem(sourceReceiptKey(entry.key)) !== JSON.stringify(entry)) throw new Error('changed') }
  catch { throw new SourceImportError('SOURCE_IMPORT_STORAGE_UNAVAILABLE') }
}
export function finishSourceReceipt(storage: ImportStorage, entry: SourceImportReceipt) { assertSourceReceipt(storage, entry); storage.removeItem(sourceReceiptKey(entry.key)) }
export function sourceImportNotFound(error: unknown) { return object(error) && error.status === 404 && error.code === 'SOURCE_IMPORT_NOT_FOUND' }
export function sourceImportErrorText(value: unknown): string {
  const code = object(value) && typeof value.code === 'string' ? value.code : ''
  const messages: Record<string, string> = {
    SOURCE_URL_UNSUPPORTED: '请填写公开的单条 YouTube、Bilibili 或抖音视频链接（HTTPS），不要粘贴整段分享文案。',
    INVALID_SOURCE_URL: '链接不受支持，请使用无需登录的单条 YouTube、Bilibili 或抖音视频链接。',
    SOURCE_PLAYLIST_UNSUPPORTED: '暂不支持播放列表，请使用不带列表参数的单条视频链接。',
    SOURCE_MULTIPART_UNSUPPORTED: '暂不支持 Bilibili 多 P 的后续分集，请使用单条录播或本地 MP4。',
    SOURCE_IMPORT_PENDING: '还有提交结果待核对，请先核对原提交。',
    SOURCE_IMPORT_STORAGE_UNAVAILABLE: '本机回执无法可靠保存，请保留当前页面并恢复浏览器存储后再试。',
    SOURCE_IMPORT_RECEIPT_INVALID: '本机有无法核对的原提交记录，未覆盖或重发。',
    SOURCE_IMPORT_RECEIPT_LIMIT: '待核对提交数量已达上限，请先核对原提交。',
    REQUEST_TIMEOUT: '请求超时，原提交已保留；请核对原提交，不要重新创建。',
    NETWORK_UNAVAILABLE: '暂时无法连接服务，已有进度和原提交保留。',
    INVALID_SOURCE_IMPORT_RESPONSE: '服务响应未通过核对，原提交和当前选择保持不变。',
    SOURCE_IMPORT_NOT_FOUND: '未找到该导入任务。',
    SOURCE_IMPORT_ATTEMPTS_EXHAUSTED: '此导入已达到三次尝试上限，可改用本地 MP4。',
    SOURCE_IMPORT_NOT_RETRYABLE: '此任务当前不可重试，请刷新核对。',
    SOURCE_IMPORT_RETRY_CONFLICT: '导入尝试已变化，请刷新后核对当前任务。',
    SOURCE_IMPORT_LEASE_EXPIRED: '下载执行器断连或停止，本次尝试已结束，可明确重试。',
    SOURCE_IMPORT_DEADLINE_EXCEEDED: '本次导入超过执行时限，可重试或使用本地 MP4。',
    IMPORT_UNAVAILABLE: '视频不可访问、需要登录或受地区限制，请改用有权使用的本地 MP4。',
    IMPORT_DOWNLOAD_FAILED: '视频下载未完成，请核对链接后明确重试。',
    IMPORT_SIZE_EXCEEDED: '视频超过当前导入大小限制，请使用较短视频或本地 MP4。',
    IMPORT_DURATION_EXCEEDED: '视频超过当前导入时长限制，请使用较短录播。',
    IMPORT_NORMALIZATION_FAILED: '视频格式处理失败，可改用本地 H.264 / AAC MP4。',
    SOURCE_IMPORT_AUTH_REQUIRED: '此视频需要登录或访问授权，请改用有权使用的本地 MP4。',
    SOURCE_IMPORT_UNSUPPORTED_SOURCE: '此链接不是支持的公开单条录播，请改用本地 MP4。',
    SOURCE_IMPORT_NETWORK_DENIED: '下载地址未通过公网校验，请检查代理 DNS 或换用本地上传。',
    SOURCE_IMPORT_DOWNLOAD_FAILED: '视频下载未完成，请核对链接后明确重试。',
    SOURCE_IMPORT_TOO_LARGE: '视频超过当前导入大小限制，请使用较短视频或本地 MP4。',
    SOURCE_IMPORT_DURATION_LIMIT: '视频超过当前导入时长限制，请使用较短录播。',
    SOURCE_IMPORT_TIMEOUT: '本次导入超时，可明确重试或使用本地 MP4。',
    SOURCE_IMPORT_NORMALIZE_FAILED: '视频格式处理失败，可改用本地 H.264 / AAC MP4。',
    SOURCE_IMPORT_AUDIO_REQUIRED: '此视频没有可用音轨，不能用于当前分析流程。',
    SOURCE_IMPORT_STAGING_LIMIT: '下载暂存空间不足，请稍后重试或使用本地 MP4。',
    SOURCE_IMPORT_DISABLED: '当前服务未启用链接导入，可继续使用本地 MP4。',
  }
  return messages[code] ?? '本次导入未完成，请刷新核对任务；需要登录、受限或不可用的视频请改用有权使用的本地 MP4。'
}
