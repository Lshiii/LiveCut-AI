package io.livecut.agent;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Display metadata only; never a conversation snapshot or a submission receipt. */
public final class AgentSessionHistoryModels {
    private AgentSessionHistoryModels() {}
    public record Item(String id,@JsonProperty("job_id") String jobId,@JsonProperty("video_id") String videoId,
                       @JsonProperty("created_at") Instant createdAt,AgentModels.Source source,
                       @JsonProperty("first_question") String firstQuestion,
                       @JsonProperty("turn_count") int turnCount,@JsonProperty("active_turn_count") int activeTurnCount) {}
    public record Page(@JsonProperty("schema_version") String schemaVersion,@JsonProperty("job_id") String jobId,
                       List<Item> items,int limit,@JsonProperty("next_cursor") String nextCursor,
                       @JsonProperty("active_count") long activeCount) {}
}
