CREATE TABLE export_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approved_revision BIGINT NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    encoding_profile VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    frozen_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    state_version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_export_fingerprint (fingerprint),
    KEY idx_export_job_created (job_id,created_at,id),
    CONSTRAINT fk_export_analysis FOREIGN KEY (job_id) REFERENCES analysis_job(id) ON DELETE CASCADE,
    CONSTRAINT ck_export_revision CHECK (approved_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE export_clip (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    export_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stage_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    segment_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_name VARCHAR(256) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    size_bytes BIGINT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_export_segment (export_id,segment_id),
    UNIQUE KEY uk_export_stage (stage_id),
    CONSTRAINT fk_clip_export FOREIGN KEY (export_id) REFERENCES export_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_clip_stage FOREIGN KEY (stage_id) REFERENCES job_stage(id) ON DELETE CASCADE,
    CONSTRAINT ck_export_interval CHECK (start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT ck_export_size CHECK (size_bytes IS NULL OR size_bytes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Globally unique clip UUIDs are shard keys, preserving V2's existing identity key.
CREATE INDEX idx_stage_owner_status ON job_stage(owner_type,owner_id,status,next_run_at);
