-- 소셜 로그인 계정을 (provider, provider_user_id)로 식별합니다.
-- 이메일은 더 이상 계정 식별자가 아니며, 제공자가 다르면 이메일이 같아도 별개 사용자입니다.
CREATE TABLE social_accounts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  provider VARCHAR(20) NOT NULL,
  provider_user_id VARCHAR(255) NOT NULL,
  email VARCHAR(255) NULL,
  email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL,
  last_login_at DATETIME NULL,
  CONSTRAINT uk_social_provider_user UNIQUE (provider, provider_user_id),
  CONSTRAINT uk_social_user_provider UNIQUE (user_id, provider),
  CONSTRAINT fk_social_accounts_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- users.email의 UNIQUE 인덱스를 제거합니다. baseline-on-migrate로 올라온 DB는
-- 인덱스 이름이 다를 수 있어 information_schema에서 이름을 찾아 지웁니다.
SET @users_email_unique_index := (
  SELECT s.index_name
  FROM information_schema.statistics s
  WHERE s.table_schema = DATABASE()
    AND s.table_name = 'users'
    AND s.non_unique = 0
    AND s.index_name <> 'PRIMARY'
  GROUP BY s.index_name
  HAVING COUNT(*) = 1 AND MAX(s.column_name) = 'email'
  LIMIT 1
);

SET @users_email_unique_sql := IF(
  @users_email_unique_index IS NULL,
  'SELECT ''users.email unique index not found'' AS message',
  CONCAT('ALTER TABLE users DROP INDEX `', @users_email_unique_index, '`')
);

PREPARE users_email_unique_stmt FROM @users_email_unique_sql;
EXECUTE users_email_unique_stmt;
DEALLOCATE PREPARE users_email_unique_stmt;

-- 카카오 이메일 미동의, 검증되지 않은 이메일 등은 NULL로 둡니다.
-- 소셜 전용 서비스라 비밀번호도 저장하지 않습니다.
ALTER TABLE users
  MODIFY email VARCHAR(255) NULL,
  MODIFY password VARCHAR(255) NULL;

-- 기존 회원의 첫 로그인 연결(이메일 조회)에 쓰는 일반 인덱스입니다.
CREATE INDEX idx_users_email ON users (email);

-- 이메일 대신 저장하던 'kakao_{id}', 'naver_{id}' 식별자를 소셜 계정으로 옮깁니다.
-- '@'가 들어간 값은 실제 이메일(예: kakao_fan@example.com)이므로 제외합니다.
INSERT INTO social_accounts (user_id, provider, provider_user_id, email_verified, created_at)
SELECT id, 'KAKAO', SUBSTRING(email, 7), FALSE, created_at
FROM users
WHERE email LIKE 'kakao\_%' AND email NOT LIKE '%@%';

INSERT INTO social_accounts (user_id, provider, provider_user_id, email_verified, created_at)
SELECT id, 'NAVER', SUBSTRING(email, 7), FALSE, created_at
FROM users
WHERE email LIKE 'naver\_%' AND email NOT LIKE '%@%';

UPDATE users
SET email = NULL
WHERE (email LIKE 'kakao\_%' OR email LIKE 'naver\_%') AND email NOT LIKE '%@%';

UPDATE users SET password = NULL WHERE password = '';
