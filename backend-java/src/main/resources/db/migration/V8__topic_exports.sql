-- Generic topic clips have a title and no fabricated product association.
ALTER TABLE export_clip
    MODIFY product_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY product_name VARCHAR(256) NULL,
    ADD COLUMN topic_title VARCHAR(256) NULL,
    ADD CONSTRAINT ck_export_clip_subject CHECK (
        (product_id IS NOT NULL AND product_name IS NOT NULL AND topic_title IS NULL)
        OR (product_id IS NULL AND product_name IS NULL AND topic_title IS NOT NULL));
