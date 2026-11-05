CREATE TABLE clip_request (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instruction TEXT NOT NULL,
    text_model_id VARCHAR(128) NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transcript_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transcript_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_json JSON NOT NULL,
    duration_ms BIGINT NOT NULL,
    max_text_calls INT NOT NULL,
    text_calls_reserved INT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    latest_revision BIGINT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_clip_request_idempotency (idempotency_key),
    KEY idx_clip_request_job (job_id,created_at,id),
    CONSTRAINT fk_clip_request_job FOREIGN KEY (job_id) REFERENCES analysis_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_clip_request_video FOREIGN KEY (video_id) REFERENCES video_asset(id),
    CONSTRAINT ck_clip_request_budget CHECK (max_text_calls BETWEEN 1 AND 24 AND text_calls_reserved>=0 AND text_calls_reserved<=max_text_calls AND duration_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE clip_plan_revision (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(request_id,revision),
    CONSTRAINT fk_clip_plan_request FOREIGN KEY(request_id) REFERENCES clip_request(id) ON DELETE CASCADE,
    CONSTRAINT ck_clip_plan_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE clip_plan_confirmation (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(request_id,revision),
    CONSTRAINT fk_clip_plan_confirmation FOREIGN KEY(request_id,revision) REFERENCES clip_plan_revision(request_id,revision) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE export_job ADD COLUMN clip_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE export_job ADD KEY idx_export_clip_request (clip_request_id,created_at,id);
ALTER TABLE export_clip ADD COLUMN source_parts_json JSON NULL;
ALTER TABLE model_call ADD COLUMN owner_type VARCHAR(16) NOT NULL DEFAULT 'ANALYSIS';
ALTER TABLE model_call ADD COLUMN owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
