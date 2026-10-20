-- PLUS를 영구 등급이 아니라 기간제 이용권(기본 30일)으로 바꾸고, 결제 전에 서버가 주문을 만듭니다.
-- 주문(payment_orders)은 "누가, 얼마짜리를" 사는지 결제 전에 기록해, 결제 확인 때 주문한 사용자 본인인지 대조합니다.
-- payments 테이블은 지금처럼 결제 완료 장부로 남깁니다.

ALTER TABLE users ADD COLUMN plus_expires_at DATETIME NULL;

-- 지금 PLUS인 회원은 이 마이그레이션 시점부터 30일 동안 PLUS로 둡니다(운영 회원 없음, 로컬 테스트 계정만 해당).
UPDATE users SET plus_expires_at = DATE_ADD(NOW(), INTERVAL 30 DAY) WHERE grade = 'PLUS';

CREATE TABLE payment_orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_uid VARCHAR(64) NOT NULL,
  user_id BIGINT NULL,
  product_code VARCHAR(40) NOT NULL,
  amount INT NOT NULL,
  plus_days INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  imp_uid VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  paid_at DATETIME NULL,
  cancelled_at DATETIME NULL,
  UNIQUE KEY uk_payment_orders_merchant_uid (merchant_uid),
  UNIQUE KEY uk_payment_orders_imp_uid (imp_uid),
  INDEX idx_payment_orders_user_status (user_id, status),
  CONSTRAINT fk_payment_orders_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
