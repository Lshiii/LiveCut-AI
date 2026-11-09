import { parseAgentTurn, type AgentSessionIdentity, type AgentTurn } from './videoAgent.ts'

export const MAX_AGENT_NOTE_TEXT_BYTES = 2 * 1024 * 1024
export const MAX_AGENT_NOTE_JSON_BYTES = 5 * 1024 * 1024
export const MAX_AGENT_NOTE_ZIP_BYTES = 24 * 1024 * 1024
export interface AgentNoteOwner {
  turn_id: string; session_id: string; job_id: string; video_id: string; source_hash: string; transcript_hash: string
  answer_scope: 'VIDEO' | 'GENERAL' | 'INSUFFICIENT'; citation_count: number; image_count: number
}
export interface AgentNotePreview extends AgentNoteOwner {
  schema_version: 1; result_hash: string; source_filename: string; markdown: string; download_filename: string
}
export class AgentNoteError extends Error {
  code: string
  constructor(code = 'INVALID_AGENT_NOTE_RESPONSE') { super(code); this.code = code }
}
interface ReadOptions { signal?: AbortSignal; current?: () => boolean; fetcher?: typeof fetch; timeoutMs?: number }
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, HASH = /^[a-f0-9]{64}$/
const fields = ['schema_version', 'turn_id', 'session_id', 'job_id', 'video_id', 'source_hash', 'transcript_hash', 'result_hash',
  'source_filename', 'answer_scope', 'citation_count', 'image_count', 'markdown', 'download_filename']
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown, max: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= max
const utf8Size = (value: string) => new TextEncoder().encode(value).byteLength
function invalid(): never { throw new AgentNoteError() }
function ensureCurrent(options: ReadOptions) {
  if (options.signal?.aborted || options.current?.() === false) throw new AgentNoteError('AGENT_NOTE_READ_CANCELLED')
}
function checkOwner(owner: AgentNoteOwner) {
  if (!['turn_id', 'session_id', 'job_id', 'video_id'].every(key => UUID.test(owner[key as keyof AgentNoteOwner] as string))
    || !HASH.test(owner.source_hash) || !HASH.test(owner.transcript_hash) || !['VIDEO', 'GENERAL', 'INSUFFICIENT'].includes(owner.answer_scope)
    || !integer(owner.citation_count, 24) || !integer(owner.image_count, 32)) invalid()
}
/** Bind a note to this verified successful turn; no model or HTTP work occurs. */
export function agentNoteOwner(session: AgentSessionIdentity, raw: AgentTurn): AgentNoteOwner {
  const turn = parseAgentTurn(raw, session, raw.id)
  if (turn.status !== 'SUCCEEDED' || !turn.result) throw new AgentNoteError('AGENT_NOTE_NOT_READY')
  const result = turn.result
  const owner: AgentNoteOwner = { turn_id: turn.id, session_id: session.id, job_id: session.job_id, video_id: session.video_id,
    source_hash: session.source.video_sha256, transcript_hash: session.source.transcript_hash, answer_scope: result.answer_scope,
    citation_count: result.citations.length, image_count: result.visual_citations?.length ?? 0 }
  checkOwner(owner); return Object.freeze(owner)
}
export function parseAgentNotePreview(raw: unknown, owner: AgentNoteOwner): AgentNotePreview {
  checkOwner(owner)
  if (!row(raw) || Object.keys(raw).length !== fields.length || !fields.every(key => Object.hasOwn(raw, key)) || raw.schema_version !== 1
    || !Object.keys(owner).every(key => raw[key] === owner[key as keyof AgentNoteOwner])
    || typeof raw.result_hash !== 'string' || !HASH.test(raw.result_hash)
    || typeof raw.source_filename !== 'string' || !raw.source_filename.trim() || raw.source_filename.length > 1000
    || typeof raw.markdown !== 'string' || !raw.markdown.trim() || raw.markdown.length > MAX_AGENT_NOTE_TEXT_BYTES
    || raw.download_filename !== `video-note-${owner.turn_id}.zip`) invalid()
  if (utf8Size(raw.markdown) > MAX_AGENT_NOTE_TEXT_BYTES) throw new AgentNoteError('AGENT_NOTE_TOO_LARGE')
  return Object.freeze({ schema_version: 1, ...owner, result_hash: raw.result_hash, source_filename: raw.source_filename,
    markdown: raw.markdown, download_filename: raw.download_filename as string })
}
async function boundedBytes(response: Response, maximum: number, signal: AbortSignal): Promise<Uint8Array> {
  const length = response.headers.get('Content-Length')
  if (length !== null && (!/^\d+$/.test(length) || !Number.isSafeInteger(Number(length)))) invalid()
  if (length !== null && Number(length) > maximum) throw new AgentNoteError('AGENT_NOTE_TOO_LARGE')
  if (!response.body) invalid()
  const reader = response.body.getReader(), chunks: Uint8Array[] = []; let size = 0
  try {
    while (true) {
      if (signal.aborted) throw new AgentNoteError('AGENT_NOTE_READ_CANCELLED')
      const { value, done } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > maximum) throw new AgentNoteError('AGENT_NOTE_TOO_LARGE')
      chunks.push(value)
    }
  } catch (error) { await reader.cancel().catch(() => {}); throw error }
  finally { reader.releaseLock() }
  if (!size) invalid()
  const bytes = new Uint8Array(size); let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return bytes
}
const errorCodes = new Set(['AGENT_NOTE_BUSY', 'AGENT_NOTE_TOO_LARGE', 'AGENT_NOTE_NOT_READY', 'AGENT_NOTE_UNAVAILABLE', 'AGENT_NOTE_BUILD_FAILED',
  'AGENT_SOURCE_CHANGED', 'AGENT_SOURCE_INVALID', 'AGENT_SOURCE_UNAVAILABLE', 'AGENT_SOURCE_NOT_READY', 'ARTIFACT_HASH_MISMATCH', 'ARTIFACT_INPUT_MISMATCH', 'ARTIFACT_NOT_FOUND', 'ARTIFACT_UNREADABLE',
  'AGENT_VISUAL_EVIDENCE_INVALID', 'AGENT_NOTE_SOURCE_INVALID', 'AGENT_RESULT_INVALID', 'AGENT_STAGE_UNAVAILABLE'])
