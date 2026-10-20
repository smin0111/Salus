-- 관리자 계정과 세션. 서비스 회원(users)과 완전히 분리된 계정입니다.
-- 가입 화면은 없고, 서버 명령(CLI)이나 기존 관리자만 계정을 만듭니다.
CREATE TABLE admin_accounts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  -- 임시 비밀번호로 만든 계정은 첫 로그인에서 비밀번호를 바꿔야 하며, 임시 비밀번호는 만료 시각이 있습니다.
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  password_expires_at DATETIME NULL,
  -- TOTP 비밀키는 AES-GCM으로 암호화해 저장합니다. 등록 확인 전에는 totp_enabled = FALSE입니다.
  totp_secret_enc VARCHAR(255) NULL,
  totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  -- 같은 TOTP 코드를 다시 쓰지 못하도록 마지막으로 사용한 시간 구간(30초 단위)을 저장합니다.
  last_totp_step BIGINT NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME NULL,
  created_at DATETIME NOT NULL,
  last_login_at DATETIME NULL,
  CONSTRAINT uk_admin_accounts_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 관리자 세션. 관리자 토큰은 세션 ID를 담고, 요청마다 여기서 만료·강제 종료 여부를 확인합니다.
CREATE TABLE admin_sessions (
  id VARCHAR(64) PRIMARY KEY,
  admin_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  revoked_at DATETIME NULL,
  CONSTRAINT fk_admin_sessions_admin
    FOREIGN KEY (admin_id) REFERENCES admin_accounts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_admin_sessions_admin ON admin_sessions (admin_id);
