import type { ExportClip, ExportJob } from './api'
import { parseExportSnapshot } from './snapshotStream.ts'

export class VideoPackageError extends Error {
  code: string
  constructor(code = 'INVALID_VIDEO_PACKAGE_RESPONSE') { super(code); this.code = code }
}
export interface VideoPackageSelection {
  signature: string; export_id: string; clip_ids: string[]; total_bytes: number; url: string | null
}
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
export function videoPackageClipSelectable(batchId: string, clip: ExportClip): boolean {
  const content = `/api/exports/${batchId}/clips/${clip.id}/content`
  return UUID.test(batchId) && UUID.test(clip.id) && clip.status === 'SUCCEEDED' && typeof clip.size_bytes === 'number'
    && Number.isSafeInteger(clip.size_bytes) && clip.size_bytes > 0 && clip.content_url === content && clip.download_url === `${content}?download=true`
}
function batchIdentity(batch: ExportJob) {
  const request = (batch as ExportJob & { clip_request_id?: unknown }).clip_request_id ?? null
  if (request !== null && (typeof request !== 'string' || !UUID.test(request))) throw new VideoPackageError()
  const manual = batch.manual_clip_plan_id ?? null
  if (manual !== null && (typeof manual !== 'string' || !UUID.test(manual) || request !== null || !Number.isSafeInteger(batch.approved_revision) || batch.approved_revision < 1 || batch.clips.length !== 1)) throw new VideoPackageError()
  return [batch.id, batch.job_id, batch.video_id, batch.profile, batch.approved_revision, batch.snapshot_hash, request, manual]
}
function clipIdentity(clip: ExportClip) {
  const extra = clip as ExportClip & { parts?: unknown; duration_ms?: unknown }
  const rawParts = extra.parts == null ? [{ start_ms: clip.start_ms, end_ms: clip.end_ms }] : extra.parts
  if (!Array.isArray(rawParts) || !rawParts.length || rawParts.length > 12) throw new VideoPackageError()
  let previousEnd = -1, duration = 0
  const parts = rawParts.map(part => {
    if (!part || typeof part !== 'object' || Array.isArray(part)) throw new VideoPackageError()
    const { start_ms: start, end_ms: end } = part as Record<string, unknown>
    if (typeof start !== 'number' || !Number.isSafeInteger(start) || start < 0 || start < previousEnd
      || typeof end !== 'number' || !Number.isSafeInteger(end) || end <= start) throw new VideoPackageError()
    previousEnd = end; duration += end - start
    return [start, end]
  })
  if (parts[0]![0] !== clip.start_ms || parts.at(-1)![1] !== clip.end_ms || !Number.isSafeInteger(duration)
    || (extra.duration_ms !== undefined && extra.duration_ms !== null && extra.duration_ms !== duration)) throw new VideoPackageError()
  return [clip.id, clip.segment_id, clip.product_id, clip.product_name, clip.title ?? null, clip.stage_id, clip.attempt,
    clip.start_ms, clip.end_ms, parts, duration, clip.status, clip.size_bytes, clip.content_url, clip.download_url]
}
/** Frozen selected identities exclude batch state_version and every unselected clip's progress. */
export function videoPackageSelection(raw: ExportJob, selectedIds: readonly string[], requireSelection = false): VideoPackageSelection {
  let batch: ExportJob
  try { batch = parseExportSnapshot(raw, raw.id, raw.job_id) } catch { throw new VideoPackageError() }
  if (selectedIds.length > 100 || new Set(selectedIds).size !== selectedIds.length || selectedIds.some(id => !UUID.test(id))) throw new VideoPackageError('INVALID_VIDEO_PACKAGE')
  if (requireSelection && !selectedIds.length) throw new VideoPackageError('INVALID_VIDEO_PACKAGE')
  const clips = batch.clips.filter(clip => selectedIds.includes(clip.id))
  if (clips.length !== selectedIds.length || clips.some(clip => !videoPackageClipSelectable(batch.id, clip))) throw new VideoPackageError('VIDEO_PACKAGE_SELECTION_CHANGED')
  const totalBytes = clips.reduce((size, clip) => size + clip.size_bytes!, 0)
  if (!Number.isSafeInteger(totalBytes)) throw new VideoPackageError('VIDEO_PACKAGE_LIMIT')
  const ids = clips.map(clip => clip.id), query = new URLSearchParams({ clips: ids.join(',') })
  return Object.freeze({ signature: JSON.stringify([batchIdentity(batch), clips.map(clipIdentity)]), export_id: batch.id,
    clip_ids: Object.freeze(ids) as unknown as string[], total_bytes: totalBytes,
    url: clips.length ? `/api/exports/${batch.id}/video-package?${query}` : null })
}
export interface PreparedVideoPackage extends VideoPackageSelection { url: string }
export async function prepareVideoPackage(batch: ExportJob, selectedIds: readonly string[], io: {
  signal: AbortSignal; current(): boolean; get(path: string, signal: AbortSignal): Promise<unknown>
}): Promise<PreparedVideoPackage> {
  const current = () => !io.signal.aborted && io.current()
  if (!current()) throw new VideoPackageError('VIDEO_PACKAGE_READ_CANCELLED')
  const expected = videoPackageSelection(batch, selectedIds, true)
  const raw = await io.get(`/api/exports/${expected.export_id}`, io.signal)
  if (!current()) throw new VideoPackageError('VIDEO_PACKAGE_READ_CANCELLED')
  let fresh: ExportJob
  try { fresh = parseExportSnapshot(raw, expected.export_id, batch.job_id) } catch { throw new VideoPackageError() }
  const checked = videoPackageSelection(fresh, expected.clip_ids, true)
  if (checked.signature !== expected.signature) throw new VideoPackageError('VIDEO_PACKAGE_SELECTION_CHANGED')
  if (!current()) throw new VideoPackageError('VIDEO_PACKAGE_READ_CANCELLED')
  return { ...checked, url: checked.url! }
}
export function videoPackageErrorCopy(error: unknown): string {
  const code = error instanceof VideoPackageError ? error.code : error && typeof error === 'object' && 'code' in error ? error.code : ''
  switch (code) {
    case 'INVALID_VIDEO_PACKAGE': return '请明确选择 1～100 个已完成成片。超过 100 个时请分批选择。'
    case 'VIDEO_PACKAGE_SELECTION_CHANGED': return '所选成片的来源、状态或文件信息已变化，请核对选择后重新读取；未自动省略任何已选项。'
    case 'VIDEO_PACKAGE_LIMIT': return '本次选择超过可核对的大小上限，请减少所选成片后重试。'
    case 'REQUEST_TIMEOUT': return '核对成片超时，请稍后重试。当前选择与编辑内容保留。'
    case 'NETWORK_UNAVAILABLE': return '暂时无法连接成片服务，请稍后重试。当前选择与编辑内容保留。'
    default: return '本批成片暂时无法核对，未提供下载链接。请重新核对；当前选择与编辑内容保留。'
  }
}
export function videoPackageBytes(bytes: number): string {
  return bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(2)} GiB` : `${(bytes / 1024 ** 2).toFixed(2)} MiB`
}
