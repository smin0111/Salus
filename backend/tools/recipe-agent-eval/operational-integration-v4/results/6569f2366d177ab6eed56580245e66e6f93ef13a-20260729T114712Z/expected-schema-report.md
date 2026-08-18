# Operational evaluation v3 expected schema

Unknown `expected*` and top-level `must*` fields are `REJECTED_AT_LOAD`. Semantic migration is reported only when the legacy boolean and its canonical replacement are both asserted `true` and the canonical field executes a direct assertion.

| legacy field | status | canonical replacement | 의미 보존 규칙 | 실제 assertion 위치 | 관련 평가 케이스 수 | 관련 단위 테스트 |
|---|---|---|---|---|---:|---|
| expectedAllergyPriorityPreserved | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedAlternative | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedAuthorMatched | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedAutoBlock | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedBaseCandidateCount | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 3 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedBlockingReason | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedCandidateCreated | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 9 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedCaptionDeclared | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedConflicts | SUPPORTED_AND_ASSERTED | expectedConflicts | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 6 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedContextStatus | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 7 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedCreatorMatchStatus | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 3 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedDecision | SUPPORTED_AND_ASSERTED | expectedDecision | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 63 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedDecisionType | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 18 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedDecisionTypeWhenTrustedConflict | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedDecisionTypeWhenUnknown | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedDeduplicated | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedDescriptionEvidence | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedDishMatched | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedEvidenceScore | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedEvidenceScoreHigh | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedExpiringSoonIngredients | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 3 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedExposable | SUPPORTED_AND_ASSERTED | expectedExposable | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 63 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedExternalLinkVerified | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedFetchSuccess | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedFinalUserExposable | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 6 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedForbiddenIngredients | SUPPORTED_AND_ASSERTED | expectedForbiddenIngredients | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 63 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedFridgeItemsUsed | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 4 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedFridgeItemsUsedOrSubstitution | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedHealthPolicyApplied | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedHealthPolicyAppliedByPolicyEngine | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedIngredientIdentified | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedInstructionCompleteness | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedInteractionStatus | SUPPORTED_AND_ASSERTED | expectedInteractionStatus | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 13 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedJsonLdExtracted | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 3 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedLinkExcluded | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedMedicationConflictOverridesFridgeConvenience | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedMedicationConflictPreserved | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedMedicationNormalization | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedMedicationPolicyApplied | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedMedicationSourceShown | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedMedicationStatus | SUPPORTED_AND_ASSERTED | expectedMedicationStatus | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 63 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedMetadataSuccess | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedModifications | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedNoGeneratedDoseSchedule | SUPPORTED_AND_ASSERTED | expectedNoGeneratedDoseSchedule | 구조화된 복약 판단과 최종 응답의 구체적 복용 시간표를 공식 medication evidence와 대조한다. | observeSafety + evaluateCanonicalSafetyExpectations | 1 | RecipeAgentOperationalEvaluationReliabilityTest.canonicalSafetyExpectationsExerciseStructuredStateAndFinalResponse |
| expectedNoStopMedicationInstruction | DEPRECATED_WITH_SEMANTIC_MIGRATION_COMPLETE | expectedNoMedicationStopInstruction | 구조화된 복약 판단과 최종 응답 모두에 복용 중단 또는 용량 변경 지시가 없어야 한다. | observeSafety + evaluateCanonicalSafetyExpectations | 1 | RecipeAgentOperationalEvaluationReliabilityTest.canonicalSafetyExpectationsExerciseStructuredStateAndFinalResponse |
| expectedNormalizationStatus | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedNotClaimedAsOfficialCreatorRecipe | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedNotMergedAcrossCreators | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedNotSafeClaim | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedOfficialSourceFallback | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedOriginalContains | SUPPORTED_AND_ASSERTED | expectedOriginalContains | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedPersonalizedExcludes | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedPopularityScoreLow | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedPopularityScorePresent | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedPurchaseItems | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedRecipeIngredientMatchingRequired | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedRemovedIngredients | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 3 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedRequiredChanges | SUPPORTED_AND_ASSERTED | expectedRequiredChanges | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 63 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedRequiredNotices | SUPPORTED_AND_ASSERTED | expectedRequiredNotices | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 63 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedRxNormNormalizationOnly | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedRxNormNotUsedAsInteractionDatabase | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedSearchUsesExpiringIngredient | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedSourceStatus | SUPPORTED_AND_ASSERTED | expectedSourceStatus | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | 63 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedSourceType | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 3 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedTranscriptStatus | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedUnsupportedFallback | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 2 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedWebSourceStillEvaluated | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedYoutubeSearchSuccess | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| expectedYoutubeStatus | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 1 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| mustIncludeExplanation | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | 7 | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |
| mustNotGenerateFakeConflictWhenUnknown | DEPRECATED_WITH_SEMANTIC_MIGRATION_COMPLETE | expectedNoFakeConflictWhenUnknown | 불확실 medication 상태에서 confirmed conflict 구조나 확정 충돌 문구를 만들지 않아야 한다. | observeSafety + evaluateCanonicalSafetyExpectations | 1 | RecipeAgentOperationalEvaluationReliabilityTest.canonicalSafetyExpectationsExerciseStructuredStateAndFinalResponse |
| mustNotUseSnippetAsRecipeEvidence | DEPRECATED_WITH_SEMANTIC_MIGRATION_COMPLETE | expectedSnippetNotUsedAsRecipeEvidence | 검색 snippet sentinel이 RecipeCandidate, source evidence, RecipeCard 또는 최종 응답에 없어야 하고 raw parser가 검증된 조리 단계를 제공해야 한다. | observeSafety + evaluateCanonicalSafetyExpectations | 1 | RecipeAgentOperationalEvaluationReliabilityTest.canonicalSafetyExpectationsExerciseStructuredStateAndFinalResponse |

