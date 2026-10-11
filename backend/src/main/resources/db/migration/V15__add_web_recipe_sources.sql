-- 웹에서 찾은 레시피 근거를 보관합니다. 레시피 카탈로그(recipes)와 분리한 근거 자료이며 검토 대기열을 겸합니다.
-- 같은 요리를 다시 검색하지 않게 해 검색 엔진 차단을 줄이는 것이 1차 목적입니다(2026-10 SearXNG 상위 엔진 차단 실측).
-- 저장하는 것은 재료·분량(사실 정보)과 요약한 조리 단계, 원문 링크뿐입니다. 원문 조리 문장은 저장하지 않습니다.
-- 알레르기 같은 사용자별 판정은 저장하지 않고 요청마다 새로 계산합니다.
CREATE TABLE web_recipe_sources (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  cache_key VARCHAR(200) NOT NULL,
  source_url VARCHAR(1000) NOT NULL,
  source_domain VARCHAR(200) NOT NULL,
  source_title VARCHAR(300) NOT NULL,
  extraction_method VARCHAR(40) NOT NULL,
  ingredients JSON NOT NULL,
  step_summaries JSON NOT NULL,
  content_hash VARCHAR(128) NULL,
  quality_score DOUBLE NOT NULL,
  review_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
  fetched_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_web_recipe_source_key_url (cache_key, source_url(300)),
  INDEX idx_web_recipe_source_lookup (cache_key, review_status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
