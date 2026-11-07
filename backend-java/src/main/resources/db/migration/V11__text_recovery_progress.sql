ALTER TABLE clip_request ADD COLUMN text_fallback_models_json JSON NULL;
ALTER TABLE job_stage ADD COLUMN text_progress_json JSON NULL;
