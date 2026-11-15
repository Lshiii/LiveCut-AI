ALTER TABLE agent_turn ADD COLUMN qa_policy VARCHAR(32) NOT NULL DEFAULT 'none';
ALTER TABLE agent_turn ADD CONSTRAINT ck_agent_text_coverage CHECK (
    qa_policy IN ('none','coverage_v1') AND (qa_policy='none' OR
    (visual_mode='none' AND evidence_mode='none' AND evidence_policy='none'
     AND focus_timestamp_ms IS NULL AND replay_source_turn_id IS NULL AND screen_source_json IS NULL))
);
