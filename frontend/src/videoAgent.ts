import { parseDiagnostic, type StageDiagnostic } from './diagnostics.ts'
import { parseTextProgress, type TextProgress } from './textProgress.ts'
import { inspectClipRequestedTimes, parseAgentVisualCitations, parseAgentVisualFrames, visualFrameId, visualFrameInRange, type AgentVisualCitation, type AgentVisualFrame } from './agentVisualEvidence.ts'
import { evidenceTextValid, parseAgentEvidenceBlocks, type AgentEvidenceBlock } from './agentEvidenceAnswer.ts'
import { parseAgentSpeechLookup, type AgentSpeechLookup } from './agentSpeechLookup.ts'
import { parseAgentScreenSource, parseAgentScreenTools, parseAgentScreenSearches, sameAgentScreenSource, type AgentScreenSource, type AgentScreenSearch } from './agentScreenSearch.ts'

export interface AgentSource { video_sha256: string; transcript_hash: string; duration_ms: number }
export interface AgentCitation { utterance_id: string; start_ms: number; end_ms: number; text: string }
export type AgentToolCall = {
  step: number; tool_name: 'search_transcript' | 'read_context' | 'list_topics' | 'inspect_frames' | 'inspect_clip' | 'search_screen_text'
  arguments: Record<string, string | number | number[]>; result_utterance_ids: string[]; result_frame_ids?: string[]
  origin?: 'USER_FOCUS' | 'EVIDENCE_POLICY' | 'MODEL'
}
export interface AgentResult {
  schema_version: '1.0' | '1.1' | '1.2' | '1.3' | '1.4' | '1.5'; session_id: string; turn_id: string; job_id: string; video_id: string
  source_hash: string; transcript_hash: string; question: string; answer: string
  answer_scope: 'VIDEO' | 'GENERAL' | 'INSUFFICIENT'; citations: AgentCitation[]; tool_calls: AgentToolCall[]
  provenance: { text_model_id: string; prompt_version: 'video-agent-v1' | 'video-agent-visual-v1' | 'video-agent-visual-focus-v1' | 'video-agent-evidence-v1' | 'video-agent-evidence-speech-v1' | 'video-agent-evidence-screen-v1'; retrieval_mode: 'none' | 'bm25' | 'bm25_dense_rrf' }
  suggested_clip_instruction: string | null
  visual_evidence?: AgentVisualFrame[]; visual_citations?: AgentVisualCitation[]
  focus_timestamp_ms?: number
  evidence_mode?: 'structured'; answer_blocks?: AgentEvidenceBlock[]
  evidence_policy?: 'speech_lookup_v1' | 'screen_search_v1'; speech_lookup?: AgentSpeechLookup; screen_source?: AgentScreenSource; screen_searches?: AgentScreenSearch[]
}
export interface AgentTurn {
  id: string; session_id: string; job_id: string; question: string; sequence: number
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'; state_version: number
  created_at: string; updated_at: string; budget: { max_text_calls: number; text_calls_reserved: number }
  visual_mode?: 'none' | 'frames'
  evidence_mode?: 'structured'
  evidence_policy?: 'speech_lookup_v1' | 'screen_search_v1'
  screen_source?: AgentScreenSource
  focus_timestamp_ms?: number
  replay_source_turn_id?: string; replay_snapshot_hash?: string
  stage: { id: string; stage_type: 'SEGMENT' | 'AGENT_VISUAL' | 'AGENT_VISUAL_FOCUS' | 'AGENT_EVIDENCE'; status: string; attempt: number; error_code: string | null; diagnostic?: StageDiagnostic; text_progress?: TextProgress; required_worker_protocol?: 3 | 4 }
  result: AgentResult | null
}
export interface AgentSession { id: string; job_id: string; video_id: string; created_at: string; source: AgentSource; turns: AgentTurn[] }
export type AgentSessionIdentity = Pick<AgentSession, 'id' | 'job_id' | 'video_id' | 'source'>
export interface AgentReceipt { input: string; key: string }
export class AgentResponseError extends Error { constructor() { super('INVALID_AGENT_RESPONSE') } }
type Row = Record<string, unknown>
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const HASH = /^[a-f0-9]{64}$/
const STAGES = new Set(['READY', 'BLOCKED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'])
function row(v: unknown): v is Row { return v !== null && typeof v === 'object' && !Array.isArray(v) }
function integer(v: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): v is number { return Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max }
function text(v: unknown, max: number): v is string { return typeof v === 'string' && !!v.trim() && v.length <= max }
function uuid(v: unknown): v is string { return typeof v === 'string' && UUID.test(v) }
function identifier(v: unknown): v is string { return typeof v === 'string' && ID.test(v) }
function hash(v: unknown): v is string { return typeof v === 'string' && HASH.test(v) }
function date(v: unknown) { return typeof v === 'string' && Number.isFinite(Date.parse(v)) }
function invalid(): never { throw new AgentResponseError() }
function fields(value: Row, expected: string[]) { return Object.keys(value).length === expected.length && expected.every(key => Object.hasOwn(value, key)) }
export function agentTurnActive(turn: Pick<AgentTurn, 'status'>) { return turn.status === 'QUEUED' || turn.status === 'RUNNING' }

