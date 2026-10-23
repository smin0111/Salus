-- 채팅에서 사용자에게 보낸 레시피 카드를 서버가 만든 그대로 보관합니다.
-- 대화를 다시 열 때 카드를 복원하고, 이후 "내 레시피에 저장"의 원본으로 씁니다(앱이 보낸 레시피 JSON은 원본으로 믿지 않음).
-- 모델 답변 메시지 하나에 카드 하나이며, 대화나 회원이 지워지면 함께 지워집니다.

CREATE TABLE chat_recipe_cards (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id VARCHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  chat_session_id BIGINT NOT NULL,
  chat_message_id BIGINT NOT NULL,
  parent_card_id BIGINT NULL,
  provenance VARCHAR(40) NOT NULL,
  card_json JSON NOT NULL,
  sources_json JSON NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_chat_recipe_cards_public_id (public_id),
  UNIQUE KEY uk_chat_recipe_cards_message (chat_message_id),
  INDEX idx_chat_recipe_cards_session (chat_session_id, id),
  INDEX idx_chat_recipe_cards_user (user_id),
  CONSTRAINT fk_chat_recipe_cards_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_chat_recipe_cards_session
    FOREIGN KEY (chat_session_id) REFERENCES chat_sessions(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_chat_recipe_cards_message
    FOREIGN KEY (chat_message_id) REFERENCES chat_messages(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_chat_recipe_cards_parent
    FOREIGN KEY (parent_card_id) REFERENCES chat_recipe_cards(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
