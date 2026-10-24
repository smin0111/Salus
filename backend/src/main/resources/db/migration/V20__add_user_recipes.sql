-- 사용자가 저장하거나 직접 쓴 "내 레시피"입니다(나만 봄).
-- original_snapshot은 처음 저장(작성)한 내용 그대로이고 바꾸지 않습니다. 사용자는 current_recipe와 수정 메모만 고칩니다.
-- 채팅 카드에서 저장한 경우 source_card_id로 원래 카드를 가리키며, 대화를 지워 카드가 사라져도 내 레시피는 남습니다(SET NULL).
-- 같은 카드는 한 번만 저장합니다(UNIQUE). 직접 쓴 레시피는 source_card_id가 NULL이라 개수 제한이 없습니다.

CREATE TABLE user_recipes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  origin_type VARCHAR(20) NOT NULL,
  source_card_id BIGINT NULL,
  provenance VARCHAR(40) NULL,
  original_snapshot JSON NOT NULL,
  sources_json JSON NOT NULL,
  title VARCHAR(200) NOT NULL,
  current_recipe JSON NOT NULL,
  modification_note VARCHAR(500) NULL,
  version INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_recipes_user_card (user_id, source_card_id),
  INDEX idx_user_recipes_user_updated (user_id, updated_at, id),
  CONSTRAINT fk_user_recipes_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_user_recipes_source_card
    FOREIGN KEY (source_card_id) REFERENCES chat_recipe_cards(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
