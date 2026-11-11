package io.livecut.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/** Public conversation snapshots never expose source paths or execution credentials. */
public final class AgentModels {
    private AgentModels() {}
    public record Source(@JsonProperty("video_sha256") String videoSha256,
                         @JsonProperty("transcript_hash") String transcriptHash,
                         @JsonProperty("duration_ms") long durationMs) {}
    public record Budget(@JsonProperty("max_text_calls") int maxTextCalls,
                         @JsonProperty("text_calls_reserved") int textCallsReserved) {}
    public record Stage(String id,@JsonProperty("stage_type") String stageType,String status,int attempt,
                        @JsonProperty("error_code") String errorCode,Map<String,Object> diagnostic,
                        @JsonProperty("text_progress") @JsonInclude(JsonInclude.Include.NON_NULL) Map<String,Object> textProgress,
                        @JsonProperty("required_worker_protocol") @JsonInclude(JsonInclude.Include.NON_NULL) Integer requiredWorkerProtocol) {
        public Stage(String id,String type,String status,int attempt,String errorCode,Map<String,Object> diagnostic,Map<String,Object> textProgress){this(id,type,status,attempt,errorCode,diagnostic,textProgress,null);}
    }
    public record Turn(String id,@JsonProperty("session_id") String sessionId,
                       @JsonProperty("job_id") String jobId,String question,int sequence,String status,
                       @JsonProperty("state_version") long stateVersion,
                       @JsonProperty("created_at") Instant createdAt,@JsonProperty("updated_at") Instant updatedAt,
                       Budget budget,Stage stage,JsonNode result,@JsonProperty("visual_mode") String visualMode,
                       @JsonProperty("focus_timestamp_ms") @JsonInclude(JsonInclude.Include.NON_NULL) Long focusTimestampMs,
                       @JsonProperty("evidence_mode") @JsonInclude(JsonInclude.Include.NON_NULL) String evidenceMode,
                       @JsonProperty("evidence_policy") @JsonInclude(JsonInclude.Include.NON_NULL) String evidencePolicy,
                       @JsonProperty("replay_source_turn_id") @JsonInclude(JsonInclude.Include.NON_NULL) String replaySourceTurnId,
                       @JsonProperty("replay_snapshot_hash") @JsonInclude(JsonInclude.Include.NON_NULL) String replaySnapshotHash,
                       @JsonProperty("screen_source") @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode screenSource) {}
    public record ScreenSearchCapability(@JsonProperty("screen_search_supported") boolean screenSearchSupported,@JsonProperty("worker_protocol") int workerProtocol) {}
    public record FocusCapability(@JsonProperty("focus_timestamp_supported") boolean focusTimestampSupported) {}
    public record EvidenceCapability(@JsonProperty("structured_evidence_supported") boolean structuredEvidenceSupported) {}
    public record SpeechLookupCapability(@JsonProperty("speech_lookup_supported") boolean speechLookupSupported,
                                        @JsonProperty("worker_protocol") int workerProtocol) {}
    public record CachedAnswerReplayCapability(@JsonProperty("cached_answer_replay_supported") boolean cachedAnswerReplaySupported,
                                               @JsonProperty("worker_protocol") int workerProtocol) {}
    public record Capabilities(@JsonProperty("visual_mode") String visualMode,@JsonProperty("visual_model_id") String visualModelId,
                               @JsonProperty("max_frames") int maxFrames,@JsonProperty("max_clip_duration_ms") int maxClipDurationMs,
                               @JsonProperty("clip_sample_interval_ms") int clipSampleIntervalMs) {}
    public record Session(String id,@JsonProperty("job_id") String jobId,
                          @JsonProperty("video_id") String videoId,@JsonProperty("created_at") Instant createdAt,
                          Source source,List<Turn> turns) {}
    public record Page(List<Session> items) {}
}
