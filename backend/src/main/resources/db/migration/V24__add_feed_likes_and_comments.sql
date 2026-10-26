-- 피드 좋아요와 댓글(대댓글)입니다. 피드 공유본(feed_posts)을 지우면 함께 지워집니다.

-- 좋아요: 사용자·공유본마다 하나(UNIQUE). 취소하면 행을 지우고, 다시 누르면 새 시각으로 저장합니다(인기순은 최근 7일 created_at 기준).
CREATE TABLE feed_likes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_feed_likes_post_user (post_id, user_id),
  INDEX idx_feed_likes_created (created_at, post_id),
  CONSTRAINT fk_feed_likes_post
    FOREIGN KEY (post_id) REFERENCES feed_posts(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_feed_likes_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 댓글: parent_comment_id는 실제로 답한 댓글, root_comment_id는 화면에서 묶이는 최상위 댓글(최상위 댓글은 둘 다 NULL).
-- 삭제는 소프트 삭제(status=DELETED, content=NULL)라 답글 구조가 유지됩니다. 회원 탈퇴 시에는 탈퇴 처리에서 그 사람의 댓글을
-- 같은 방식으로 지우고, 회원 행이 지워지면 user_id만 NULL이 됩니다(부모·root 연결은 그대로).
-- status는 ACTIVE / DELETED이며 운영 안전 단계에서 관리자 숨김 상태를 추가합니다.
-- client_request_id·request_hash는 같은 화면 안의 재전송 판별입니다(해시 = 공유본 ID·부모 댓글 ID·정규화한 본문, 수정해도 그대로).
CREATE TABLE feed_comments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id BIGINT NOT NULL,
  user_id BIGINT NULL,
  parent_comment_id BIGINT NULL,
  root_comment_id BIGINT NULL,
  content VARCHAR(1000) NULL,
  status VARCHAR(20) NOT NULL,
  edited BOOLEAN NOT NULL,
  client_request_id VARCHAR(36) NULL,
  request_hash VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  deleted_at DATETIME NULL,
  UNIQUE KEY uk_feed_comments_user_request (user_id, client_request_id),
  INDEX idx_feed_comments_thread (post_id, root_comment_id, id),
  INDEX idx_feed_comments_root (root_comment_id, id),
  CONSTRAINT fk_feed_comments_post
    FOREIGN KEY (post_id) REFERENCES feed_posts(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_feed_comments_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- parent_comment_id·root_comment_id에는 FK를 두지 않습니다. 댓글 행은 공유본 삭제(CASCADE)로만 실제 삭제되는데,
-- 같은 테이블을 가리키는 FK가 있으면 한 공유본의 댓글을 함께 지울 때 삭제 순서·연쇄 깊이(15) 제한으로 실패할 수 있습니다.
-- 같은 공유본의 댓글만 부모로 지정할 수 있다는 규칙은 서비스가 검사합니다.
