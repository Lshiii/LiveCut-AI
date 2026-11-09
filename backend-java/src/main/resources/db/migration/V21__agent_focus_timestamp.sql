ALTER TABLE agent_turn
 ADD COLUMN focus_timestamp_ms BIGINT NULL,
 ADD CONSTRAINT ck_agent_turn_focus_timestamp CHECK (focus_timestamp_ms IS NULL OR
   (visual_mode='frames' AND focus_timestamp_ms>=0 AND focus_timestamp_ms<=9007199254740991));
