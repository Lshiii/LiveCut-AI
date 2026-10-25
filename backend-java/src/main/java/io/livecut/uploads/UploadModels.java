package io.livecut.uploads;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.livecut.video.VideoAssetResponse;

public final class UploadModels {
    private UploadModels() {}

    public record Part(@JsonProperty("index") int index,
                       @JsonProperty("size_bytes") long sizeBytes, String sha256) {}

    public record Snapshot(String id, String status,
                           @JsonProperty("original_filename") String originalFilename,
                           @JsonProperty("size_bytes") long sizeBytes,
                           @JsonProperty("content_hash") String contentHash,
                           @JsonProperty("chunk_size") int chunkSize,
                           @JsonProperty("total_parts") int totalParts,
                           @JsonProperty("received_parts") List<Part> receivedParts,
                           @JsonProperty("received_bytes") long receivedBytes,
                           @JsonProperty("state_version") long stateVersion,
                           @JsonProperty("expires_at") Instant expiresAt,
                           @JsonProperty("video_id") String videoId, VideoAssetResponse video,
                           @JsonProperty("error_code") String errorCode) {}

    /** Trusted internal identity, never serialized as the public session response. */
    public record Source(String uploadId, String videoId, String originalFilename,
                         long sizeBytes, String contentHash, int chunkSize, int totalParts) {}
}
