-- 약관·개인정보 처리방침·건강정보(민감정보) 수집 동의와 만 14세 이상 확인 기록입니다.
-- 동의한 문서 버전마다 한 행을 남깁니다(문서가 바뀌면 새 버전으로 다시 동의). 탈퇴하면 DB가 함께 지웁니다(FK CASCADE).
-- 기존 회원은 행이 없으므로 다음 로그인 때 앱이 동의 화면을 보여 줍니다.
CREATE TABLE user_consents (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  consent_type VARCHAR(20) NOT NULL,
  version VARCHAR(20) NOT NULL,
  agreed_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_consents_type_version (user_id, consent_type, version),
  CONSTRAINT fk_user_consents_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
