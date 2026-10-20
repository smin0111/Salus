-- 다른 제공자의 소셜 계정을 기존 회원에 연결하기 위한 일회용 요청.
-- 같은 검증 이메일의 회원이 있으면 새 회원을 만들지 않고 이 요청을 만든 뒤,
-- 사용자가 기존 계정으로 다시 로그인했을 때만 연결합니다.
-- 요청 ID 원문은 앱만 갖고 서버는 SHA-256 해시만 저장합니다.
CREATE TABLE link_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  token_hash CHAR(64) NOT NULL,
  -- 연결을 시작한 앱이 만든 값의 해시. 요청 ID만 새어 나간 경우를 막는 추가 방어입니다.
  flow_nonce_hash CHAR(64) NOT NULL,
  purpose VARCHAR(20) NOT NULL,
  pending_provider VARCHAR(20) NOT NULL,
  pending_provider_user_id VARCHAR(255) NOT NULL,
  pending_email VARCHAR(255) NULL,
  pending_email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  target_user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  cancelled_at DATETIME NULL,
  CONSTRAINT uk_link_requests_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_link_requests_target_user
    FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_link_requests_target_user ON link_requests (target_user_id);
