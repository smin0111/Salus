CREATE TABLE IF NOT EXISTS health_checkups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    checkup_date DATE NOT NULL,
    height DOUBLE,
    weight DOUBLE,
    bmi DOUBLE,
    systolic_bp INT,
    diastolic_bp INT,
    fasting_glucose INT,
    total_cholesterol INT,
    hdl INT,
    ldl INT,
    triglyceride INT,
    ast INT,
    alt INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_health_checkups_user_date (user_id, checkup_date)
);
