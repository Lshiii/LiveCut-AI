package io.livecut.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/** Public snapshots omit execution tokens; only authenticated claims expose them. */
public final class JobModels {
    private JobModels() {}

    public record CreateJobRequest(@JsonProperty("video_id") String videoId, Map<String, Object> config) {}
    public record Credential(int attempt, @JsonProperty("run_token") String runToken) {}
    public record ClaimRequest(@JsonProperty("stage_id") String stageId,
                               @JsonProperty("worker_id") String workerId,
                               @JsonProperty("claim_request_id") String claimRequestId,
                               @JsonProperty("job_id") String jobId,
                               @JsonProperty("session_id") String sessionId) {
        public ClaimRequest(String stageId, String workerId, String claimRequestId) {
            this(stageId, workerId, claimRequestId, null, null);
        }
        public ClaimRequest(String stageId, String workerId, String claimRequestId, String jobId) {
            this(stageId, workerId, claimRequestId, jobId, null);
        }
    }
    public record CheckpointRequest(int attempt, @JsonProperty("run_token") String runToken,
                                    @JsonProperty("checkpoint_key") String checkpointKey,
                                    @JsonProperty("checkpoint_hash") String checkpointHash) {
        public Credential credential() { return new Credential(attempt, runToken); }
    }
    public record CompleteRequest(int attempt, @JsonProperty("run_token") String runToken,
                                  @JsonProperty("manifest_key") String manifestKey,
                                  @JsonProperty("manifest_hash") String manifestHash) {
        public Credential credential() { return new Credential(attempt, runToken); }
    }
    public record FailRequest(int attempt, @JsonProperty("run_token") String runToken,
                              @JsonProperty("error_code") String errorCode, boolean retryable,
                              @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode diagnostic) {
        public FailRequest(int attempt, String runToken, String errorCode, boolean retryable) {
            this(attempt, runToken, errorCode, retryable, null);
        }
        public Credential credential() { return new Credential(attempt, runToken); }
    }
    public record StageSnapshot(String id, @JsonProperty("stage_type") String stageType,
                                 @JsonProperty("shard_key") String shardKey, String status, int attempt,
                                 @JsonProperty("lease_until") Instant leaseUntil,
                                 @JsonProperty("hard_deadline_at") Instant hardDeadlineAt,
                                 @JsonProperty("next_run_at") Instant nextRunAt,
                                 @JsonProperty("input_hash") String inputHash,
                                 @JsonProperty("checkpoint_key") String checkpointKey,
                                 @JsonProperty("manifest_key") String manifestKey,
                                 @JsonProperty("manifest_hash") String manifestHash,
                                 @JsonProperty("error_code") String errorCode,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> diagnostic) {
        public StageSnapshot(String id, String stageType, String shardKey, String status, int attempt,
                             Instant leaseUntil, Instant hardDeadlineAt, Instant nextRunAt, String inputHash,
                             String checkpointKey, String manifestKey, String manifestHash, String errorCode) {
            this(id, stageType, shardKey, status, attempt, leaseUntil, hardDeadlineAt, nextRunAt, inputHash,
                    checkpointKey, manifestKey, manifestHash, errorCode, null);
        }
    }
    public record BudgetSnapshot(@JsonProperty("max_text_calls") int maxTextCalls,
                                  @JsonProperty("text_calls_reserved") int textCallsReserved,
                                  @JsonProperty("max_asr_submissions") int maxAsrSubmissions,
                                  @JsonProperty("asr_calls_reserved") int asrCallsReserved) {}
    public record JobActions(@JsonProperty("can_cancel") boolean canCancel,
                             @JsonProperty("can_retry") boolean canRetry,
                             @JsonProperty("retry_block_reason") String retryBlockReason,
                             @JsonProperty("max_attempts") int maxAttempts) {}
    public record JobSnapshot(String id, @JsonProperty("video_id") String videoId, int generation,
                               String status, @JsonProperty("config_hash") String configHash,
                               Map<String, Object> config, @JsonProperty("state_version") long stateVersion,
                               List<StageSnapshot> stages, BudgetSnapshot budget, JobActions actions,
                               @JsonProperty("created_at") Instant createdAt,
                               @JsonProperty("updated_at") Instant updatedAt) {}
    public record JobPage(List<JobSnapshot> items, int limit, int offset) {}
    public record ReadyStage(String id, @JsonProperty("job_id") String jobId,
                              @JsonProperty("stage_type") String stageType,
                              @JsonProperty("shard_key") String shardKey,
                              @JsonProperty("next_run_at") Instant nextRunAt) {}
    public record ArtifactRef(@JsonProperty("stage_id") String stageId,
                               @JsonProperty("stage_type") String stageType,
                               @JsonProperty("shard_key") String shardKey,
                               @JsonProperty("manifest_key") String manifestKey,
                               @JsonProperty("manifest_hash") String manifestHash) {}
    public record ClaimResult(String outcome, @JsonProperty("stage_id") String stageId, Integer attempt,
                                @JsonProperty("run_token") String runToken,
                                @JsonProperty("lease_until") Instant leaseUntil,
                                @JsonProperty("hard_deadline_at") Instant hardDeadlineAt,
                                @JsonProperty("job_id") String jobId,
                                @JsonProperty("stage_type") String stageType,
                                @JsonProperty("shard_key") String shardKey,
                                @JsonProperty("source_key") String sourceKey,
                                @JsonProperty("source_hash") String sourceHash,
                                Map<String, Object> config, List<ArtifactRef> artifacts,
                                @JsonProperty("checkpoint_key") String checkpointKey,
                                @JsonProperty("checkpoint_hash") String checkpointHash,
                                @JsonProperty("input_hash") String inputHash,
                                @JsonProperty("video_id") String videoId, int generation) {}
    public record StageActionResult(String outcome, StageSnapshot stage) {}

    /** Internal row, never return this record from public job endpoints. */
    public record StageRow(String id, String jobId, String stageType, String shardKey, String status,
                           int attempt, String runToken, String workerId, Instant leaseUntil,
                           Instant hardDeadlineAt, Instant nextRunAt, String inputHash,
                           String checkpointKey, String checkpointHash, String manifestKey,
                           String manifestHash, String errorCode, String ownerType, String ownerId,
                           Map<String, Object> diagnostic) {
        public StageRow(String id, String jobId, String stageType, String shardKey, String status,
                        int attempt, String runToken, String workerId, Instant leaseUntil,
                        Instant hardDeadlineAt, Instant nextRunAt, String inputHash,
                        String checkpointKey, String checkpointHash, String manifestKey,
                        String manifestHash, String errorCode, String ownerType, String ownerId) {
            this(id, jobId, stageType, shardKey, status, attempt, runToken, workerId, leaseUntil,
                    hardDeadlineAt, nextRunAt, inputHash, checkpointKey, checkpointHash, manifestKey,
                    manifestHash, errorCode, ownerType, ownerId, null);
        }
    }
    public record LockedStage(String jobId, StageRow stage, Instant dbNow) {}
}
