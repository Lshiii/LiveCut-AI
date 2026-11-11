ALTER TABLE agent_turn ADD COLUMN evidence_policy VARCHAR(32) NOT NULL DEFAULT 'none';
ALTER TABLE agent_turn ADD CONSTRAINT ck_agent_speech_lookup_policy CHECK (evidence_policy IN ('none','speech_lookup_v1') AND (evidence_policy='none' OR (visual_mode='frames' AND evidence_mode='structured')));
