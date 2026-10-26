-- 피드 운영 안전 1단계: 신고, 관리자 숨김, 조치 감사 기록.

-- 관리자 숨김은 작성자 삭제와 따로 관리합니다. 공유본의 작성자 삭제는 행 삭제이고, 댓글의 작성자 삭제는 status=DELETED입니다.
-- 숨김을 해제해도 작성자가 지운 내용은 되살아나지 않고, 숨긴 댓글을 작성자가 고쳐도 숨김은 그대로입니다.
ALTER TABLE feed_posts
  ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN hidden_at DATETIME NULL;

ALTER TABLE feed_comments
  ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN hidden_at DATETIME NULL;

-- 신고: 사용자·대상마다 한 건(UNIQUE). 대상 테이블에는 FK를 두지 않아 대상이 지워져도 신고는 남습니다.
-- snapshot_json은 신고 시점의 공개 내용만 담습니다(비공개 조리 단계·원본 설명, 건강정보·알레르기 판정은 넣지 않음).
-- 처리 완료 90일 뒤 정리 작업이 snapshot_json·detail을 비우고 purged_at을 기록합니다. 미처리 신고는 지우지 않습니다.
CREATE TABLE feed_reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reporter_id BIGINT NULL,
  target_type VARCHAR(20) NOT NULL,
  target_id BIGINT NOT NULL,
  target_owner_id BIGINT NULL,
  reason VARCHAR(30) NOT NULL,
  detail VARCHAR(500) NULL,
  snapshot_json JSON NULL,
  status VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL,
  resolved_at DATETIME NULL,
  resolved_by_action_id BIGINT NULL,
  purged_at DATETIME NULL,
  UNIQUE KEY uk_feed_reports_reporter_target (reporter_id, target_type, target_id),
  INDEX idx_feed_reports_target (target_type, target_id, status, id),
  INDEX idx_feed_reports_status (status, created_at),
  CONSTRAINT fk_feed_reports_reporter
    FOREIGN KEY (reporter_id) REFERENCES users(id)
    ON DELETE SET NULL,
  CONSTRAINT fk_feed_reports_owner
    FOREIGN KEY (target_owner_id) REFERENCES users(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 관리자 조치 감사 기록. 행을 추가만 하고 고치지 않습니다.
-- 명시적 예외: 정리 작업은 조치 시각 90일 뒤 note만 비우고 note_purged_at을 기록합니다(누가·무엇을·언제는 남김).
-- report_cutoff_id는 이 조치로 함께 처리한 신고의 상한 ID(관리자가 화면에서 본 마지막 신고)입니다.
CREATE TABLE moderation_actions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT NULL,
  target_type VARCHAR(20) NOT NULL,
  target_id BIGINT NOT NULL,
  action VARCHAR(20) NOT NULL,
  note VARCHAR(500) NULL,
  report_cutoff_id BIGINT NOT NULL,
  resolved_count INT NOT NULL,
  created_at DATETIME NOT NULL,
  note_purged_at DATETIME NULL,
  INDEX idx_moderation_actions_target (target_type, target_id, id),
  INDEX idx_moderation_actions_created (created_at),
  CONSTRAINT fk_moderation_actions_admin
    FOREIGN KEY (admin_id) REFERENCES admin_accounts(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
