import { sha256 } from 'hash-wasm'
import type { IntentPart, IntentSnapshot } from './intentClips.ts'

export interface FrameCandidate { id: string; parts: IntentPart[]; text?: string }
interface RankingFrame { timestamp_ms: number; requested_ms: number; source_pts: number; source_time_base: string; sha256: string; bytes: number; width: number; height: number; jpeg_base64: string }
export interface RankedCandidate { id: string; sample_count: number; score: number | null; frame: RankingFrame | null }
export interface FrameRanking { model_id: string; rows: RankedCandidate[] }
const integer = Number.isSafeInteger
function invalid(): never { throw new Error('INVALID_PRODUCT_FRAME_RESPONSE') }
function inParts(candidate: FrameCandidate, ms: number) { return candidate.parts.some(p => p.start_ms <= ms && ms < p.end_ms) }

export function rankingRequest(snapshot: IntentSnapshot, label: string, candidates: FrameCandidate[]) {
  if (snapshot.status !== 'PLAN_READY' || !snapshot.plan_hash || !label.trim() || label.trim().length > 80
    || !candidates.length || candidates.length > 12 || new Set(candidates.map(c => c.id)).size !== candidates.length) invalid()
  const utterances = new Map(snapshot.utterances.map(u => [u.utterance_id, u]))
  for (const c of candidates) {
    if (!/^[\w.:-]{1,128}$/.test(c.id) || !c.parts.length || c.parts.length > 12) invalid()
    let previous = 0
    for (const p of c.parts) {
      const first = utterances.get(p.start_utterance_id), last = utterances.get(p.end_utterance_id)
      if (!first || !last || first.start_ms !== p.start_ms || last.end_ms !== p.end_ms || first.start_ms > last.start_ms
        || p.start_ms < previous || p.end_ms <= p.start_ms || p.end_ms > snapshot.source.duration_ms) invalid()
      previous = p.end_ms
    }
  }
  return { plan_hash: snapshot.plan_hash, source_hash: snapshot.source.video_sha256, transcript_hash: snapshot.source.transcript_hash,
    label: label.trim(), candidates: candidates.map(c => ({ id: c.id, parts: c.parts.map(p => ({ start_utterance_id: p.start_utterance_id, end_utterance_id: p.end_utterance_id })) })) }
}

export async function parseFrameRanking(raw: any, snapshot: IntentSnapshot, label: string, candidates: FrameCandidate[]): Promise<FrameRanking> {
  if (!raw || raw.request_id !== snapshot.id || raw.video_id !== snapshot.video_id || raw.source_hash !== snapshot.source.video_sha256
    || raw.transcript_hash !== snapshot.source.transcript_hash || raw.plan_hash !== snapshot.plan_hash || raw.label !== label.trim()
    || typeof raw.model_id !== 'string' || !raw.model_id.trim() || !Array.isArray(raw.rows) || raw.rows.length !== candidates.length) invalid()
  const remaining = new Map(candidates.map(c => [c.id, c]))
  let previous = Infinity
  for (const row of raw.rows) {
    const candidate = remaining.get(row?.id)
    if (!candidate || !integer(row.sample_count) || row.sample_count < 0 || row.sample_count > 3) invalid()
    remaining.delete(row.id)
    if (!row.sample_count) {
      if (row.score !== null || row.frame !== null) invalid()
      previous = -Infinity
      continue
    }
    const f = row.frame
    if (!Number.isFinite(row.score) || Math.abs(row.score) > 1.001 || row.score > previous || !f || !integer(f.requested_ms)
      || !integer(f.timestamp_ms) || !integer(f.source_pts) || !/^\d{1,10}\/[1-9]\d{0,9}$/.test(f.source_time_base) || !inParts(candidate, f.timestamp_ms)
      || !integer(f.width) || !integer(f.height) || f.width < 1 || f.width > 384 || f.height < 1 || f.height > 384
      || !integer(f.bytes) || f.bytes < 4 || f.bytes > 1024 * 1024 || !/^[a-f0-9]{64}$/.test(f.sha256)
      || typeof f.jpeg_base64 !== 'string' || f.jpeg_base64.length !== Math.ceil(f.bytes / 3) * 4) invalid()
    const [num, den] = (f.source_time_base as string).split('/').map(BigInt)
    if (!num || !den) invalid()
    const pts = BigInt(f.source_pts) * 1000n * num
    if ((pts + den / 2n) / den !== BigInt(f.timestamp_ms) || pts < BigInt(f.requested_ms) * den || pts > BigInt(f.requested_ms + 1000) * den
      || !candidate.parts.some(p => p.start_ms <= f.requested_ms && pts < BigInt(p.end_ms) * den)) invalid()
    const bytes = Uint8Array.from(atob(f.jpeg_base64), c => c.charCodeAt(0))
    if (bytes.length !== f.bytes || bytes[0] !== 0xff || bytes[1] !== 0xd8) invalid()
    const hash = await sha256(bytes)
    if (hash !== f.sha256) invalid()
    previous = row.score
  }
  return { model_id: raw.model_id, rows: raw.rows }
}
