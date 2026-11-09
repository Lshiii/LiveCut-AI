ALTER TABLE agent_turn
 ADD COLUMN visual_mode VARCHAR(12) NOT NULL DEFAULT 'none',
 ADD CONSTRAINT ck_agent_turn_visual_mode CHECK (visual_mode IN ('none','frames'));
