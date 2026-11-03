"""재료 정보 자료(ko-ingredients.json)를 위키백과에서 받아 만듭니다.

서버는 실행 중에 위키백과를 부르지 않습니다. 이 도구로 받은 결과를 사람이 읽고 확인한 뒤 커밋합니다.
- names.txt: 받을 문서 제목(재료 이름 사전 역할도 함)
- bad_redirects.tsv: 사람이 확인한 잘못된 넘겨주기
- excluded_documents.tsv: 사람이 읽고 뺀 문서(모순·편집자 메모·장난성 편집·사실과 다른 동일시)
- approved_documents.tsv: 자동 검사에 걸렸지만 사람이 읽고 괜찮다고 확인한 문서
- 자동 검사: 장난성 편집이나 편집자 메모 흔적이 있으면 기본으로 뺍니다(모르는 것을 '검토됨'으로 두지 않음).
- 넘겨주기(redirect)는 같은 말이 아니라 관련 검색어로만 기록합니다(코다리→명태는 반쯤 말린 명태).
- 동음이의·문서 없음·음식/생물 설명이 아닌 문서는 자료에서 빼고 unreviewedNames에 이유와 함께 남깁니다.
  서버는 이 이름을 '아는 재료지만 검토된 자료가 없음'으로 보고 지어내지 않고 보류합니다.

실행: python3 build_corpus.py  (요청 제한 429는 자동으로 기다렸다 다시 시도)
"""
import datetime, json, re, sys, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / "src/main/resources/ingredients/ko-ingredients.json"
UA = {"User-Agent": "SalusIngredientCorpus/1.0 (https://github.com/smin0111/Salus)"}
# 받은 문서가 음식·생물 설명인지 보는 거친 필터입니다. 걸러진 문서는 버리지 않고 이유와 함께 남겨 사람이 봅니다.
FOOD_HINTS = ["식물", "풀", "나무", "버섯", "음식", "요리", "먹", "식용", "열매", "채소", "물고기", "어류", "조개", "바닷말", "해조",
              "갈조", "녹조", "홍조", "가루", "기름", "장(", "양념", "재료", "식품", "곡물", "곡식", "과의", "과에", "고기", "젓", "술",
              "감미료", "발효", "연체동물", "갑각류", "해산물", "과일", "씨", "콩", "나물", "말린", "배추", "상추", "허브", "품종",
              "동물", "두부", "쌀", "벼", "딸기", "염화", "덩굴", "재배", "만든"]


def read_names():
    return [line.strip() for line in (HERE / "names.txt").read_text().splitlines()
            if line.strip() and not line.startswith("#")]


def read_bad_redirects():
    pairs = set()
    for line in (HERE / "bad_redirects.tsv").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            asked, target = line.split("\t")
            pairs.add((asked.strip(), target.strip()))
    return pairs


# 위키백과 문서에 섞여 들어오는 장난성 편집·편집자 메모의 흔적. 걸리면 사람이 approved_documents.tsv에 올리기 전까지 뺍니다.
# "사실은"은 정상 설명("견과류로 생각하지만 사실은 핵과")에도 쓰여 넣지 않습니다(아몬드 오탐).
SUSPICIOUS = re.compile(r"[ㄱ-ㅎㅏ-ㅣ]{2,}|!!|\?\?|근데|ㅋ|ㅎㅎ")


def read_title_reasons(filename):
    path = HERE / filename
    if not path.exists():
        return {}
    result = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            title, _, reason = line.partition("\t")
            result[title.strip()] = reason.strip()
    return result


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


def clean(text):
    """발음 기호·학명·한자처럼 한글 설명이 아닌 괄호는 뺍니다. "광어(廣魚)라고도"는 "광어라고도"가 됩니다.
    안쪽 괄호부터 반복해서 지웁니다. 한글 설명이 든 괄호("(다른 이름: 숭채)")는 남깁니다."""
    prev = None
    while prev != text:
        prev = text
        text = re.sub(r"\s*\(([^()]*)\)",
                      lambda m: "" if (not re.search(r"[가-힣]", m.group(1)) or m.group(1).lstrip().startswith("←")
                                       or "[" in m.group(1)) else m.group(0),
                      text)
    return re.sub(r"[ \t]{2,}", " ", text).strip()


