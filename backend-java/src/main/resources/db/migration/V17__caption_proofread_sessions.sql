ALTER TABLE agent_session
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'VIDEO',
    ADD COLUMN caption_export_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN caption_clip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD KEY idx_agent_caption_history(kind,caption_export_id,caption_clip_id,created_at,id),
    ADD CONSTRAINT ck_agent_session_kind CHECK (
        (kind='VIDEO' AND caption_export_id IS NULL AND caption_clip_id IS NULL)
        OR (kind='CAPTION' AND caption_export_id IS NOT NULL AND caption_clip_id IS NOT NULL));

ALTER TABLE agent_turn ADD COLUMN caption_context_json JSON NULL;

-- VIDEO still admits at most 100 turns in its service; caption batches may traverse long documents.
ALTER TABLE agent_turn DROP CHECK ck_agent_turn_limits,
    ADD CONSTRAINT ck_agent_turn_limits CHECK(sequence_number BETWEEN 1 AND 10000 AND max_text_calls BETWEEN 1 AND 24 AND text_calls_reserved>=0 AND text_calls_reserved<=max_text_calls);
