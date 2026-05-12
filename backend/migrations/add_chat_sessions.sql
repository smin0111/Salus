CREATE TABLE chat_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_messages_session
        FOREIGN KEY (session_id) REFERENCES chat_sessions(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_chat_sessions_user_updated ON chat_sessions (user_id, updated_at);
CREATE INDEX idx_chat_messages_session_created ON chat_messages (session_id, created_at);
