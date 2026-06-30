CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password VARCHAR(255) NOT NULL,
  name VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  grade VARCHAR(20) NOT NULL DEFAULT 'BASIC',
  role VARCHAR(20) NOT NULL DEFAULT 'USER'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE health_profiles (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL UNIQUE,
  allergies JSON NULL,
  chronic_conditions JSON NULL,
  dietary_restrictions JSON NULL,
  medications JSON NULL,
  goals JSON NULL,
  CONSTRAINT fk_health_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE meal_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  record_date DATE NOT NULL,
  breakfast VARCHAR(255) NULL,
  lunch VARCHAR(255) NULL,
  dinner VARCHAR(255) NULL,
  snacks JSON NULL,
  breakfast_calories INT NULL,
  lunch_calories INT NULL,
  dinner_calories INT NULL,
  is_ai_breakfast BOOLEAN DEFAULT FALSE,
  is_ai_lunch BOOLEAN DEFAULT FALSE,
  is_ai_dinner BOOLEAN DEFAULT FALSE,
  meal_details JSON NULL,
  daily_stats JSON NULL,
  CONSTRAINT fk_meal_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  INDEX idx_meal_user_date (user_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fridge_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  quantity VARCHAR(80) NULL,
  category VARCHAR(80) NULL,
  expiry_date DATE NULL,
  CONSTRAINT fk_fridge_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  INDEX idx_fridge_user (user_id),
  INDEX idx_fridge_expiry (expiry_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE activity_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  activity_date DATE NOT NULL,
  has_ai_interaction BOOLEAN DEFAULT FALSE,
  CONSTRAINT fk_activity_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  UNIQUE KEY uk_activity_user_date (user_id, activity_date),
  INDEX idx_activity_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recipes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  description TEXT NULL,
  ingredients JSON NOT NULL,
  steps JSON NOT NULL,
  calories INT NULL,
  difficulty INT NULL,
  cooking_time INT NULL,
  average_rating DOUBLE NOT NULL DEFAULT 0,
  image_url VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_recipe_title (title),
  INDEX idx_recipe_title (title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recipe_shares (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  recipe_id BIGINT NOT NULL,
  visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
  share_message VARCHAR(300) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_share_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_share_recipe
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
    ON DELETE CASCADE,
  INDEX idx_share_recipe (recipe_id),
  INDEX idx_share_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recipe_stats (
  recipe_id BIGINT PRIMARY KEY,
  view_count INT NOT NULL DEFAULT 0,
  like_count INT NOT NULL DEFAULT 0,
  share_count INT NOT NULL DEFAULT 0,
  last_updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_stats_recipe
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recommendations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  recipe_id BIGINT NOT NULL,
  score DOUBLE NOT NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reco_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_reco_recipe
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
    ON DELETE CASCADE,
  INDEX idx_reco_user (user_id, created_at),
  INDEX idx_reco_recipe (recipe_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE community_posts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  ingredients JSON NULL,
  steps JSON NULL,
  tags JSON NULL,
  image_url VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_post_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  INDEX idx_post_user (user_id),
  INDEX idx_post_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE post_comments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_comment_post
    FOREIGN KEY (post_id) REFERENCES community_posts(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_comment_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_comment_parent
    FOREIGN KEY (parent_id) REFERENCES post_comments(id)
    ON DELETE CASCADE,
  INDEX idx_comment_post (post_id),
  INDEX idx_comment_user (user_id),
  INDEX idx_comment_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE post_likes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_like_post
    FOREIGN KEY (post_id) REFERENCES community_posts(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_like_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  UNIQUE KEY uk_post_user_like (post_id, user_id),
  INDEX idx_like_post (post_id),
  INDEX idx_like_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_uid VARCHAR(255) NOT NULL,
  imp_uid VARCHAR(255),
  amount INT NOT NULL,
  status VARCHAR(50) NOT NULL,
  user_id BIGINT,
  paid_at DATETIME,
  UNIQUE KEY uk_payments_merchant_uid (merchant_uid),
  UNIQUE KEY uk_payments_imp_uid (imp_uid),
  CONSTRAINT fk_payment_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE SET NULL,
  INDEX idx_payments_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE health_checkups (
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
  CONSTRAINT fk_health_checkups_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  INDEX idx_health_checkups_user_date (user_id, checkup_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_chat_sessions_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  INDEX idx_chat_sessions_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_messages (
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

CREATE TABLE generated_recipes (
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

CREATE TABLE search_cache (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  query VARCHAR(255) NOT NULL,
  found BOOLEAN NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_search_cache_query (query)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
