-- 테스트 데이터 삽입

-- 1. 사용자 데이터
INSERT INTO users (email, password, name, created_at) VALUES
('kim@example.com', 'password123', '김철수', NOW()),
('park@example.com', 'password123', '박영희', NOW()),
('lee@example.com', 'password123', '이민수', NOW()),
('choi@example.com', 'password123', '최지영', NOW());

-- 2. 레시피 데이터
INSERT INTO recipes (title, description, ingredients, steps, calories, difficulty, cooking_time, average_rating, image_url, created_at) VALUES
('김치찌개', '매콤하고 시원한 김치찌개입니다', 
 '["김치 200g", "돼지고기 100g", "두부 1/2모", "대파 1대", "고춧가루 1큰술"]',
 '["김치를 먹기 좋은 크기로 자른다", "냄비에 기름을 두르고 김치를 볶는다", "물을 붓고 끓인다", "돼지고기와 두부를 넣는다", "양념을 넣고 10분간 끓인다"]',
 350, 2, 30, 4.5, 'https://images.unsplash.com/photo-1582067683513-88ac65d95ce4?w=400', NOW()),

('불닭볶음면', '매운맛의 불닭볶음면', 
 '["불닭볶음면 1봉지", "계란 1개", "모짜렐라 치즈", "파"]',
 '["물을 끓인다", "면을 넣고 5분간 끓인다", "물을 버리고 소스를 넣는다", "계란과 치즈를 올린다"]',
 550, 1, 10, 4.8, 'https://images.unsplash.com/photo-1569718212165-3a8278d5f624?w=400', NOW()),

('간장계란밥', '간단하고 맛있는 한 끼',
 '["밥 1공기", "계란 2개", "간장 2큰술", "참기름 1작은술", "김가루"]',
 '["계란 프라이를 만든다", "밥 위에 올린다", "간장과 참기름을 섞어 뿌린다", "김가루를 뿌린다"]',
 400, 1, 5, 4.3, 'https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=400', NOW()),

('아보카도 샐러드', '건강한 샐러드',
 '["아보카도 1개", "토마토 1개", "양파 1/4개", "올리브유", "레몬즙"]',
 '["아보카도를 깍둑썰기 한다", "토마토와 양파를 자른다", "모두 섞는다", "올리브유와 레몬즙을 뿌린다"]',
 250, 1, 10, 4.6, 'https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=400', NOW());

-- 3. 레시피 Stats
INSERT INTO recipe_stats (recipe_id, view_count, like_count, share_count) VALUES
(1, 1250, 320, 45),
(2, 980, 210, 38),
(3, 750, 180, 25),
(4, 620, 150, 20);

-- 4. 레시피 공유
INSERT INTO recipe_shares (user_id, recipe_id, visibility, share_message, created_at) VALUES
(1, 1, 'PUBLIC', '김치찌개 진짜 맛있어요! 저는 돼지고기 대신 참치 넣어서 먹었어요', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
(2, 2, 'PUBLIC', '불닭볶음면에 치즈 듬뿍!! 이게 진리입니다', DATE_SUB(NOW(), INTERVAL 5 HOUR)),
(3, 3, 'PUBLIC', '바쁜 아침에 딱이에요. 5분 컷!', DATE_SUB(NOW(), INTERVAL 1 DAY)),
(4, 4, 'PUBLIC', '다이어트 중인데 이거 먹으면 포만감 짱', DATE_SUB(NOW(), INTERVAL 3 HOUR)),
(1, 4, 'PUBLIC', '저는 여기에 닭가슴살 추가했어요!', DATE_SUB(NOW(), INTERVAL 30 MINUTE));

-- 5. 추천 데이터
INSERT INTO recommendations (user_id, recipe_id, score, reason, created_at) VALUES
(1, 1, 0.95, '매운 음식을 좋아하시는 사용자님께 추천합니다', NOW()),
(1, 2, 0.92, '간단하고 빠른 한 끼 식사로 추천합니다', NOW()),
(2, 3, 0.88, '아침 식사로 적합한 메뉴입니다', NOW()),
(2, 4, 0.85, '건강한 샐러드 메뉴를 찾으시는 분께 추천합니다', NOW());

-- 6. 평점 데이터
INSERT INTO ratings (user_id, recipe_id, score, comment, created_at) VALUES
(1, 2, 5, '너무 맛있어요!', DATE_SUB(NOW(), INTERVAL 1 DAY)),
(2, 1, 4, '집에서 쉽게 만들 수 있네요', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(3, 3, 5, '간단하고 맛있습니다', DATE_SUB(NOW(), INTERVAL 3 DAY)),
(4, 4, 4, '건강한 한 끼', DATE_SUB(NOW(), INTERVAL 4 DAY));
