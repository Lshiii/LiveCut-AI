-- Existing workers and stages retain protocol 1; new policies opt in per immutable stage.
ALTER TABLE worker_instance ADD COLUMN supported_protocol_versions JSON NOT NULL DEFAULT (JSON_ARRAY(1));
ALTER TABLE job_stage ADD COLUMN required_worker_protocol SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE job_stage ADD CONSTRAINT ck_stage_worker_protocol CHECK (required_worker_protocol IN (1,2));