function tool(raw: unknown, index: number, visual: boolean, duration: number, focus: boolean): AgentToolCall {
  if (!row(raw) || raw.step !== index + 1 || !row(raw.arguments) || !Array.isArray(raw.result_utterance_ids)
    || raw.result_utterance_ids.length > 200 || raw.result_utterance_ids.some(id => !identifier(id))
    || new Set(raw.result_utterance_ids).size !== raw.result_utterance_ids.length) invalid()
  const args = raw.arguments
  if (visual && (!fields(raw, ['step', 'tool_name', 'arguments', 'result_utterance_ids', 'result_frame_ids', ...(focus ? ['origin'] : [])]) || !Array.isArray(raw.result_frame_ids)
    || raw.result_frame_ids.length > 24 || raw.result_frame_ids.some(id => !visualFrameId(id)) || new Set(raw.result_frame_ids).size !== raw.result_frame_ids.length)) invalid()
  if (raw.tool_name === 'search_transcript') {
    if (!fields(args, ['query', 'top_k']) || !text(args.query, 300) || !integer(args.top_k, 1, 8)) invalid()
  } else if (raw.tool_name === 'read_context') {
    if (!fields(args, ['utterance_id', 'before', 'after']) || !identifier(args.utterance_id)
      || !integer(args.before, 0, 3) || !integer(args.after, 0, 3)) invalid()
  } else if (raw.tool_name === 'list_topics') {
    if (!fields(args, ['offset', 'limit']) || !integer(args.offset) || !integer(args.limit, 1, 12)) invalid()
  } else if (visual && raw.tool_name === 'inspect_frames') {
    if (!fields(args, ['timestamps_ms']) || !Array.isArray(args.timestamps_ms) || !args.timestamps_ms.length || args.timestamps_ms.length > 4
      || args.timestamps_ms.some((value, i, values) => !integer(value, 0, duration - 1) || i > 0 && value <= (values[i - 1] as number)) || raw.result_utterance_ids.length) invalid()
  } else if (visual && raw.tool_name === 'inspect_clip') {
    if (!fields(args, ['start_ms', 'end_ms']) || !integer(args.start_ms, 0, duration - 1) || !integer(args.end_ms, args.start_ms + 1, duration)
      || args.end_ms - args.start_ms > 12000 || raw.result_utterance_ids.length) invalid()
  } else invalid()
  const visualTool = raw.tool_name === 'inspect_frames' || raw.tool_name === 'inspect_clip'
  if (visual && !visualTool && (raw.result_frame_ids as unknown[]).length) invalid()
  if (focus && raw.origin !== (index === 0 ? 'USER_FOCUS' : 'MODEL')) invalid()
  return { step: raw.step as number, tool_name: raw.tool_name as AgentToolCall['tool_name'],
    arguments: { ...args } as AgentToolCall['arguments'], result_utterance_ids: [...raw.result_utterance_ids] as string[],
    ...(visual ? { result_frame_ids: [...raw.result_frame_ids as string[]] } : {}), ...(focus ? { origin: raw.origin as 'USER_FOCUS' | 'MODEL' } : {}) }
}
function result(raw: unknown, turn: AgentTurn, session: AgentSessionIdentity, evidenceProjection = false): AgentResult {
  const visual = turn.visual_mode === 'frames', focus = turn.focus_timestamp_ms !== undefined
  if (turn.evidence_policy === 'screen_search_v1' && !evidenceProjection) {
    if (!row(raw) || raw.schema_version !== '1.5' || raw.evidence_policy !== 'screen_search_v1' || !Object.hasOwn(raw, 'screen_source') || !Object.hasOwn(raw, 'screen_searches')
      || !row(raw.provenance) || raw.provenance.prompt_version !== 'video-agent-evidence-screen-v1') invalid()
    try {
      const source = parseAgentScreenSource(raw.screen_source, session.source.duration_ms)
      if (!sameAgentScreenSource(source, turn.screen_source)) invalid()
      const frames = parseAgentVisualFrames(raw.visual_evidence, session.source.duration_ms)
      const trace = parseAgentScreenTools(raw.tool_calls, frames, session.source.duration_ms, turn.focus_timestamp_ms,
        (call, index) => tool(call, index, true, session.source.duration_ms, false))
      const searches = parseAgentScreenSearches(raw.screen_searches, source, trace.calls, frames)
      const { evidence_policy, screen_source, screen_searches, ...legacy } = raw
      const checked = result({ ...legacy, schema_version: '1.3', tool_calls: trace.projected,
        provenance: { ...raw.provenance, prompt_version: 'video-agent-evidence-v1' } }, { ...turn, evidence_policy: undefined }, session)
      // Blocks must also be valid against the real tools: OCR lookup is never a short-clip change proof.
      const real = { ...checked, tool_calls: trace.calls }
      parseAgentEvidenceBlocks(raw.answer_blocks, real)
      return { ...real, schema_version: '1.5', evidence_policy: 'screen_search_v1', screen_source: source, screen_searches: searches,
        provenance: { ...checked.provenance, prompt_version: 'video-agent-evidence-screen-v1' } }
    } catch { invalid() }
  }
  if (turn.evidence_policy === 'speech_lookup_v1' && !evidenceProjection) {
    if (!row(raw) || raw.schema_version !== '1.4' || raw.evidence_policy !== 'speech_lookup_v1' || !Object.hasOwn(raw, 'speech_lookup')
      || !Array.isArray(raw.tool_calls) || !Array.isArray(raw.citations) || !row(raw.provenance)
      || raw.provenance.prompt_version !== 'video-agent-evidence-speech-v1' || !['none', 'bm25'].includes(String(raw.provenance.retrieval_mode))) invalid()
    try {
      const receipt = parseAgentSpeechLookup(raw.speech_lookup, turn, raw.tool_calls as AgentToolCall[], raw.citations as AgentCitation[])
      const { evidence_policy, speech_lookup, ...legacy } = raw
      const projectedCalls = raw.tool_calls.map((call, index) => {
        if (!row(call)) invalid()
        const { origin, ...fields } = call
        return { ...fields, ...(focus ? { origin: index === 0 ? 'USER_FOCUS' : 'MODEL' } : {}) }
      })
      const checked = result({ ...legacy, schema_version: '1.3', tool_calls: projectedCalls, provenance: { ...raw.provenance, prompt_version: 'video-agent-evidence-v1' } }, { ...turn, evidence_policy: undefined }, session)
      return { ...checked, schema_version: '1.4', evidence_policy, speech_lookup: receipt,
        tool_calls: checked.tool_calls.map((call, index) => ({ ...call, origin: (raw.tool_calls as AgentToolCall[])[index]!.origin })),
        provenance: { ...checked.provenance, prompt_version: 'video-agent-evidence-speech-v1' } } as AgentResult
    } catch { invalid() }
  }
  if (turn.evidence_mode === 'structured' && !evidenceProjection) {
    if (!row(raw) || raw.schema_version !== '1.3' || raw.evidence_mode !== 'structured' || !Object.hasOwn(raw, 'answer_blocks')
      || !row(raw.provenance) || raw.provenance.prompt_version !== 'video-agent-evidence-v1') invalid()
    const { evidence_mode, answer_blocks, ...legacy } = raw
    const checked = result({ ...legacy, schema_version: focus ? '1.2' : '1.1', provenance: { ...raw.provenance, prompt_version: focus ? 'video-agent-visual-focus-v1' : 'video-agent-visual-v1' } }, turn, session, true)
    try { return { ...checked, schema_version: '1.3', evidence_mode, answer_blocks: parseAgentEvidenceBlocks(answer_blocks, checked), provenance: { ...checked.provenance, prompt_version: 'video-agent-evidence-v1' } } as AgentResult }
    catch { invalid() }
  }
  if (!row(raw) || raw.schema_version !== (focus ? '1.2' : visual ? '1.1' : '1.0') || raw.session_id !== session.id || raw.turn_id !== turn.id
    || raw.job_id !== session.job_id || raw.video_id !== session.video_id || raw.source_hash !== session.source.video_sha256
    || raw.transcript_hash !== session.source.transcript_hash || raw.question !== turn.question || !text(raw.answer, 6000)
    || !['VIDEO', 'GENERAL', 'INSUFFICIENT'].includes(raw.answer_scope as string)
    || !Array.isArray(raw.citations) || raw.citations.length > 24 || !Array.isArray(raw.tool_calls) || raw.tool_calls.length > 4
    || !row(raw.provenance) || !text(raw.provenance.text_model_id, 128) || raw.provenance.prompt_version !== (focus ? 'video-agent-visual-focus-v1' : visual ? 'video-agent-visual-v1' : 'video-agent-v1')
    || !['none', 'bm25', 'bm25_dense_rrf'].includes(raw.provenance.retrieval_mode as string)
    || !(raw.suggested_clip_instruction === null || text(raw.suggested_clip_instruction, 2000))) invalid()
  if (visual && (!fields(raw, ['schema_version', 'session_id', 'turn_id', 'job_id', 'video_id', 'source_hash', 'transcript_hash', 'question', 'answer', 'answer_scope', 'citations', 'tool_calls', 'provenance', 'suggested_clip_instruction', 'visual_evidence', 'visual_citations', ...(focus ? ['focus_timestamp_ms'] : [])])
    || !fields(raw.provenance, ['text_model_id', 'prompt_version', 'retrieval_mode']) || !identifier(raw.provenance.text_model_id))) invalid()
  if (!visual && (Object.hasOwn(raw, 'visual_evidence') || Object.hasOwn(raw, 'visual_citations'))) invalid()
  if (focus ? raw.focus_timestamp_ms !== turn.focus_timestamp_ms : Object.hasOwn(raw, 'focus_timestamp_ms')) invalid()
  const calls = raw.tool_calls.map((value, index) => tool(value, index, visual, session.source.duration_ms, focus)), ids = new Set<string>(); let previous = 0
  const citations = raw.citations.map(value => {
    if (!row(value) || visual && !fields(value, ['utterance_id', 'start_ms', 'end_ms', 'text']) || !identifier(value.utterance_id) || ids.has(value.utterance_id) || !text(value.text, 100000)
      || !integer(value.start_ms, previous) || !integer(value.end_ms, value.start_ms + 1, session.source.duration_ms)) invalid()
    previous = value.end_ms; ids.add(value.utterance_id)
    return { utterance_id: value.utterance_id, start_ms: value.start_ms, end_ms: value.end_ms, text: value.text }
  })
  let frames: AgentVisualFrame[] = [], visualCitations: AgentVisualCitation[] = []
  if (visual) {
    try { frames = parseAgentVisualFrames(raw.visual_evidence, session.source.duration_ms); visualCitations = parseAgentVisualCitations(raw.visual_citations, frames) } catch { invalid() }
    if (focus && (!frames.length || !calls.length || calls[0]!.tool_name !== 'inspect_frames'
      || JSON.stringify(calls[0]!.arguments.timestamps_ms) !== JSON.stringify([turn.focus_timestamp_ms])
      || JSON.stringify(calls[0]!.result_frame_ids) !== JSON.stringify(['f0001']) || frames[0]!.requested_ms !== turn.focus_timestamp_ms)) invalid()
    const seen = new Set<string>(), visualArguments = new Set<string>(), observedUtterances = new Set(calls.flatMap(call => call.result_utterance_ids))
    if (citations.some(citation => !observedUtterances.has(citation.utterance_id))) invalid()
    for (const call of calls) {
      const signature = call.tool_name === 'inspect_frames' ? `frames:${(call.arguments.timestamps_ms as number[]).join(',')}`
        : call.tool_name === 'inspect_clip' ? `clip:${call.arguments.start_ms}:${call.arguments.end_ms}` : null
      if (signature && visualArguments.has(signature) && call.result_frame_ids!.length) invalid()
      if (signature) visualArguments.add(signature)
      if (signature && call.result_frame_ids!.length) {
        const expectedTimes = call.tool_name === 'inspect_frames' ? call.arguments.timestamps_ms as number[]
          : inspectClipRequestedTimes(call.arguments.start_ms as number, call.arguments.end_ms as number)
        const actualTimes = call.result_frame_ids!.map(id => frames.find(frame => frame.frame_id === id)?.requested_ms)
        if (actualTimes.length !== expectedTimes.length || actualTimes.some((time, index) => time !== expectedTimes[index])) invalid()
      }
      let previousRequested = -1
      for (const frameId of call.result_frame_ids!) {
      const frame = frames.find(value => value.frame_id === frameId)
      if (!frame) invalid()
      const times = call.tool_name === 'inspect_frames' ? call.arguments.timestamps_ms as number[]
        : call.tool_name === 'inspect_clip' ? inspectClipRequestedTimes(call.arguments.start_ms as number, call.arguments.end_ms as number) : []
      if (!times.includes(frame.requested_ms) || call.tool_name === 'inspect_clip' && !visualFrameInRange(frame, call.arguments.start_ms as number, call.arguments.end_ms as number)) invalid()
      if (frame.requested_ms <= previousRequested) invalid()
      previousRequested = frame.requested_ms
      if (!seen.has(frameId) && frameId !== `f${String(seen.size + 1).padStart(4, '0')}`) invalid()
      seen.add(frameId)
      }
    }
    if (seen.size !== frames.length) invalid()
  }
  if (raw.answer_scope === 'VIDEO' && !citations.length && !visualCitations.length) invalid()
  const search = calls.some(call => call.tool_name === 'search_transcript')
  if (search === (raw.provenance.retrieval_mode === 'none')) invalid()
  return { ...raw, tool_calls: calls, citations, ...(visual ? { visual_evidence: frames, visual_citations: visualCitations } : {}) } as unknown as AgentResult
}
export function parseAgentTurn(raw: unknown, session: AgentSessionIdentity, turnId?: string): AgentTurn {
  const replay = row(raw) && (Object.hasOwn(raw, 'replay_source_turn_id') || Object.hasOwn(raw, 'replay_snapshot_hash') || row(raw.stage) && raw.stage.required_worker_protocol === 3)
  if (!row(raw) || !uuid(raw.id) || (turnId !== undefined && raw.id !== turnId) || raw.session_id !== session.id
    || raw.job_id !== session.job_id || !text(raw.question, 2000) || !integer(raw.sequence, 1, 100)
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(raw.status as string)
    || !integer(raw.state_version, 1) || !date(raw.created_at) || !date(raw.updated_at)
    || !row(raw.budget) || (replay ? raw.budget.max_text_calls !== 0 || raw.budget.text_calls_reserved !== 0 : !integer(raw.budget.max_text_calls, 1, 24) || !integer(raw.budget.text_calls_reserved, 0, raw.budget.max_text_calls))
    || !(raw.visual_mode === undefined || raw.visual_mode === 'none' || raw.visual_mode === 'frames')
    || Object.hasOwn(raw, 'evidence_mode') && (raw.evidence_mode !== 'structured' || raw.visual_mode !== 'frames')
    || Object.hasOwn(raw, 'evidence_policy') && (!['speech_lookup_v1', 'screen_search_v1'].includes(String(raw.evidence_policy)) || raw.visual_mode !== 'frames' || raw.evidence_mode !== 'structured' || !evidenceTextValid(raw.question, 2000))
    || Object.hasOwn(raw, 'focus_timestamp_ms') && (raw.visual_mode !== 'frames' || !integer(raw.focus_timestamp_ms, 0, session.source.duration_ms - 1))
    || !row(raw.stage) || !uuid(raw.stage.id) || raw.stage.stage_type !== (raw.evidence_mode === 'structured' ? 'AGENT_EVIDENCE' : Object.hasOwn(raw, 'focus_timestamp_ms') ? 'AGENT_VISUAL_FOCUS' : raw.visual_mode === 'frames' ? 'AGENT_VISUAL' : 'SEGMENT') || !STAGES.has(raw.stage.status as string)
    || !integer(raw.stage.attempt) || !(raw.stage.error_code === null || (typeof raw.stage.error_code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(raw.stage.error_code)))) invalid()
  if (replay && (!uuid(raw.replay_source_turn_id) || raw.replay_source_turn_id === raw.id || !hash(raw.replay_snapshot_hash)
    || raw.evidence_policy !== 'speech_lookup_v1' || raw.stage.required_worker_protocol !== 3)) invalid()
  if (raw.evidence_policy === 'screen_search_v1') {
    if (replay || raw.stage.required_worker_protocol !== 4) invalid()
    try { parseAgentScreenSource(raw.screen_source, session.source.duration_ms) } catch { invalid() }
  } else if (Object.hasOwn(raw, 'screen_source') || !replay && Object.hasOwn(raw.stage, 'required_worker_protocol')) invalid()
  const { diagnostic: rawDiagnostic, text_progress: rawProgress, ...stage } = raw.stage
  const diagnostic = parseDiagnostic(rawDiagnostic, raw.stage.attempt, raw.stage.error_code)
  const progress = parseTextProgress(rawProgress)
  const turn = { ...raw, ...(raw.evidence_policy === 'screen_search_v1' ? { screen_source: parseAgentScreenSource(raw.screen_source, session.source.duration_ms) } : {}), stage: { ...stage, ...(diagnostic ? { diagnostic } : {}), ...(progress ? { text_progress: progress } : {}) }, result: null } as unknown as AgentTurn
  if (raw.status === 'SUCCEEDED') turn.result = result(raw.result, turn, session)
  else if (raw.result !== null) invalid()
  if (replay && turn.result && (turn.result.tool_calls.length !== (turn.focus_timestamp_ms === undefined ? 1 : 2) || turn.result.tool_calls.some(call => call.origin === 'MODEL'))) invalid()
  return turn
}
export function parseAgentSession(raw: unknown, jobId: string, videoId: string, videoHash: string, sessionId?: string): AgentSession {
  if (!row(raw) || !uuid(raw.id) || (sessionId !== undefined && raw.id !== sessionId) || raw.job_id !== jobId || !uuid(jobId)
    || raw.video_id !== videoId || !uuid(videoId) || !date(raw.created_at) || !row(raw.source)
    || raw.source.video_sha256 !== videoHash || !hash(videoHash) || !hash(raw.source.transcript_hash) || !integer(raw.source.duration_ms, 1)
    || !Array.isArray(raw.turns) || raw.turns.length > 100) invalid()
  const source: AgentSource = { video_sha256: videoHash, transcript_hash: raw.source.transcript_hash, duration_ms: raw.source.duration_ms }
  const identity: AgentSessionIdentity = { id: raw.id, job_id: jobId, video_id: videoId, source }
  const turns = raw.turns.map(value => parseAgentTurn(value, identity)), ids = new Set<string>()
  let previous = 0, active = 0
  for (const turn of turns) {
    if (ids.has(turn.id) || turn.sequence <= previous) invalid()
    ids.add(turn.id); previous = turn.sequence; if (agentTurnActive(turn)) active++
  }
  const replayedSources = new Set<string>()
  for (const turn of turns.filter(turn => turn.replay_source_turn_id)) {
    const source = turns.find(source => source.id === turn.replay_source_turn_id)
    if (replayedSources.has(turn.replay_source_turn_id!) || !source || source.status !== 'FAILED' || source.stage.error_code !== 'INVALID_AGENT_OUTPUT' || source.stage.attempt !== 1 || source.replay_source_turn_id
      || source.evidence_policy !== 'speech_lookup_v1' || source.sequence >= turn.sequence || source.question !== turn.question || source.focus_timestamp_ms !== turn.focus_timestamp_ms) invalid()
    replayedSources.add(turn.replay_source_turn_id!)
  }
  if (active > 1) invalid()
  return { ...raw, source, turns } as unknown as AgentSession
}
export function newestAgentTurn(known: AgentTurn | undefined, incoming: AgentTurn): AgentTurn {
  if (!known) return incoming
  if (known.id !== incoming.id || known.session_id !== incoming.session_id || known.job_id !== incoming.job_id
    || known.sequence !== incoming.sequence || known.question !== incoming.question || known.created_at !== incoming.created_at
    || known.stage.id !== incoming.stage.id || known.stage.stage_type !== incoming.stage.stage_type || known.focus_timestamp_ms !== incoming.focus_timestamp_ms
    || known.replay_source_turn_id !== incoming.replay_source_turn_id || known.replay_snapshot_hash !== incoming.replay_snapshot_hash || known.stage.required_worker_protocol !== incoming.stage.required_worker_protocol
    || (known.visual_mode ?? 'none') !== (incoming.visual_mode ?? 'none') || known.budget.max_text_calls !== incoming.budget.max_text_calls) invalid()
  if (known.evidence_mode !== incoming.evidence_mode || known.evidence_policy !== incoming.evidence_policy || !sameAgentScreenSource(known.screen_source, incoming.screen_source)) invalid()
  if (incoming.state_version < known.state_version || (!agentTurnActive(known) && incoming.status !== known.status)
    || (incoming.state_version === known.state_version && incoming.status !== known.status)) return known
  return incoming
}
export function newestAgentSession(known: AgentSession | undefined, incoming: AgentSession): AgentSession {
  if (!known) return incoming
  if (known.id !== incoming.id || known.job_id !== incoming.job_id || known.video_id !== incoming.video_id
    || known.source.video_sha256 !== incoming.source.video_sha256 || known.source.transcript_hash !== incoming.source.transcript_hash
    || known.source.duration_ms !== incoming.source.duration_ms || known.created_at !== incoming.created_at) invalid()
  const turns = new Map(known.turns.map(turn => [turn.id, turn]))
  for (const turn of incoming.turns) turns.set(turn.id, newestAgentTurn(turns.get(turn.id), turn))
  const sorted = [...turns.values()].sort((a, b) => a.sequence - b.sequence)
  if (new Set(sorted.map(turn => turn.sequence)).size !== sorted.length || sorted.filter(agentTurnActive).length > 1) invalid()
  return { ...incoming, turns: sorted }
}
export function agentTurnReceipt(previous: AgentReceipt | null, question: string, maxCalls: number, newKey: () => string): AgentReceipt {
  if (!text(question, 2000) || question !== question.trim() || !integer(maxCalls, 1, 24)) invalid()
  const input = JSON.stringify({ question, max_text_calls: maxCalls })
  if (previous) {
    if (previous.input !== input || typeof previous.key !== 'string' || !/^[A-Za-z0-9_-]{1,100}$/.test(previous.key)) invalid()
    return previous
  }
  return { input, key: newKey() }
}
export function canPrefillClipInstruction(value: unknown, dirty: boolean, pending: boolean): value is string {
  return !dirty && !pending && text(value, 2000)
}
/** A session selection owns its reads; aborted or late data cannot select another session. */
export class AgentReadScope {
  private epoch = 0
  private stopped = false
  private controllers = new Set<AbortController>()
  begin(parent?: AbortSignal) {
    const controller = new AbortController(), epoch = this.epoch
    const abort = () => controller.abort()
    if (this.stopped || parent?.aborted) controller.abort()
    else parent?.addEventListener('abort', abort, { once: true })
    this.controllers.add(controller)
    return { signal: controller.signal, current: () => !this.stopped && epoch === this.epoch && !controller.signal.aborted,
      release: () => { this.controllers.delete(controller); parent?.removeEventListener('abort', abort) } }
  }
  invalidate() { this.epoch++; for (const controller of this.controllers) controller.abort(); this.controllers.clear() }
  dispose() { this.stopped = true; this.invalidate() }
}
