-- 이메일 로그인용 이메일 열을 정확히 같은 값만 같다고 보는 비교(utf8mb4_bin)로 바꿉니다.
-- 기본 비교(utf8mb4_0900_ai_ci)는 악센트·대소문자를 무시해 jose@example.com과 josé@example.com을 같은 값으로 봅니다.
-- 그러면 비슷하게 생긴 다른 주소를 가진 사람이 재설정 코드를 받아 남의 비밀번호를 바꿀 수 있습니다(PR #51 리뷰).
-- 앱은 이메일을 소문자로 정규화해 저장·조회하므로 대소문자 구분은 동작을 바꾸지 않습니다.
ALTER TABLE password_credentials
  MODIFY email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

ALTER TABLE email_verifications
  MODIFY email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
