-- 웹 레시피 근거에 원문의 인분 수(schema.org recipeYield 등)를 함께 저장합니다.
-- "1인분으로 알려줘"처럼 인분을 바꿔 달라고 하면 이 값을 기준으로 재료 양을 비율대로 바꿉니다. 모르면 NULL이고,
-- 그때는 인분을 바꾸지 않고 모른다고 안내합니다(추측하지 않음).
ALTER TABLE web_recipe_sources ADD COLUMN servings INT NULL;
