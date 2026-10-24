package io.livecut.exports;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ExportModels {
    private ExportModels() {}

    public record CreateRequest(@JsonProperty("job_id") String jobId,
                                @JsonProperty("approved_revision") Long approvedRevision,
                                @JsonProperty("segment_ids") List<String> segmentIds, String profile) {}
    public record Clip(String id, @JsonProperty("segment_id") String segmentId,
                       @JsonProperty("product_id") String productId,
                       @JsonProperty("product_name") String productName,
                       @JsonProperty("start_ms") long startMs, @JsonProperty("end_ms") long endMs,
                       @JsonProperty("stage_id") String stageId, String status, int attempt,
                       @JsonProperty("error_code") String errorCode,
                       @JsonProperty("size_bytes") Long sizeBytes,
                       @JsonProperty("content_url") String contentUrl,
                       @JsonProperty("download_url") String downloadUrl) {}
    public record Snapshot(String id, @JsonProperty("job_id") String jobId,
                           @JsonProperty("video_id") String videoId,
                           @JsonProperty("approved_revision") long approvedRevision,
                           @JsonProperty("snapshot_hash") String snapshotHash, String profile,
                           String status, @JsonProperty("state_version") long stateVersion,
                           @JsonProperty("created_at") Instant createdAt,
                           @JsonProperty("updated_at") Instant updatedAt, List<Clip> clips) {}
    public record Page(List<Snapshot> items, int limit, int offset) {}
    /** Internal streaming reference; never expose local paths as JSON. */
    public record Content(Path path, long sizeBytes, String hash, String filename) {}
}
