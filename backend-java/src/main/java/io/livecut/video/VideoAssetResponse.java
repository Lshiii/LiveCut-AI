package io.livecut.video;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Public metadata deliberately excludes local filesystem keys and paths. */
public record VideoAssetResponse(
        String id,
        @JsonProperty("original_filename") String originalFilename,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("size_bytes") long sizeBytes,
        @JsonProperty("media_validation_status") String mediaValidationStatus,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("content_url") String contentUrl) {
    static VideoAssetResponse from(VideoAsset asset) {
        return new VideoAssetResponse(asset.id(), asset.originalFilename(), asset.contentHash(), asset.sizeBytes(),
                asset.mediaValidationStatus(), asset.createdAt(), "/api/videos/" + asset.id() + "/content");
    }
}