## Canonical v3 fields

| field | status | related case count |
|---|---|---:|
| expectedConflicts | SUPPORTED_AND_ASSERTED | 13 |
| expectedDecision | SUPPORTED_AND_ASSERTED | 70 |
| expectedExposable | SUPPORTED_AND_ASSERTED | 70 |
| expectedFollowUpSafetyContextPreserved | SUPPORTED_AND_ASSERTED | 1 |
| expectedFollowUpState | SUPPORTED_AND_ASSERTED | 70 |
| expectedForbiddenIngredients | SUPPORTED_AND_ASSERTED | 70 |
| expectedInteractionStatus | SUPPORTED_AND_ASSERTED | 13 |
| expectedMedicationCount | SUPPORTED_AND_ASSERTED | 1 |
| expectedMedicationNamesPreserved | SUPPORTED_AND_ASSERTED | 1 |
| expectedMedicationResultCount | SUPPORTED_AND_ASSERTED | 6 |
| expectedMedicationStatus | SUPPORTED_AND_ASSERTED | 70 |
| expectedMedicationSummary | SUPPORTED_AND_ASSERTED | 6 |
| expectedNoFakeConflictWhenUnknown | SUPPORTED_AND_ASSERTED | 1 |
| expectedNoGeneratedDoseSchedule | SUPPORTED_AND_ASSERTED | 2 |
| expectedNoMedicationDoseChangeInstruction | SUPPORTED_AND_ASSERTED | 1 |
| expectedNoMedicationStopInstruction | SUPPORTED_AND_ASSERTED | 2 |
| expectedOriginalContains | SUPPORTED_AND_ASSERTED | 3 |
| expectedOriginalRecipeState | SUPPORTED_AND_ASSERTED | 70 |
| expectedOverallSafe | SUPPORTED_AND_ASSERTED | 1 |
| expectedPartialMedicationFailureVisible | SUPPORTED_AND_ASSERTED | 1 |
| expectedPerDrugInteractionStatuses | SUPPORTED_AND_ASSERTED | 6 |
| expectedRequiredChanges | SUPPORTED_AND_ASSERTED | 70 |
| expectedRequiredNotices | SUPPORTED_AND_ASSERTED | 70 |
| expectedSnippetNotUsedAsRecipeEvidence | SUPPORTED_AND_ASSERTED | 1 |
| expectedSourceStatus | SUPPORTED_AND_ASSERTED | 70 |
