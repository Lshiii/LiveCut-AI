ALTER TABLE clip_request
    ADD COLUMN output_policy VARCHAR(32) NULL,
    ADD CONSTRAINT ck_clip_output_policy CHECK (
        output_policy IS NULL OR
        (output_policy = 'single_montage_v1' AND source_answer_hash IS NULL AND source_answer_json IS NULL)
    );

ALTER TABLE job_stage DROP CHECK ck_stage_worker_protocol,
    ADD CONSTRAINT ck_stage_worker_protocol CHECK (required_worker_protocol BETWEEN 1 AND 7);
