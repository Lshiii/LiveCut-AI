import type { AgentToolCall } from './videoAgent.ts'
import { inspectClipRequestedTimes, visualFrameInRange, type AgentVisualFrame } from './agentVisualEvidence.ts'

export interface AgentScreenSource {
  segment_stage_id: string; segment_manifest_hash: string; frames_hash: string; observations_hash: string
  index_version: 'screen-text-literal-v1'; index_hash: string; duration_ms: number
  sampling_policy: 'prefix' | 'uniform'; interval_ms: number; frame_count: number; text_frame_count: number; coverage_truncated: boolean
}
export interface AgentScreenHit { ocr_frame_id: string; requested_ms: number; ocr_timestamp_ms: number; source_pts: number; source_time_base: string; observed_text: string; frame_id: string }
export interface AgentScreenSearch { tool_step: number; status: 'OBSERVED' | 'EMPTY' | 'NO_MATCH' | 'TOOL_ERROR'; error_code: null | 'CONTEXT_LIMIT' | 'FRAME_UNAVAILABLE' | 'FRAME_POSITION_MISMATCH' | 'DUPLICATE_TOOL_CALL'; total_match_count: number; hits: AgentScreenHit[] }
export class AgentScreenSearchError extends Error { code = 'INVALID_AGENT_RESPONSE'; constructor() { super('INVALID_AGENT_RESPONSE') } }
const row = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => row(v) && keys.length === Object.keys(v).length && keys.every(key => Object.hasOwn(v, key))
const integer = (v: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): v is number => Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max
function invalid(): never { throw new AgentScreenSearchError() }
export function wellFormedScreenText(v: string) { for (let i = 0; i < v.length; i++) { const c = v.charCodeAt(i); if (c >= 0xd800 && c <= 0xdbff) { const n = v.charCodeAt(++i); if (!(n >= 0xdc00 && n <= 0xdfff)) return false } else if (c >= 0xdc00 && c <= 0xdfff) return false } return true }
export function screenSearchTokens(query: unknown): string[] {
  if (typeof query !== 'string' || query.length > 120 || !wellFormedScreenText(query) || /[\u0000-\u0008\u000e-\u001f\u007f-\u009f]/u.test(query)) invalid()
  const words = query.split(/\s+/u).filter(Boolean)
  if (!words.length || words.length > 8) invalid()
  return [...new Set(words.map(word => word.replace(/[A-Z]/g, c => c.toLowerCase())))]
}
export function parseAgentScreenSearchCapability(raw: unknown): true {
  if (!exact(raw, ['screen_search_supported', 'worker_protocol']) || raw.screen_search_supported !== true || raw.worker_protocol !== 4) invalid()
  return true
}
export function screenRequestedTime(source: AgentScreenSource, index: number): number {
  return Number(source.sampling_policy === 'prefix' ? BigInt(index) * BigInt(source.interval_ms)
    : source.frame_count === 1 ? 0n : BigInt(2 * index + 1) * BigInt(source.duration_ms) / BigInt(2 * source.frame_count))
}
export function parseAgentScreenSource(raw: unknown, duration?: number): AgentScreenSource {
  if (!exact(raw, ['segment_stage_id', 'segment_manifest_hash', 'frames_hash', 'observations_hash', 'index_version', 'index_hash', 'duration_ms', 'sampling_policy', 'interval_ms', 'frame_count', 'text_frame_count', 'coverage_truncated'])
    || typeof raw.segment_stage_id !== 'string' || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(raw.segment_stage_id)
    || ['segment_manifest_hash', 'frames_hash', 'observations_hash', 'index_hash'].some(key => typeof raw[key] !== 'string' || !/^[a-f0-9]{64}$/.test(raw[key] as string))
    || raw.index_version !== 'screen-text-literal-v1' || !integer(raw.duration_ms, 1) || duration !== undefined && raw.duration_ms !== duration
    || !['prefix', 'uniform'].includes(String(raw.sampling_policy)) || !integer(raw.interval_ms, 1) || !integer(raw.frame_count, 1, 120)
    || !integer(raw.text_frame_count, 0, raw.frame_count) || typeof raw.coverage_truncated !== 'boolean') invalid()
  const source = raw as unknown as AgentScreenSource
  const uncappedCount = (BigInt(source.duration_ms) + BigInt(source.interval_ms) - 1n) / BigInt(source.interval_ms)
  const expectedTruncated = source.sampling_policy === 'prefix' && uncappedCount > BigInt(source.frame_count)
  if (source.coverage_truncated !== expectedTruncated || source.frame_count > source.duration_ms || BigInt(source.frame_count) > uncappedCount
    || screenRequestedTime(source, source.frame_count - 1) >= source.duration_ms) invalid()
  return Object.freeze({ ...source })
}
export function parseAgentScreenSourceResponse(raw: unknown, duration?: number) {
  if (!exact(raw, ['screen_source'])) invalid()
  return parseAgentScreenSource(raw.screen_source, duration)
}
export function sameAgentScreenSource(a: AgentScreenSource | undefined, b: AgentScreenSource | undefined) {
  return a === undefined || b === undefined ? a === b : Object.keys(a).every(key => a[key as keyof AgentScreenSource] === b[key as keyof AgentScreenSource])
}
function rational(pts: number, base: string): [bigint, bigint] { const [n, d] = base.split('/').map(BigInt); return [BigInt(pts) * n!, d!] }
/** Public checks bind exposed hits to actual frames; the full private index is verified by the server. */
export function parseAgentScreenSearches(raw: unknown, source: AgentScreenSource, calls: readonly AgentToolCall[], frames: readonly AgentVisualFrame[]): AgentScreenSearch[] {
  const searches = calls.filter(call => call.tool_name === 'search_screen_text')
  if (!Array.isArray(raw) || raw.length !== searches.length) invalid()
  const signatures = new Map<string, number>(), exposed = new Map<string, string>()
  return raw.map((value, index) => {
    const call = searches[index]!, args = call.arguments, tokens = screenSearchTokens(args.query), top = args.top_k as number
    if (!exact(value, ['tool_step', 'status', 'error_code', 'total_match_count', 'hits']) || value.tool_step !== call.step
      || !['OBSERVED', 'EMPTY', 'NO_MATCH', 'TOOL_ERROR'].includes(String(value.status)) || !integer(value.total_match_count, 0, source.text_frame_count) || !Array.isArray(value.hits) || value.hits.length > top) invalid()
    const signature = JSON.stringify([args.query, top]), repeated = signatures.has(signature)
    if (repeated && (value.status !== 'TOOL_ERROR' || value.error_code !== 'DUPLICATE_TOOL_CALL' || value.total_match_count !== signatures.get(signature))) invalid()
    if (!repeated && value.error_code === 'DUPLICATE_TOOL_CALL') invalid()
    signatures.set(signature, value.total_match_count)
    if (value.status === 'OBSERVED') {
      if (value.error_code !== null || !value.total_match_count || value.hits.length !== Math.min(top, value.total_match_count)) invalid()
    } else {
      if (value.hits.length || call.result_frame_ids!.length) invalid()
      if (value.status === 'TOOL_ERROR') {
        if (!['CONTEXT_LIMIT', 'FRAME_UNAVAILABLE', 'FRAME_POSITION_MISMATCH', 'DUPLICATE_TOOL_CALL'].includes(String(value.error_code))
          || ['FRAME_UNAVAILABLE', 'FRAME_POSITION_MISMATCH'].includes(String(value.error_code)) && !value.total_match_count) invalid()
      } else if (value.error_code !== null || value.total_match_count || (value.status === 'EMPTY') !== (source.text_frame_count === 0)) invalid()
    }
    let previous = -1; const ptsSeen: [bigint, bigint][] = []
    const hits = value.hits.map((hit, hitIndex) => {
      if (!exact(hit, ['ocr_frame_id', 'requested_ms', 'ocr_timestamp_ms', 'source_pts', 'source_time_base', 'observed_text', 'frame_id'])
        || typeof hit.ocr_frame_id !== 'string' || !/^ocr_\d{6}$/.test(hit.ocr_frame_id) || !integer(hit.requested_ms, previous + 1, source.duration_ms - 1)
        || !integer(hit.ocr_timestamp_ms, 0, source.duration_ms - 1) || BigInt(hit.ocr_timestamp_ms) + 1n < BigInt(hit.requested_ms) || !integer(hit.source_pts)
        || typeof hit.source_time_base !== 'string' || !/^[1-9][0-9]{0,9}\/[1-9][0-9]{0,9}$/.test(hit.source_time_base)
        || typeof hit.observed_text !== 'string' || !hit.observed_text.length || hit.observed_text.length > 4000 || [...hit.observed_text].length > 2000 || !wellFormedScreenText(hit.observed_text)
        || typeof hit.frame_id !== 'string' || call.result_frame_ids![hitIndex] !== hit.frame_id) invalid()
      const ordinal = Number(hit.ocr_frame_id.slice(4))
      if (!integer(ordinal, 1, source.frame_count) || hit.requested_ms !== screenRequestedTime(source, ordinal - 1)
        || tokens.some(token => !(hit.observed_text as string).replace(/[A-Z]/g, c => c.toLowerCase()).includes(token))) invalid()
      const frame = frames.find(frame => frame.frame_id === hit.frame_id)
      if (!frame || frame.requested_ms !== hit.requested_ms) invalid()
      const [n, d] = rational(hit.source_pts, hit.source_time_base), [fn, fd] = rational(frame.source_pts, frame.source_time_base)
      // Legacy OCR timestamps may differ by a rounding millisecond; exact rational PTS must still match.
      if (n * fd !== fn * d || (n * 1000n - BigInt(hit.ocr_timestamp_ms) * d > d) || (BigInt(hit.ocr_timestamp_ms) * d - n * 1000n > d)
        || ptsSeen.some(([pn, pd]) => pn * d === n * pd)) invalid()
      const identity = JSON.stringify(hit), known = exposed.get(hit.ocr_frame_id)
      if (known && known !== identity) invalid()
      exposed.set(hit.ocr_frame_id, identity); ptsSeen.push([n, d]); previous = hit.requested_ms
      return Object.freeze({ ...hit }) as unknown as AgentScreenHit
    })
    if (hits.length !== call.result_frame_ids!.length) invalid()
    return Object.freeze({ ...value, hits }) as unknown as AgentScreenSearch
  })
}
/** Validate the complete real trace before projecting to the unchanged 1.3 validator. */
export function parseAgentScreenTools(raw: unknown, frames: readonly AgentVisualFrame[], duration: number, focus: number | undefined,
  legacy: (raw: unknown, index: number) => AgentToolCall): { calls: AgentToolCall[]; projected: AgentToolCall[] } {
  if (!Array.isArray(raw) || raw.length > 4) invalid()
  const calls = raw.map((value, index) => {
    if (!exact(value, ['step', 'tool_name', 'arguments', 'result_utterance_ids', 'result_frame_ids', 'origin']) || value.step !== index + 1
      || value.origin !== (focus !== undefined && index === 0 ? 'USER_FOCUS' : 'MODEL')) invalid()
    const { origin, ...old } = value
    if (value.tool_name !== 'search_screen_text') return { ...legacy(old, index), origin } as AgentToolCall
    if (!exact(value.arguments, ['query', 'top_k']) || !integer(value.arguments.top_k, 1, 3) || !Array.isArray(value.result_utterance_ids) || value.result_utterance_ids.length
      || !Array.isArray(value.result_frame_ids) || value.result_frame_ids.length > value.arguments.top_k || new Set(value.result_frame_ids).size !== value.result_frame_ids.length
      || value.result_frame_ids.some(id => typeof id !== 'string' || !/^f(?:00(?:0[1-9]|[12][0-9]|3[0-2]))$/.test(id))) invalid()
    screenSearchTokens(value.arguments.query)
    return { ...value, arguments: { ...value.arguments }, result_utterance_ids: [], result_frame_ids: [...value.result_frame_ids] } as unknown as AgentToolCall
  })
  if (focus !== undefined && (!calls.length || calls[0]!.tool_name !== 'inspect_frames' || JSON.stringify(calls[0]!.arguments.timestamps_ms) !== JSON.stringify([focus]) || JSON.stringify(calls[0]!.result_frame_ids) !== '["f0001"]')) invalid()
  const seen = new Set<string>(), signatures = new Set<string>(), projected: AgentToolCall[] = []
  for (const call of calls) {
    const visual = call.tool_name === 'inspect_frames' || call.tool_name === 'inspect_clip', ids = call.result_frame_ids!
    // Known argument values are primitives or ordered timestamp arrays. Sort object keys,
    // preserving the raw query, so different key insertion order cannot bypass duplicate checks.
    const signature = JSON.stringify([call.tool_name, Object.keys(call.arguments).sort().map(key => [key, call.arguments[key]])])
    if (signatures.has(signature) && (ids.length || call.result_utterance_ids.length)) invalid()
    signatures.add(signature)
    const newFrames = ids.some(id => !seen.has(id)), times = ids.map(id => frames.find(frame => frame.frame_id === id)?.requested_ms)
    if (visual && ids.length) {
      const expected = call.tool_name === 'inspect_frames' ? call.arguments.timestamps_ms : inspectClipRequestedTimes(call.arguments.start_ms as number, call.arguments.end_ms as number)
      if (JSON.stringify(times) !== JSON.stringify(expected)) invalid()
    }
    for (let i = 0; i < ids.length; i++) {
      const frame = frames.find(frame => frame.frame_id === ids[i])
      if (!frame || i > 0 && frame.requested_ms <= times[i - 1]! || call.tool_name === 'inspect_clip' && !visualFrameInRange(frame, call.arguments.start_ms as number, call.arguments.end_ms as number)
        || !seen.has(frame.frame_id) && frame.frame_id !== `f${String(seen.size + 1).padStart(4, '0')}`) invalid()
      seen.add(frame.frame_id)
    }
    if ((visual || call.tool_name === 'search_screen_text') && (!ids.length || call.tool_name !== 'inspect_clip' && !newFrames)) continue
    const { origin, ...fields } = call
    projected.push({ ...fields, step: projected.length + 1, ...(call.tool_name === 'search_screen_text' ? { tool_name: 'inspect_frames', arguments: { timestamps_ms: times as number[] } } : {}), ...(focus !== undefined ? { origin: projected.length === 0 ? 'USER_FOCUS' : 'MODEL' } : {}) } as AgentToolCall)
  }
  if (seen.size !== frames.length) invalid()
  return { calls, projected }
}
export function screenSearchMessage(search: AgentScreenSearch): string {
  if (search.status === 'OBSERVED') return `稀疏 OCR 索引匹配 ${search.total_match_count} 个不同画面位置，本次实际查看 ${search.hits.length} 帧。`
  if (search.status === 'EMPTY') return '已发布的稀疏 OCR 索引没有文字记录；不表示视频没有文字。'
  if (search.status === 'NO_MATCH') return '本次文字 AND 检索没有命中已采样索引；不表示全片没有相关内容。'
  return `本次定位未返回可用画面（${search.error_code}），索引匹配 ${search.total_match_count} 个位置；不能把定位文字当作已看画面。`
}
