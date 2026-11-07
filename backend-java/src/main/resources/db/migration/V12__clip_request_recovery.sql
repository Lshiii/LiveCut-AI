ALTER TABLE clip_request ADD COLUMN resumed_from_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
CREATE INDEX idx_clip_request_recovery ON clip_request(resumed_from_request_id,created_at,id);
