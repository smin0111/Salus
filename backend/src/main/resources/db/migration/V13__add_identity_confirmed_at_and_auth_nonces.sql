-- 최근 계정 확인용 데이터.
-- identity_confirmed_at: 서버가 이번 요청에서 소셜 계정을 확인한 시각(네이버 일회용 코드 교환, 서버 nonce 소비 등).
-- 제공자가 보증하는 실제 인증 시각(OIDC auth_time)과는 다릅니다. refresh로 토큰을 교체해도 처음 값을 그대로 이어받습니다.
-- 기존 토큰은 NULL로 둡니다(현재 시각으로 채우면 최근 인증 제한을 우회하게 되므로). NULL이면 재인증이 필요합니다.
ALTER TABLE refresh_tokens
  ADD COLUMN identity_confirmed_at DATETIME NULL AFTER created_at;

-- 서버가 발급하는 일회용 nonce. 제공자 토큰에 이 값이 담겨 있으면 "이번 요청에 대한 응답"으로 인정합니다
-- (비밀번호를 다시 입력했다는 보장은 아닙니다).
-- 원문은 앱만 갖고 서버는 SHA-256 해시만 저장합니다.
CREATE TABLE auth_nonces (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  nonce_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  CONSTRAINT uk_auth_nonces_hash UNIQUE (nonce_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_auth_nonces_expires ON auth_nonces (expires_at);
