"""한국어 위키백과 요약(첫 문단)으로 재료 자료를 모읍니다. 결과: corpus.json"""
import json, sys, time, urllib.error, urllib.parse, urllib.request

NAMES = """무청 시래기 우거지 깻잎 참나물 취나물 고사리 도라지 더덕 냉이 달래 쑥 씀바귀 미나리 쑥갓 부추 아욱 근대 시금치 열무
얼갈이배추 고들빼기 곤드레 비름 유채 머위 두릅 방풍 톳 미역 다시마 김_(음식) 파래 매생이 꼬막 바지락 멸치 황태 북어 코다리
과메기 도토리묵 청포묵 메밀 들기름 참기름 고춧가루 된장 고추장 청국장 누룽지 조청 엿기름 곶감 대추 은행_(열매) 연근 우엉 토란
마_(식물) 곤약 표고버섯 느타리 팽이버섯 새송이버섯 콩나물 숙주나물 두부 유부 어묵 당면 쪽파 대파 생강 마늘""".split()

def fetch_batch(titles):
    params = {"action": "query", "format": "json", "formatversion": "2", "redirects": "1",
              "prop": "extracts|pageimages|info", "exintro": "1", "explaintext": "1", "exsentences": "4",
              "piprop": "thumbnail", "pithumbsize": "330", "inprop": "url", "titles": "|".join(titles)}
    url = "https://ko.wikipedia.org/w/api.php?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "SalusPoC/0.1 (personal research; seungmin)"})
    for wait in (0, 30, 60, 120):
        time.sleep(wait)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)["query"]
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            print("429, 기다렸다 다시", file=sys.stderr)
    raise RuntimeError("위키백과 요청 제한이 풀리지 않았습니다")

out = []
for i in range(0, len(NAMES), 20):
    chunk = NAMES[i:i + 20]
    q = fetch_batch([n.replace("_", " ") for n in chunk])
    alias = {}
    for m in q.get("normalized", []) + q.get("redirects", []):
        alias[m["to"]] = alias.get(m["from"], m["from"])
    for page in q["pages"]:
        text = (page.get("extract") or "").strip()
        if page.get("missing") or not text or "다음 뜻" in text[:80]:
            print("제외", page.get("title"), file=sys.stderr); continue
        out.append({
            "query_name": alias.get(page["title"], page["title"]).split(" (")[0],
            "title": page["title"], "text": text, "url": page.get("fullurl"),
            "image": (page.get("thumbnail") or {}).get("source"),
        })
    time.sleep(2)

json.dump(out, open("corpus.json", "w"), ensure_ascii=False, indent=1)
print(len(out), "개 저장")
