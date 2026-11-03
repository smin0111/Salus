-- 살루스 자체 이메일·비밀번호 로그인입니다.
-- password_credentials: 회원당 한 행. 이메일은 소문자로 정규화해 저장하고 UNIQUE입니다. 비밀번호는 BCrypt 해시만 저장합니다.
--   가입 때 이메일 인증 코드를 확인한 회원만 만들므로, users.email에도 같은 확인된 이메일을 넣습니다(소셜 계정 연결 후보 찾기와 같은 기준).
--   탈퇴하면 DB가 함께 지웁니다(FK CASCADE).
CREATE TABLE password_credentials (
  user_id BIGINT PRIMARY KEY,
  email VARCHAR(254) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME NULL,
  password_changed_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_password_credentials_email (email),
  CONSTRAINT fk_password_credentials_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- email_verifications: 가입·비밀번호 재설정용 6자리 인증 코드. 코드는 해시만 저장하고 10분 뒤 만료, 5번 틀리면 못 씁니다.
--   가입 전 이메일이라 회원과 묶지 않습니다. 만료 하루 뒤 정리 작업이 지우고, 탈퇴할 때 그 이메일의 행도 지웁니다.
CREATE TABLE email_verifications (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  purpose VARCHAR(20) NOT NULL,
  email VARCHAR(254) NOT NULL,
  code_hash CHAR(64) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  KEY idx_email_verifications_lookup (email, purpose, created_at),
  KEY idx_email_verifications_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
