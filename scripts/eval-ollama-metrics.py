#!/usr/bin/env python3
"""Salus 평가 run 디렉터리에서 Ollama 성능 지표를 계산한다.

하네스가 이미 저장해 둔 raw envelope(raw/<model>/<case>__iter<n>.envelope.json)의
total_duration / load_duration / prompt_eval_* / eval_* 필드만 읽는다.
프로덕션 코드도, 평가 하네스 코드도 수정하지 않는다. 관측된 값만 쓰고 없으면 null로 둔다.

주의: 하네스는 최종 호출(repair가 있었으면 repair 호출)의 envelope만 남긴다.
따라서 토큰/duration 지표는 최종 호출 기준이고, e2e latency만 repair를 포함한 값이다.

또한 --records 를 주면 Notion 연동용 보강 레코드를 만든다. 하네스가 쓰는 latest-records.json은
필드가 좁아서(Expected Conditions / Validator Codes / Reasons / 토큰 / Repair 없음) latest.json의
results와 envelope 계산값을 합쳐 한 파일로 낸다. 하네스 코드는 건드리지 않는다.

사용법: python3 scripts/eval-ollama-metrics.py <runDir> [--out <파일>] [--records <파일>]
"""
import json
import statistics
import sys
from pathlib import Path


def slug(model):
    return "".join(c if c.isalnum() or c in "._-" else "_" for c in model)


def ns_to_ms(value):
    return None if value is None else round(value / 1e6, 1)


def per_sec(count, duration_ns):
    if not count or not duration_ns:
        return None
    return round(count / (duration_ns / 1e9), 2)


def collect(run_dir: Path):
    summary = json.loads((run_dir / "latest.json").read_text())
    rows = []
    for result in summary["results"]:
        envelope_path = (run_dir / "raw" / slug(result["model"])
                         / f"{result['caseId']}__iter{result['iteration']}.envelope.json")
        envelope = {}
        if envelope_path.exists():
            try:
                envelope = json.loads(envelope_path.read_text())
            except json.JSONDecodeError:
                envelope = {}
        rows.append({
            "runId": result["runId"],
            "model": result["model"],
            "caseId": result["caseId"],
            "suite": result["suite"],
            "iteration": result["iteration"],
            "verdict": result["verdict"],
            "score": result["score"],
            "repairAttempted": result["repairAttempted"],
            "repairPassed": result["repairPassed"],
            "e2eLatencyMs": result["latencyMs"],
            "promptTokens": envelope.get("prompt_eval_count"),
            "completionTokens": envelope.get("eval_count"),
            "loadMs": ns_to_ms(envelope.get("load_duration")),
            "promptEvalMs": ns_to_ms(envelope.get("prompt_eval_duration")),
            "generationMs": ns_to_ms(envelope.get("eval_duration")),
            "ollamaTotalMs": ns_to_ms(envelope.get("total_duration")),
            "promptTokensPerSec": per_sec(envelope.get("prompt_eval_count"),
                                          envelope.get("prompt_eval_duration")),
            "generationTokensPerSec": per_sec(envelope.get("eval_count"),
                                              envelope.get("eval_duration")),
            "doneReason": envelope.get("done_reason"),
            "envelopePresent": bool(envelope),
        })
    return summary, rows


def aggregate(rows):
    models = {}
    for row in rows:
        models.setdefault(row["model"], []).append(row)

    out = {}
    for model, model_rows in models.items():
        def values(key):
            return [r[key] for r in model_rows if r[key] is not None]

        gen = values("generationTokensPerSec")
        prompt = values("promptTokensPerSec")
        out[model] = {
            "runs": len(model_rows),
            "envelopesFound": sum(1 for r in model_rows if r["envelopePresent"]),
            "promptTokensTotal": sum(values("promptTokens")) or None,
            "completionTokensTotal": sum(values("completionTokens")) or None,
            "generationTokensPerSec": {
                "mean": round(statistics.mean(gen), 2) if gen else None,
                "median": round(statistics.median(gen), 2) if gen else None,
                "min": min(gen) if gen else None,
                "max": max(gen) if gen else None,
            },
            "promptTokensPerSec": {
                "mean": round(statistics.mean(prompt), 2) if prompt else None,
                "median": round(statistics.median(prompt), 2) if prompt else None,
            },
            "loadMs": {
                "mean": round(statistics.mean(values("loadMs")), 1) if values("loadMs") else None,
                "max": max(values("loadMs")) if values("loadMs") else None,
            },
            "generationMsMean": round(statistics.mean(values("generationMs")), 1)
            if values("generationMs") else None,
            "promptEvalMsMean": round(statistics.mean(values("promptEvalMs")), 1)
            if values("promptEvalMs") else None,
            "e2eLatencyMsMean": round(statistics.mean(values("e2eLatencyMs")), 1)
            if values("e2eLatencyMs") else None,
        }
    return out


