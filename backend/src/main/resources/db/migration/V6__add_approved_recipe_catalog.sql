ALTER TABLE recipes
  ADD COLUMN catalog_key VARCHAR(100) NULL AFTER id,
  ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED' AFTER catalog_key,
  ADD COLUMN catalog_version INT NOT NULL DEFAULT 1 AFTER approval_status,
  ADD COLUMN base_servings INT NULL AFTER catalog_version,
  ADD COLUMN verified_by VARCHAR(100) NULL AFTER base_servings,
  ADD COLUMN verified_at DATETIME NULL AFTER verified_by,
  ADD COLUMN source_hash CHAR(64) NULL AFTER verified_at,
  ADD UNIQUE KEY uk_recipe_catalog_key (catalog_key),
  ADD INDEX idx_recipe_approval_title (approval_status, title);

CREATE TABLE approved_recipe_documents (
  recipe_id BIGINT PRIMARY KEY,
  catalog_key VARCHAR(100) NOT NULL,
  catalog_version INT NOT NULL,
  document_json JSON NOT NULL,
  source_hash CHAR(64) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_approved_recipe_document_recipe
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
    ON DELETE CASCADE,
  UNIQUE KEY uk_approved_recipe_document_key (catalog_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recipe_sources (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipe_id BIGINT NOT NULL,
  source_type VARCHAR(30) NOT NULL,
  source_name VARCHAR(200) NOT NULL,
  source_url VARCHAR(1000) NOT NULL,
  retrieved_at DATE NOT NULL,
  CONSTRAINT fk_recipe_source_recipe
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
    ON DELETE CASCADE,
  UNIQUE KEY uk_recipe_source_url (recipe_id, source_url(500)),
  INDEX idx_recipe_source_recipe (recipe_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
