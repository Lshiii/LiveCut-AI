import { parseDiagnostic, type StageDiagnostic } from './diagnostics.ts'
import type { CaptionSnapshot } from './captions.ts'

export const captionRenderStyles = { WHITE_OUTLINE_V1: '白字黑描边', DARK_BOX_V1: '深色底字幕' } as const
export const captionRenderFrames = { SOURCE: '保留原画幅', LANDSCAPE_720: '横屏 16:9 · 1280×720', PORTRAIT_720: '竖屏 9:16 · 720×1280' } as const
export type CaptionRenderStyle = keyof typeof captionRenderStyles
export type CaptionRenderFrame = keyof typeof captionRenderFrames
export type CaptionRenderStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export interface CaptionRenderSource { export_id: string; clip_id: string; clip_sha256: string }
export interface CaptionRenderInput { caption_revision: number; caption_content_hash: string; style_preset: CaptionRenderStyle; frame_preset: CaptionRenderFrame }
export interface CaptionRenderProgress { phase: 'PREPARING' | 'ENCODING' | 'VALIDATING' | 'PUBLISHING'; processed_ms: number; total_ms: number; updated_at: string }
export interface CaptionRenderSnapshot extends CaptionRenderInput {
  id: string; source_export_id: string; source_clip_id: string; job_id: string; source_clip_sha256: string
  profile_version: 'caption-burn-v1'; font_profile: 'noto-sans-cjk-sc-2.004'; status: CaptionRenderStatus
  state_version: number; attempt: number; created_at: string; updated_at: string; error_code: string | null; diagnostic?: StageDiagnostic
  actions: { can_cancel: boolean; can_retry: boolean; max_attempts: number; retry_block_reason: null | 'NOT_FAILED' | 'ATTEMPTS_EXHAUSTED' | 'CANCELLED_REQUIRES_NEW_RENDER' | 'INPUT_REQUIRES_NEW_RENDER' }
  progress: CaptionRenderProgress | null; content_url: string | null; download_url: string | null; size_bytes: number | null
  media: { duration_ms: number; width: number; height: number } | null
}
export interface CaptionRenderReceipt { input: string; key: string }
export interface CaptionRenderReceiptStorage { getItem(key: string): string | null; setItem(key: string, value: string): void; removeItem(key: string): void }
export class CaptionRenderResponseError extends Error { constructor() { super('INVALID_CAPTION_RENDER_RESPONSE') } }
export class CaptionRenderReceiptStorageError extends Error { constructor() { super('CAPTION_RENDER_RECEIPT_UNAVAILABLE') } }
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const HASH = /^[a-f0-9]{64}$/
const KEY = /^[A-Za-z0-9_-]{1,100}$/
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number => Number.isSafeInteger(value) && (value as number) >= min && (value as number) <= max
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value)
const hash = (value: unknown): value is string => typeof value === 'string' && HASH.test(value)
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
function invalid(): never { throw new CaptionRenderResponseError() }
export function captionRenderBase(source: CaptionRenderSource) {
  if (!uuid(source.export_id) || !uuid(source.clip_id) || !hash(source.clip_sha256)) invalid()
  return `/api/exports/${source.export_id}/clips/${source.clip_id}/caption-renders`
}
export function captionRenderPath(id: string) { if (!uuid(id)) invalid(); return `/api/caption-renders/${id}` }
export function captionRenderActive(value: Pick<CaptionRenderSnapshot, 'status'>) { return value.status === 'QUEUED' || value.status === 'RUNNING' }
function inputValid(raw: Record<string, unknown>) {
  return integer(raw.caption_revision) && hash(raw.caption_content_hash)
    && Object.hasOwn(captionRenderStyles, raw.style_preset as string) && Object.hasOwn(captionRenderFrames, raw.frame_preset as string)
}
export function captionRenderInput(caption: CaptionSnapshot, style: CaptionRenderStyle, frame: CaptionRenderFrame): CaptionRenderInput {
  if (caption.status !== 'CONFIRMED' || !caption.confirmed_revisions.includes(caption.revision) || !caption.cues.length
    || caption.cues.some(cue => cue.requires_review) || !inputValid({ caption_revision: caption.revision, caption_content_hash: caption.content_hash, style_preset: style, frame_preset: frame })) invalid()
  return { caption_revision: caption.revision, caption_content_hash: caption.content_hash, style_preset: style, frame_preset: frame }
}
export function parseCaptionRender(raw: unknown, source: CaptionRenderSource, id?: string): CaptionRenderSnapshot {
  captionRenderBase(source)
  if (!row(raw) || !uuid(raw.id) || (id !== undefined && raw.id !== id) || raw.source_export_id !== source.export_id
    || raw.source_clip_id !== source.clip_id || raw.source_clip_sha256 !== source.clip_sha256 || !uuid(raw.job_id)
    || !inputValid(raw) || raw.profile_version !== 'caption-burn-v1' || raw.font_profile !== 'noto-sans-cjk-sc-2.004'
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(raw.status as string)
    || !integer(raw.state_version, 1) || !integer(raw.attempt) || !date(raw.created_at) || !date(raw.updated_at)
    || !(raw.error_code === null || typeof raw.error_code === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(raw.error_code))
    || !row(raw.actions) || typeof raw.actions.can_cancel !== 'boolean' || typeof raw.actions.can_retry !== 'boolean'
    || !integer(raw.actions.max_attempts, 1)
    || ![null, 'NOT_FAILED', 'ATTEMPTS_EXHAUSTED', 'CANCELLED_REQUIRES_NEW_RENDER', 'INPUT_REQUIRES_NEW_RENDER'].includes(raw.actions.retry_block_reason as string | null)) invalid()
  if (raw.actions.can_cancel && !['QUEUED', 'RUNNING'].includes(raw.status as string)
    || raw.actions.can_retry && (raw.status !== 'FAILED' || raw.attempt >= raw.actions.max_attempts || raw.actions.retry_block_reason !== null)) invalid()
  let progress: CaptionRenderProgress | null = null
  if (raw.progress !== null) {
    if (!row(raw.progress) || !['PREPARING', 'ENCODING', 'VALIDATING', 'PUBLISHING'].includes(raw.progress.phase as string)
      || !integer(raw.progress.total_ms, 1) || !integer(raw.progress.processed_ms, 0, raw.progress.total_ms) || !date(raw.progress.updated_at)) invalid()
    progress = { phase: raw.progress.phase as CaptionRenderProgress['phase'], processed_ms: raw.progress.processed_ms, total_ms: raw.progress.total_ms, updated_at: raw.progress.updated_at }
  }
  if (raw.status === 'SUCCEEDED') {
    const content = `${captionRenderPath(raw.id)}/content`
    if (raw.content_url !== content || raw.download_url !== `${content}?download=true` || !integer(raw.size_bytes, 1)
      || !row(raw.media) || !integer(raw.media.duration_ms, 1) || !integer(raw.media.width, 2, 4096) || raw.media.width % 2
      || !integer(raw.media.height, 2, 4096) || raw.media.height % 2 || raw.error_code !== null
      || raw.frame_preset === 'LANDSCAPE_720' && (raw.media.width !== 1280 || raw.media.height !== 720)
      || raw.frame_preset === 'PORTRAIT_720' && (raw.media.width !== 720 || raw.media.height !== 1280)) invalid()
  } else if (raw.content_url !== null || raw.download_url !== null || raw.size_bytes !== null || raw.media !== null) invalid()
  const { diagnostic: rawDiagnostic, ...rest } = raw
  const diagnostic = parseDiagnostic(rawDiagnostic, raw.attempt, raw.error_code)
  return { ...rest, progress, ...(diagnostic ? { diagnostic } : {}) } as unknown as CaptionRenderSnapshot
}
export function parseCaptionRenderList(raw: unknown, source: CaptionRenderSource) {
  if (!row(raw) || !Array.isArray(raw.items) || raw.items.length > 50) invalid()
  const values = raw.items.map(value => parseCaptionRender(value, source))
  if (new Set(values.map(value => value.id)).size !== values.length || new Set(values.map(value => value.job_id)).size > 1) invalid()
  return values.sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))
}
export function newestCaptionRender(known: CaptionRenderSnapshot | undefined, incoming: CaptionRenderSnapshot): CaptionRenderSnapshot {
  if (!known) return incoming
  const fields = ['id', 'source_export_id', 'source_clip_id', 'job_id', 'source_clip_sha256', 'caption_revision', 'caption_content_hash', 'style_preset', 'frame_preset', 'profile_version', 'font_profile', 'created_at'] as const
  if (fields.some(field => known[field] !== incoming[field])) invalid()
  if (known.status === 'SUCCEEDED' && incoming.status === 'SUCCEEDED'
    && (known.size_bytes !== incoming.size_bytes || JSON.stringify(known.media) !== JSON.stringify(incoming.media))) invalid()
  if (incoming.state_version < known.state_version || incoming.attempt < known.attempt
    || (incoming.state_version === known.state_version && incoming.status !== known.status)
    || (['SUCCEEDED', 'CANCELLED'].includes(known.status) && incoming.status !== known.status)) return known
  return incoming
}
function inputSignature(input: CaptionRenderInput) {
  if (!inputValid(input as unknown as Record<string, unknown>)) invalid()
  return JSON.stringify({ caption_revision: input.caption_revision, caption_content_hash: input.caption_content_hash, style_preset: input.style_preset, frame_preset: input.frame_preset })
}
function matchingReceipt(previous: unknown, signature: string): CaptionRenderReceipt | undefined {
  if (row(previous) && previous.input === signature && typeof previous.key === 'string' && KEY.test(previous.key)) return { input: signature, key: previous.key }
}
export function captionRenderReceipt(previous: unknown, input: CaptionRenderInput, newKey: () => string): CaptionRenderReceipt {
  const signature = inputSignature(input), existing = matchingReceipt(previous, signature)
  if (existing) return existing
  const key = newKey(); if (!KEY.test(key)) invalid()
  return { input: signature, key }
}
/** Keep every unknown outcome independently; neither a different configuration nor an age limit can evict it. */
export function createCaptionRenderReceiptStore(baseKey: string, storage: CaptionRenderReceiptStorage, newKey: () => string) {
  const mounted = new Map<string, CaptionRenderReceipt>()
  const location = (signature: string) => `${baseKey}:${encodeURIComponent(signature)}`
  function stored(key: string): unknown {
    const value = storage.getItem(key)
    if (value === null) return null
    try { return JSON.parse(value) } catch { return null }
  }
  return {
    prepare(input: CaptionRenderInput): CaptionRenderReceipt {
      const signature = inputSignature(input)
      let previous: CaptionRenderReceipt | undefined
      try {
        previous = matchingReceipt(stored(location(signature)), signature) ?? mounted.get(signature) ?? matchingReceipt(stored(baseKey), signature)
      } catch { throw new CaptionRenderReceiptStorageError() }
      const receipt = captionRenderReceipt(previous, input, newKey)
      try { storage.setItem(location(signature), JSON.stringify(receipt)) }
      catch { throw new CaptionRenderReceiptStorageError() }
      mounted.set(signature, receipt)
      return receipt
    },
    accept(input: CaptionRenderInput, receipt: CaptionRenderReceipt): boolean {
      const signature = inputSignature(input)
      if (!matchingReceipt(receipt, signature)) invalid()
      let cleaned = true
      // The legacy per-clip slot may still belong to another configuration or another browser tab.
      for (const key of [location(signature), baseKey]) {
        try { if (matchingReceipt(stored(key), signature)?.key === receipt.key) storage.removeItem(key) }
        catch { cleaned = false }
      }
      if (mounted.get(signature)?.key === receipt.key) mounted.delete(signature)
      return cleaned
    },
  }
}
export function captionRenderProgressCopy(progress: CaptionRenderProgress | null) {
  if (!progress) return '等待执行服务更新进度。可关闭页面，稍后回来查看。'
  if (progress.phase === 'PREPARING') return '正在准备已确认字幕与原片。'
  if (progress.phase === 'VALIDATING') return '编码已结束，正在检查画面、音轨与文件完整性。'
  if (progress.phase === 'PUBLISHING') return '校验已通过，正在保存成片。'
  return `正在编码 ${Math.floor(progress.processed_ms / progress.total_ms * 100)}% · 完成校验后开放下载。`
}
