-- ==========================================
-- Salus Database Script (Full Reset)
-- ==========================================

SET FOREIGN_KEY_CHECKS = 0;

-- 1. Drop Existing Tables (Order matters for clean drop)
DROP TABLE IF EXISTS recommendations;
DROP TABLE IF EXISTS recipe_stats;
DROP TABLE IF EXISTS recipe_shares;
DROP TABLE IF EXISTS ratings;
DROP TABLE IF EXISTS recipes;
DROP TABLE IF EXISTS activity_logs;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS fridge_items;
DROP TABLE IF EXISTS meal_logs;
DROP TABLE IF EXISTS health_profiles;
DROP TABLE IF EXISTS users;

SET FOREIGN_KEY_CHECKS = 1;

-- ==========================================
-- 2. Create Tables (Schema)
-- ==========================================

-- 1) USERS
CREATE TABLE users (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  email       VARCHAR(255) NOT NULL UNIQUE,
  password    VARCHAR(255) NOT NULL,
  name        VARCHAR(100) NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  grade       VARCHAR(20) DEFAULT 'BASIC'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 2) HEALTH_PROFILES (1:1 with users)
CREATE TABLE health_profiles (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id              BIGINT NOT NULL UNIQUE,
  allergies            JSON NULL,
  chronic_conditions   JSON NULL,
  dietary_restrictions JSON NULL,
  medications          JSON NULL,
  goals                JSON NULL,
  CONSTRAINT fk_health_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 3) MEAL_LOGS (1:N)
