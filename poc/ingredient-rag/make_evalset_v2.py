"""v2 평가 세트. split: dev(기준점 정하기) / test(정한 기준점으로 확인만).
kind: in(자료에 답 있음) / confuse(헷갈리는 두 재료) / related(넘겨주기로만 연결, 본문에 정의 없음) / ooc(자료에 없음)."""
import json
OLD = json.load(open("evalset.json"))
V1_TO_V2 = {"우거지": "우거지"}  # 제목이 같으면 그대로
items = []
for it in OLD:
    if it["gold"] is None or it["question"].startswith("유채"):  # 유채는 v2 자료 목록에서 빠짐
        continue
    if it["question"].startswith("코다리"):
        continue  # v2에서는 related로 따로 다룸
    items.append(dict(question=it["question"], gold=it["gold"], must=it["must"], must_not=it["must_not"],
                      kind="confuse" if isinstance(it["gold"], list) else "in"))

NEW = [
 # 헷갈리는 두 재료
 ("무청이랑 열무는 뭐가 달라?", ["무청", "열무"], [["잎"], ["어린"]], []),
 ("참기름이랑 들기름 차이가 뭐야?", ["참기름", "들기름"], [["참깨"], ["들깨"]], []),
 ("된장이랑 청국장은 어떻게 달라?", ["된장", "청국장"], [["메주"], ["콩"]], []),
 ("간장이랑 국간장 차이가 뭐야?", ["간장", "국간장"], [["국"]], []),
 ("청포묵이랑 도토리묵 차이가 뭐야?", ["녹두묵", "도토리묵"], [["녹두"], ["도토리"]], []),
 ("숙주나물이랑 콩나물은 뭐가 달라?", ["숙주나물", "콩나물"], [["녹두"], ["대두"]], []),
 ("피망이랑 파프리카는 다른 거야?", ["단고추"], [["단고추", "맵지 않"]], []),
 ("고추냉이랑 겨자는 같은 거야?", ["고추냉이", "겨자"], [["배추과", "다른", "달라"]], ["같은 식물입니다"]),
 ("미림이랑 맛술 차이가 뭐야?", ["미림", "맛술"], [["일본"], ["청주", "소주", "포도주"]], []),
 ("상추랑 양상추는 뭐가 달라?", ["상추", "양상추"], [["결구", "둥글게"]], []),
 ("곰취랑 참취는 같은 거야?", ["곰취", "참취"], [["국화과"]], ["같은 식물입니다"]),
 ("붕장어랑 뱀장어 차이가 뭐야?", ["붕장어", "뱀장어"], [["비늘"]], []),
 ("곱창이랑 막창은 어느 부위야?", ["곱창", "막창"], [["작은창자"], ["주름위", "네 번째 위"]], []),
 ("명란젓이랑 창난젓 차이가 뭐야?", ["명란젓", "창난젓"], [["알"], ["창자"]], []),
 ("식혜랑 수정과는 뭘로 만들어?", ["식혜", "수정과"], [["엿기름"], ["생강", "곶감", "계피"]], []),
 ("순두부랑 두부는 뭐가 달라?", ["순두부", "두부"], [["굳히지", "무르"]], []),
 ("산초랑 초피는 같은 거야?", ["산초나무", "초피나무"], [["운향과"]], ["같은 나무입니다"]),
 # 다른 이름·정체
 ("굴비는 무슨 생선으로 만들어?", "참조기", [["조기"]], []),
 ("광어 정식 이름이 뭐야?", "넙치", [["넙치"]], []),
 ("우럭은 정식으로 뭐라고 불러?", "조피볼락", [["조피볼락"]], []),
 ("노가리는 뭐야?", "명태", [["명태"], ["어린"]], []),
 ("한천은 뭘로 만들어?", "우무", [["우뭇가사리"]], []),
 ("참치 정식 이름은 뭐야?", "다랑어", [["다랑어"]], []),
 ("멍게 표준어가 뭐야?", ["멍게"], [["우렁쉥이"]], []),
 ("능이버섯 정식 이름은?", "노루털버섯", [["노루털버섯"]], []),
 ("명이나물은 무슨 식물이야?", "울릉산마늘", [["산마늘"]], []),
 ("부추를 경상도에서 뭐라고 해?", "부추", [["정구지"]], []),
 ("아욱 다른 이름이 뭐야?", "아욱", [["동규"]], []),
 ("양배추 다른 이름은?", "양배추", [["감람"]], []),
 ("배추는 무슨 식물의 재배종이야?", "배추", [["순무"]], []),
 ("시금치는 무슨 과 식물이야?", "시금치", [["비름과"]], []),
 ("셀러리는 무슨 과야?", "셀러리", [["미나리과"]], []),
 ("치커리는 무슨 과 식물이야?", "치커리", [["국화과"]], []),
 ("청경채는 뭐야?", "청경채", [["배추"]], []),
 ("재첩은 어디 사는 조개야?", ["재첩"], [["민물"]], []),
 ("초장은 뭘로 만들어?", "초장", [["고추장"], ["식초"]], []),
 ("춘장은 어느 나라 요리에 쓰는 장이야?", "춘장", [["중국"]], []),
 ("미숫가루는 뭘로 만들어?", "미수 (음료)", [["찹쌀", "멥쌀", "보리", "콩"]], []),
 ("단무지는 어떻게 만들어?", "단무지", [["무"], ["절"]], []),
 ("동치미는 어떤 김치야?", "동치미", [["무"], ["물김치", "소금물"]], []),
 ("새우젓은 뭐야?", "새우젓", [["새우"], ["소금", "젓갈"]], []),
 ("정향은 식물의 어느 부분이야?", "정향나무", [["꽃봉오리", "봉오리"]], []),
 ("율무는 무슨 과 식물이야?", "율무", [["벼과"]], []),
 ("오미자 이름은 왜 오미자야?", "오미자", [["다섯"]], []),
 ("송이버섯은 어디서 자라?", "송이", [["소나무"]], []),
 ("석이버섯은 어디서 자라?", "석이", [["바위"]], []),
 ("근대는 무슨 과 채소야?", "근대 (식물)", [["비름과"]], []),
 # 넘겨주기로만 연결: 본문에 정의가 없으므로 단정하지 않으면 정답(사람이 판정)
 ("북어는 뭐야?", None, [], [], "related"),
 ("코다리는 무슨 생선이야?", None, [], [], "related"),
 ("물엿은 뭐야?", None, [], [], "related"),
 # 자료에 없음
 ("황태는 뭐야?", None, [], []), ("동태는 뭐야?", None, [], []), ("얼갈이배추가 뭐야?", None, [], []),
 ("케일은 어떤 채소야?", None, [], []), ("서리태는 뭐야?", None, [], []), ("까나리액젓은 뭐로 만들어?", None, [], []),
 ("민어는 어떤 생선이야?", None, [], []), ("방어는 어떤 생선이야?", None, [], []), ("청각은 어떤 해조류야?", None, [], []),
 ("꼬시래기는 뭐야?", None, [], []), ("목이버섯은 뭐야?", None, [], []), ("퀴노아는 뭐야?", None, [], []),
 ("오크라는 어떤 채소야?", None, [], []), ("아티초크는 어떻게 먹어?", None, [], []), ("공심채는 뭐야?", None, [], []),
 ("콜라비는 어떤 채소야?", None, [], []), ("여주는 어떤 채소야?", None, [], []),
]
for n in NEW:
    q, g, m, nn = n[:4]
    kind = n[4] if len(n) > 4 else ("ooc" if g is None else ("confuse" if isinstance(g, list) and len(g) > 1 else "in"))
    items.append(dict(question=q, gold=g, must=m, must_not=nn, kind=kind))

