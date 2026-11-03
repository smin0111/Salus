"""요리 상식 평가 세트(evalset.json)를 만듭니다.

정답 기준은 공공기관 원문(농촌진흥청·식품의약품안전처·식품안전나라)과 학술 초록의 문장으로만 잡습니다.
evidence의 quote는 cache/<source>.txt 본문에 글자 그대로 있어야 하며, 없으면 만들기를 멈춥니다
(먼저 python3 tools/cooking/fetch_sources.py 로 원문을 내려받습니다).

항목
- kind: storage(보관) / heat(해동·가열·온도) / hygiene(교차오염·손씻기) / wash(세척) / toxin(자연독소·독버섯)
        / why(원리) / myth(잘못 알려진 말) / contested(공공 자료끼리 다름) / unsupported(검토한 자료에 근거 없음)
- must: 정답에 있어야 하는 말 묶음(묶음마다 하나 이상). 자동 채점의 하한일 뿐이고 최종 판정은 사람이 읽고 합니다.
- must_not: 들어가면 틀린 말.
- risk: high면 틀린 안내가 건강에 해가 될 수 있는 항목.
- paraphrase_of: 같은 정답을 다른 말로 물은 항목이면 원래 항목의 key. 표현에 따라 답이 흔들리는지 봅니다.
- contested·unsupported: 한쪽을 단정하면 오답. 자료가 엇갈린다거나 근거가 없다고 밝히면 정답(사람 판정).
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent


def ev(source, quote):
    return {"source": source, "quote": quote}


ITEMS = [
    # ── 보관 ──
    dict(key="apple_apart", kind="storage", q="사과를 다른 과일이랑 같이 보관해도 돼?",
         must=[["따로", "분리", "떨어"], ["에틸렌"]], must_not=["같이 보관해도 괜찮"],
         evidence=[ev("rda_store1", "사과는 식물 노화 호르몬인 ‘에틸렌’ 생성량이 많은 과일로, 다른 과일․채소 품질을 떨어뜨릴 수 있어 따로 보관해야 한다.")]),
    dict(key="banana_room", kind="storage", q="바나나는 냉장고에 넣어야 해?",
         must=[["실온", "상온"]], must_not=["냉장고에 보관하세요", "냉장 보관하는 것이 좋"],
         evidence=[ev("rda_store1", "바나나, 망고, 키위 등은 실온*에 보관한다.")]),
    dict(key="banana_room_casual", paraphrase_of="banana_room", kind="storage", q="바나나 냉장 보관 ㄱㅊ?",
         must=[["실온", "상온"]], must_not=["냉장고에 보관하세요", "냉장 보관하는 것이 좋"],
         evidence=[ev("rda_store1", "바나나, 망고, 키위 등은 실온*에 보관한다.")]),
    dict(key="mango_ripen", kind="storage", q="망고는 어떻게 보관해?",
         must=[["실온", "상온", "숙성", "후숙"], ["신문지"]], must_not=[],
         evidence=[ev("rda_store2", "망고는 실온에서 3~4일 숙성한 후, 당도가 충분히 오르면 신문지에 싸서 냉장 보관을 한다.")]),
    dict(key="sweetpotato_room", kind="storage", q="고구마 냉장고에 보관해도 돼?",
         must=[["상온", "실온", "13"]], must_not=["냉장 보관하세요", "냉장고에 보관하는 것이 좋"],
         evidence=[ev("rda_store2", "고구마는 13~15도(℃)에서 저장해야 단맛이 유지되므로 냉장보다는 상온 보관이 알맞다.")]),
    dict(key="sweetpotato_room_casual", paraphrase_of="sweetpotato_room", kind="storage", q="고구마 냉장고에 넣어도 됨?",
         must=[["상온", "실온", "13"]], must_not=["냉장 보관하세요", "냉장고에 보관하는 것이 좋"],
         evidence=[ev("rda_store2", "고구마는 13~15도(℃)에서 저장해야 단맛이 유지되므로 냉장보다는 상온 보관이 알맞다.")]),
    dict(key="peach_fridge", kind="storage", q="복숭아는 김치냉장고에 넣어도 돼?",
         must=[["저온", "냉장실"]], must_not=[],
         evidence=[ev("rda_store1", "저온에 강한 사과, 배, 포도, 단감은 김치냉장고에, 복숭아는 일반 냉장고 냉장실에 보관한다.")]),
    dict(key="apple_pear_place", kind="storage", q="사과랑 배는 어디에 보관하는 게 제일 좋아?",
         must=[["김치냉장고", "0도", "0℃"]], must_not=[],
         evidence=[ev("rda_store2", "사과, 배, 포도, 단감은 0도(℃) 전후 저온에 강해 김치냉장고 보관이 알맞고")]),
    dict(key="leafy_store", kind="storage", q="상추 같은 잎채소는 어떻게 보관해?",
         must=[["물기"], ["비닐", "밀폐", "랩"]], must_not=[],
         evidence=[ev("rda_store2", "잎채소는 호흡이 활발해 시들기 쉬우므로 씻은 뒤 물기를 충분히 제거하고, 밀폐용기나 비닐에 포장해 냉장 보관을 하는 것이 좋다.")]),
    dict(key="cucumber_temp", kind="storage", q="오이나 가지는 몇 도에서 보관하는 게 좋아?",
         must=[["10"], ["12"]], must_not=[],
         evidence=[ev("rda_store1", "오이, 가지 등 저온에 민감한 품목은 10∼12도에 보관하는 것이 알맞다.")]),
    dict(key="kiwi_ripen", kind="storage", q="덜 익은 키위를 빨리 익히려면 어떻게 해?",
         must=[["사과"]], must_not=[],
         evidence=[ev("rda_store2", "덜 익은 키위나 아보카도를 빨리 익게 하고 싶다면 사과와 함께 두면 됨")]),
    dict(key="root_soil", kind="storage", q="무나 당근은 흙을 털고 보관해야 해?",
         must=[["털지 말", "털지 않", "털지 마", "그대로"]], must_not=[],
         evidence=[ev("rda_store2", "무, 당근, 양파, 감자 같은 뿌리채소는 흙을 털지 말고 바람이 잘 통하는 서늘한 곳에 둔다.")]),
    dict(key="walnut_store", kind="storage", q="껍질 깐 호두는 어떻게 보관해?",
         must=[["냉장"], ["산패"]], must_not=[],
         evidence=[ev("kr_seeds", "껍질을 깐 호두는 산패하기 쉬우므로 냉장보관하는 것이 좋으며")]),
    dict(key="chicken_shelf", kind="storage", risk="high", q="생닭은 냉장고 어디에 보관해?",
         must=[["아래"], ["밀폐"]], must_not=[],
         evidence=[ev("kr_campy1", "밀폐 용기에 넣어 냉장고 맨 아래 칸에서 보관하면 핏물로 냉장고 내 다른 식품이 오염되는 것을 막을 수 있다.")]),
    dict(key="chicken_shelf_short", paraphrase_of="chicken_shelf", kind="storage", risk="high", q="생닭 냉장고 어느 칸?",
         must=[["아래"], ["밀폐"]], must_not=[],
         evidence=[ev("kr_campy1", "밀폐 용기에 넣어 냉장고 맨 아래 칸에서 보관하면 핏물로 냉장고 내 다른 식품이 오염되는 것을 막을 수 있다.")]),
    dict(key="egg_store", kind="storage", risk="high", q="달걀은 냉장고에 어떻게 보관해?",
         must=[["닿지", "구분", "분리", "따로"]], must_not=[],
         evidence=[ev("kr_salmonella", "구입한 달걀은 즉시 냉장고에 넣어 다른 식재료와 닿지 않게 구분해서 보관한다."),
                   ev("kr_buy", "달걀은 바로 먹는 채소와 직접 닿지 않도록 보관에 주의한다.")]),
    dict(key="fridge_door", kind="storage", q="냉장실 문 쪽에는 뭘 보관하는 게 좋아?",
         must=[["금방", "빨리", "곧", "자주"]], must_not=[],
         evidence=[ev("kr_buy", "냉장실 문 쪽은 온도 변화가 크므로 금방 먹을 식품만 보관하는 것이 좋다.")]),
    dict(key="fridge_fill", kind="storage", q="냉장고는 얼마나 채우는 게 좋아?",
         must=[["70"]], must_not=[],
         evidence=[ev("kr_buy", "전체용량의 70%이하로 채우기")]),
    dict(key="fridge_temp", kind="storage", risk="high", q="냉장실이랑 냉동실은 몇 도로 맞춰야 해?",
         must=[["5"], ["18"]], must_not=[],
         evidence=[ev("kr_delivery", "냉장식품은 5℃ 이하, 냉동식품은 영하 18℃ 이하로 보관해야 한다.")]),
    dict(key="fridge_temp_short", paraphrase_of="fridge_temp", kind="storage", risk="high", q="냉장고 적정 온도 알려줘",
         must=[["5"], ["18"]], must_not=[],
         evidence=[ev("kr_delivery", "냉장식품은 5℃ 이하, 냉동식품은 영하 18℃ 이하로 보관해야 한다.")]),
    dict(key="minced_days", kind="storage", risk="high", q="다진 고기는 냉장고에 며칠 둘 수 있어?",
         must=[["1~2일", "1-2일", "1∼2일", "하루나 이틀", "하루에서 이틀", "1일에서 2일", "1~2 일"]], must_not=[],
         evidence=[ev("kr_chuseok", "다진 고기는 부패 속도가 가장 빠르므로 구입 즉시 물기를 제거하고 밀봉해 보관해야 하며, 냉장 보관 시에는 1~2일, 냉동 보관이라도 2주는 넘기지 않도록 한다.")]),
    dict(key="meat_stack", kind="storage", q="고기를 여러 장 겹쳐서 보관해도 돼?",
         must=[["랩", "비닐"]], must_not=[],
         evidence=[ev("kr_chuseok", "고기를 여러 장 겹쳐 보관하면 겹친 부분의 색이 변하므로 랩이나 비닐을 끼워 보관해야 한다.")]),
    dict(key="fish_store", kind="storage", q="생선을 보관하기 전에 어떻게 손질해?",
         must=[["내장"], ["물기", "수분"]], must_not=[],
         evidence=[ev("kr_chuseok", "보관 시에는 내장을 깨끗하게 제거하고 생선에 남아있는 수분을 충분히 제거한 후에 냉장 또는 냉동해야 한다.")]),
    dict(key="cooked_temp", kind="storage", risk="high", q="만든 음식은 몇 도에서 보관해야 해?",
         must=[["60"], ["5"]], must_not=[],
         evidence=[ev("kr_buy", "따뜻하게 먹을 음식은 60℃이상에, 차갑게 먹을 음식은 빠르게 식혀 5℃이하에서 보관한다.")]),
    dict(key="holiday_food", kind="storage", risk="high", q="명절 음식을 많이 만들어 두면 어떻게 보관해?",
         must=[["2시간"], ["냉장"]], must_not=[],
         evidence=[ev("kr_chuseok", "명절 음식은 많은 양을 미리 조리해 보관하는 경우가 많으므로 2시간 내로 식혀서 덮개를 덮어 냉장 보관하고, 냉장 보관된 음식은 반드시 재가열한 후 섭취하도록 한다.")]),
    dict(key="holiday_food_casual", paraphrase_of="holiday_food", kind="storage", risk="high", q="전 부친 거 많이 남았는데 어떻게 보관해?",
         must=[["2시간"], ["냉장"]], must_not=[],
         evidence=[ev("kr_chuseok", "명절 음식은 많은 양을 미리 조리해 보관하는 경우가 많으므로 2시간 내로 식혀서 덮개를 덮어 냉장 보관하고, 냉장 보관된 음식은 반드시 재가열한 후 섭취하도록 한다.")]),
    dict(key="balcony", kind="storage", risk="high", q="겨울에 조리한 음식을 베란다에 둬도 돼?",
         must=[["햇빛", "온도"], ["세균", "식중독", "증식", "상할"]], must_not=[],
         evidence=[ev("kr_buy", "베란다에 조리된 음식을 보관하면 낮 동안에는 햇빛에 의해 온도가 올라가 세균이 증식할 수 있으므로 주의가 필요하다.")]),
    dict(key="delivery_night", kind="storage", risk="high", q="남은 치킨을 밤새 상온에 둬도 괜찮아?",
         must=[["냉장"], ["재가열", "데워", "가열"]], must_not=[],
         evidence=[ev("kr_delivery", "만약 불가피한 경우 남은 음식은 냉장고에 보관하고 다시 먹을 때 충분히 재가열한 후 섭취해서 ‘식중독’을 예방한다.")]),
    dict(key="greens_store", kind="storage", q="나물을 바로 안 먹을 때는 어떻게 보관해?",
         must=[["흙"], ["냉장"]], must_not=[],
         evidence=[ev("kr_spring1", "봄나물을 보관할 때는 뿌리에 묻어 있는 흙을 제거하고 비닐이나 뚜껑 있는 용기에 담아 냉장하면 봄나물 고유의 향기와 영양성분이 오래 유지된다.")]),

    # ── 해동·가열·온도 ──
    dict(key="thaw", kind="heat", risk="high", q="냉동 고기는 어떻게 해동해?",
         must=[["냉장"]], must_not=["상온에서 해동하면 됩", "실온에서 해동하면 됩", "실온에 두면 됩", "상온에 두면 됩", "식탁 위"],
         evidence=[ev("kr_buy", "냉장해동 또는 전자레인지 해동이 바람직하고")]),
    dict(key="thaw_hot_water", kind="heat", risk="high", q="얼린 고기 빨리 녹이려면 뜨거운 물에 담가도 돼?",
         must=[["냉장", "전자레인지"]], must_not=["뜨거운 물에 담가도 괜찮", "뜨거운 물에 담그면 됩"],
         evidence=[ev("kr_buy", "냉동된 고기나 생선을 꺼내 냉동과 해동을 되풀이하거나 온수에 해동, 상온, 물에 담군 채 오랜 시간 방치하는 것은 식중독균이 증가할 수 있으므로")]),
    dict(key="refreeze_fish", kind="heat", risk="high", q="해동한 생선이 남았는데 다시 얼려도 돼?",
         must=[["다시 냉동하지", "다시 얼리지", "재냉동하지", "재냉동은", "폐기", "버리"]], must_not=["다시 얼려도 괜찮", "재냉동해도 괜찮"],
         evidence=[ev("kr_chuseok", "다시 냉동해 보관하지 말고 폐기해야 한다.")]),
    dict(key="freeze_kills", kind="why", risk="high", q="고기를 얼리면 세균이 다 죽어?",
         must=[["다시"], ["증식", "번식", "늘어", "자랄"]], must_not=["세균이 다 죽어요", "세균이 모두 죽"],
         evidence=[ev("kr_buy", "냉동 상태에서 활동을 멈췄던 세균은 잘못된 해동과정에서 다시 증식할 수 있으므로")]),
    dict(key="meat_core", kind="heat", risk="high", q="고기는 몇 도까지 익혀야 안전해?",
         must=[["75"], ["1분"]], must_not=[],
         evidence=[ev("kr_campy1", "충분히 가열 조리(중심온도 75℃ 1분 이상)해야 한다.")]),
    dict(key="meat_core_pork", paraphrase_of="meat_core", kind="heat", risk="high", q="돼지고기 속 온도 몇 도 넘어야 돼?",
         must=[["75", "70", "77"]], must_not=[],
         evidence=[ev("kr_campy1", "충분히 가열 조리(중심온도 75℃ 1분 이상)해야 한다.")]),
    dict(key="shellfish_core", kind="heat", risk="high", q="조개나 굴 같은 어패류는 몇 도로 익혀야 해?",
         must=[["85"]], must_not=[],
         evidence=[ev("kr_holiday2", "육류 중심온도 75°C, 1분 이상, 어패류 85°C 1분 이상")]),
    dict(key="egg_cook", kind="heat", risk="high", q="달걀 요리는 어느 정도까지 익혀야 해?",
         must=[["단단", "75"]], must_not=[],
         evidence=[ev("kr_salmonella", "달걀은 가급적 노른자와 흰자가 모두 단단해질 때까지 익혀 먹는 것이 좋다.")]),
    dict(key="danger_zone", kind="heat", risk="high", q="식중독 위험 온도 구간이 몇 도야?",
         must=[["5"], ["60"]], must_not=[],
         evidence=[ev("fsk_temp", "위험구간은 5~60℃이고, 이 온도보다 낮거나 높게 식품을 보관해야 합니다.")]),
    dict(key="danger_zone_casual", paraphrase_of="danger_zone", kind="heat", risk="high", q="음식이 상하기 쉬운 온도가 몇 도야?",
         must=[["5"], ["60"]], must_not=[],
         evidence=[ev("fsk_temp", "위험구간은 5~60℃이고, 이 온도보다 낮거나 높게 식품을 보관해야 합니다.")]),
    dict(key="bacteria_best", kind="heat", q="세균성 식중독균은 몇 도에서 제일 잘 자라?",
         must=[["32"], ["43"]], must_not=[],
         evidence=[ev("kr_delivery", "세균성 식중독균은 32∼43℃의 온도에서 증식이 가장 활발하기 때문이다.")]),
    dict(key="raw_meat_days", kind="storage", risk="high", q="안 익힌 고기는 냉장고에서 며칠까지 보관할 수 있어?",
         must=[["1~2일", "1-2일", "1∼2일", "하루나 이틀", "하루에서 이틀", "1일에서 2일", "1~2 일"]], must_not=[],
         evidence=[ev("fsk_temp", "익히지 않은 식육 및 어패류 1~2일")]),
    dict(key="charred", kind="heat", risk="high", q="고기 탄 부분은 떼고 먹어야 해?",
         must=[["벤조피렌", "다환방향족", "발암"], ["제거", "떼"]], must_not=[],
         evidence=[ev("fsk_hazard", "육류를 조리할 때 검게 탄 부분이 생기지 않도록 하며 탄 부분은 반드시 제거합니다.")]),
    dict(key="fries", kind="heat", q="집에서 감자튀김 할 때 주의할 점이 있어?",
         must=[["160", "황금색", "아크릴아마이드", "식초"]], must_not=[],
         evidence=[ev("fsk_hazard", "튀김온도는 160℃를 넘지 않게 하고, 오븐에서도 200℃를 넘지 않도록 조리합니다."),
                   ev("fsk_hazard", "가정에서 생감자를 튀길 경우 물과 식초 혼합물 (물:식초=1:1)에 감자를 15분간 담근 후 튀깁니다.")]),

    # ── 교차오염·손씻기 ──
    dict(key="wash_chicken", kind="hygiene", risk="high", q="생닭은 물에 씻어서 요리해야 해?",
         must=[["튀"]], must_not=[],
         evidence=[ev("kr_campy1", "씻을 때는 물이 주변으로 튀지 않게 조심해야 한다.")]),
    dict(key="wash_chicken_casual", paraphrase_of="wash_chicken", kind="hygiene", risk="high", q="닭 씻을 때 주의할 점 있어?",
         must=[["튀"]], must_not=[],
         evidence=[ev("kr_campy1", "씻을 때는 물이 주변으로 튀지 않게 조심해야 한다.")]),
    dict(key="one_board", kind="hygiene", risk="high", q="칼이랑 도마 하나로 고기랑 채소를 다 손질해도 돼?",
         must=[["구분", "따로"], ["채소"]], must_not=[],
         evidence=[ev("kr_campy1", "부득이 하나의 칼·도마를 써야 할 경우에는 채소류, 육류, 어류, 생닭 순으로 손질해야 한다.")]),
    dict(key="egg_hands", kind="hygiene", risk="high", q="달걀 만진 다음에는 어떻게 해야 해?",
         must=[["30초"], ["비누", "세정제"]], must_not=[],
         evidence=[ev("kr_salmonella", "달걀물(액란) 등이 묻은 손은 반드시 흐르는 물에 비누 등 세정제로 30초 이상 깨끗이 씻어야 한다.")]),
    dict(key="hand_wash", kind="hygiene", q="요리 전에 손은 얼마나 씻어야 해?",
         must=[["30초"]], must_not=[],
         evidence=[ev("kr_buy", "30초 이상 흐르는 물에 손을 씻어야 한다.")]),
    dict(key="shopping_order", kind="hygiene", q="장 볼 때 어떤 순서로 사는 게 좋아?",
         must=[["어패류"], ["마지막", "나중", "→ 어패류", "순서"]], must_not=[],
         evidence=[ev("kr_buy", "냉장이 필요 없는 식품을 시작으로 과일·채소→ 햄·어묵 등 냉장이 필요한 가공식품→육류→어패류 순서로 하는 것이 좋다.")]),
    dict(key="kimbap_trunk", kind="hygiene", risk="high", q="소풍 갈 때 김밥을 차 트렁크에 둬도 돼?",
         must=[["10"], ["아이스박스", "보냉", "아이스팩"]], must_not=[],
         evidence=[ev("kr_salmonella2", "김밥과 같은 조리식품을 햇볕이 드는 차량 내부, 트렁크 등에 방치하면 식중독균 증식의 위험이 있으므로")]),
    dict(key="campy_cold", kind="why", risk="high", q="생닭에 있는 세균은 냉장고에서도 살아 있어?",
         must=[["생존", "살아"]], must_not=["냉장고에서는 죽"],
         evidence=[ev("kr_campy2", "냉동 및 냉장 상태에서 장시간 생존이 가능하다.")]),

    # ── 세척 ──
    dict(key="pesticide_wash", kind="wash", q="채소에 남은 농약은 어떻게 씻어?",
         must=[["담가", "담근", "담금", "담갔"], ["흐르는 물", "헹"]], must_not=[],
         evidence=[ev("kr_wash1", "수돗물에 담가두었다가 흐르는 물에 씻는 것이 가장")]),
    dict(key="vinegar_myth", kind="myth", q="채소를 식초물에 담가 씻으면 농약이 더 잘 빠져?",
         must=[["차이", "비슷", "근거"]], must_not=["식초물이 더 효과적", "식초가 더 효과적", "식초물에 씻는 것이 좋"],
         evidence=[ev("kr_wash1", "농약제거율에는 크게 차이가 없는 것으로 조사됐고,"),
                   ev("kr_wash3", "이는 이론적으로 전혀 근거가 없을 뿐 아니라")]),
    dict(key="vinegar_myth_casual", paraphrase_of="vinegar_myth", kind="myth", q="농약 빼려면 식초물에 담그는 게 제일 좋지?",
         must=[["차이", "비슷", "근거"]], must_not=["식초물이 더 효과적", "식초가 더 효과적", "식초물에 씻는 것이 좋"],
         evidence=[ev("kr_wash1", "농약제거율에는 크게 차이가 없는 것으로 조사됐고,")]),
    dict(key="salt_charcoal_myth", kind="myth", q="숯이나 소금을 넣고 씻으면 농약이 더 잘 빠져?",
         must=[["차이", "비슷", "근거"]], must_not=["더 잘 빠져요", "더 효과적이에요"],
         evidence=[ev("kr_wash3", "이는 이론적으로 전혀 근거가 없을 뿐 아니라")]),
    dict(key="vinegar_nutrient", kind="myth", q="식초물로 채소를 씻으면 안 좋은 점도 있어?",
         must=[["영양"]], must_not=[],
         evidence=[ev("kr_wash1", "오히려 식초나 소금물로 씻으면 영양소가 파괴될 수 있다.")]),
    dict(key="strawberry", kind="wash", q="딸기는 어떻게 씻어?",
         must=[["1분"], ["30초"]], must_not=[],
         evidence=[ev("kr_wash2", "물에 1분 동안 담근 후 흐르는 물에 30초 정도 씻어 준다.")]),
    dict(key="peach_wash", kind="wash", q="복숭아처럼 털이 많은 과일은 어떻게 씻어?",
         must=[["5분"]], must_not=[],
         evidence=[ev("kr_wash2", "물에 5분 정도 담갔다가 30초 정도 흐르는 물에 씻으면 잔류 농약이 대부분 제거된다.")]),
    dict(key="cabbage_leaves", kind="wash", q="양배추 농약은 어떻게 없애?",
         must=[["겉잎", "바깥"], ["떼", "벗겨", "제거"]], must_not=[],
         evidence=[ev("kr_wash2", "겉잎에 농약이 잔류할 수 있으므로 2~3장 떼어내고 흐르는 물에 잘 씻으면 안심하고 먹을 수 있다.")]),
    dict(key="pepper_tip_myth", kind="myth", q="고추는 끝부분에 농약이 많이 남아 있어?",
         must=[["그렇지 않", "아니", "사실이 아니"]], must_not=["끝부분에 농약이 많이 남아 있어요", "끝부분에 농약이 많이 남아있"],
         evidence=[ev("kr_wash2", "고추는 끝 부분에 농약이 남아있다고 알려져 있으나 실제로는 그렇지 않다.")]),
    dict(key="peel_myth", kind="myth", q="사과 껍질에 농약이 많으니까 깎아 먹어야 해?",
         must=[["껍질"], ["영양", "항산화", "페놀", "씻"]], must_not=["깎아 드시는 게 좋", "깎아 먹는 것이 좋"],
         evidence=[ev("kr_fruitpeel", "섭취하기 전에 물 또는 과일용 세척제로 세척하면 대부분 제거될 수 있는 수준이었다.")]),
    dict(key="raw_greens_wash", kind="wash", risk="high", q="달래나 돌나물처럼 생으로 먹는 나물은 어떻게 씻어?",
         must=[["3회", "세 번", "3번"]], must_not=[],
         evidence=[ev("kr_spring1", "생으로 먹는 달래, 돌나물, 참나물 등은 구입한 후 물에 담갔다가 흐르는 수돗물에 3회 이상 깨끗이 씻은 후 조리하면 잔류농약, 식중독균을 대부분 제거할 수 있다.")]),
    dict(key="noro_wash", kind="wash", risk="high", q="상추를 물에 담가 두기만 하면 바이러스까지 씻겨?",
         must=[["흐르는 물"]], must_not=[],
         evidence=[ev("kr_noro", "양상추와 깻잎을 2분간 물에 담근 후 흐르는 물로 30초간 세척하면 노로바이러스가")]),

    # ── 자연독소·독버섯 ──
    dict(key="blanch_toxin", kind="toxin", risk="high", q="두릅이나 원추리는 왜 데쳐서 먹어?",
         must=[["독"], ["데치", "데쳐", "끓는 물"]], must_not=[],
         evidence=[ev("kr_spring1", "두릅, 다래순, 원추리, 고사리 등은 반드시 끓는 물에 데쳐 고유의 독성분을 제거한 후 섭취해야 한다.")]),
    dict(key="daylily", kind="toxin", risk="high", q="원추리나물은 어떻게 손질해?",
         must=[["어린", "콜히친"], ["2시간"]], must_not=[],
         evidence=[ev("kr_spring1", "특히 원추리는 자랄수록 콜히친이라는 독성분이 강해지므로 반드시 어린 순만을 섭취해야 하며 끓는 물에 충분히 데친 후 차가운 물에 2시간 이상 담근 후 조리한다.")]),
    dict(key="raw_ok_greens", kind="toxin", q="봄나물 중에 생으로 먹을 수 있는 건 뭐야?",
         must=[["달래", "돌나물", "참나물", "씀바귀", "더덕", "취나물", "냉이"]], must_not=["두릅은 생으로", "고사리는 생으로", "원추리는 생으로"],
         evidence=[ev("kr_spring1", "달래, 돌나물, 씀바귀, 참나물, 취나물, 더덕 등은 생으로 먹을 수 있지만")]),
    dict(key="bracken_boil", kind="toxin", risk="high", q="고사리는 어떻게 삶아?",
         must=[["버리", "갈아"], ["2~3회", "2-3회", "2∼3회", "두세 번", "반복", "여러 번"]], must_not=[],
         evidence=[ev("kr_spring2", "고비나 고사리를 삶을 때는 찬물을 부어서 끓이고 끓으면 그 물을 버리고 다시 찬물을 부어 끓이는 과정을 2~3회 반복해야"),
                   ev("kr_buy", "끓는 물에 5분 이상 삶은 후 물에 담갔다가 사용하고")]),
    dict(key="taro", kind="toxin", risk="high", q="토란은 어떻게 손질해서 써?",
         must=[["삶", "데치", "데쳐"], ["담가", "담그", "담갔", "우려"]], must_not=[],
         evidence=[ev("kr_buy", "옥살산칼슘, 호모겐티신산·프타퀼로사이드)을 제거하기 위해 끓는 물에 5분 이상 삶은 후 물에 담갔다가 사용하고")]),
    dict(key="beans_lectin", kind="toxin", risk="high", q="송편소에 넣는 콩은 어떻게 준비해?",
         must=[["불", "5시간"], ["삶", "익혀"]], must_not=[],
         evidence=[ev("kr_buy", "콩류에 들어있는 위해성분(렉틴)을 제거하기 위해 5시간 정도 물에 불린 후 완전히 삶아 익힌 다음 사용한다.")]),
    dict(key="ginkgo", kind="toxin", risk="high", q="은행은 하루에 몇 알 정도가 적당해?",
         must=[["10"], ["2~3", "2∼3", "두세", "2-3", "2, 3"]], must_not=[],
         evidence=[ev("kr_ginkgo", "반드시 익혀먹고 일일 섭취량은 성인 10알, 어린이 2~3알 이내로 섭취해야")]),
    dict(key="ginkgo_child", paraphrase_of="ginkgo", kind="toxin", risk="high", q="아이한테 은행 몇 알까지 줘도 괜찮아?",
         must=[["2~3", "2∼3", "두세", "2-3", "2, 3"]], must_not=[],
         evidence=[ev("kr_ginkgo", "반드시 익혀먹고 일일 섭취량은 성인 10알, 어린이 2~3알 이내로 섭취해야")]),
    dict(key="green_plum", kind="toxin", risk="high", q="청매실을 날로 먹으면 왜 안 돼?",
         must=[["시안", "청산"]], must_not=[],
         evidence=[ev("kr_seeds", "청매실(과육 포함)에는 시안(청산)배당체를 함유하고 있어 날 것으로 먹지 말고")]),
    dict(key="plum_liquor", kind="toxin", risk="high", q="매실주 담글 때 씨는 어떻게 해?",
         must=[["에틸카바메이트"], ["100일", "제거", "빼"]], must_not=[],
         evidence=[ev("kr_maesil", "매실의 씨와 알코올이 직접 닿으면 유해물질인 에틸카바메이트가 자연적으로 소량 생성되기 때문이다."),
                   ev("kr_maesil", "매실의 씨를 제거한 후 사용하거나 담근 매실주로부터 100일 이내에 매실을 제거하는 것이 좋다.")]),
    dict(key="fruit_seeds", kind="toxin", risk="high", q="살구씨나 복숭아씨도 갈아서 식품으로 써도 돼?",
         must=[["시안"]], must_not=[],
         evidence=[ev("kr_seeds", "살구, 복숭아, 매실, 사과 등의 씨앗에는 시안배당체 함량이 높아 식품으로 섭취해서는 안 된다.")]),
    dict(key="mushroom_color", kind="myth", risk="high", q="독버섯은 색깔이 화려해서 구별할 수 있지?",
         must=[["구별할 수 없", "구분할 수 없", "구별이 어렵", "구분이 어렵", "구별하기 어렵", "구분하기 어렵", "알 수 없"]], must_not=[],
         evidence=[ev("kr_mush1", "모양과 색깔로 식용버섯과 독버섯을 구별할 수 없다.")]),
    dict(key="mushroom_cook", kind="myth", risk="high", q="독버섯도 푹 익히면 독이 없어져?",
         must=[["파괴되지", "없어지지", "사라지지", "줄어들지"]], must_not=["독이 없어져요", "익히면 안전"],
         evidence=[ev("kr_mush1", "독버섯의 독소는 가열·조리로 파괴되지 않으므로 무조건 섭취하지 않아야 한다.")]),
    dict(key="mushroom_cook_casual", paraphrase_of="mushroom_cook", kind="myth", risk="high", q="독버섯은 기름에 볶으면 괜찮아지지?",
         must=[["파괴되지", "없어지지", "사라지지", "줄어들지", "괜찮아지지 않"]], must_not=["볶으면 괜찮", "익히면 안전"],
         evidence=[ev("kr_mush1", "독버섯의 독소는 가열·조리로 파괴되지 않으므로 무조건 섭취하지 않아야 한다.")]),
    dict(key="mushroom_tear", kind="myth", risk="high", q="세로로 잘 찢어지는 버섯은 식용 버섯이야?",
         must=[["대부분"]], must_not=["식용 버섯이에요", "식용 버섯입니다"],
         evidence=[ev("kr_mush1", "대부분의 버섯은 세로로도 찢어진다.")]),
    dict(key="mushroom_silver", kind="myth", risk="high", q="은수저가 검게 변하면 독버섯이야?",
         must=[["변색되지", "아니", "그렇지 않", "구별할 수 없", "구분할 수 없"]], must_not=[],
         evidence=[ev("kr_mush1", "대표적인 맹독버섯인 독우산광대버섯은 은수저가 변색되지 않는다.")]),
    dict(key="mushroom_tree", kind="myth", risk="high", q="나무에서 자라는 버섯은 먹을 수 있는 버섯이지?",
         must=[["화경버섯", "붉은사슴뿔버섯", "독버섯도", "독버섯이 있", "아니"]], must_not=[],
         evidence=[ev("kr_mush1", "나무에서 자라는 버섯은 식용이라고 알려져 있으나 독성이 강한 화경버섯, 붉은사슴뿔버섯 등은 독버섯이지만 나무에서 자생한다.")]),
    dict(key="mushroom_ate", kind="toxin", risk="high", q="산에서 딴 버섯을 먹고 배가 아프면 어떻게 해?",
         must=[["병원", "응급", "119"]], must_not=[],
         evidence=[ev("kr_mush1", "발생 즉시 응급의료기관에 연락해 치료를 받아야 한다")]),
    dict(key="riverside_greens", kind="toxin", risk="high", q="하천변에서 자란 나물을 캐서 먹으면 문제 있어?",
         must=[["중금속", "농약"]], must_not=[],
         evidence=[ev("kr_spring1", "도심 하천변 등에서 자라는 나물은 농약이나 중금속 오염도가 높을 수 있으므로 채취하지 않는 것이 좋다.")]),

    # ── 원리 ──
    dict(key="pear_marinade", kind="why", q="고기 재울 때 왜 배를 넣어?",
         must=[["연육", "연하게", "부드럽", "단백질 분해", "단백질을 분해"]], must_not=[],
         evidence=[ev("kr_buy", "양념은 설탕 대신 파인애플, 배, 키위와 같은 과일을 사용하면 당도 줄이고 연육효과도 얻을 수 있다."),
                   ev("ks_tender", "배, 키위, 무화과, 파인애플, 파파야에 함유된 단백질 분해효소을 이용한 육류연화를 비교한 결과는 다음과 같다.")]),
    dict(key="pear_marinade_casual", paraphrase_of="pear_marinade", kind="why", q="불고기 양념에 배즙 넣는 이유가 뭐야?",
         must=[["연육", "연하게", "부드럽", "단백질 분해", "단백질을 분해"]], must_not=[],
         evidence=[ev("kr_buy", "양념은 설탕 대신 파인애플, 배, 키위와 같은 과일을 사용하면 당도 줄이고 연육효과도 얻을 수 있다.")]),
    dict(key="strongest_tender", kind="why", q="고기를 연하게 하는 효과는 어떤 과일이 제일 세?",
         must=[["파인애플"]], must_not=[],
         evidence=[ev("ks_tender", "pineapple>papaya> fig> kiwifruit > pear의 순으로 전단력 감소가 나타났으며")]),
    dict(key="vinegar_fish", kind="why", q="생선 요리에 식초를 넣으면 비린내가 줄어?",
         must=[["줄", "감소", "덜"]], must_not=["효과가 없"],
         evidence=[ev("ks_fish", "식초 첨가량이 증가할수록 생선 비린내는 점점 감소되었으나")]),
    dict(key="milk_soak", kind="why", q="갈비찜 고기를 우유에 담가 두는 이유가 뭐야?",
         must=[["잡내", "냄새", "누린내"]], must_not=[],
         evidence=[ev("kr_buy", "흰 우유에 담가두면 잡내를 잡아주고 육질의 결도 살아 식감이 개선된다.")]),
    dict(key="less_salty_soup", kind="why", q="국을 덜 짜게 끓이려면 어떻게 해?",
         must=[["마지막", "나중에"], ["육수", "다시마", "멸치", "무", "양파"]], must_not=[],
         evidence=[ev("kr_buy", "조리 중간보다 조리 마지막에 간을 보는 것이 덜 짜게 조리하는 방법이다.")]),

    # ── 자료끼리 다름·근거 없음(단정하면 오답) ──
    dict(key="potato_fridge", kind="contested", q="감자는 냉장고에 보관하는 게 좋아?",
         must=[], must_not=[],
         evidence=[ev("rda_potato", "일반 식용감자는 4℃ 내외에서 저장하고, 한 달 안에 사용할 경우 10℃ 내외 또는 직사광선이 들지 않는 서늘한 곳이 좋다."),
                   ev("fsk_hazard", "감자는 장기간 냉장보관을 하지 말고, 8℃ 이상의 서늘한 곳에서 보관합니다.")],
         note="농촌진흥청은 식용감자 4℃ 저장, 식품안전나라(아크릴아마이드 저감)는 장기간 냉장보관을 하지 말라고 함. 한쪽만 단정하면 오답"),
    dict(key="spinach_lid", kind="unsupported", q="시금치 데칠 때 뚜껑을 열어 두는 이유가 뭐야?",
         must=[], must_not=[], evidence=[], note="검토한 공공 자료에 없음. 이유를 단정하면 오답"),
    dict(key="beansprout_lid", kind="unsupported", q="콩나물국 끓일 때 뚜껑을 열면 비린내가 나는 과학적인 이유가 뭐야?",
         must=[], must_not=[], evidence=[], note="검토한 공공 자료에 없음(기사·블로그 설명만 있고 서로 다름). 원인을 단정하면 오답"),
    dict(key="apple_potato_sprout", kind="unsupported", q="감자 상자에 사과를 넣어 두면 싹이 덜 나?",
         must=[], must_not=[], evidence=[],
         note="기사는 된다고 하지만 공공 자료 근거 없음. 농촌진흥청은 사과 에틸렌이 다른 채소 품질을 떨어뜨린다고만 함. 된다고 단정하면 오답"),
    dict(key="salt_before_grill", kind="unsupported", q="고기 굽기 전에 소금을 미리 뿌리면 육즙이 빠져?",
         must=[], must_not=[], evidence=[], note="검토한 공공 자료에 없음. 요리 자료끼리 주장이 엇갈림. 한쪽을 단정하면 오답"),
]


def normalize(text):
    return re.sub(r"\s+", " ", text).strip()


def main():
    cache = HERE / "cache"
    sources = {s["id"]: s for s in json.loads((HERE / "sources.json").read_text(encoding="utf-8"))}
    texts = {}
    problems = []
    keys = set()
    rows = []
    # 다른 말로 물은 항목은 원래 항목과 같은 split에 둡니다(dev에서 본 정답이 test로 새지 않게).
    root_order = {}
    for index, item in enumerate(ITEMS, start=1):
        root = item.get("paraphrase_of") or item["key"]
        root_order.setdefault(root, len(root_order) + 1)
        if item["key"] in keys:
            problems.append(f'중복 key: {item["key"]}')
        keys.add(item["key"])
        if item.get("paraphrase_of") and item["paraphrase_of"] not in keys:
            problems.append(f'{item["key"]}: paraphrase_of {item["paraphrase_of"]}가 앞에 없음')
        if item["kind"] not in ("contested", "unsupported") and not item["evidence"]:
            problems.append(f'{item["key"]}: 근거 없음')
        for evidence in item["evidence"]:
            source_id = evidence["source"]
            if source_id not in sources:
                problems.append(f'{item["key"]}: 모르는 출처 {source_id}')
                continue
            if source_id not in texts:
                path = cache / f"{source_id}.txt"
                if not path.exists():
                    sys.exit(f"{path} 가 없습니다. 먼저 fetch_sources.py 를 실행하세요.")
                texts[source_id] = normalize(path.read_text(encoding="utf-8"))
            if normalize(evidence["quote"]) not in texts[source_id]:
                problems.append(f'{item["key"]}: 원문에 없는 인용 [{source_id}] {evidence["quote"][:40]}')
        # 정답 판정이 위로 경로(먹어도 돼 규칙 답변)로 새지 않게, 그 규칙이 받는 표현은 쓰지 않습니다.
        if re.search(r"먹어도|먹으면\s*(?:안|돼|되|괜찮)|먹을\s*수\s*있(?:어|나|을까|나요)|먹여도|드셔도|섭취해도", item["q"]):
            problems.append(f'{item["key"]}: "먹어도 돼" 규칙 답변으로 가는 질문')
        rows.append({
            "id": index,
            "key": item["key"],
            "split": "dev" if root_order[item.get("paraphrase_of") or item["key"]] % 2 else "test",
            "kind": item["kind"],
            "risk": item.get("risk", "normal"),
            "paraphrase_of": item.get("paraphrase_of"),
            "question": item["q"],
            "must": item["must"],
            "must_not": item["must_not"],
            "evidence": [dict(e, url=sources[e["source"]]["url"], publisher=sources[e["source"]]["publisher"])
                         for e in item["evidence"] if e["source"] in sources],
            "note": item.get("note"),
        })
    if problems:
        sys.exit("평가 세트 문제:\n" + "\n".join(problems))
    out = HERE / "evalset.json"
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    kinds = {}
    for row in rows:
        kinds[row["kind"]] = kinds.get(row["kind"], 0) + 1
    print(f"{len(rows)} items -> {out}")
    print("kind", kinds, "high risk", sum(r["risk"] == "high" for r in rows),
          "paraphrase", sum(bool(r["paraphrase_of"]) for r in rows))


if __name__ == "__main__":
    main()
