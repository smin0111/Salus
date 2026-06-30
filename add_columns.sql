-- Add missing columns to meal_logs table
ALTER TABLE meal_logs 
ADD COLUMN meal_details JSON NULL,
ADD COLUMN daily_stats JSON NULL;

-- Payment & Membership Migration
ALTER TABLE users ADD COLUMN grade VARCHAR(20) DEFAULT 'BASIC';
ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

CREATE TABLE payments (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_uid VARCHAR(255) NOT NULL,
    imp_uid      VARCHAR(255),
    amount       INT NOT NULL,
    status       VARCHAR(50) NOT NULL,
    user_id      BIGINT,
    paid_at      DATETIME,
    UNIQUE KEY uk_payments_merchant_uid (merchant_uid),
    UNIQUE KEY uk_payments_imp_uid (imp_uid),
    CONSTRAINT fk_payment_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);
