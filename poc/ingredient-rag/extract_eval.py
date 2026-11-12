"""LLM 없이 자료 원문 문장만 골라 답하는 방식(추출)을 평가합니다. Codex 설계 검토 1회차 반영:
- 검색 후보와 '답의 근거로 채택'을 나눕니다. 채택: 제목이 같음(name) / 넘겨주기(related, 같다고 말하지 않음) /
  본문이 그 이름을 직접 부르는 문장("광어라고도", "흔히 조기라고", "노가리라 부른다")이 있을 때(text_named).
  본문에 이름이 나오기만 하거나(민어과) 임베딩만 비슷하면 답하지 않습니다.
- 두 재료 차이 질문은 재료마다 따로 원문 문장을 보여 줍니다(LLM이 섞거나 바꿀 수 없음)."""
import json, re, sys
from pathlib import Path
sys.argv = [sys.argv[0]]
import importlib.util
spec = importlib.util.spec_from_file_location("rev", Path(__file__).with_name("run_eval_v2.py"))
rev = importlib.util.module_from_spec(spec); spec.loader.exec_module(rev)

ROOT = Path(__file__).resolve().parent
corpus = json.loads((ROOT / "corpus_v2.json").read_text())
evalset = json.loads((ROOT / "evalset_v2.json").read_text())
vecs = json.loads((ROOT / "corpus_v2_vectors.json").read_text())["vecs"]
idx = rev.build_index(corpus)

def sentences(text):
    parts = re.split(r"(?<=[다요])\.\s+|\n+", text.strip())
    return [p.strip().rstrip(".") + "." for p in parts if len(p.strip()) > 3]

def naming_sentence(term, text):
    t = re.escape(term)
    pat = re.compile(rf"(흔히\s*{t}|{t}\s*(?:\([^)]*\))?\s*(?:이)?라(?:고도|고\s*한다|고\s*부른|\s*부른|고\s*불리|고\s*하며|는\s*이름)"
                     rf"|{t}\s*(?:\([^)]*\))?\s*(?:으)?로도\s*(?:알려|불)|또는\s*{t}|{t}\s*또는)")
    return next((s for s in sentences(text) if pat.search(s)), None)

sent_cache = ROOT / "sentence_vectors.json"
all_sents = sorted({s for d in corpus for s in sentences(d["text"])})
if sent_cache.exists() and json.loads(sent_cache.read_text())["n"] == len(all_sents):
    svec = dict(zip(all_sents, json.loads(sent_cache.read_text())["vecs"]))
else:
    vs = []
    for i in range(0, len(all_sents), 64):
        vs += rev.embed(all_sents[i:i + 64])
    sent_cache.write_text(json.dumps({"n": len(all_sents), "vecs": vs}))
    svec = dict(zip(all_sents, vs))

def answer(question, qv):
    picked, _ = rev.hybrid_retrieve(question, qv, corpus, vecs, idx, 0.58)
    parts, adopted = [], []
    for kind, term, d in picked:
        ss = sentences(d["text"])
        if not ss:
            continue
        # 첫 문장(정의) + 나머지 중 질문과 가장 비슷한 문장 1개. 모두 원문 그대로입니다.
        best_rest = max(ss[1:], key=lambda s: rev.cos(qv, svec[s])) if len(ss) > 1 else None
        if kind == "name":
            parts.append(" ".join([ss[0]] + ([best_rest] if best_rest else []))); adopted.append((kind, d["title"]))
        elif kind == "related":
            ns = naming_sentence(term, d["text"])
            if ns:   # 본문이 그 이름을 직접 부름: 그 문장을 근거로 답합니다(노가리, 새송이).
                parts.append(" ".join([ns] + ([ss[0]] if ss[0] != ns else []))); adopted.append(("related_named", d["title"]))
            else:
                parts.append(f"‘{term}’은(는) 위키백과에서 ‘{d['title']}’ 문서로 연결돼요. 같은 것인지는 자료에 적혀 있지 않아요. "
                             + " ".join([ss[0]] + ([best_rest] if best_rest else [])))
                adopted.append((kind, d["title"]))
        elif kind == "text":
            ns = naming_sentence(term, d["text"])
            if ns:
                chosen = [ns] + ([ss[0]] if ss[0] != ns else [])
                parts.append(" ".join(chosen)); adopted.append(("text_named", d["title"]))
        # embedding: 답의 근거로 채택하지 않음
    if not parts:
        return rev.ABSTAIN + ". 자료에서 찾지 못했어요.", adopted
    return "\n".join(parts), adopted

qvecs = rev.embed([it["question"] for it in evalset])
rows = []
for it, qv in zip(evalset, qvecs):
    ans, adopted = answer(it["question"], qv)
    rows.append({"id": it["id"], "split": it["split"], "kind": it["kind"], "question": it["question"], "gold": it["gold"],
                 "answer": ans, "adopted": adopted, "score": rev.score(ans, it)})
(ROOT / "results_extract.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1))
for split in ("dev", "test"):
    s = [r for r in rows if r["split"] == split]
    for kind in ("in", "confuse", "related", "ooc"):
        k = [r for r in s if r["kind"] == kind]
        from collections import Counter
        print(split, kind, len(k), dict(Counter(r["score"] for r in k)))
