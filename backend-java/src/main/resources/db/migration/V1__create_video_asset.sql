CREATE TABLE video_asset (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    media_validation_status VARCHAR(32) NOT NULL,
    duration_ms BIGINT UNSIGNED NULL,
    media_metadata JSON NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_source_key (source_key),
    KEY idx_video_created (created_at, id),
    KEY idx_video_content_hash (content_hash),
    CONSTRAINT ck_video_size CHECK (size_bytes > 0 AND size_bytes <= 1073741824)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
