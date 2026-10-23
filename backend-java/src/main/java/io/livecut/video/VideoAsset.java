package io.livecut.video;

import java.time.Instant;

public record VideoAsset(String id, String originalFilename, String contentHash, String sourceKey,
                         long sizeBytes, String mediaValidationStatus, Instant createdAt) {}
