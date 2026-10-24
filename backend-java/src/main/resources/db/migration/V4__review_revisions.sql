CREATE TABLE review_document (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_json JSON NOT NULL,
    duration_ms BIGINT NOT NULL,
    latest_revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (job_id),
    CONSTRAINT fk_review_document_job FOREIGN KEY (job_id) REFERENCES analysis_job(id) ON DELETE CASCADE,
    CONSTRAINT ck_review_document_values CHECK (duration_ms > 0 AND latest_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_revision (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    snapshot_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (job_id, revision),
    CONSTRAINT fk_review_revision_document FOREIGN KEY (job_id) REFERENCES review_document(job_id) ON DELETE CASCADE,
    CONSTRAINT ck_review_revision_number CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Confirmation is separate from the immutable revision payload. Editing creates
-- another revision and never modifies an already confirmed snapshot.
CREATE TABLE review_confirmation (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (job_id, revision),
    CONSTRAINT fk_review_confirmation_revision FOREIGN KEY (job_id, revision) REFERENCES review_revision(job_id, revision) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
