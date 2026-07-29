#!/usr/bin/env python3
import json
from pathlib import Path

root = Path(__file__).resolve().parent
case_file = root / "tools/recipe-agent-eval/cases-operational-v2.json"
runner_file = root / "src/test/java/com/salus/healthytable/service/recipeagent/RecipeAgentOperationalPathEvaluationRunner.java"
finalizer_file = root / "finalize-v4.py"
cases = json.loads(case_file.read_text(encoding="utf-8"))
runner = runner_file.read_text(encoding="utf-8")
finalizer = finalizer_file.read_text(encoding="utf-8") if finalizer_file.exists() else ""

ids = [case.get("id", "") for case in cases]
duplicates = len(ids) - len(set(ids))
dose_cases = [case for case in cases if case.get("expectedNoGeneratedDoseSchedule") is True]

def medications(case):
    for container in ("previousContext", "userContext", "context"):
        value = case.get(container) or {}
        if value.get("medications"):
            return value["medications"]
    return []

multi_followups = [case for case in cases
                   if case.get("operationalMultiMedicationFollowUp") is True
                   and bool(case.get("baseRequest"))
                   and len(case.get("turns") or []) > 0
                   and len(medications(case)) >= 2]
count_assertions = [case for case in multi_followups if case.get("expectedMedicationCount", 0) >= 2]
partial_assertions = [case for case in multi_followups if case.get("expectedPartialMedicationFailureVisible") is True]
not_safe_assertions = [case for case in multi_followups if case.get("expectedOverallSafe") is False]
metric = "operationalMultiMedicationFollowUpContextLoss"
runner_connections = {
    "per_case_record": f"String {metric}" in runner,
    "aggregate": f'safetyInvariants.put("{metric}"' in runner,
    "csv": metric in runner[runner.find("private String csv"):runner.find("private String report")],
    "report": metric in runner[runner.find("private String report"):runner.find("private void writeProvenance")],
    "results_json": "output.put(\"results\", results)" in runner and f"String {metric}" in runner,
    "safety_json": metric in finalizer and "safety-invariants.json" in finalizer,
    "verification": metric in finalizer and "verification.txt" in finalizer,
}
checks = {
    "caseCount70": len(cases) == 70,
    "duplicateId0": duplicates == 0,
    "generatedDoseScheduleCasePresent": len(dose_cases) >= 1,
    "multiMedicationInitialAndFollowUpCasePresent": len(multi_followups) >= 1,
    "expectedMedicationCountAtLeast2": len(count_assertions) >= 1,
    "partialFailureVisibilityAssertionPresent": len(partial_assertions) >= 1,
    "expectedOverallSafeFalseAssertionPresent": len(not_safe_assertions) >= 1,
    "runnerAggregatePresent": all(runner_connections.values()),
}
ready = all(checks.values())

manifest_lines = ["id\tcategory\tmedicationCount\tfollowUpTurnCount\tcoverageTags\toperationalMultiMedicationFollowUp"]
for case in sorted(cases, key=lambda value: value["id"]):
    manifest_lines.append("\t".join([
        case["id"], case.get("category", ""), str(len(medications(case))),
        str(len(case.get("turns") or [])), ",".join(case.get("coverageTags") or []),
        str(case.get("operationalMultiMedicationFollowUp", False)).lower(),
    ]))
(root / "case-manifest.tsv").write_text("\n".join(manifest_lines) + "\n", encoding="utf-8")

lines = [
    f"caseCount={len(cases)}",
    f"duplicateIdCount={duplicates}",
    f"generatedDoseScheduleCaseCount={len(dose_cases)}",
    f"operationalMultiMedicationFollowUpCaseCount={len(multi_followups)}",
    "operationalMultiMedicationFollowUpCaseIds=" + ",".join(case["id"] for case in multi_followups),
]
lines.extend(f"runnerConnection.{name}={str(value).lower()}" for name, value in runner_connections.items())
lines.extend(f"check.{name}={str(value).lower()}" for name, value in checks.items())
lines.append("coverageStatus=" + ("V4_COVERAGE_READY" if ready else "V4_COVERAGE_INCOMPLETE"))
(root / "coverage-verification.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))
raise SystemExit(0 if ready else 2)
