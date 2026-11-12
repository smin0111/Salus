"""지금 방식(근거 없는 일반 대화)과 근거 방식(bge-m3 검색 + 자료만 보고 답하기)을 같은 질문으로 비교합니다.
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
- 쉬운 한국어 존댓말로 답하고, 이모지와 목록 기호는 쓰지 마세요."""

def doc_text(d):
    alias = f" (다른 이름: {d['query_name']})" if d["query_name"] != d["title"].split(" (")[0] else ""
    return f"{d['title']}{alias}: {d['text']}"

def golds(item):
    g = item["gold"]
    return [] if g is None else (g if isinstance(g, list) else [g])

def score(answer, item):
    """correct / wrong / abstain. 자료에 없는 질문은 abstain이 정답."""
    if ABSTAIN in answer or "모르" in answer:
        return "abstain"
    if item["gold"] is None:
        return "answered"   # 자료에 없는데 답함: 사람이 읽고 판정
    ok = all(any(t in answer for t in group) for group in item["must"])
    bad = any(t in answer for t in item["must_not"])
    return "correct" if ok and not bad else "wrong"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--retrieval-only", action="store_true")
    ap.add_argument("--threshold", type=float, default=0.55)
    args = ap.parse_args()

    corpus = json.loads((ROOT / "corpus.json").read_text())
    evalset = json.loads((ROOT / "evalset.json").read_text())
    cache = ROOT / "corpus_vectors.json"
    if cache.exists() and json.loads(cache.read_text())["n"] == len(corpus):
        vecs = json.loads(cache.read_text())["vecs"]
    else:
        vecs = embed([doc_text(d) for d in corpus])
        cache.write_text(json.dumps({"n": len(corpus), "vecs": vecs}))
    qvecs = embed([it["question"] for it in evalset])

    base_system = service_system_prompt()
    rows = []
    for it, qv in zip(evalset, qvecs):
        ranked = sorted(((cos(qv, v), d) for v, d in zip(vecs, corpus)), key=lambda x: -x[0])[:3]
        top = [{"title": d["title"], "score": round(s, 3)} for s, d in ranked]
        row = {"id": it["id"], "question": it["question"], "gold": it["gold"], "top3": top,
               "retrieval_hit": bool(golds(it)) and ranked[0][1]["title"] in golds(it)}
        if not args.retrieval_only:
            row["baseline"], row["baseline_sec"] = chat(base_system, GENERAL_CONTEXT + it["question"])
            if ranked[0][0] >= args.threshold:
                ctx = "\n\n".join(f"[자료 {i + 1}] {doc_text(d)}" for i, (s, d) in enumerate(ranked[:2]) if s >= args.threshold)
                row["grounded"], row["grounded_sec"] = chat(GROUNDED_SYSTEM, f"[자료]\n{ctx}\n\n[질문]\n{it['question']}")
                row["source"] = {"title": ranked[0][1]["title"], "url": ranked[0][1]["url"], "image": ranked[0][1]["image"]}
            else:
                row["grounded"], row["grounded_sec"] = f"{ABSTAIN}. 자료에서 찾지 못했어요.", 0.0
                row["source"] = None
            row["baseline_score"] = score(row["baseline"], it)
            row["grounded_score"] = score(row["grounded"], it)
        rows.append(row)
        print(it["id"], it["question"], top[0], row.get("baseline_score"), row.get("grounded_score"), flush=True)

    out = ROOT / ("retrieval.json" if args.retrieval_only else "results.json")
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    inc = [r for r in rows if r["gold"] is not None]
    print(f"\n검색 1위 정답률: {sum(r['retrieval_hit'] for r in inc)}/{len(inc)}")
    if not args.retrieval_only:
        for k in ("baseline", "grounded"):
            c = sum(r[k + "_score"] == "correct" for r in inc)
            w = sum(r[k + "_score"] == "wrong" for r in inc)
            a = sum(r[k + "_score"] == "abstain" for r in inc)
            ooc = [r for r in rows if r["gold"] is None]
            oa = sum(r[k + "_score"] == "abstain" for r in ooc)
            print(f"{k}: 자료 있는 {len(inc)}문항 정답 {c} 오답 {w} 보류 {a} | 자료 없는 {len(ooc)}문항 보류 {oa}")

if __name__ == "__main__":
    main()