CREATE TABLE meal_logs (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id             BIGINT NOT NULL,
  record_date         DATE NOT NULL,
  breakfast           VARCHAR(255) NULL,
  lunch               VARCHAR(255) NULL,
  dinner              VARCHAR(255) NULL,
  snacks              JSON NULL,
  breakfast_calories  INT NULL,
  lunch_calories      INT NULL,
  dinner_calories     INT NULL,
  is_ai_breakfast     BOOLEAN DEFAULT FALSE,
  is_ai_lunch         BOOLEAN DEFAULT FALSE,
  is_ai_dinner        BOOLEAN DEFAULT FALSE,
  meal_details        JSON NULL,
  daily_stats         JSON NULL,
  CONSTRAINT fk_meal_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  INDEX idx_meal_user_date (user_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 4) FRIDGE_ITEMS (1:N)
CREATE TABLE fridge_items (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  name        VARCHAR(120) NOT NULL,
  quantity    VARCHAR(80) NULL,
  category    VARCHAR(80) NULL,
  expiry_date DATE NULL,
  CONSTRAINT fk_fridge_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  INDEX idx_fridge_user (user_id),
  INDEX idx_fridge_expiry (expiry_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 4.5) ACTIVITY_LOGS (1:N) - Daily user activity tracking
CREATE TABLE activity_logs (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id            BIGINT NOT NULL,
  activity_date      DATE NOT NULL,
  has_ai_interaction BOOLEAN DEFAULT FALSE,
  CONSTRAINT fk_activity_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  UNIQUE KEY uk_activity_user_date (user_id, activity_date),
  INDEX idx_activity_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 4.6) PAYMENTS - 멤버십 결제 내역
CREATE TABLE IF NOT EXISTS payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_uid VARCHAR(255) UNIQUE NOT NULL,
    imp_uid VARCHAR(255),
    amount INT NOT NULL,
    status VARCHAR(50) NOT NULL,
    user_id BIGINT,
    paid_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 5) RECIPES
CREATE TABLE recipes (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  title          VARCHAR(200) NOT NULL,
  description    TEXT NULL,
  ingredients    JSON NOT NULL,
  steps          JSON NOT NULL,
  calories       INT NULL,
  difficulty     INT NULL,
  cooking_time   INT NULL,
  average_rating DOUBLE NOT NULL DEFAULT 0,
  image_url      VARCHAR(500) NULL,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_recipe_title (title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 6) RATINGS (user -> recipe)
CREATE TABLE ratings (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  recipe_id  BIGINT NOT NULL,
  score      INT NOT NULL,
  comment    TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_rating_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_rating_recipe
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
    ON DELETE CASCADE,
  UNIQUE KEY uk_rating_user_recipe (user_id, recipe_id),
  INDEX idx_rating_recipe (recipe_id),
  INDEX idx_rating_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 7) RECIPE_SHARES (공유 기능)
CREATE TABLE recipe_shares (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT NOT NULL,
  recipe_id     BIGINT NOT NULL,
  visibility    ENUM('PUBLIC','PRIVATE') NOT NULL DEFAULT 'PUBLIC',
  share_message VARCHAR(300) NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_share_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_share_recipe
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
    ON DELETE CASCADE,
  INDEX idx_share_recipe (recipe_id),
  INDEX idx_share_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 8) RECIPE_STATS (1:1 with recipes)
CREATE TABLE recipe_stats (
  recipe_id       BIGINT PRIMARY KEY,
  view_count      INT NOT NULL DEFAULT 0,
  like_count      INT NOT NULL DEFAULT 0,
  share_count     INT NOT NULL DEFAULT 0,
  last_updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                   ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_stats_recipe
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 9) RECOMMENDATIONS
CREATE TABLE recommendations (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  recipe_id  BIGINT NOT NULL,
  score      DOUBLE NOT NULL,
  reason     VARCHAR(255) NULL,
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


-- 10) PAYMENTS (멤버십 결제 내역)
CREATE TABLE payments (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_uid VARCHAR(100) UNIQUE NOT NULL,
    imp_uid      VARCHAR(100),
    amount       INT NOT NULL,
    status       VARCHAR(20),
    user_id      BIGINT,
    paid_at      DATETIME,
    CONSTRAINT fk_payment_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ==========================================
-- 3. Insert Test Data (DML)
-- ==========================================

-- 1) USERS
INSERT INTO users (email, password, name, created_at) VALUES
('kim@example.com',  'password123', '김철수', NOW()),
('park@example.com', 'password123', '박영희', NOW()),
('lee@example.com',  'password123', '이민수', NOW()),
('choi@example.com', 'password123', '최지영', NOW());


-- 2) RECIPES
INSERT INTO recipes (
  title, description, ingredients, steps,
  calories, difficulty, cooking_time,
  average_rating, image_url, created_at
) VALUES
(
  '김치찌개',
  '매콤하고 시원한 김치찌개입니다',
  '["김치 200g","돼지고기 100g","두부 1/2모","대파 1대","고춧가루 1큰술"]',
  '["김치를 자른다","김치를 볶는다","물을 붓고 끓인다","돼지고기와 두부 추가","10분간 끓인다"]',
  350, 2, 30, 4.5,
  'https://images.unsplash.com/photo-1582067683513-88ac65d95ce4?w=400',
  NOW()
),
(
  '불닭볶음면',
  '매운맛의 불닭볶음면',
  '["불닭볶음면","계란","모짜렐라 치즈","파"]',
  '["물 끓이기","면 삶기","소스 추가","토핑 올리기"]',
  550, 1, 10, 4.8,
  'https://images.unsplash.com/photo-1569718212165-3a8278d5f624?w=400',
  NOW()
),
(
  '간장계란밥',
  '간단하고 맛있는 한 끼',
  '["밥","계란","간장","참기름","김가루"]',
  '["계란 프라이","밥 위에 올리기","양념","김가루"]',
  400, 1, 5, 4.3,
  'https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=400',
  NOW()
),
(
  '아보카도 샐러드',
  '건강한 샐러드',
  '["아보카도","토마토","양파","올리브유","레몬즙"]',
  '["썰기","섞기","드레싱"]',
  250, 1, 10, 4.6,
  'https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=400',
  NOW()
);
