ALTER TABLE analysis_job
    ADD COLUMN resumed_from_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN preparation_source_json JSON NULL;
CREATE INDEX idx_analysis_recovery ON analysis_job(resumed_from_job_id,created_at,id);
