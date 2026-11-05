package io.livecut.clips;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

public final class ClipRequestModels {
    private ClipRequestModels() {}
    public record CreateRequest(String instruction, @JsonProperty("max_text_calls") Integer maxTextCalls) {}
    public record Budget(@JsonProperty("max_text_calls") int maxTextCalls,
                         @JsonProperty("text_calls_reserved") int textCallsReserved) {}
    public record Source(@JsonProperty("video_sha256") String videoSha256,
                         @JsonProperty("transcript_hash") String transcriptHash,
                         @JsonProperty("duration_ms") long durationMs) {}
    public record Stage(String id, @JsonProperty("stage_type") String stageType, String status,
                        int attempt, @JsonProperty("error_code") String errorCode, Map<String,Object> diagnostic,
                        @JsonProperty("text_progress") @JsonInclude(JsonInclude.Include.NON_NULL) Map<String,Object> textProgress) {
        public Stage(String id,String stageType,String status,int attempt,String errorCode,Map<String,Object> diagnostic){this(id,stageType,status,attempt,errorCode,diagnostic,null);}
    }
    public record Snapshot(String id,@JsonProperty("job_id") String jobId,
        @JsonProperty("video_id") String videoId,String instruction,String status,
        @JsonProperty("state_version") long stateVersion,@JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,Budget budget,Stage stage,Source source,long revision,
        @JsonProperty("plan_hash") String planHash,@JsonProperty("confirmed_revisions") List<Long> confirmedRevisions,
        JsonNode plan,JsonNode utterances) {}
    public record Page(List<Snapshot> items) {}
}
