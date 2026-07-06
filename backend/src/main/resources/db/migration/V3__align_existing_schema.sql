SET @community_posts_tags_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'community_posts'
    AND column_name = 'tags'
);

SET @community_posts_tags_sql := IF(
  @community_posts_tags_exists = 0,
  'ALTER TABLE community_posts ADD COLUMN tags JSON NULL AFTER steps',
  'SELECT ''community_posts.tags already exists'' AS message'
);

PREPARE community_posts_tags_stmt FROM @community_posts_tags_sql;
EXECUTE community_posts_tags_stmt;
DEALLOCATE PREPARE community_posts_tags_stmt;

SET @meal_logs_meal_details_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'meal_logs'
    AND column_name = 'meal_details'
);

SET @meal_logs_meal_details_sql := IF(
  @meal_logs_meal_details_exists = 0,
  'ALTER TABLE meal_logs ADD COLUMN meal_details JSON NULL',
  'SELECT ''meal_logs.meal_details already exists'' AS message'
);

PREPARE meal_logs_meal_details_stmt FROM @meal_logs_meal_details_sql;
EXECUTE meal_logs_meal_details_stmt;
DEALLOCATE PREPARE meal_logs_meal_details_stmt;

SET @meal_logs_daily_stats_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'meal_logs'
    AND column_name = 'daily_stats'
);

SET @meal_logs_daily_stats_sql := IF(
  @meal_logs_daily_stats_exists = 0,
  'ALTER TABLE meal_logs ADD COLUMN daily_stats JSON NULL',
  'SELECT ''meal_logs.daily_stats already exists'' AS message'
);

PREPARE meal_logs_daily_stats_stmt FROM @meal_logs_daily_stats_sql;
EXECUTE meal_logs_daily_stats_stmt;
DEALLOCATE PREPARE meal_logs_daily_stats_stmt;

SET @post_comments_parent_id_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'post_comments'
    AND column_name = 'parent_id'
);

SET @post_comments_parent_id_sql := IF(
  @post_comments_parent_id_exists = 0,
  'ALTER TABLE post_comments ADD COLUMN parent_id BIGINT NULL AFTER user_id',
  'SELECT ''post_comments.parent_id already exists'' AS message'
);

PREPARE post_comments_parent_id_stmt FROM @post_comments_parent_id_sql;
EXECUTE post_comments_parent_id_stmt;
DEALLOCATE PREPARE post_comments_parent_id_stmt;

SET @post_comments_parent_fk_exists := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'post_comments'
    AND constraint_name = 'fk_comment_parent'
);

SET @post_comments_parent_fk_sql := IF(
  @post_comments_parent_fk_exists = 0,
  'ALTER TABLE post_comments ADD CONSTRAINT fk_comment_parent FOREIGN KEY (parent_id) REFERENCES post_comments(id) ON DELETE CASCADE',
  'SELECT ''fk_comment_parent already exists'' AS message'
);

PREPARE post_comments_parent_fk_stmt FROM @post_comments_parent_fk_sql;
EXECUTE post_comments_parent_fk_stmt;
DEALLOCATE PREPARE post_comments_parent_fk_stmt;

SET @post_comments_parent_idx_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'post_comments'
    AND index_name = 'idx_comment_parent'
);

SET @post_comments_parent_idx_sql := IF(
  @post_comments_parent_idx_exists = 0,
  'CREATE INDEX idx_comment_parent ON post_comments(parent_id)',
  'SELECT ''idx_comment_parent already exists'' AS message'
);

PREPARE post_comments_parent_idx_stmt FROM @post_comments_parent_idx_sql;
EXECUTE post_comments_parent_idx_stmt;
DEALLOCATE PREPARE post_comments_parent_idx_stmt;

CREATE TABLE IF NOT EXISTS health_checkups (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  checkup_date DATE NOT NULL,
  height DOUBLE,
  weight DOUBLE,
  bmi DOUBLE,
  systolic_bp INT,
  diastolic_bp INT,
  fasting_glucose INT,
  total_cholesterol INT,
  hdl INT,
  ldl INT,
  triglyceride INT,
  ast INT,
  alt INT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_health_checkups_user_date (user_id, checkup_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chat_sessions_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_messages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_chat_messages_session
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id)
    ON DELETE CASCADE,
  INDEX idx_chat_messages_session_created (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS generated_recipes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  description TEXT,
  ingredients JSON,
  steps JSON,
  calories INT,
  difficulty INT,
  cooking_time INT,
  search_query VARCHAR(255),
  search_context LONGTEXT,
  ai_response TEXT,
  source VARCHAR(50),
  confidence_score DOUBLE,
  has_forbidden_ingredients BOOLEAN,
  valid BOOLEAN DEFAULT FALSE,
  validation_reason TEXT,
  validation_details LONGTEXT,
  validator_version VARCHAR(20),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS search_cache (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  query VARCHAR(255) NOT NULL,
  found BOOLEAN NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_search_cache_query (query)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