def main():
    names, bad = read_names(), read_bad_redirects()
    docs, unreviewed = {}, []
    for i in range(0, len(names), 20):
        chunk = [n.replace("_", " ") for n in names[i:i + 20]]
        q = api({"action": "query", "format": "json", "formatversion": "2", "redirects": "1",
                 "prop": "extracts|info|pageprops", "ppprop": "disambiguation", "exintro": "1",
                 "explaintext": "1", "exsentences": "4", "inprop": "url", "titles": "|".join(chunk)})
        norm = {m["from"]: m["to"] for m in q.get("normalized", [])}
        redir = {m["from"]: m["to"] for m in q.get("redirects", [])}
        pages = {p["title"]: p for p in q["pages"]}
        for asked in chunk:
            t = norm.get(asked, asked)
            final = redir.get(t, t)
            page = pages.get(final)
            name = asked.split(" (")[0]
            text = clean((page or {}).get("extract", "").strip())
            reason = None
            if not page or page.get("missing"):
                reason = "문서 없음"
            elif "pageprops" in page and "disambiguation" in page["pageprops"]:
                reason = "동음이의 문서"
            elif not text:
                reason = "본문 없음"
            elif (name, page["title"]) in bad:
                reason = "다른 대상으로 넘겨짐: " + page["title"]
            elif not any(h in text for h in FOOD_HINTS):
                reason = "음식·생물 설명이 아님: " + text[:40]
            if reason:
                unreviewed.append({"name": name, "reason": reason})
                continue
            d = docs.setdefault(page["title"], {
                "id": page["pageid"], "title": page["title"], "text": text, "url": page.get("fullurl"),
                "revisionId": page.get("lastrevid"), "relatedTerms": []})
            if t != final and name != page["title"].split(" (")[0] and name not in d["relatedTerms"]:
                d["relatedTerms"].append(name)
        time.sleep(2)

    excluded, approved = read_title_reasons("excluded_documents.tsv"), read_title_reasons("approved_documents.tsv")
    for title in list(docs):
        d = docs[title]
        reason = None
        if title in excluded:
            reason = "사람이 뺌: " + excluded[title]
        elif title not in approved and SUSPICIOUS.search(d["text"]):
            reason = "자동 검사: 편집 흔적 의심(" + SUSPICIOUS.search(d["text"]).group(0) + ")"
        if reason:
            del docs[title]
            for name in [title.split(" (")[0]] + d["relatedTerms"]:
                unreviewed.append({"name": name, "reason": reason})

    # 받은 문서 제목과 같은 이름은 '검토된 자료 없음'이 아닙니다(예: 다른 이름으로 같은 문서를 받은 경우).
    known = {d["title"].split(" (")[0] for d in docs.values()} | {r for d in docs.values() for r in d["relatedTerms"]}
    unreviewed = list({u["name"]: u for u in unreviewed if u["name"] not in known}.values())
    corpus = {
        "source": "ko.wikipedia",
        "license": "CC BY-SA 4.0",
        "licenseUrl": "https://creativecommons.org/licenses/by-sa/4.0/deed.ko",
        "fetchedAt": datetime.date.today().isoformat(),
        "documents": sorted(docs.values(), key=lambda d: d["title"]),
        "unreviewedNames": sorted(unreviewed, key=lambda u: u["name"]),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(corpus, ensure_ascii=False, indent=1) + "\n")
    print(len(names), "개 요청,", len(docs), "개 문서,", len(unreviewed), "개 검토된 자료 없음 →", OUT)


if __name__ == "__main__":
    main()
