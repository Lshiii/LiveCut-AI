ALTER TABLE agent_turn ADD COLUMN evidence_mode VARCHAR(16) NOT NULL DEFAULT 'none';
ALTER TABLE agent_turn ADD CONSTRAINT ck_agent_evidence_mode CHECK (evidence_mode IN ('none','structured') AND (evidence_mode='none' OR visual_mode='frames'));
