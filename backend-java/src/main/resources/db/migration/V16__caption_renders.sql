ALTER TABLE export_job ADD COLUMN render_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CLEAN';
ALTER TABLE export_job ADD CONSTRAINT ck_export_render_mode CHECK (render_mode IN ('CLEAN','CAPTIONED'));
ALTER TABLE job_stage ADD COLUMN render_progress_json JSON NULL;

CREATE TABLE caption_render (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_export_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_clip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    derived_clip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    caption_revision BIGINT NOT NULL,
    caption_content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    caption_render_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_json JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_caption_render_request (source_clip_id,idempotency_key),
    KEY idx_caption_render_history (source_export_id,source_clip_id,id),
    CONSTRAINT fk_caption_render_export FOREIGN KEY (id) REFERENCES export_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_caption_render_source_export FOREIGN KEY (source_export_id) REFERENCES export_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_caption_render_source_clip FOREIGN KEY (source_clip_id) REFERENCES export_clip(id) ON DELETE CASCADE,
    CONSTRAINT fk_caption_render_analysis FOREIGN KEY (job_id) REFERENCES analysis_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_caption_render_derived_clip FOREIGN KEY (derived_clip_id) REFERENCES export_clip(id) ON DELETE CASCADE,
    CONSTRAINT ck_caption_render_revision CHECK (caption_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
