import { intentPayload, parseIntentSnapshot, type IntentOutput, type IntentPlan, type IntentSnapshot } from './intentClips.ts'

function planIdentity(plan: IntentPlan) {
  return JSON.stringify({ schema_version: plan.schema_version, request_id: plan.request_id, job_id: plan.job_id,
    video_id: plan.video_id, source_hash: plan.source_hash, transcript_hash: plan.transcript_hash,
    duration_ms: plan.duration_ms, instruction: plan.instruction,
    goals: plan.goals.map(goal => ({ goal_id: goal.goal_id, description: goal.description })),
    constraints: { keep_explanation_complete: plan.constraints.keep_explanation_complete, exclude: [...plan.constraints.exclude] },
    provenance: { text_model_id: plan.provenance.text_model_id, prompt_version: plan.provenance.prompt_version,
      retrieval_mode: plan.provenance.retrieval_mode, index_hash: plan.provenance.index_hash },
    permissions: plan.outputs.map(output => ({ output_id: output.output_id, goal_id: output.goal_id, status: output.status })) })
}

/** Recognize an already-saved draft after a lost response, without saving or confirming anything. */
export function intentSaveRecovered(known: IntentSnapshot | null, incoming: IntentSnapshot,
  outputs: IntentOutput[], draftRevision: number | null): boolean {
  if (!known || !Number.isSafeInteger(draftRevision) || draftRevision === null || draftRevision < 0) return false
  try {
    // This helper is safe to call independently of the caller's HTTP parsing.
    const previous = parseIntentSnapshot(known, known.job_id, known.id)
    const latest = parseIntentSnapshot(incoming, previous.job_id, previous.id)
    if (!previous.plan || !latest.plan || previous.status !== 'PLAN_READY' || latest.status !== 'PLAN_READY'
      || previous.revision < draftRevision || latest.revision < previous.revision || latest.revision <= draftRevision
      || latest.state_version < previous.state_version
      || latest.revision > previous.revision && latest.state_version === previous.state_version
      || latest.revision === previous.revision && latest.plan_hash !== previous.plan_hash
      || previous.video_id !== latest.video_id || previous.instruction !== latest.instruction
      || previous.resumed_from_request_id !== latest.resumed_from_request_id || previous.created_at !== latest.created_at
      || previous.source.video_sha256 !== latest.source.video_sha256 || previous.source.transcript_hash !== latest.source.transcript_hash
      || previous.source.duration_ms !== latest.source.duration_ms
      || previous.stage.id !== latest.stage.id || previous.stage.stage_type !== latest.stage.stage_type
      || previous.stage.status !== latest.stage.status || previous.stage.attempt !== latest.stage.attempt
      || previous.stage.error_code !== latest.stage.error_code
      || previous.confirmed_revisions.some(revision => !latest.confirmed_revisions.includes(revision))
      || previous.utterances.length !== latest.utterances.length) return false
    for (let index = 0; index < previous.utterances.length; index++) {
      const before = previous.utterances[index]!, after = latest.utterances[index]!
      if (before.utterance_id !== after.utterance_id || before.start_ms !== after.start_ms
        || before.end_ms !== after.end_ms || before.text !== after.text) return false
    }
    if (planIdentity(previous.plan) !== planIdentity(latest.plan)) return false
    return JSON.stringify(intentPayload(outputs)) === JSON.stringify(intentPayload(latest.plan.outputs))
  } catch { return false }
}
