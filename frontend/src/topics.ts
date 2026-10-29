export interface TopicSegment {
  id: string; start_ms: number; end_ms: number; title: string; summary: string; keywords: string[]; enabled: boolean
  origin?: string; boundary_source?: string; content_source?: string; original_prediction_id?: string | null
}
export interface TopicUtterance { utterance_id: string; start_ms: number; end_ms: number; text: string; source_chunk_id: string }
export interface TopicReview {
  job_id: string; video_id: string; generation: number; analysis_mode: 'topics'; data_kind: 'model_result'
  duration_ms: number; revision: number; status: 'DRAFT' | 'CONFIRMED'; source_hash: string; confirmed_revisions: number[]
  segments: TopicSegment[]; utterances: TopicUtterance[]
}
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const identifier = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function integer(value: unknown, min = 0): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= min }
function text(value: unknown, maximum: number): value is string { return typeof value === 'string' && !!value.trim() && [...value.trim()].length <= maximum }
export class TopicResponseError extends Error { constructor() { super('INVALID_SERVER_RESPONSE') } }
function invalidTopic(): never { throw new TopicResponseError() }

export function topicPayload(segments: TopicSegment[]) {
  return { segments: segments.map(({ id, start_ms, end_ms, title, summary, keywords, enabled }) => ({ id, start_ms, end_ms, title, summary, keywords: [...keywords], enabled })) }
}

/** Selected topics may touch at an endpoint; they must not overlap or leave the source duration. */
export function topicValidation(segments: TopicSegment[], duration: number): string {
  const ids = new Set<string>()
  for (const segment of segments) {
    if (!identifier.test(segment.id) || ids.has(segment.id)) return '片段标识重复或无效，请重新读取。'
    ids.add(segment.id)
    if (!integer(segment.start_ms) || !integer(segment.end_ms, segment.start_ms + 1) || segment.end_ms > duration) return '请填写有效的起止时间（如 05:00.000），须在源视频内且结束晚于开始。'
    if (!text(segment.title, 120) || !text(segment.summary, 1000)) return '每段须填写标题（最多 120 字）和摘要（最多 1000 字）。'
    if (!Array.isArray(segment.keywords) || segment.keywords.length > 8 || segment.keywords.some(word => !text(word, 40)) || new Set(segment.keywords).size !== segment.keywords.length) return '关键词最多 8 个，每个最多 40 字，且不能重复。'
    if (typeof segment.enabled !== 'boolean') return '片段选择状态无效，请重新读取。'
  }
  const selected = segments.filter(segment => segment.enabled).sort((a, b) => a.start_ms - b.start_ms)
  if (selected.some((segment, index) => index > 0 && selected[index - 1]!.end_ms > segment.start_ms)) return '已勾选的片段存在重叠，请调整边界或取消其中一段。'
  return ''
}

/** Validate the mode and resource before putting a network response into an editable review. */
export function parseTopicReview(raw: unknown, jobId: string): TopicReview {
  if (!object(raw) || raw.job_id !== jobId || !uuid.test(jobId) || typeof raw.video_id !== 'string' || !uuid.test(raw.video_id)
      || raw.analysis_mode !== 'topics' || raw.data_kind !== 'model_result' || !integer(raw.generation, 1) || !integer(raw.duration_ms, 1)
      || !integer(raw.revision) || !['DRAFT', 'CONFIRMED'].includes(raw.status as string)
      || typeof raw.source_hash !== 'string' || !/^[a-f0-9]{64}$/.test(raw.source_hash)
      || !Array.isArray(raw.confirmed_revisions) || raw.confirmed_revisions.some(value => !integer(value))
      || new Set(raw.confirmed_revisions).size !== raw.confirmed_revisions.length
      || !Array.isArray(raw.segments) || !Array.isArray(raw.utterances)) invalidTopic()
  if ((raw.status === 'CONFIRMED') !== raw.confirmed_revisions.includes(raw.revision)) invalidTopic()
  for (const segment of raw.segments) {
    if (!object(segment) || typeof segment.id !== 'string' || !Array.isArray(segment.keywords)) invalidTopic()
  }
  if (topicValidation(raw.segments as unknown as TopicSegment[], raw.duration_ms)) invalidTopic()
  const ids = new Set<string>()
  let previousEnd = 0
  for (const utterance of raw.utterances) {
    if (!object(utterance) || typeof utterance.utterance_id !== 'string' || !identifier.test(utterance.utterance_id) || ids.has(utterance.utterance_id)
        || !integer(utterance.start_ms) || !integer(utterance.end_ms, utterance.start_ms + 1) || utterance.start_ms < previousEnd || utterance.end_ms > raw.duration_ms
        || !text(utterance.text, 20000) || typeof utterance.source_chunk_id !== 'string' || !identifier.test(utterance.source_chunk_id)) invalidTopic()
    ids.add(utterance.utterance_id); previousEnd = utterance.end_ms
  }
  return raw as unknown as TopicReview
}

export function topicTime(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—'
  return `${Math.floor(ms / 60000).toString().padStart(2, '0')}:${Math.floor(ms % 60000 / 1000).toString().padStart(2, '0')}.${Math.floor(ms % 1000).toString().padStart(3, '0')}`
}

export function parseTopicTime(value: string): number | null {
  const match = /^(?:(\d+):([0-5]\d)|([0-9]+)):([0-5]\d)(?:\.(\d{1,3}))?$/.exec(value.trim())
  if (!match) return null
  const minutes = match[1] === undefined ? Number(match[3]) : Number(match[1]) * 60 + Number(match[2])
  const result = minutes * 60000 + Number(match[4]) * 1000 + Number((match[5] ?? '').padEnd(3, '0'))
  return Number.isSafeInteger(result) && result >= 0 ? result : null
}

export function splitTopic(segment: TopicSegment, playhead: number, newId: string): [TopicSegment, TopicSegment] | null {
  const point = playhead > segment.start_ms && playhead < segment.end_ms ? Math.round(playhead) : Math.floor((segment.start_ms + segment.end_ms) / 2)
  if (!integer(point) || point <= segment.start_ms || point >= segment.end_ms || !identifier.test(newId) || newId === segment.id) return null
  return [{ ...segment, keywords: [...segment.keywords], end_ms: point, boundary_source: 'manual' },
    { ...segment, keywords: [...segment.keywords], id: newId, start_ms: point, origin: 'manual', boundary_source: 'manual', original_prediction_id: null }]
}