async function noteGet<T>(path: string, options: ReadOptions, read: (response: Response, signal: AbortSignal) => Promise<T>): Promise<T> {
  ensureCurrent(options)
  const controller = new AbortController(); let timedOut = false
  const abort = () => controller.abort()
  options.signal?.addEventListener('abort', abort, { once: true })
  const timer = globalThis.setTimeout(() => { timedOut = true; controller.abort() }, options.timeoutMs ?? 30000)
  try {
    const response = await (options.fetcher ?? fetch)(path, { method: 'GET', cache: 'no-store', credentials: 'same-origin', redirect: 'error', signal: controller.signal })
    ensureCurrent(options)
    if (response.redirected) invalid()
    if (!response.ok) {
      const bytes = await boundedBytes(response, 64 * 1024, controller.signal)
      let raw: unknown
      try { raw = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) } catch { throw new AgentNoteError('AGENT_NOTE_UNAVAILABLE') }
      throw new AgentNoteError(row(raw) && typeof raw.error_code === 'string' && errorCodes.has(raw.error_code) ? raw.error_code : 'AGENT_NOTE_UNAVAILABLE')
    }
    const result = await read(response, controller.signal)
    ensureCurrent(options)
    if (controller.signal.aborted) throw new AgentNoteError('REQUEST_TIMEOUT')
    return result
  } catch (error) {
    if (options.signal?.aborted || options.current?.() === false) throw new AgentNoteError('AGENT_NOTE_READ_CANCELLED')
    if (timedOut) throw new AgentNoteError('REQUEST_TIMEOUT')
    if (error instanceof AgentNoteError) throw error
    throw new AgentNoteError('NETWORK_UNAVAILABLE')
  } finally { controller.abort(); globalThis.clearTimeout(timer); options.signal?.removeEventListener('abort', abort) }
}
export async function readAgentNote(owner: AgentNoteOwner, options: ReadOptions = {}): Promise<AgentNotePreview> {
  checkOwner(owner)
  return noteGet(`/api/agent-turns/${owner.turn_id}/note`, options, async (response, signal) => {
    if (response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== 'application/json') invalid()
    const bytes = await boundedBytes(response, MAX_AGENT_NOTE_JSON_BYTES, signal)
    let raw: unknown
    try { raw = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) } catch { invalid() }
    return parseAgentNotePreview(raw, owner)
  })
}
export async function readAgentNoteZip(owner: AgentNoteOwner, raw: AgentNotePreview, options: ReadOptions = {}): Promise<{ blob: Blob; filename: string }> {
  const note = parseAgentNotePreview(raw, owner)
  return noteGet(`/api/agent-turns/${owner.turn_id}/note/download`, options, async (response, signal) => {
    if (response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== 'application/zip'
      || response.headers.get('Content-Disposition') !== `attachment; filename="${note.download_filename}"`
      || response.headers.get('X-LiveCut-Note-Turn-Id') !== note.turn_id
      || response.headers.get('X-LiveCut-Note-Result-Hash') !== note.result_hash) invalid()
    const bytes = await boundedBytes(response, MAX_AGENT_NOTE_ZIP_BYTES, signal)
    if (bytes.length < 22 || bytes[0] !== 0x50 || bytes[1] !== 0x4b || bytes[2] !== 0x03 || bytes[3] !== 0x04) invalid()
    return { blob: new Blob([bytes as Uint8Array<ArrayBuffer>], { type: 'application/zip' }), filename: note.download_filename }
  })
}
/** The final lifecycle check precedes the only download side effect. */
export async function downloadAgentNoteZip(owner: AgentNoteOwner, preview: AgentNotePreview, options: ReadOptions,
  save: (blob: Blob, filename: string) => void): Promise<void> {
  const value = await readAgentNoteZip(owner, preview, options)
  ensureCurrent(options); save(value.blob, value.filename)
}
export async function copyAgentNoteMarkdown(preview: AgentNotePreview, writeText: ((text: string) => Promise<void>) | undefined,
  current: () => boolean): Promise<void> {
  if (!current()) throw new AgentNoteError('AGENT_NOTE_READ_CANCELLED')
  if (!writeText) throw new AgentNoteError('AGENT_NOTE_CLIPBOARD_UNAVAILABLE')
  try { await writeText(preview.markdown) } catch { throw new AgentNoteError('AGENT_NOTE_CLIPBOARD_UNAVAILABLE') }
  if (!current()) throw new AgentNoteError('AGENT_NOTE_READ_CANCELLED')
}
export function agentNoteErrorCopy(value: unknown): string {
  const code = value instanceof AgentNoteError ? value.code : 'AGENT_NOTE_UNAVAILABLE'
  switch (code) {
    case 'AGENT_NOTE_BUSY': return '同时准备的笔记较多，请稍后重试。当前回答与输入保留。'
    case 'AGENT_NOTE_TOO_LARGE': return '本轮笔记超过导出大小上限，未截断内容或下载不完整文件。'
    case 'AGENT_NOTE_NOT_READY': return '本轮尚未生成可导出的成功回答。请等待回答完成后再打开学习笔记。'
    case 'AGENT_NOTE_BUILD_FAILED': return '笔记文件暂未准备成功，请稍后重新下载。已有回答与输入保留。'
    case 'AGENT_SOURCE_NOT_READY': return '视频分析来源尚未准备完成，暂不能导出本轮笔记。'
    case 'AGENT_RESULT_INVALID': return '本轮回答与已保存产物未通过一致性校验，暂不能导出笔记。已有回答仍保留。'
    case 'AGENT_VISUAL_EVIDENCE_INVALID': return '本轮截图依据未通过完整性校验，暂不能导出含图笔记。已有回答仍保留。'
    case 'AGENT_NOTE_SOURCE_INVALID': return '本轮笔记的来源或引用图片无法核对，未下载缺图或来源不明的文件。'
    case 'AGENT_STAGE_UNAVAILABLE': return '本轮执行记录暂时无法核对，请稍后重新读取笔记。已有回答仍保留。'
    case 'AGENT_SOURCE_CHANGED': return '视频来源已变化，暂不能导出本轮笔记。请先核对原素材。'
    case 'AGENT_SOURCE_INVALID': case 'AGENT_SOURCE_UNAVAILABLE': case 'ARTIFACT_HASH_MISMATCH': case 'ARTIFACT_INPUT_MISMATCH': case 'ARTIFACT_NOT_FOUND': case 'ARTIFACT_UNREADABLE':
      return '视频来源或已保存产物未通过校验，暂不能导出笔记。已有回答仍保留。'
    case 'AGENT_NOTE_CLIPBOARD_UNAVAILABLE': return '无法写入剪贴板。已保留文字笔记，可选中文字手动复制，或下载含图 ZIP。'
    case 'REQUEST_TIMEOUT': return '读取学习笔记超时，请稍后重试。当前回答与输入保留。'
    case 'NETWORK_UNAVAILABLE': return '暂时无法连接笔记服务，请稍后重试。当前回答与输入保留。'
    case 'INVALID_AGENT_NOTE_RESPONSE': return '笔记内容或来源无法核对，未复制或下载。请重新读取本轮笔记。'
    default: return '本轮来源或笔记暂时无法核验，请稍后重试。已有回答与输入保留。'
  }
}