def notion_records(run_dir: Path, rows):
    """latest.json 원본 + envelope 계산값을 합친 평면 레코드. 값은 관측된 것만 넣는다."""
    summary = json.loads((run_dir / "latest.json").read_text())
    by_key = {(r["model"], r["caseId"], r["iteration"]): r for r in rows}
    records = []
    for result in summary["results"]:
        metrics = by_key.get((result["model"], result["caseId"], result["iteration"]), {})
        records.append({
            "runId": result["runId"],
            "runDate": summary["startedAt"],
            "caseId": result["caseId"],
            "suite": result["suite"],
            "model": result["model"],
            "iteration": result["iteration"],
            "input": result["input"],
            "expectedConditions": result["expectedConditions"],
            "ragCondition": result["ragCondition"],
            "firstDraftRawOutput": result["rawOutputFirstDraft"],
            "finalRawOutput": result["rawOutput"],
            "validatorCodes": result["validatorCodes"],
            "validatorReasons": result["validatorReasons"],
            "allergenConflicts": result["allergenConflicts"],
            "failedDimensions": [d["name"] for d in result["dimensions"]
                                 if d["applicable"] and not d["passed"]],
            "problemTypes": [],
            "systemHarnessScore": result["score"],
            "humanQualityScore": "NOT_EVALUATED",
            "verdict": result["verdict"],
            "timeout": result["timeout"],
            "failureCode": result["failureCode"],
            "e2eLatencyMs": result["latencyMs"],
            "promptTokens": result["promptTokens"],
            "completionTokens": result["completionTokens"],
            "doneReason": result["doneReason"],
            "generationTokensPerSec": metrics.get("generationTokensPerSec"),
            "promptTokensPerSec": metrics.get("promptTokensPerSec"),
            "loadMs": metrics.get("loadMs"),
            "promptEvalMs": metrics.get("promptEvalMs"),
            "generationMs": metrics.get("generationMs"),
            "firstDraftValid": result["firstDraftValid"],
            "repairAttempted": result["repairAttempted"],
            "repairPassed": result["repairPassed"],
            "judgeModel": result["judgeModel"],
            "judgeVerdict": result["judgeVerdict"],
            "judgeAgreement": result["judgeAgreement"],
            "rawOutputFile": f"raw/{slug(result['model'])}/{result['caseId']}__iter{result['iteration']}.txt",
        })
    # problemTypes는 하네스가 이미 계산해 둔 값을 그대로 옮긴다.
    flat = {(r["caseId"], r["model"], r["iteration"]): r["problemTypes"]
            for r in json.loads((run_dir / "latest-records.json").read_text())}
    for record in records:
        record["problemTypes"] = flat.get(
            (record["caseId"], record["model"], record["iteration"]), [])
    return records


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    run_dir = Path(sys.argv[1])
    summary, rows = collect(run_dir)
    report = {
        "runId": summary["runId"],
        "startedAt": summary["startedAt"],
        "mode": summary["mode"],
        "note": "지표는 하네스가 저장한 raw envelope에서만 계산했다. repair가 있었던 실행의 "
                "토큰/duration은 최종(repair) 호출 기준이고, e2eLatencyMs만 repair를 포함한다.",
        "perModel": aggregate(rows),
        "perRun": rows,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if "--records" in sys.argv:
        target = Path(sys.argv[sys.argv.index("--records") + 1])
        target.write_text(json.dumps(notion_records(run_dir, rows), ensure_ascii=False, indent=2))
        print(f"wrote {target}")
    if "--out" in sys.argv:
        target = Path(sys.argv[sys.argv.index("--out") + 1])
        target.write_text(text)
        print(f"wrote {target}")
    for model, stats in report["perModel"].items():
        gen = stats["generationTokensPerSec"]
        print(f"{model:20s} runs={stats['runs']:2d} "
              f"gen tok/s mean={gen['mean']} median={gen['median']} "
              f"prompt tok/s mean={stats['promptTokensPerSec']['mean']} "
              f"loadMs mean={stats['loadMs']['mean']}")


if __name__ == "__main__":
    main()
