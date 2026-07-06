SET @users_role_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'users'
    AND column_name = 'role'
);

SET @users_role_sql := IF(
  @users_role_exists = 0,
  'ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT ''USER''',
  'SELECT ''users.role already exists'' AS message'
);

PREPARE users_role_stmt FROM @users_role_sql;
EXECUTE users_role_stmt;
DEALLOCATE PREPARE users_role_stmt;

UPDATE users
SET role = 'USER'
WHERE role IS NULL OR role = '';

SET @payments_imp_uid_unique_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'payments'
    AND column_name = 'imp_uid'
    AND non_unique = 0
);

SET @payments_imp_uid_unique_sql := IF(
  @payments_imp_uid_unique_exists = 0,
  'ALTER TABLE payments ADD CONSTRAINT uk_payments_imp_uid UNIQUE (imp_uid)',
  'SELECT ''uk_payments_imp_uid already exists'' AS message'
);

PREPARE payments_imp_uid_unique_stmt FROM @payments_imp_uid_unique_sql;
EXECUTE payments_imp_uid_unique_stmt;
DEALLOCATE PREPARE payments_imp_uid_unique_stmt;
