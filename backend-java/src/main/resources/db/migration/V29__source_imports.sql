CREATE TABLE source_import (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_url VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    phase VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    progress_percent INT NULL,
    downloaded_bytes BIGINT NOT NULL DEFAULT 0,
    max_bytes BIGINT NOT NULL,
    reserved_video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    run_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    worker_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    hard_deadline_at DATETIME(6) NULL,
    result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_size BIGINT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_import_request (request_key),
    UNIQUE KEY uk_import_video (reserved_video_id),
    KEY ix_import_ready (status, created_at, id),
    CONSTRAINT ck_import_status CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    CONSTRAINT ck_import_progress CHECK (attempt BETWEEN 0 AND 3 AND downloaded_bytes >= 0 AND max_bytes BETWEEN 1 AND 1073741824 AND (progress_percent IS NULL OR progress_percent BETWEEN 0 AND 100))
);

CREATE TABLE source_import_claim (
    worker_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attempt INT NULL,
    run_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (worker_id, claim_request_id)
);
