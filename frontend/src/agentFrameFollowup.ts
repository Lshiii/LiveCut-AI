import { AgentFocusError, type AgentFocusHint, type AgentFocusOwner } from './agentFocus.ts'
import { visualFrameId } from './agentVisualEvidence.ts'
import { parseAgentTurn, type AgentSession } from './videoAgent.ts'

export interface AgentFrameFollowupContext {
  owner: AgentFocusOwner; session_id: string; transcript_hash: string
  active: boolean; visible: boolean; pending: boolean; visual_available: boolean; focus_supported: boolean
}
export interface AgentFrameFollowupTarget {
  session_id: string; turn_id: string; sequence: number; frame_id: string; frame_signature: string
  transcript_hash: string; duration_ms: number; hint: AgentFocusHint
}
export interface AgentFrameFollowupProposal { target: AgentFrameFollowupTarget; previous_hint: AgentFocusHint }
export function sameAgentFocusHint(a: AgentFocusHint | null, b: AgentFocusHint | null): boolean {
  return !a && !b || !!a && !!b && a.job_id === b.job_id && a.video_id === b.video_id && a.source_hash === b.source_hash && a.timestamp_ms === b.timestamp_ms
}
function invalid(code = 'AGENT_FRAME_FOLLOWUP_INVALID'): never { throw new AgentFocusError(code) }
/** Only the currently selected turn's cited frame metadata can supply the next request's time hint. */
export function agentFrameFollowupTarget(session: AgentSession | null, turnId: string, frameId: string,
  context: AgentFrameFollowupContext): AgentFrameFollowupTarget {
  if (context.active !== true || context.visible !== true || context.pending !== false) invalid('AGENT_FRAME_FOLLOWUP_NOT_READY')
  if (context.visual_available !== true || context.focus_supported !== true) invalid('AGENT_FRAME_FOLLOWUP_UNAVAILABLE')
  const owner = context.owner
  const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
  if (!session || session.id !== context.session_id || session.job_id !== owner.job_id || session.video_id !== owner.video_id
    || !uuid.test(session.id) || !uuid.test(owner.job_id) || !uuid.test(owner.video_id)
    || session.source.video_sha256 !== owner.source_hash || session.source.transcript_hash !== context.transcript_hash
    || session.source.duration_ms !== owner.duration_ms || !Number.isSafeInteger(owner.duration_ms) || owner.duration_ms <= 0
    || !/^[a-f0-9]{64}$/.test(owner.source_hash) || !/^[a-f0-9]{64}$/.test(context.transcript_hash) || !visualFrameId(frameId)) invalid()
  const raw = session.turns.find(turn => turn.id === turnId)
  if (!raw) invalid()
  let turn
  try { turn = parseAgentTurn(raw, session, turnId) } catch { invalid() }
  if (turn.status !== 'SUCCEEDED' || !turn.result || !['1.1', '1.2', '1.3'].includes(turn.result.schema_version)
    || !turn.result.visual_citations?.some(citation => citation.frame_id === frameId)) invalid()
  const frame = turn.result.visual_evidence?.find(value => value.frame_id === frameId)
  if (!frame || !Number.isSafeInteger(frame.timestamp_ms) || frame.timestamp_ms < 0 || frame.timestamp_ms >= owner.duration_ms) invalid()
  const hint = Object.freeze({ job_id: owner.job_id, video_id: owner.video_id, source_hash: owner.source_hash, timestamp_ms: frame.timestamp_ms })
  return Object.freeze({ session_id: session.id, turn_id: turn.id, sequence: turn.sequence, frame_id: frame.frame_id,
    transcript_hash: session.source.transcript_hash, duration_ms: session.source.duration_ms, hint,
    frame_signature: JSON.stringify([frame.frame_id, frame.requested_ms, frame.timestamp_ms, frame.source_pts, frame.source_time_base,
      frame.sha256, frame.bytes, frame.width, frame.height]) })
}
export function prepareAgentFrameFollowup(session: AgentSession | null, turnId: string, frameId: string,
  context: AgentFrameFollowupContext, previousHint: AgentFocusHint | null):
  { kind: 'apply'; target: AgentFrameFollowupTarget } | { kind: 'replace'; proposal: AgentFrameFollowupProposal } {
  const target = agentFrameFollowupTarget(session, turnId, frameId, context)
  if (!previousHint || sameAgentFocusHint(previousHint, target.hint)) return { kind: 'apply', target }
  return { kind: 'replace', proposal: Object.freeze({ target, previous_hint: Object.freeze({ ...previousHint }) }) }
}
/** Confirmation rechecks both the old draft hint and the still-current cited frame; no player state is consulted. */
export function confirmAgentFrameFollowup(proposal: AgentFrameFollowupProposal, session: AgentSession | null,
  context: AgentFrameFollowupContext, currentHint: AgentFocusHint | null): AgentFrameFollowupTarget {
  if (!sameAgentFocusHint(proposal.previous_hint, currentHint)) invalid('AGENT_FRAME_FOLLOWUP_CHANGED')
  const target = agentFrameFollowupTarget(session, proposal.target.turn_id, proposal.target.frame_id, context)
  if (JSON.stringify(target) !== JSON.stringify(proposal.target)) invalid('AGENT_FRAME_FOLLOWUP_CHANGED')
  return target
}
