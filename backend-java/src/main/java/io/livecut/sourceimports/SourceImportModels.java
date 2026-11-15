package io.livecut.sourceimports;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SourceImportModels {
    private SourceImportModels() {}
    public record Snapshot(String id, String url, String status, String phase,
        @JsonProperty("progress_percent") Integer progressPercent,
        @JsonProperty("downloaded_bytes") long downloadedBytes,
        @JsonProperty("video_id") String videoId,
        @JsonProperty("error_code") String errorCode, int attempt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
    public record Page(List<Snapshot> items) {}
    public record Claim(String id, String url, int attempt,
        @JsonProperty("run_token") String runToken,
        @JsonProperty("lease_until") Instant leaseUntil,
        @JsonProperty("hard_deadline_at") Instant hardDeadlineAt,
        @JsonProperty("max_bytes") long maxBytes) {}
    public record ClaimResponse(Claim claim) {}
    public record Heartbeat(@JsonProperty("lease_until") Instant leaseUntil,
        @JsonProperty("hard_deadline_at") Instant hardDeadlineAt) {}
}
