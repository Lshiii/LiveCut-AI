ALTER TABLE clip_plan_revision
 ADD COLUMN manual_review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN manual_review_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD CONSTRAINT ck_clip_revision_manual_marker CHECK ((manual_review_id IS NULL AND manual_review_hash IS NULL) OR (manual_review_id IS NOT NULL AND manual_review_hash IS NOT NULL AND manual_review_hash REGEXP '^[a-f0-9]{64}$'));

CREATE TABLE clip_plan_manual_review (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 base_revision BIGINT NOT NULL,
 base_plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 review_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 review_json JSON NOT NULL,
 source_snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_clip_manual_revision (request_id,revision),
 CONSTRAINT fk_clip_manual_revision FOREIGN KEY (request_id,revision) REFERENCES clip_plan_revision(request_id,revision) ON DELETE CASCADE,
 CONSTRAINT ck_clip_manual_review CHECK (base_revision>=0 AND revision=base_revision+1 AND JSON_TYPE(review_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE clip_plan_confirmation ADD COLUMN manual_review_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
