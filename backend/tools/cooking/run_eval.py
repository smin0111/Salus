"""요리 상식 평가: 실제 채팅 API(게스트)에 질문을 보내고 답을 기록합니다.

실행: python3 tools/cooking/run_eval.py --base http://localhost:8097 --label baseline --repeat 3
결과: tools/cooking/results/<label>.json
- runs[]: 회차마다 답·경로·시간·자동 채점
- judgement: 사람이 답을 읽고 회차마다 적습니다(correct / partial / wrong / harmful / hedged).
  harmful = risk가 high인 항목에서 위험한 쪽으로 안내한 답, hedged = 모르겠다·자료가 엇갈린다고 밝힌 답.
자동 채점(auto)은 정답 단어가 있는지 보는 하한입니다. 점수는 사람 판정으로 냅니다.
"""
import argparse
import json
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
UNCERTAIN = ("확실하지 않", "잘 모르", "확인되지 않", "근거를 찾지", "자료에 없", "자료가 엇갈", "단정하기 어렵")


def ask(base, message):
    body = json.dumps({"message": message, "history": []}).encode()
    request = urllib.request.Request(base + "/api/chat/message", body, {"Content-Type": "application/json"})
    started = time.time()
    with urllib.request.urlopen(request, timeout=600) as response:
        data = json.load(response)
    return data, time.time() - started


def route(data):
    if data.get("recipe"):
        return "recipe"
    if data.get("ingredientInfo"):
        return "ingredient"
    return "conversation"


def auto_verdict(reply, item):
    if any(marker in reply for marker in UNCERTAIN):
        return "hedged"
    if item["kind"] in ("contested", "unsupported"):
        return "needs_review"
    if any(term in reply for term in item["must_not"]):
        return "must_not_hit"
    for group in item["must"]:
        if not any(term in reply for term in group):
            return "missing_terms"
    return "terms_present"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8097")
    parser.add_argument("--label", required=True)
    parser.add_argument("--split", choices=["dev", "test", "all"], default="all")
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()

    items = [item for item in json.loads((HERE / "evalset.json").read_text(encoding="utf-8"))
             if args.split == "all" or item["split"] == args.split]
    rows = {item["key"]: {"id": item["id"], "key": item["key"], "split": item["split"], "kind": item["kind"],
                          "risk": item["risk"], "paraphrase_of": item["paraphrase_of"], "question": item["question"],
                          "runs": []} for item in items}
    for run in range(1, args.repeat + 1):
        for item in items:
            data, seconds = ask(args.base, item["question"])
            reply = data.get("reply") or ""
            result = {"run": run, "route": route(data), "seconds": round(seconds, 1),
                      "auto": auto_verdict(reply, item), "judgement": None, "note": None, "reply": reply}
            rows[item["key"]]["runs"].append(result)
            print(f'r{run} {item["id"]:>3} {result["route"]:<12} {result["auto"]:<14} {result["seconds"]:>5}s {item["question"]}',
                  flush=True)

    out_dir = HERE / "results"
    out_dir.mkdir(exist_ok=True)
    out = out_dir / f"{args.label}.json"
    out.write_text(json.dumps(list(rows.values()), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    runs = [r for row in rows.values() for r in row["runs"]]
    counts = {}
    for r in runs:
        counts[r["auto"]] = counts.get(r["auto"], 0) + 1
    print("auto", counts, "avg_sec", round(sum(r["seconds"] for r in runs) / max(len(runs), 1), 1), "->", out)


if __name__ == "__main__":
    main()
