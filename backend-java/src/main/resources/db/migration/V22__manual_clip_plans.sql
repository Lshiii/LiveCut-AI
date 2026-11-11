CREATE TABLE manual_clip_plan (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_json JSON NOT NULL,
 source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 latest_revision BIGINT NOT NULL DEFAULT 1,
 state_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_manual_clip_job (job_id,created_at,id),
 CONSTRAINT fk_manual_clip_job FOREIGN KEY(job_id) REFERENCES analysis_job(id) ON DELETE CASCADE,
 CONSTRAINT fk_manual_clip_video FOREIGN KEY(video_id) REFERENCES video_asset(id),
 CONSTRAINT ck_manual_clip_owner CHECK(latest_revision BETWEEN 1 AND 9007199254740991 AND state_version>=1 AND JSON_TYPE(source_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE manual_clip_revision (
 plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(120) NOT NULL,
 content_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(plan_id,revision),
 CONSTRAINT fk_manual_clip_revision FOREIGN KEY(plan_id) REFERENCES manual_clip_plan(id) ON DELETE CASCADE,
 CONSTRAINT ck_manual_clip_revision CHECK(revision BETWEEN 1 AND 9007199254740991 AND JSON_TYPE(content_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE manual_clip_confirmation (
 plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(plan_id,revision),
 CONSTRAINT fk_manual_clip_confirmation FOREIGN KEY(plan_id,revision) REFERENCES manual_clip_revision(plan_id,revision) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE manual_clip_submission (
 idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_json JSON NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 receipt_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_manual_clip_submission FOREIGN KEY(plan_id,revision) REFERENCES manual_clip_revision(plan_id,revision) ON DELETE CASCADE,
 CONSTRAINT ck_manual_clip_submission CHECK(operation IN ('CREATE','REVISION') AND JSON_TYPE(input_json)='OBJECT' AND JSON_TYPE(receipt_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE export_job ADD COLUMN manual_clip_plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE export_job ADD KEY idx_export_manual_clip (manual_clip_plan_id,created_at,id);
ALTER TABLE export_job ADD CONSTRAINT ck_export_manual_clip_owner CHECK(manual_clip_plan_id IS NULL OR clip_request_id IS NULL);

CREATE TABLE manual_clip_export_submission (
 idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_json JSON NOT NULL,
 export_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_manual_clip_export_receipt (plan_id,created_at,idempotency_key),
 CONSTRAINT fk_manual_clip_export_receipt FOREIGN KEY(plan_id) REFERENCES manual_clip_plan(id) ON DELETE CASCADE,
 CONSTRAINT ck_manual_clip_export_input CHECK(JSON_TYPE(input_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
