SET @recipe_shares_visibility_is_enum := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'recipe_shares'
    AND column_name = 'visibility'
    AND data_type = 'enum'
);

SET @recipe_shares_visibility_sql := IF(
  @recipe_shares_visibility_is_enum = 1,
  'ALTER TABLE recipe_shares MODIFY COLUMN visibility VARCHAR(20) NOT NULL DEFAULT ''PUBLIC''',
  'SELECT ''recipe_shares.visibility already normalized'' AS message'
);

PREPARE recipe_shares_visibility_stmt FROM @recipe_shares_visibility_sql;
EXECUTE recipe_shares_visibility_stmt;
DEALLOCATE PREPARE recipe_shares_visibility_stmt;
