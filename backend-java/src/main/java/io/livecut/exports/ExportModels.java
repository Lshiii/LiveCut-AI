package io.livecut.exports;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

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
                       @JsonProperty("download_url") String downloadUrl,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> diagnostic) {
        public Clip(String id, String segmentId, String productId, String productName,
                    long startMs, long endMs, String stageId, String status, int attempt,
                    String errorCode, Long sizeBytes, String contentUrl, String downloadUrl) {
            this(id, segmentId, productId, productName, startMs, endMs, stageId, status, attempt,
                    errorCode, sizeBytes, contentUrl, downloadUrl, null);
        }
    }
    public record Snapshot(String id, @JsonProperty("job_id") String jobId,
                           @JsonProperty("video_id") String videoId,
                           @JsonProperty("approved_revision") long approvedRevision,
                           @JsonProperty("snapshot_hash") String snapshotHash, String profile,
                           String status, @JsonProperty("state_version") long stateVersion,
                           @JsonProperty("created_at") Instant createdAt,
                           @JsonProperty("updated_at") Instant updatedAt, List<Clip> clips) {}
    public record Page(List<Snapshot> items, int limit, int offset) {}
    public record ManifestFile(String filename, @JsonProperty("size_bytes") long sizeBytes,
                               String sha256, @JsonProperty("download_url") String downloadUrl) {}
    public record ManifestClip(@JsonProperty("clip_id") String clipId,
                               @JsonProperty("segment_id") String segmentId,
                               @JsonProperty("product_id") String productId,
                               @JsonProperty("product_name") String productName,
                               @JsonProperty("start_ms") long startMs, @JsonProperty("end_ms") long endMs,
                               String status, int attempt, @JsonProperty("error_code") String errorCode,
                               ManifestFile file) {}
    /** A state-versioned inventory, distinct from the private worker manifest. */
    public record Manifest(@JsonProperty("schema_version") String schemaVersion,
                           @JsonProperty("data_kind") String dataKind,
                           @JsonProperty("export_id") String exportId,
                           @JsonProperty("job_id") String jobId, @JsonProperty("video_id") String videoId,
                           int generation, @JsonProperty("approved_revision") long approvedRevision,
                           @JsonProperty("source_video_sha256") String sourceVideoSha256,
                           @JsonProperty("snapshot_hash") String snapshotHash, String profile, String status,
                           @JsonProperty("state_version") long stateVersion,
                           @JsonProperty("created_at") Instant createdAt,
                           @JsonProperty("updated_at") Instant updatedAt, List<ManifestClip> clips) {}
    /** Internal streaming reference; never expose local paths as JSON. */
    public record Content(Path path, long sizeBytes, String hash, String filename) {}
}
