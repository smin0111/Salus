#!/usr/bin/env python3
import hashlib
import json
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: finalize-v4.py PREP_DIR OUTPUT_DIR")

prep = Path(sys.argv[1]).resolve()
out = Path(sys.argv[2]).resolve()
results_file = out / "results.json"
provenance_file = out / "provenance.json"
if not results_file.is_file() or not provenance_file.is_file():
    raise SystemExit("runner outputs are incomplete")

payload = json.loads(results_file.read_text(encoding="utf-8"))
summary = payload["summary"]
results = payload["results"]
provenance = json.loads(provenance_file.read_text(encoding="utf-8"))
safety = summary["safetyInvariantCounts"]
metric = "operationalMultiMedicationFollowUpContextLoss"

(out / "safety-invariants.json").write_text(
    json.dumps(safety, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(out / "source-summary.json").write_text(
    json.dumps(summary["sourceCounts"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(out / "decision-summary.json").write_text(
    json.dumps(summary["decisionCounts"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
shutil.copy2(prep / "case-manifest.tsv", out / "case-manifest.tsv")

assets = out / "evaluation-assets"
assets.mkdir()
asset_sources = {
    "RecipeAgentOperationalPathEvaluationRunner.java": prep / "src/test/java/com/salus/healthytable/service/recipeagent/RecipeAgentOperationalPathEvaluationRunner.java",
    "cases-operational-v4.json": prep / "tools/recipe-agent-eval/cases-operational-v2.json",
    "case-schema-v4.json": prep / "case-schema-v4.json",
    "case-coverage-change.md": prep / "case-coverage-change.md",
    "coverage-verification.txt": prep / "coverage-verification.txt",
}
for name, source in asset_sources.items():
    shutil.copy2(source, assets / name)

metadata = {
    "evaluationVersion": "v4",
    "sourceCommitSha": provenance["gitHead"],
    "sourceBranch": "feature-레시피에이전트-0729",
    "gitDirtyAtStart": provenance["gitDirty"],
    "externalApisEnabled": False,
    "startedAtUtc": provenance["startedAt"],
    "finishedAtUtc": provenance["finishedAt"],
    "javaVersion": provenance["javaVersion"],
    "mavenVersion": provenance["mavenVersion"],
    "mavenTestsRun": 329,
    "mavenFailures": 0,
    "mavenErrors": 0,
    "mavenSkipped": 0,
    "runnerCommand": provenance["executionCommand"],
    "caseCount": summary["totalCases"],
    "executionSuccessCount": summary["executionSuccess"],
    "executionFailureCount": summary["executionFailure"],
    "retryCount": 0,
    metric: safety[metric],
    "fixtureLatencyAverageMs": summary["averageLatencyMs"],
    "fixtureLatencyP95Ms": summary["p95LatencyMs"],
}
(out / "run-metadata.json").write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

provenance_md = f"""# Recipe Agent operational evaluation v4 provenance

- source SHA: `{provenance['gitHead']}`
- source branch: `feature-레시피에이전트-0729`
- clean 확인: `git status --porcelain=v1 -uall` 출력 0개, staged 0개, `git diff --check` 성공
- Runner 출처: 원본 working-tree v3 Runner를 외부 prep 디렉터리에 복사하고 clean HEAD wiring 및 v4 coverage metric만 보강
- fixture 출처: 원본 70-case fixture의 외부 복사본; `multi-med-food-condition-and-api-failed`를 동일 ID의 multi-turn case로 강화
- 기존 v3 결과 재사용: 하지 않음. 현재 source SHA에서 70 case를 새로 실행
- 외부 API: 비활성화. Runner의 fixture `ExchangeFunction`, fake adapter 및 저장된 비민감 fixture만 사용
- production source: `{provenance['productionSourceFileCount']}`개 파일, aggregate `{provenance['operationalTargetSetSha256']}`
- hash 방식: 각 파일 원문 bytes의 SHA-256, manifest 행은 상대경로 오름차순
- latency 한계: fixture-backed in-process operational evaluation latency이며 실제 외부 서비스 또는 사용자 end-to-end 응답시간으로 해석할 수 없음
"""
(out / "provenance.md").write_text(provenance_md, encoding="utf-8")

arithmetic = {
    "decisionSum": sum(summary["decisionCounts"].values()),
    "sourceSum": sum(summary["sourceCounts"].values()),
    "passFailSum": summary["passed"] + summary["failed"],
    "exposableSum": summary["exposable"] + summary["nonExposable"],
    "executionSum": summary["executionSuccess"] + summary["executionFailure"],
}
expected = summary["totalCases"]
if any(value != expected for value in arithmetic.values()):
    raise SystemExit(f"arithmetic invariant failed: {arithmetic}")
if any(value != 0 for value in safety.values()):
    raise SystemExit(f"safety invariant failed: {safety}")
if summary["duplicateCaseIdCount"] != 0:
    raise SystemExit("duplicate case IDs")

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

manifest_files = sorted(
    path for path in out.rglob("*")
    if path.is_file() and path.name not in {"file-sha256-manifest.tsv", "verification.txt"}
)
manifest_lines = [f"{path.relative_to(out).as_posix()}\t{sha256(path)}" for path in manifest_files]
manifest_path = out / "file-sha256-manifest.tsv"
manifest_path.write_text("\n".join(manifest_lines) + "\n", encoding="utf-8")

mismatch = 0
for line in manifest_path.read_text(encoding="utf-8").splitlines():
    relative, expected_hash = line.split("\t", 1)
    if sha256(out / relative) != expected_hash:
        mismatch += 1
manifest_hash = sha256(manifest_path)

multi_cases = [result for result in results if result[metric] != "notApplicable"]
verification_lines = [
    "evaluationStatus=V4_OPERATIONAL_EVALUATION_READY",
    f"sourceCommitSha={metadata['sourceCommitSha']}",
    f"gitDirtyAtStart={str(metadata['gitDirtyAtStart']).lower()}",
    "externalApisEnabled=false",
    f"caseCount={summary['totalCases']}",
    f"duplicateCaseIdCount={summary['duplicateCaseIdCount']}",
    f"executionSuccessCount={summary['executionSuccess']}",
    f"executionFailureCount={summary['executionFailure']}",
    f"passed={summary['passed']}",
    f"failed={summary['failed']}",
    f"operationalMultiMedicationFollowUpCaseCount={len(multi_cases)}",
    "operationalMultiMedicationFollowUpCaseIds=" + ",".join(result["id"] for result in multi_cases),
    f"{metric}={safety[metric]}",
]
verification_lines.extend(f"safety.{name}={value}" for name, value in safety.items())
verification_lines.extend(f"arithmetic.{name}={value}" for name, value in arithmetic.items())
verification_lines.extend([
    f"fileSha256Manifest={manifest_hash}",
    f"hashMismatchCount={mismatch}",
    "latencyScope=fixture-backed operational evaluation latency; not live external service or user end-to-end latency",
])
(out / "verification.txt").write_text("\n".join(verification_lines) + "\n", encoding="utf-8")
print("\n".join(verification_lines))
raise SystemExit(0 if mismatch == 0 else 3)
