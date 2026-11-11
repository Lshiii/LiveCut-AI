ALTER TABLE job_stage DROP CHECK ck_stage_worker_protocol,
    ADD CONSTRAINT ck_stage_worker_protocol CHECK (required_worker_protocol IN (1,2,3));
ALTER TABLE agent_turn
    ADD COLUMN replay_source_turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN replay_snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN replay_descriptor_json JSON NULL,
    ADD UNIQUE KEY uk_agent_turn_replay_source(replay_source_turn_id),
    ADD CONSTRAINT fk_agent_turn_replay_source FOREIGN KEY(replay_source_turn_id) REFERENCES agent_turn(id) ON DELETE CASCADE;
ALTER TABLE agent_turn DROP CHECK ck_agent_turn_limits,
    ADD CONSTRAINT ck_agent_turn_limits CHECK(sequence_number BETWEEN 1 AND 10000 AND text_calls_reserved>=0 AND
      ((replay_source_turn_id IS NULL AND max_text_calls BETWEEN 1 AND 24 AND text_calls_reserved<=max_text_calls)
       OR (replay_source_turn_id IS NOT NULL AND max_text_calls=0 AND text_calls_reserved=0))),
    ADD CONSTRAINT ck_agent_turn_replay_identity CHECK(
      (replay_source_turn_id IS NULL AND replay_snapshot_hash IS NULL AND replay_descriptor_json IS NULL)
      OR (replay_source_turn_id IS NOT NULL AND replay_source_turn_id<>id AND replay_snapshot_hash IS NOT NULL AND replay_descriptor_json IS NOT NULL
          AND visual_mode='frames' AND evidence_mode='structured' AND evidence_policy='speech_lookup_v1'));
