-- 커뮤니티 활동(피드·댓글·좋아요)에 쓰는 닉네임입니다. 소셜 로그인 실명(name)은 다른 사용자에게 보여 주지 않습니다.
-- nickname은 화면에 보이는 값, nickname_normalized는 중복 판정용 값(NFKC 정규화 + 소문자)입니다.
-- 중복 판정은 애플리케이션 정규화에만 맡기도록 비교 규칙을 utf8mb4_bin(바이트 그대로)으로 둡니다
-- (기본 정렬 규칙 utf8mb4_0900_ai_ci는 악센트·대소문자를 같게 보아 의도와 다르게 막을 수 있음).
-- 기존 회원은 NULL이며 다음 로그인 때 앱이 닉네임을 묻습니다.

ALTER TABLE users
  ADD COLUMN nickname VARCHAR(12) NULL,
  ADD COLUMN nickname_normalized VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  ADD UNIQUE KEY uk_users_nickname_normalized (nickname_normalized);
