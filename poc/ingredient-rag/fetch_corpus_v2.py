"""v2: 요리 재료 약 330개. 넘겨주기(redirect)는 '같은 말'이 아니라 '관련 검색어'로만 기록하고,
동음이의 문서와 음식·생물 설명이 아닌 문서는 빼고 목록으로 남깁니다. 결과: corpus_v2.json, corpus_v2_excluded.json"""
import json, sys, time, urllib.error, urllib.parse, urllib.request

NAMES = """배추 무_(식물) 양배추 상추 시금치 깻잎 부추 미나리 쑥갓 아욱 근대_(식물) 청경채 케일 브로콜리 콜리플라워 셀러리 아스파라거스
양상추 치커리 비트 당근 감자 고구마 토란 마_(식물) 우엉 연근 도라지 더덕 생강 마늘 양파 파_(종) 쪽파 달래 고추 피망 파프리카 오이
호박 애호박 단호박 가지_(식물) 토마토 방울토마토 옥수수 완두 강낭콩 콩나물 숙주나물 열무 얼갈이배추 고수_(식물) 바질 로즈메리 파슬리
딜 박하 루콜라 갓_(식물) 냉이 쑥 씀바귀 고들빼기 취나물 곰취 참취 참나물 머위 두릅나무 엄나무 원추리 돌나물 비름 산마늘 갯기름나물
고려엉겅퀴 고사리 고비 죽순 무청 시래기 우거지 표고 느타리 큰느타리 팽나무버섯 양송이 목이 석이 송이 능이 만가닥버섯 노루궁뎅이
미역 다시마 김_(음식) 톳 파래 매생이 모자반 꼬시래기 우뭇가사리 한천 청각_(해조류) 오징어 낙지 문어 주꾸미 새우 꽃게 대게 전복 굴_(조개)
홍합 바지락 꼬막 가리비 소라 멍게 해삼 성게 키조개 재첩 백합_(조개) 고등어 꽁치 갈치 조기 굴비 명태 대구_(물고기) 연어 다랑어 넙치
조피볼락 참돔 삼치 방어_(물고기) 전어 멸치 뱀장어 붕장어 아귀 홍어 가자미 병어 민어 청어 과메기 황태 북어 코다리 노가리 동태
소고기 돼지고기 닭고기 오리고기 양고기 삼겹살 차돌박이 곱창 막창 순대 베이컨 햄 소시지 두부 순두부 유부 어묵 당면 곤약 도토리묵
녹두묵 메밀묵 떡 가래떡 만두 국수 소면 우동 냉면 칼국수 쌀 찹쌀 현미 보리 귀리 수수 조_(곡식) 기장 율무 메밀 밀가루 녹두 팥 대두
서리태 땅콩 호두 잣 밤_(열매) 은행_(식물) 대추 아몬드 캐슈 곶감 감 사과 배_(과일) 귤 유자 레몬 매실 오미자 복분자딸기 블루베리 딸기
수박 참외 포도 간장 된장 고추장 청국장 쌈장 초고추장 춘장 국간장 고춧가루 참기름 들기름 들깨 참깨 식초 설탕 소금 천일염 꿀 조청
물엿 엿기름 맛술 미림 액젓 멸치액젓 까나리액젓 새우젓 명란젓 창난젓 굴소스 고추냉이 겨자 후추 카레 강황 계피 정향 팔각 산초나무
초피나무 달걀 메추리알 우유 버터 치즈 요구르트 크림 누룽지 미숫가루 식혜 수정과 김치 깍두기 동치미 단무지 장아찌 젓갈 도라지청
곤드레 방풍나물 청포묵 새송이버섯 팽이버섯 우럭 광어 명이나물 참치 능이버섯 붕장어 도미 깻잎 유채
""".split()

# 사람이 확인한 잘못된 넘겨주기(꼬시래기는 해조류인데 물고기 문서로 감)
BAD_REDIRECTS = {("꼬시래기", "풀망둑")}
UA = {"User-Agent": "SalusPoC/0.2 (personal research)"}
FOOD_HINTS = ["식물", "풀", "나무", "버섯", "음식", "요리", "먹", "식용", "열매", "채소", "물고기", "어류", "조개", "바닷말", "해조",
              "갈조", "녹조", "홍조", "가루", "기름", "장(", "양념", "재료", "식품", "곡물", "곡식", "과의", "과에", "고기", "젓", "술",
              "감미료", "발효", "연체동물", "갑각류", "해산물", "과일", "씨", "콩",
              "나물", "말린", "배추", "상추", "허브", "품종", "동물", "두부", "쌀", "벼", "딸기", "염화", "덩굴", "재배"]

def api(params):
    url = "https://ko.wikipedia.org/w/api.php?" + urllib.parse.urlencode(params)
    for wait in (0, 30, 60, 120, 240):
        time.sleep(wait)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                return json.load(r)["query"]
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            print("429, 기다렸다 다시", file=sys.stderr)
    raise RuntimeError("요청 제한이 풀리지 않았습니다")

docs, excluded, seen = {}, [], set()
for i in range(0, len(NAMES), 20):
    chunk = [n.replace("_", " ") for n in NAMES[i:i + 20]]
    q = api({"action": "query", "format": "json", "formatversion": "2", "redirects": "1",
             "prop": "extracts|pageimages|info|pageprops", "ppprop": "disambiguation", "exintro": "1",
             "explaintext": "1", "exsentences": "4", "piprop": "thumbnail", "pithumbsize": "330", "inprop": "url",
             "titles": "|".join(chunk)})
    norm = {m["from"]: m["to"] for m in q.get("normalized", [])}
    redir = {m["from"]: m["to"] for m in q.get("redirects", [])}
    pages = {p["title"]: p for p in q["pages"]}
    for asked in chunk:
        t = norm.get(asked, asked)
        final = redir.get(t, t)
        page = pages.get(final)
        name = asked.split(" (")[0]
        text = (page or {}).get("extract", "").strip()
        if not page or page.get("missing"):
            excluded.append({"name": asked, "reason": "문서 없음"}); continue
        if "pageprops" in page and "disambiguation" in page["pageprops"]:
            excluded.append({"name": asked, "reason": "동음이의 문서"}); continue
        if not text:
            excluded.append({"name": asked, "reason": "본문 없음"}); continue
        if (name, page["title"]) in BAD_REDIRECTS:
            excluded.append({"name": asked, "reason": "다른 대상으로 넘겨짐: " + page["title"]}); continue
        if not any(h in text for h in FOOD_HINTS):
            excluded.append({"name": asked, "reason": "음식·생물 설명이 아님: " + text[:40]}); continue
        d = docs.setdefault(page["title"], {"title": page["title"], "text": text, "url": page.get("fullurl"),
                                            "image": (page.get("thumbnail") or {}).get("source"), "related_terms": []})
        if t != final and name != page["title"].split(" (")[0] and name not in d["related_terms"]:
            d["related_terms"].append(name)   # 넘겨주기: 같은 말이라는 근거가 아님
    time.sleep(2)

json.dump(list(docs.values()), open("corpus_v2.json", "w"), ensure_ascii=False, indent=1)
json.dump(excluded, open("corpus_v2_excluded.json", "w"), ensure_ascii=False, indent=1)
print(len(NAMES), "개 요청,", len(docs), "개 문서 저장,", len(excluded), "개 제외")
