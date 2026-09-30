-- 로그인 유지용 refresh token. 원문은 저장하지 않고 SHA-256 해시만 저장합니다.
-- 사용할 때마다 새 토큰으로 교체(rotation)하고, 이미 교체된 토큰이 다시 오면 탈취로 보고
-- 그 사용자의 토큰을 모두 폐기합니다.
CREATE TABLE refresh_tokens (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  used_at DATETIME NULL,
  revoked_at DATETIME NULL,
  CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
  CONSTRAINT fk_refresh_tokens_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
