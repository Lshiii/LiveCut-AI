ALTER TABLE job_stage DROP CHECK ck_stage_worker_protocol,
    ADD CONSTRAINT ck_stage_worker_protocol CHECK(required_worker_protocol IN (1,2,3,4,5,6));
