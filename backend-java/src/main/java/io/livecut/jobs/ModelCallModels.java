package io.livecut.jobs;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ModelCallModels {
    private ModelCallModels() {}

    public record ReserveRequest(
            @JsonProperty("stage_id") String stageId, Integer attempt,
            @JsonProperty("run_token") String runToken,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("input_hash") String inputHash,
            @JsonProperty("operation_key") String operationKey,
            @JsonProperty("model_id") String modelId, String kind) {}

    public record UpdateRequest(
            @JsonProperty("stage_id") String stageId, Integer attempt,
            @JsonProperty("run_token") String runToken, String status,
            @JsonProperty("provider_task_id") String providerTaskId,
            Map<String, Object> usage,
            @JsonProperty("response_key") String responseKey,
            @JsonProperty("response_hash") String responseHash) {
        public UpdateRequest(String stageId, Integer attempt, String runToken, String status,
                             String providerTaskId, Map<String, Object> usage) {
            this(stageId, attempt, runToken, status, providerTaskId, usage, null, null);
        }
    }

    public record CallView(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("stage_id") String stageId,
            @JsonProperty("operation_key") String operationKey,
            @JsonProperty("input_hash") String inputHash,
            @JsonProperty("model_id") String modelId,
            String kind, String status,
            @JsonProperty("provider_task_id") String providerTaskId,
            Map<String, Long> usage,
            @JsonProperty("response_key") String responseKey,
            @JsonProperty("response_hash") String responseHash) {}

    public record CallResponse(String outcome, @JsonProperty("call") CallView call) {}
}
