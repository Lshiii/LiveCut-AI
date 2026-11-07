ALTER TABLE clip_request
    ADD COLUMN source_answer_json JSON NULL,
    ADD COLUMN source_answer_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT ck_clip_answer_source CHECK (
        (source_answer_json IS NULL AND source_answer_hash IS NULL)
        OR (source_answer_json IS NOT NULL AND source_answer_hash IS NOT NULL
            AND JSON_TYPE(source_answer_json)='OBJECT' AND source_answer_hash REGEXP '^[a-f0-9]{64}$'));
