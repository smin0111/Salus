-- Add missing columns to meal_logs table
ALTER TABLE meal_logs 
ADD COLUMN meal_details JSON NULL,
ADD COLUMN daily_stats JSON NULL;

-- Payment & Membership Migration
ALTER TABLE users ADD COLUMN grade VARCHAR(20) DEFAULT 'BASIC';

CREATE TABLE payments (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_uid VARCHAR(100) UNIQUE NOT NULL,
    imp_uid      VARCHAR(100),
    amount       INT NOT NULL,
    status       VARCHAR(20),
    user_id      BIGINT,
    paid_at      DATETIME,
    CONSTRAINT fk_payment_user FOREIGN KEY (user_id) REFERENCES users(id)
);
