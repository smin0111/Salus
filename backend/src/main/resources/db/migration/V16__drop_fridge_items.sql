-- 냉장고 기능 제거(2026-10-15). 재료 데이터와, 냉장고 재료와 겹치는 레시피로만 계산되던 추천 기록을 지웁니다.
DROP TABLE IF EXISTS fridge_items;
DELETE FROM recommendations;
