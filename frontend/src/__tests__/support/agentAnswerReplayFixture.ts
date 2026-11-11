import { speechSample } from './agentSpeechLookupFixture.ts'
import type { AgentTurn } from '../../videoAgent.ts'
export function answerReplaySample() {
  const { session, turn } = speechSample()
  const source: AgentTurn = { ...structuredClone(turn), status: 'FAILED', state_version: 4, result: null,
    stage: { ...turn.stage, status: 'FAILED', error_code: 'INVALID_AGENT_OUTPUT' } }
  const child: AgentTurn = { ...structuredClone(turn), id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', sequence: 2,
    replay_source_turn_id: source.id, replay_snapshot_hash: 'a'.repeat(64), budget: { max_text_calls: 0, text_calls_reserved: 0 },
    stage: { ...turn.stage, id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', required_worker_protocol: 3 } }
  child.result!.turn_id = child.id
  session.turns = [source]
  const preview = { schema_version: 1, source_turn_id: source.id, eligible: true, block_reason: null, snapshot_hash: child.replay_snapshot_hash!, source_budget: { ...source.budget }, existing_turn: null }
  return { session, source, child, preview, owner: { job_id: session.job_id, video_id: session.video_id, video_sha256: session.source.video_sha256 } }
}
