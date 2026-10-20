-- 서버 콜백 방식의 제공자 웹 로그인 요청(현재 카카오 재확인용).
-- 카카오 웹 로그인은 Redirect URI에 앱 주소를 쓸 수 없어, 카카오 → 서버 콜백 → 앱 순서로 돌아옵니다.
-- state: 카카오 주소에 실리는 값(콜백과 시작을 서버가 대조), request_token: 앱만 가진 값(완료 요청에 사용).
-- 둘 다 해시만 저장하며, 요청은 시작한 회원에게 묶입니다. 10분 안에 한 번만 쓸 수 있습니다.
CREATE TABLE provider_web_auth_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider VARCHAR(20) NOT NULL,
  user_id BIGINT NOT NULL,
  state_hash CHAR(64) NOT NULL,
  request_token_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  -- 콜백이 들어와 처리를 시작한 시각(같은 state의 두 번째 콜백을 막음)
  callback_started_at DATETIME NULL,
  -- 제공자 확인을 마친 시각과 결과
  completed_at DATETIME NULL,
  provider_user_id VARCHAR(255) NULL,
  provider_email VARCHAR(255) NULL,
  provider_email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  failure_reason VARCHAR(40) NULL,
  -- 앱이 완료 요청으로 결과를 가져간 시각
  consumed_at DATETIME NULL,
  CONSTRAINT uk_provider_web_auth_state UNIQUE (state_hash),
  CONSTRAINT uk_provider_web_auth_request_token UNIQUE (request_token_hash),
  CONSTRAINT fk_provider_web_auth_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_provider_web_auth_user ON provider_web_auth_requests (user_id);
