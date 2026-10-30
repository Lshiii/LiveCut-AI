package io.livecut.jobs;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Internal sessions are credentials for registration, never part of public views. */
public final class WorkerModels {
    private WorkerModels() {}
    public record Registration(@JsonProperty("worker_id") String workerId,
                               @JsonProperty("session_id") String sessionId,
                               @JsonProperty("stage_types") List<String> stageTypes,
                               Integer slots, @JsonProperty("target_job_id") String targetJobId) {}
    public record Session(@JsonProperty("worker_id") String workerId,
                          @JsonProperty("session_id") String sessionId) {}
    public record Receipt(String outcome, @JsonProperty("worker_id") String workerId,
                          @JsonProperty("session_id") String sessionId,
                          @JsonProperty("heartbeat_timeout_seconds") int heartbeatTimeoutSeconds,
                          @JsonProperty("last_seen_at") Instant lastSeenAt) {}
    public record WorkerView(@JsonProperty("worker_id") String workerId, String status,
                             @JsonProperty("stage_types") List<String> stageTypes, int slots,
                             @JsonProperty("busy_slots") int busySlots,
                             @JsonProperty("last_seen_at") Instant lastSeenAt,
                             @JsonProperty("target_job_id") String targetJobId) {}
    public record StageView(@JsonProperty("stage_type") String stageType,
                            @JsonProperty("online_workers") int onlineWorkers,
                            @JsonProperty("total_slots") int totalSlots,
                            @JsonProperty("busy_slots") int busySlots,
                            @JsonProperty("available_slots") int availableSlots, String reason) {}
    public record ResourceView(String name, int limit, @JsonProperty("in_use") int inUse) {}
    public record Snapshot(@JsonProperty("generated_at") Instant generatedAt,
                           @JsonProperty("heartbeat_timeout_seconds") int heartbeatTimeoutSeconds,
                           @JsonProperty("online_workers") int onlineWorkers,
                           @JsonProperty("total_slots") int totalSlots,
                           @JsonProperty("busy_slots") int busySlots,
                           @JsonProperty("available_slots") int availableSlots,
                           List<WorkerView> workers, List<StageView> stages, List<ResourceView> resources) {}
}
