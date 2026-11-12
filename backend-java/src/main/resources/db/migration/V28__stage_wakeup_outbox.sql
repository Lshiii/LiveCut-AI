CREATE TABLE stage_wakeup_outbox (
    event_id CHAR(36) PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    stage_id CHAR(36) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token CHAR(36) NULL,
    lease_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    last_error_code VARCHAR(64) NULL,
    CONSTRAINT ck_wakeup_status CHECK (status IN ('PENDING','INFLIGHT','PUBLISHED')),
    CONSTRAINT ck_wakeup_attempts CHECK (attempts >= 0),
    INDEX ix_wakeup_due (status, available_at, event_id),
    INDEX ix_wakeup_lease (status, lease_until, event_id),
    INDEX ix_wakeup_published (status, published_at)
);
