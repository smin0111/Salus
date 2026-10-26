-- 게시글 등록 재전송을 한 번만 저장하기 위한 요청 ID와 최초 요청 내용 해시.
-- 같은 사용자의 같은 요청 ID는 한 번만 저장되고(UNIQUE), 다시 오면 기존 게시글을 돌려줍니다.
-- request_hash는 처음 요청의 정규화된 전체 내용(SHA-256)이며, 게시글을 수정해도 바뀌지 않습니다.
-- 기존 게시글과 요청 ID 없이 등록한 글은 NULL입니다(UNIQUE에 걸리지 않음).
ALTER TABLE community_posts
  ADD COLUMN client_request_id VARCHAR(36) NULL,
  ADD COLUMN request_hash VARCHAR(64) NULL,
  ADD UNIQUE KEY uk_post_user_request (user_id, client_request_id);
