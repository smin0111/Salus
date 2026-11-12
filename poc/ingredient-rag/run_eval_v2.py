"""v2: 자료 253개·평가 115문항(dev/test). 지금 방식(근거 없는 일반 대화)과 근거 방식(bge-m3 검색 + 자료만 보고 답하기)을 같은 질문으로 비교합니다.
사용: python3 run_eval.py [--retrieval-only] [--threshold 0.55]"""
import argparse, json, math, re, textwrap, time, urllib.request
from pathlib import Path

OLLAMA = "http://127.0.0.1:11434"
ROOT = Path(__file__).resolve().parent
JAVA = ROOT.parents[1] / "backend/src/main/java/com/salus/healthytable/service/OllamaLlmService.java"
ABSTAIN = "확실하지 않아요"

def post(path, body):
    req = urllib.request.Request(OLLAMA + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)

def embed(texts):
    return post("/api/embed", {"model": "bge-m3", "input": texts})["embeddings"]

def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / (math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b)))

def chat(system, user):
    # 서비스의 일반 대화와 같은 설정(qwen3:8b, thinking 끔, temperature 0.1, top_p 0.65, num_predict 700)
    t = time.time()
    r = post("/api/chat", {"model": "qwen3:8b", "stream": False, "think": False,
                           "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                           "options": {"temperature": 0.1, "top_p": 0.65, "num_predict": 700}})
    return r["message"]["content"].strip(), round(time.time() - t, 1)

def service_system_prompt():
    src = JAVA.read_text(encoding="utf-8")
    m = re.search(r'private String systemInstruction\(\) \{\s*return """\n(.*?)""";', src, re.S)
    return textwrap.dedent(m.group(1))

# ChatService가 일반 대화일 때 메시지 앞에 붙이는 안내(로그인 사용자 기준, 건강 정보 없음)
GENERAL_CONTEXT = ("\n=== 일반 대화 ===\n사용자가 인사, 자기소개 요청, 잡담을 한 경우 레시피를 만들지 말고 Salus를 짧게 소개하며 자연스럽게 답하세요.\n"
                   "사용자가 메뉴 추천만 원하면 조리법을 쓰지 말고 메뉴 후보와 이유만 짧게 답하세요. 사용자가 불만을 말하면 인정하고 더 쉬운 대안으로 전환하세요.\n"
                   "정체를 묻지 않은 일반 질문에는 자기소개를 반복하지 마세요.\n================\n")

GROUNDED_SYSTEM = f"""당신은 Salus의 식재료 설명 도우미입니다.
아래 [자료]에 적힌 내용만으로 사용자의 질문에 2~3문장으로 답하세요.
- [자료]에 없는 사실, 효능, 영양 정보, 조리법은 덧붙이지 마세요.
- [자료]가 질문의 재료를 다루지 않거나 답이 [자료]에 없으면 "{ABSTAIN}. 자료에서 찾지 못했어요."라고만 답하세요.
- [관련 검색어]는 검색을 돕는 표시일 뿐입니다. 질문한 이름이 [자료] 본문에 직접 설명되어 있지 않으면 그 이름이 무엇인지 단정하지 말고 "{ABSTAIN}. 자료에서 찾지 못했어요."라고 답하세요.
- 질문한 재료가 [자료]의 재료와 이름만 비슷하거나 분류 이름에만 나오면 같은 것으로 보지 마세요.
- 모든 문장을 "~입니다", "~예요"처럼 존댓말로 끝내세요. 반말("~이야")과 "~이다"는 쓰지 마세요. 이모지와 목록 기호는 쓰지 마세요."""

def doc_text(d):
    # 넘겨주기 이름은 검색에만 도움이 되도록 붙이고, 같은 말이라는 근거가 아니라고 적습니다(v1 코다리 오답).
    rel = f" [관련 검색어: {', '.join(d['related_terms'])} — 같은 뜻이라는 근거가 아님]" if d.get("related_terms") else ""
    return f"{d['title']}{rel}: {d['text']}"

STOP = set("뭐야 뭐 정식 이름 이름은 다른 무슨 어떤 차이 차이가 어디 같은 거야 어떻게 만들어 뭘로 뭐로 종류 식물 채소 생선 해조류 버섯 "
          "과 속 영어로 표준어 부분 사는 조개 나는 자라 키워 콩으로 먹어 먹을 수 있어 재료 원료 왜 다른거야 달라 불러 정식으로 "
          "경상도에서 해 말린 걸 라고 어느 나라 요리에 쓰는 장이야 김치야 생물 종류의".split())
PARTICLE = re.compile(r"(이랑|랑|이나|하고|와|과|은|는|이|가|을|를|의|도|으로|로|에서|이야|야)$")
PARTICLES = "이랑|랑|이나|하고|와|과|은|는|이|가|을|를|의|도|으로|로|에서|이야|야".split("|")

KNOWN_NAMES = set()  # build_index가 채움: 조사를 떼다 이름까지 잘라 먹지 않게(매생이→매생) 아는 이름을 먼저 봅니다.

def subject_tokens(question):
    toks = []
    for w in re.findall(r"[가-힣A-Za-z]+", question):
        once = PARTICLE.sub("", w) or w
        twice = PARTICLE.sub("", once) or once
        # "고추냉이랑"은 '이랑'이 먼저 맞아 "고추냉"이 되므로, 조사마다 떼어 본 후보도 함께 봅니다.
        each = [w[:-len(p)] for p in PARTICLES if w.endswith(p) and len(w) > len(p)]
        base = next((c for c in (w, *each, once, twice) if c in KNOWN_NAMES
                     or any(c.endswith(sfx) and c[:-len(sfx)] in KNOWN_NAMES for sfx in ("버섯", "나물"))), once)
        if len(base) >= 1 and base not in STOP and w not in STOP:
            toks.append(base)
    return toks

def build_index(corpus):
    idx = {}
    KNOWN_NAMES.clear()
    KNOWN_NAMES.update(d["title"].split(" (")[0] for d in corpus)
    KNOWN_NAMES.update(r for d in corpus for r in d.get("related_terms", []))
    for d in corpus:
        idx.setdefault(d["title"].split(" (")[0], []).append(("name", d))
        for r in d.get("related_terms", []):
            idx.setdefault(r, []).append(("related", d))
    return idx

def hybrid_retrieve(question, qv, corpus, vecs, idx, emb_threshold):
    """1) 이름이 제목·관련 검색어와 정확히 같으면 그 문서 2) 본문에 이름이 나오는 문서(임베딩 순)
    3) 둘 다 없으면 임베딩 점수가 높을 때만 4) 아니면 없음 → LLM을 부르지 않고 보류."""
    sims = {d["title"]: cos(qv, v) for v, d in zip(vecs, corpus)}
    picked, used = [], set()
    toks = subject_tokens(question)
    # 긴 이름부터: "무청"이 있으면 "무"는 따로 찾지 않음
    names = sorted({t for t in toks}, key=len, reverse=True)
    for t in names:
        # "송이버섯"·"참취나물"처럼 뒤에 버섯·나물을 붙여 물어도 제목("송이")과 맞춥니다.
        key = t if t in idx else next((t[:-len(sfx)] for sfx in ("버섯", "나물") if t.endswith(sfx) and t[:-len(sfx)] in idx), None)
        if key:
            for kind, d in idx[key]:
                picked.append((kind, t, d)); used.add(t)
        else:
            # 제목이 이름 + 나무·버섯·나물·류이면 관련 문서로 봅니다(두릅→두릅나무). 같은 것이라고 말하지는 않습니다.
            for d in corpus:
                base = d["title"].split(" (")[0]
                if any(base == t + sfx for sfx in ("나무", "버섯", "나물", "류")):
                    picked.append(("related", t, d)); used.add(t)
    for t in names:
        if any(t in u or u in t for u in used) or len(t) < 2:
            continue
        hits = sorted((d for d in corpus if t in d["text"]), key=lambda d: -sims[d["title"]])[:2]
        for d in hits:
            picked.append(("text", t, d))
        if hits:
            used.add(t)
    if not picked:
        best = max(corpus, key=lambda d: sims[d["title"]])
        if sims[best["title"]] >= emb_threshold:
            picked.append(("embedding", None, best))
    seen, out = set(), []
    for kind, t, d in picked:
        if d["title"] not in seen:
            seen.add(d["title"]); out.append((kind, t, d))
    return out[:3], sims

def golds(item):
    g = item["gold"]
    return [] if g is None else (g if isinstance(g, list) else [g])

def score(answer, item):
    """correct / wrong / abstain. 자료에 없는 질문은 abstain이 정답."""
    if ABSTAIN in answer or "모르" in answer:
        return "abstain"
    if item["gold"] is None or item.get("kind") == "related":
        return "answered"   # 자료에 없는데 답함: 사람이 읽고 판정
    ok = all(any(t in answer for t in group) for group in item["must"])
    bad = any(t in answer for t in item["must_not"])
    return "correct" if ok and not bad else "wrong"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--retrieval-only", action="store_true")
    ap.add_argument("--threshold", type=float, default=0.55)
    ap.add_argument("--corpus", default="corpus_v2.json")
    ap.add_argument("--evalset", default="evalset_v2.json")
    ap.add_argument("--out", default=None)
    ap.add_argument("--skip-baseline", action="store_true")
    args = ap.parse_args()

    corpus = json.loads((ROOT / args.corpus).read_text())
    evalset = json.loads((ROOT / args.evalset).read_text())
    cache = ROOT / (Path(args.corpus).stem + "_vectors.json")
    if cache.exists() and json.loads(cache.read_text())["n"] == len(corpus):
        vecs = json.loads(cache.read_text())["vecs"]
    else:
        vecs = embed([doc_text(d) for d in corpus])
        cache.write_text(json.dumps({"n": len(corpus), "vecs": vecs}))
    qvecs = embed([it["question"] for it in evalset])
    idx = build_index(corpus)

    base_system = service_system_prompt()
    rows = []
    for it, qv in zip(evalset, qvecs):
        picked, sims = hybrid_retrieve(it["question"], qv, corpus, vecs, idx, args.threshold)
        ranked = [(sims[d["title"]], d) for _, _, d in picked]
        top = [{"title": d["title"], "match": k, "term": t, "score": round(sims[d["title"]], 3)} for k, t, d in picked]
        row = {"id": it["id"], "question": it["question"], "gold": it["gold"], "top3": top,
               "kind": it.get("kind"), "split": it.get("split"),
               "retrieval_hit": bool(golds(it)) and bool(ranked) and ranked[0][1]["title"] in golds(it),
               "retrieval_all_top2": bool(golds(it)) and all(g in [d["title"] for _, d in ranked[:2]] for g in golds(it))}
        if not args.retrieval_only and not args.skip_baseline:
            row["baseline"], row["baseline_sec"] = chat(base_system, GENERAL_CONTEXT + it["question"])
        if not args.retrieval_only:
            if ranked:
                ctx = "\n\n".join(f"[자료 {i + 1}] {doc_text(d)}" for i, (s, d) in enumerate(ranked))
                row["grounded"], row["grounded_sec"] = chat(GROUNDED_SYSTEM, f"[자료]\n{ctx}\n\n[질문]\n{it['question']}")
                row["source"] = {"title": ranked[0][1]["title"], "url": ranked[0][1]["url"], "image": ranked[0][1]["image"]}
            else:
                row["grounded"], row["grounded_sec"] = f"{ABSTAIN}. 자료에서 찾지 못했어요.", 0.0
                row["source"] = None
            if "baseline" in row:
                row["baseline_score"] = score(row["baseline"], it)
            row["grounded_score"] = score(row["grounded"], it)
        rows.append(row)
        print(it["id"], it["question"], top[:1], row.get("baseline_score"), row.get("grounded_score"), flush=True)

    out = ROOT / (args.out or ("retrieval_v2.json" if args.retrieval_only else "results_v2.json"))
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    inc = [r for r in rows if r["gold"] is not None]
    print(f"\n검색 1위 정답률: {sum(r['retrieval_hit'] for r in inc)}/{len(inc)}")
    if not args.retrieval_only:
        for k in [k for k in ("baseline", "grounded") if k + "_score" in rows[0]]:
            c = sum(r[k + "_score"] == "correct" for r in inc)
            w = sum(r[k + "_score"] == "wrong" for r in inc)
            a = sum(r[k + "_score"] == "abstain" for r in inc)
            ooc = [r for r in rows if r["gold"] is None]
            oa = sum(r[k + "_score"] == "abstain" for r in ooc)
            print(f"{k}: 자료 있는 {len(inc)}문항 정답 {c} 오답 {w} 보류 {a} | 자료 없는 {len(ooc)}문항 보류 {oa}")

if __name__ == "__main__":
    main()
