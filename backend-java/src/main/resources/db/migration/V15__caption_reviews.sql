CREATE TABLE caption_document (
    clip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    export_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    basis_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    basis_json JSON NOT NULL,
    latest_revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_caption_clip FOREIGN KEY(clip_id) REFERENCES export_clip(id) ON DELETE CASCADE,
    CONSTRAINT fk_caption_export FOREIGN KEY(export_id) REFERENCES export_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_caption_job FOREIGN KEY(job_id) REFERENCES analysis_job(id) ON DELETE CASCADE,
    CONSTRAINT ck_caption_latest CHECK(latest_revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE caption_revision (
    clip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(clip_id,revision),
    CONSTRAINT fk_caption_revision_document FOREIGN KEY(clip_id) REFERENCES caption_document(clip_id) ON DELETE CASCADE,
    CONSTRAINT ck_caption_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE caption_confirmation (
    clip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(clip_id,revision),
    CONSTRAINT fk_caption_confirmation_revision FOREIGN KEY(clip_id,revision) REFERENCES caption_revision(clip_id,revision) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
