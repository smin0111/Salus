ALTER TABLE generated_recipes
  ADD COLUMN attempt_number INT NOT NULL DEFAULT 1 AFTER validator_version,
  ADD COLUMN generation_stage VARCHAR(40) NULL AFTER attempt_number,
  ADD COLUMN failure_codes JSON NULL AFTER generation_stage,
  ADD COLUMN generation_ms BIGINT NULL AFTER failure_codes,
  ADD COLUMN repair_used BOOLEAN NOT NULL DEFAULT FALSE AFTER generation_ms,
  ADD COLUMN final_status VARCHAR(40) NULL AFTER repair_used;

CREATE INDEX idx_generated_recipe_attempt
  ON generated_recipes (search_query, created_at, attempt_number);
