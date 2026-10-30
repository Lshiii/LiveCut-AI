CREATE TABLE executor_capacity_lock (
    id INT NOT NULL PRIMARY KEY,
    CONSTRAINT ck_executor_capacity_singleton CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO executor_capacity_lock (id) VALUES (1);

CREATE TABLE worker_instance (
    worker_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stage_types JSON NOT NULL,
    slots INT NOT NULL,
    target_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    accepting BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_worker_slots CHECK (slots BETWEEN 1 AND 32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Retain retired session identities so delayed registration cannot resurrect them.
CREATE TABLE worker_session_history (
    worker_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (worker_id, session_id),
    CONSTRAINT fk_worker_session_instance FOREIGN KEY (worker_id) REFERENCES worker_instance(worker_id) ON DELETE CASCADE
) ENGINE=InnoDB;

ALTER TABLE job_stage ADD COLUMN worker_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE job_stage ADD KEY idx_stage_worker_capacity (worker_id, status, lease_until);
ALTER TABLE stage_claim_request ADD COLUMN worker_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
