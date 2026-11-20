ALTER TABLE agent_session DROP CHECK ck_agent_session_kind,
    ADD CONSTRAINT ck_agent_session_kind CHECK (
        (kind IN ('VIDEO','PAPER_CUT') AND caption_export_id IS NULL AND caption_clip_id IS NULL)
        OR (kind='CAPTION' AND caption_export_id IS NOT NULL AND caption_clip_id IS NOT NULL));

ALTER TABLE job_stage DROP CHECK ck_stage_worker_protocol,
    ADD CONSTRAINT ck_stage_worker_protocol CHECK (required_worker_protocol BETWEEN 1 AND 8);
