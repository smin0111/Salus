-- RAG 감사 로그 테이블 생성 (JSON 및 LONGTEXT 타입 적용)
CREATE TABLE IF NOT EXISTS generated_recipes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    ingredients JSON, -- MySQL 8 JSON 타입
    steps JSON, -- MySQL 8 JSON 타입
    calories INT,
    difficulty INT,
    cooking_time INT,
    search_query VARCHAR(255),
    search_context LONGTEXT, -- 대량의 웹 검색 스니펫 원본 보존
    ai_response TEXT, -- LLM 응답 날것
    source VARCHAR(50), -- 예: "duckduckgo"
    confidence_score DOUBLE,
    has_forbidden_ingredients BOOLEAN,
    valid BOOLEAN DEFAULT FALSE,
    validation_reason TEXT,
    validation_details LONGTEXT, -- 검증 상세 결과 리포트 (JSON 문자열)
    validator_version VARCHAR(20),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Negative Caching 테이블 생성
CREATE TABLE IF NOT EXISTS search_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    query VARCHAR(255) NOT NULL,
    found BOOLEAN NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_search_cache_query (query)
);

-- Recipes 중복 방지 UNIQUE 제약조건 추가 (재실행 가능)
SET @recipe_title_unique_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'recipes'
      AND index_name = 'uk_recipe_title'
);

SET @recipe_title_unique_sql := IF(
    @recipe_title_unique_exists = 0,
    'ALTER TABLE recipes ADD CONSTRAINT uk_recipe_title UNIQUE(title)',
    'SELECT ''uk_recipe_title already exists'' AS message'
);

PREPARE recipe_title_unique_stmt FROM @recipe_title_unique_sql;
EXECUTE recipe_title_unique_stmt;
DEALLOCATE PREPARE recipe_title_unique_stmt;
