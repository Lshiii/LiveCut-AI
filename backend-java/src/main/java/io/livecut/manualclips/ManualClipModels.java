package io.livecut.manualclips;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Manual ASR plans deliberately have no model, budget or planning-stage fields. */
public final class ManualClipModels {
    private ManualClipModels() {}
    public record Summary(String id,@JsonProperty("job_id") String jobId,@JsonProperty("video_id") String videoId,
            String title,long revision,@JsonProperty("plan_hash") String planHash,String status,
            @JsonProperty("created_at") Instant createdAt,@JsonProperty("updated_at") Instant updatedAt) {}
    public record Page(@JsonProperty("schema_version") String schemaVersion,@JsonProperty("data_kind") String dataKind,
            @JsonProperty("job_id") String jobId,List<Summary> items,long total,int limit,long offset) {
        public Page { items=List.copyOf(items); }
    }
}
