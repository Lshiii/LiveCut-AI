export interface AgentVisualFrame {
  frame_id: string; requested_ms: number; timestamp_ms: number; source_pts: number; source_time_base: string
  sha256: string; bytes: number; width: number; height: number
}
export interface AgentVisualCitation { frame_id: string; explanation: string }
export interface AgentVisualCapability { visual_mode: 'frames' | null; visual_model_id: string | null; max_frames: 32; max_clip_duration_ms: 12000; clip_sample_interval_ms: 500 }
export class AgentVisualError extends Error { constructor() { super('INVALID_AGENT_VISUAL_RESPONSE') } }
type Row = Record<string, unknown>
const row = (raw: unknown): raw is Row => raw !== null && typeof raw === 'object' && !Array.isArray(raw)
const exact = (raw: unknown, keys: string[]): raw is Row => row(raw) && Object.keys(raw).length === keys.length && keys.every(key => Object.hasOwn(raw, key))
const integer = (raw: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): raw is number => Number.isSafeInteger(raw) && (raw as number) >= min && (raw as number) <= max
export const visualFrameId = (raw: unknown): raw is string => typeof raw === 'string' && /^f00(?:0[1-9]|[12][0-9]|3[0-2])$/.test(raw)
function invalid(): never { throw new AgentVisualError() }
export function parseAgentVisualCapability(raw: unknown): AgentVisualCapability {
  if (!exact(raw, ['visual_mode', 'visual_model_id', 'max_frames', 'max_clip_duration_ms', 'clip_sample_interval_ms'])
    || !(raw.visual_mode === null || raw.visual_mode === 'frames') || raw.max_frames !== 32 || raw.max_clip_duration_ms !== 12000 || raw.clip_sample_interval_ms !== 500
    || (raw.visual_mode === null ? raw.visual_model_id !== null : typeof raw.visual_model_id !== 'string' || !raw.visual_model_id.trim() || raw.visual_model_id.length > 128)) invalid()
  return raw as unknown as AgentVisualCapability
}
export function parseAgentVisualFrames(raw: unknown, duration: number): AgentVisualFrame[] {
  if (!Array.isArray(raw) || raw.length > 32) invalid()
  const requested = new Set<number>(), actual = new Set<string>(); let total = 0
  return raw.map((frame, index) => {
    if (!exact(frame, ['frame_id', 'requested_ms', 'timestamp_ms', 'source_pts', 'source_time_base', 'sha256', 'bytes', 'width', 'height'])
      || frame.frame_id !== `f${String(index + 1).padStart(4, '0')}` || !integer(frame.requested_ms, 0, duration - 1) || requested.has(frame.requested_ms)
      || !integer(frame.timestamp_ms, frame.requested_ms, Math.min(duration - 1, frame.requested_ms + 1000)) || !integer(frame.source_pts) || typeof frame.source_time_base !== 'string'
      || !/^[1-9]\d{0,9}\/[1-9]\d{0,9}$/.test(frame.source_time_base)
      || typeof frame.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(frame.sha256) || !integer(frame.bytes, 4, 1024 * 1024)
      || !integer(frame.width, 1, 1280) || !integer(frame.height, 1, 1280)) invalid()
    const [num, den] = frame.source_time_base.split('/').map(value => BigInt(value)) as [bigint, bigint]
    const exactNumeratorMs = BigInt(frame.source_pts) * num * 1000n
    if ((2n * exactNumeratorMs + den) / (2n * den) !== BigInt(frame.timestamp_ms)
      || exactNumeratorMs < BigInt(frame.requested_ms) * den
      || exactNumeratorMs > (BigInt(frame.requested_ms) + 1000n) * den
      || exactNumeratorMs >= BigInt(duration) * den) invalid()
    let numerator = BigInt(frame.source_pts) * num, denominator = den
    let a = numerator, b = denominator
    while (b) { const remainder = a % b; a = b; b = remainder }
    numerator /= a; denominator /= a
    const actualKey = `${numerator}/${denominator}`
    if (actual.has(actualKey)) invalid()
    actual.add(actualKey)
    total += frame.bytes
    if (total > 16 * 1024 * 1024) invalid()
    requested.add(frame.requested_ms)
    return { ...frame } as unknown as AgentVisualFrame
  })
}
/** Frame display time is rounded; interval membership always uses exact source PTS. */
export function visualFrameInRange(frame: AgentVisualFrame, start: number, end: number): boolean {
  if (!integer(start) || !integer(end, start + 1) || !integer(frame.source_pts) || !/^[1-9]\d{0,9}\/[1-9]\d{0,9}$/.test(frame.source_time_base)) return false
  const [num, den] = frame.source_time_base.split('/').map(value => BigInt(value)) as [bigint, bigint]
  const exactNumeratorMs = BigInt(frame.source_pts) * num * 1000n
  return exactNumeratorMs >= BigInt(start) * den && exactNumeratorMs < BigInt(end) * den
}
export function parseAgentVisualCitations(raw: unknown, frames: readonly AgentVisualFrame[]): AgentVisualCitation[] {
  if (!Array.isArray(raw) || raw.length > 12) invalid()
  let previous = ''
  return raw.map(citation => {
    if (!exact(citation, ['frame_id', 'explanation']) || !visualFrameId(citation.frame_id) || citation.frame_id <= previous
      || !frames.some(frame => frame.frame_id === citation.frame_id) || typeof citation.explanation !== 'string' || !citation.explanation.trim() || citation.explanation.length > 1000) invalid()
    previous = citation.frame_id
    return { frame_id: citation.frame_id, explanation: citation.explanation }
  })
}
export function visualEvidenceUrl(turnId: string, frameId: string): string {
  if (!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(turnId) || !visualFrameId(frameId)) invalid()
  return `/api/agent-turns/${turnId}/visual-evidence/${frameId}/content`
}
export function inspectClipRequestedTimes(start: number, end: number): number[] {
  if (!integer(start) || !integer(end, start + 1) || end - start > 12000) invalid()
  return Array.from({ length: Math.ceil((end - start) / 500) }, (_, index) => start + index * 500)
}
