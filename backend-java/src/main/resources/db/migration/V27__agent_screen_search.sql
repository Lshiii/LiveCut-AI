ALTER TABLE job_stage DROP CHECK ck_stage_worker_protocol,
    ADD CONSTRAINT ck_stage_worker_protocol CHECK(required_worker_protocol IN (1,2,3,4));
ALTER TABLE agent_turn ADD COLUMN screen_source_json JSON NULL;
ALTER TABLE agent_turn DROP CHECK ck_agent_speech_lookup_policy,
    ADD CONSTRAINT ck_agent_speech_lookup_policy CHECK(evidence_policy IN ('none','speech_lookup_v1','screen_search_v1') AND
      (evidence_policy='none' OR (visual_mode='frames' AND evidence_mode='structured'))),
    ADD CONSTRAINT ck_agent_screen_source CHECK(
      (evidence_policy='screen_search_v1' AND screen_source_json IS NOT NULL AND JSON_TYPE(screen_source_json)='OBJECT' AND replay_source_turn_id IS NULL)
      OR (evidence_policy<>'screen_search_v1' AND screen_source_json IS NULL));
