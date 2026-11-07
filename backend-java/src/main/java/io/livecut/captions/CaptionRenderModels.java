package io.livecut.captions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Public render state never contains storage keys, local paths or execution credentials. */
public final class CaptionRenderModels {
    private CaptionRenderModels() {}
    public record Actions(@JsonProperty("can_cancel") boolean canCancel,@JsonProperty("can_retry") boolean canRetry,
        @JsonProperty("max_attempts") int maxAttempts,@JsonProperty("retry_block_reason") String retryBlockReason) {}
    public record Progress(String phase,@JsonProperty("processed_ms") long processedMs,@JsonProperty("total_ms") long totalMs,
        @JsonProperty("updated_at") Instant updatedAt) {}
    public record Media(@JsonProperty("duration_ms") long durationMs,int width,int height) {}
    public record Snapshot(String id,@JsonProperty("source_export_id") String sourceExportId,@JsonProperty("source_clip_id") String sourceClipId,
        @JsonProperty("job_id") String jobId,@JsonProperty("caption_revision") long captionRevision,
        @JsonProperty("caption_content_hash") String captionContentHash,@JsonProperty("source_clip_sha256") String sourceClipSha256,
        @JsonProperty("style_preset") String stylePreset,@JsonProperty("frame_preset") String framePreset,
        @JsonProperty("profile_version") String profileVersion,@JsonProperty("font_profile") String fontProfile,
        String status,@JsonProperty("state_version") long stateVersion,int attempt,
        @JsonProperty("created_at") Instant createdAt,@JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("error_code") String errorCode,Map<String,Object> diagnostic,Actions actions,Progress progress,
        @JsonProperty("content_url") String contentUrl,@JsonProperty("download_url") String downloadUrl,
        @JsonProperty("size_bytes") Long sizeBytes,Media media) {}
    public record Page(List<Snapshot> items) { public Page { items=List.copyOf(items); } }
}
