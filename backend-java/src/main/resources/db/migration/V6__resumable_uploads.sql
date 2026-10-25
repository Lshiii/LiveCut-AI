CREATE TABLE upload_capacity (
    id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    active_sessions INT UNSIGNED NOT NULL DEFAULT 0,
    reserved_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT ck_upload_capacity_singleton CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO upload_capacity (id, active_sessions, reserved_bytes) VALUES (1, 0, 0);

CREATE TABLE upload_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_size INT UNSIGNED NOT NULL,
    total_parts INT UNSIGNED NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    expires_at TIMESTAMP(6) NOT NULL,
    merge_attempt INT UNSIGNED NOT NULL DEFAULT 0,
    merge_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    merge_lease_until TIMESTAMP(6) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reservation_bytes BIGINT UNSIGNED NOT NULL,
    capacity_reserved BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_upload_request (request_key),
    UNIQUE KEY uk_upload_video (video_id),
    KEY idx_upload_recovery (status, merge_lease_until),
    KEY idx_upload_expiry (capacity_reserved, expires_at),
    CONSTRAINT ck_upload_size CHECK (size_bytes > 0 AND size_bytes <= 1073741824),
    CONSTRAINT ck_upload_chunks CHECK (chunk_size >= 16 AND chunk_size <= 8388608 AND total_parts >= 1 AND total_parts <= 4096),
    CONSTRAINT ck_upload_status CHECK (status IN ('UPLOADING', 'MERGING', 'COMPLETED', 'FAILED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE upload_part (
    upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    part_index INT UNSIGNED NOT NULL,
    size_bytes INT UNSIGNED NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (upload_id, part_index),
    CONSTRAINT fk_upload_part_session FOREIGN KEY (upload_id) REFERENCES upload_session(id) ON DELETE CASCADE,
    CONSTRAINT ck_upload_part_size CHECK (size_bytes > 0 AND size_bytes <= 8388608)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
