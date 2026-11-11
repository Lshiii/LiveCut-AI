package io.livecut.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class AgentResponseReplayModels {
    private AgentResponseReplayModels() {}
    public record Preview(@JsonProperty("schema_version") int schemaVersion,@JsonProperty("source_turn_id") String sourceTurnId,
            boolean eligible,@JsonProperty("block_reason") String blockReason,@JsonProperty("snapshot_hash") String snapshotHash,
            @JsonProperty("source_budget") AgentModels.Budget sourceBudget,@JsonProperty("existing_turn") AgentModels.Turn existingTurn) {}
}
