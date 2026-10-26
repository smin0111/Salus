-- 피드 공유본입니다. 내 레시피(user_recipes)를 공유하는 순간의 내용을 서버가 복사해 둡니다.
-- 내 레시피를 고치거나 지워도 공유본은 바뀌지 않습니다(source_user_recipe_id만 SET NULL).
-- snapshot_json은 공유 당시 전체 내용(조리 단계 포함)이며 그대로 응답하지 않습니다. 공개 응답은 steps_public 등
-- 출처 규칙에 따라 서버가 허용한 필드만 구성합니다(웹 추출·웹 검색 기반 AI·출처 계보 불명 변형은 조리 단계 비공개).
-- 보는 사람의 알레르기나 개인별 판정 결과는 저장하지 않습니다(볼 때 계산).
-- client_request_id·request_hash는 게시글 등록(V22)과 같은 재전송 판별입니다.
CREATE TABLE feed_posts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  source_user_recipe_id BIGINT NULL,
  origin_type VARCHAR(20) NOT NULL,
  provenance VARCHAR(40) NULL,
  steps_public BOOLEAN NOT NULL,
  title VARCHAR(200) NOT NULL,
  snapshot_json JSON NOT NULL,
  sources_json JSON NOT NULL,
  note VARCHAR(500) NULL,
  client_request_id VARCHAR(36) NULL,
  request_hash VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_feed_posts_user_request (user_id, client_request_id),
  INDEX idx_feed_posts_user (user_id, id),
  CONSTRAINT fk_feed_posts_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_feed_posts_source_recipe
    FOREIGN KEY (source_user_recipe_id) REFERENCES user_recipes(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
