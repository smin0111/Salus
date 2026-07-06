DELETE hc
FROM health_checkups hc
LEFT JOIN users u ON u.id = hc.user_id
WHERE u.id IS NULL;

DELETE cm
FROM chat_messages cm
LEFT JOIN chat_sessions cs ON cs.id = cm.session_id
WHERE cs.id IS NULL;

DELETE cs
FROM chat_sessions cs
LEFT JOIN users u ON u.id = cs.user_id
WHERE u.id IS NULL;

SET @health_checkups_user_fk_exists := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'health_checkups'
    AND constraint_name = 'fk_health_checkups_user'
);

SET @health_checkups_user_fk_sql := IF(
  @health_checkups_user_fk_exists = 0,
  'ALTER TABLE health_checkups ADD CONSTRAINT fk_health_checkups_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE',
  'SELECT ''fk_health_checkups_user already exists'' AS message'
);

PREPARE health_checkups_user_fk_stmt FROM @health_checkups_user_fk_sql;
EXECUTE health_checkups_user_fk_stmt;
DEALLOCATE PREPARE health_checkups_user_fk_stmt;

SET @chat_sessions_user_fk_exists := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'chat_sessions'
    AND constraint_name = 'fk_chat_sessions_user'
);

SET @chat_sessions_user_fk_sql := IF(
  @chat_sessions_user_fk_exists = 0,
  'ALTER TABLE chat_sessions ADD CONSTRAINT fk_chat_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE',
  'SELECT ''fk_chat_sessions_user already exists'' AS message'
);

PREPARE chat_sessions_user_fk_stmt FROM @chat_sessions_user_fk_sql;
EXECUTE chat_sessions_user_fk_stmt;
DEALLOCATE PREPARE chat_sessions_user_fk_stmt;
