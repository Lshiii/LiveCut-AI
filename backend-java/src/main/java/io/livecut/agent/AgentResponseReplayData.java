package io.livecut.agent;

import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** Distinct zero-request identity. It is never admitted through normal turn creation. */
public final class AgentResponseReplayData {
    private AgentResponseReplayData() {}
    public static final String DECODER="speech-optional-hint-v1";
    public static boolean isReplay(Map<String,Object> owner){return owner.get("replay_source_turn_id")!=null;}
    public static JsonNode descriptor(Map<String,Object> owner){
        if(!isReplay(owner)||!(owner.get("replay_snapshot_hash") instanceof String hash)||!hash.matches("[a-f0-9]{64}")
                ||((Number)owner.get("max_text_calls")).intValue()!=0||((Number)owner.get("text_calls_reserved")).intValue()!=0)throw invalid();
        JsonNode value;try{value=AgentAnswerValidator.JSON.readTree(String.valueOf(owner.get("replay_descriptor_json")));}catch(RuntimeException malformed){throw invalid();}
        if(!value.isObject()||!ClipRequestService.sha(ClipRequestService.canonicalJson(value)).equals(hash)
                ||!value.path("source_turn_id").asText().equals(owner.get("replay_source_turn_id"))||!value.path("source_session_id").asText().equals(owner.get("session_id"))
                ||!DECODER.equals(value.path("decoder_profile").asText())||!AgentSpeechLookup.POLICY.equals(owner.get("evidence_policy"))||!AgentEvidence.structured(owner))throw invalid();
        String request=requestHash(String.valueOf(owner.get("replay_source_turn_id")),hash);
        if(!request.equals(owner.get("request_hash")))throw invalid();return value;
    }
    public static String requestHash(String source,String snapshot){return ClipRequestService.sha("agent-response-replay-v1\n"+source+"\n"+snapshot);}
    public static String inputHash(String turn,String request,String snapshot){return ClipRequestService.sha("agent-response-replay-stage-v1\n"+turn+"\n"+request+"\n"+snapshot);}
    private static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"AGENT_SUBMISSION_INVALID","Stored replay identity is inconsistent");}
}