corpus = {d["title"]: d for d in json.load(open("corpus_v2.json"))}
problems = []
for i, it in enumerate(items):
    it["id"] = i + 1
    it["split"] = "dev" if i % 2 == 0 else "test"
    gl = [] if it["gold"] is None else (it["gold"] if isinstance(it["gold"], list) else [it["gold"]])
    it["gold"] = gl or None
    text = " ".join(corpus[g]["text"] for g in gl if g in corpus)
    for g in gl:
        if g not in corpus:
            problems.append((it["question"], "자료 없음: " + g))
    for group in it["must"]:
        if not any(t in text for t in group):
            problems.append((it["question"], "정답 단어가 자료에 없음: " + "/".join(group)))
    if it["kind"] == "ooc":
        name = it["question"].split("는 ")[0].split("은 ")[0].split("가 ")[0].split("이 ")[0]
        hits = [t for t, d in corpus.items() if name in d["text"] or name in t]
        if hits:
            problems.append((it["question"], f"'{name}'이 자료에 나옴: {hits[:3]}"))
# 질문한 이름이 정답 자료의 제목·본문에 직접 나오는지 확인합니다. 넘겨주기(관련 검색어)로만 이어지면 kind=related.
import importlib.util, sys
spec = importlib.util.spec_from_file_location("rev", "run_eval_v2.py"); rev = importlib.util.module_from_spec(spec)
sys.argv = ["x"]; spec.loader.exec_module(rev)
for it in items:
    if it["kind"] not in ("in", "confuse"):
        continue
    gl = it["gold"]
    toks = rev.subject_tokens(it["question"])
    direct = any(any(t == corpus[g]["title"].split(" (")[0] or t in corpus[g]["text"] for g in gl) for t in toks)
    related = any(any(t in corpus[g].get("related_terms", []) for g in gl) for t in toks)
    if not direct:
        it["kind"] = "related" if related else it["kind"]
        if not related:
            problems.append((it["question"], f"질문한 이름 {toks}이 정답 자료에 없음"))
json.dump(items, open("evalset_v2.json", "w"), ensure_ascii=False, indent=1)
from collections import Counter
print(len(items), "문항", Counter(i["kind"] for i in items), Counter(i["split"] for i in items))
for p in problems: print("확인 필요:", p)
