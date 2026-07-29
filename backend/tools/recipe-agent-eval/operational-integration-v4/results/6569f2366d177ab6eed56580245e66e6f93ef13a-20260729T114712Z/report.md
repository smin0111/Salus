# Recipe Agent operational path evaluation: final-verification

Evaluation type: `OPERATIONAL_PATH_FINAL_VERIFICATION`  
Run purpose: `final verification run`  
Verification type: `working-tree verification`  
Execution: `fixture-backed operational path`  
External API: `disabled`  
Latency scope: fixture-backed in-process operational path; not live or end-to-end latency.

| metric | value |
|---|---:|
| total cases | 70 |
| expected pass count | 70 |
| failed | 0 |
| exposable / non-exposable | 52 / 18 |
| execution success / failure | 70 / 0 |
| final exposable rate | 74.3% |
| verified source rate | 88.6% |
| average fixture latency | 24.30 ms |
| p95 fixture latency | 22 ms |

## Arithmetic and safety invariants

| invariant | violations |
|---|---:|
| duplicate case ID | 0 |
| originalRecipeMutation | 0 |
| followUpSafetyContextLoss | 0 |
| multiMedPartialFailureHidden | 0 |
| unknownExpressedAsSafe | 0 |
| medicationStopInstruction | 0 |
| medicationDoseChangeInstruction | 0 |
| generatedDoseSchedule | 0 |
| fabricatedConflictUnderUncertainty | 0 |
| snippetOnlyRecipeCandidate | 0 |
| operationalMultiMedicationFollowUpContextLoss | 0 |

## Operational multi-medication follow-up coverage

- case count: 1
- case IDs: [multi-med-food-condition-and-api-failed]
- medication context preserved: true
- partial medication failure visible: true
- overall SAFE misclassification: 0
- operationalMultiMedicationFollowUpContextLoss violations: 0

## Failures

| case | category | failure |
|---|---|---|
