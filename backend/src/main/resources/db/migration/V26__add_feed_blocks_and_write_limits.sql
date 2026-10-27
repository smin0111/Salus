-- 피드 운영 안전 2단계: 사용자 차단과 작성 제한 카운터.

-- 차단: 차단한 사람 화면에만 적용하고 상대에게 알리지 않습니다. 같은 쌍은 한 건(복합 UNIQUE)이고, 둘 중 누가 탈퇴해도 함께 지워집니다.
-- 해제 API는 id(blockId)로 지웁니다. 상대의 사용자 ID는 앱에 내보내지 않습니다. 사용자당 최대 500건은 서비스가 검사합니다
-- (차단한 사람의 users 행을 잠가 검사·저장을 차례로 처리).
CREATE TABLE user_blocks (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  blocker_id BIGINT NOT NULL,
  blocked_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_blocks_pair (blocker_id, blocked_id),
  INDEX idx_user_blocks_blocked (blocked_id),
  CONSTRAINT fk_user_blocks_blocker
    FOREIGN KEY (blocker_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_user_blocks_blocked
    FOREIGN KEY (blocked_id) REFERENCES users(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 작성 제한 카운터: 사용자·종류·고정 구간 시작 시각(한국 시간)마다 한 행입니다. 글 저장과 같은 트랜잭션에서
-- INSERT ... ON DUPLICATE KEY UPDATE로 1을 더하므로, 저장이 롤백되면 차감도 함께 롤백됩니다.
-- 이 행의 잠금이 같은 사용자·같은 종류의 동시 작성을 차례로 처리합니다. 2일 지난 행은 정리 작업이 지웁니다.
CREATE TABLE user_write_counters (
  user_id BIGINT NOT NULL,
  kind VARCHAR(20) NOT NULL,
  window_start DATETIME NOT NULL,
  count INT NOT NULL,
  PRIMARY KEY (user_id, kind, window_start),
  INDEX idx_user_write_counters_window (window_start),
  CONSTRAINT fk_user_write_counters_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
