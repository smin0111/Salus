# Ingredient candidate triage — 3.5단계

2026-09-19. 코드 수정 전 확정한 24종/30회 검토 기록. 이 fixture는 제품 Gold가 아니라 Registry/semantic normalization 회귀 계약이다. 제품 원문과 기존 reviewedCandidates는 그대로 유지하고, 정규화 근거가 있는 Evidence 기대값만 추가한다. CERTAIN은 입력 명칭과 Registry 관계의 확신이며 임상적 위험·안전·표시 의무 판정이 아니다.

## 후보 전수 분류

| 표현 | 발견 제품 | 분류 | Normalized Allergen | Relation | Confidence | 처리 | 근거/비고 |
|---|---|---|---|---|---|---|---|
| 원유 | S001, S002, S003 | CERTAIN_ALIAS | MILK | DIRECT_NAME | CERTAIN | RESOLVED_CERTAIN | 유제품 원료의 직접 명칭. 원유 자체의 우유 정체성만 정규화. [근거1](https://inspection.canada.ca/en/about-cfia/acts-and-regulations/list-acts-and-regulations/documents-incorporated-reference/canadian-standards-identity-volume-1) |
| 혼합탈지분유 | S003 | CERTAIN_DERIVED | MILK | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 탈지분유를 포함하는 혼합분유 명칭. 혼합물 전체 성분은 추론하지 않음. [근거1](https://www.foodsafetykorea.go.kr/upload/20181228/20181228041605_1545981365016.pdf) [근거2](https://inspection.canada.ca/en/about-cfia/acts-and-regulations/list-acts-and-regulations/documents-incorporated-reference/canadian-standards-identity-volume-1) |
| 소맥분 | S007 | CERTAIN_DERIVED | WHEAT | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 소맥분은 밀가루의 동의 명칭. [근거1](https://www.mafra.go.kr/bbs/mafra/71/251740/download.do) |
| 밀: 미국산 | S007, S008, S009, S011 | ANNOTATED_KNOWN_INGREDIENT | WHEAT | DIRECT_NAME | CERTAIN | NORMALIZED_ANNOTATION | S Reference의 명칭: 원산지 구조. 밀로 조회하고 미국산은 주석 보존.  |
| 건새우분말 | S007 | CERTAIN_DERIVED | SHRIMP | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 건새우의 분말이라는 일반 원료명이며 S007에 새우 출처 자식도 명시.  |
| 새우: 중국산 | S007 | ANNOTATED_KNOWN_INGREDIENT | SHRIMP | DIRECT_NAME | CERTAIN | NORMALIZED_ANNOTATION | 새우로 조회하고 중국산은 주석 보존.  |
| 새우추출물분말 | S007 | CERTAIN_DERIVED | SHRIMP | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 새우 추출물의 분말이라는 명시 원료명. S007에도 새우 자식 명시.  |
| 새우: 미국산 | S007 | ANNOTATED_KNOWN_INGREDIENT | SHRIMP | DIRECT_NAME | CERTAIN | NORMALIZED_ANNOTATION | 새우로 조회하고 미국산은 주석 보존.  |
| 새우풍미유 | S007 | LEXICAL_HINT | SHRIMP | LEXICAL_HINT | POSSIBLE | RESOLVED_POSSIBLE | 풍미 명칭만으로 실제 새우 원료 함유를 확정하지 않음.  |
| 전지분유 | S008 | CERTAIN_DERIVED | MILK | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 전유를 건조한 분유 원료. [근거1](https://inspection.canada.ca/en/about-cfia/acts-and-regulations/list-acts-and-regulations/documents-incorporated-reference/canadian-standards-identity-volume-1) |
| 돼지 | S008 | CERTAIN_ALIAS | PORK | DIRECT_NAME | CERTAIN | RESOLVED_CERTAIN | S008 젤라틴 아래 독립 출처 노드만 매칭. 젤라틴 자체는 미정.  |
| 전란액 | S008 | CERTAIN_DERIVED | EGG | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 요청의 전란 원료 매핑과 기존 계란 정책 적용. EGG_GROUP으로 확장하지 않음.  |
| 카제인나트륨 | S008 | CERTAIN_DERIVED | MILK | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 우유 카제인에서 제조하는 카제인염 원료. [근거1](https://inspection.canada.ca/en/about-cfia/acts-and-regulations/list-acts-and-regulations/documents-incorporated-reference/canadian-standards-identity-volume-1) |
| 유당 | S008, S011 | NEEDS_REVIEW | - | - | - | UNRESOLVED_AMBIGUOUS | 현재 Relation은 파생을 CERTAIN으로 고정한다. 유당의 별도 confidence 정책은 보류.  |
| 버터향 | S009 | LEXICAL_HINT | MILK | LEXICAL_HINT | POSSIBLE | RESOLVED_POSSIBLE | 향 명칭은 실물 버터 함유 증거가 아님.  |
| 산성아황산나트륨 | S009 | CERTAIN_ALIAS | SULFITE | DIRECT_NAME | CERTAIN | RESOLVED_CERTAIN | 아황산염 화학종의 직접 명칭. 농도/규제/위험 판정은 하지 않음. [근거1](https://www.foodsafetykorea.go.kr/upload/residue/20200120015334_1579496014158.pdf) |
| 유청 | S011 | CERTAIN_DERIVED | MILK | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 유제품 제조에서 분리한 유청 원료. [근거1](https://inspection.canada.ca/en/about-cfia/acts-and-regulations/list-acts-and-regulations/documents-incorporated-reference/canadian-standards-identity-volume-1) |
| 레시틴 | S011 | SOURCE_UNRESOLVED | - | - | - | UNRESOLVED_AMBIGUOUS | 출처가 여러 가지라 대두로 확정 불가. Declaration을 참조하지 않음. [근거1](https://www.ams.usda.gov/sites/default/files/media/2023Technical_Report_Lecithin_deoiled_Handling.pdf) |
| 젤라틴 | S014 | SOURCE_UNRESOLVED | - | - | - | UNRESOLVED_AMBIGUOUS | S014에 동물종 자식 없음. 별도 돼지고기 Declaration으로 역추론하지 않음.  |
| 사골엑기스 | S017 | SOURCE_UNRESOLVED | - | - | - | UNRESOLVED_AMBIGUOUS | S017에 동물종 자식 없음. 별도 쇠고기 노드는 이 원료의 출처 증거가 아님.  |
| 유크림 | S017 | CERTAIN_DERIVED | MILK | DERIVED_FROM | CERTAIN | RESOLVED_CERTAIN | 우유에서 분리한 크림 원료. [근거1](https://inspection.canada.ca/en/about-cfia/acts-and-regulations/list-acts-and-regulations/documents-incorporated-reference/canadian-standards-identity-volume-1) |
| 오뚜기참치간장분말 | S017 | NEEDS_REVIEW | - | - | - | UNRESOLVED_AMBIGUOUS | 브랜드 복합원료의 구성 미상. 간장/참치 substring 추론 금지.  |
| 쇠고기브이용 | S017 | NEEDS_REVIEW | - | - | - | UNRESOLVED_AMBIGUOUS | 복합원료 명칭의 세부 정체성과 구성이 확인되지 않아 보류.  |
| 비프맛양념 | S017 | LEXICAL_HINT | BEEF | LEXICAL_HINT | POSSIBLE | RESOLVED_POSSIBLE | 맛 명칭은 실제 쇠고기 원료 함유를 확정하지 않음.  |

## 판단 근거 범위

외부 자료는 원료의 정체성 확인에만 사용했다. CFIA의 우유·분유·크림·유청·카제인염 정의, 농림축산식품부의 소맥분(밀가루) 명칭, 식약처의 산성아황산나트륨 규격과 혼합분유 분류, USDA의 레시틴 출처 자료를 참고했다. 한국어 exact alias와 relation 선택은 프로젝트 정책이다. 과거 식약처 통계의 혼합분유 분류는 원료 의미 참고이며 현행 규제 근거로 사용하지 않는다. 외부 링크가 없는 행은 요청 정의와 기존 Registry semantics 및 S Reference 독립 원재료 노드에 근거한 보수적 검토다.

## 변경 전 기준

Ingredient 구조 19/19, coverage 11/19 (57.9%), Evidence 16 (CERTAIN 15, POSSIBLE 1), 미해결 30회/24종. S020 REFERENCE_VERSION_CONFLICT 제외. 원본 후보를 삭제하지 않고 현재 semantic lookup 결과로 미해결을 재계산한다.

## 적용 계약과 의도적 동작 변경

- Registry의 기존 스키마를 사용한다. 직접 명칭은 declarationAliases, 파생은 registryDerived, 낮은 확신의 힌트는 lexicalHints에만 추가했다. 기존 프로필 Matcher용 aliases/derived 목록은 변경하지 않았다.
- 원유·돼지·산성아황산나트륨은 직접 별칭이므로 공용 Registry를 사용하는 Declaration/Cross-contact에서도 직접 명칭으로 인식한다. 24종 후보 회귀에서 이 범위와 파생/힌트/원산지 표현의 비승격을 함께 검증한다.
- 산성아황산나트륨은 아황산염의 직접 화학 명칭으로 DIRECT_NAME이다. 임상 위험·표시 기준 농도·법적 의무는 모델링하지 않는다.
- IngredientNode의 구조 normalizedName/path를 유지하고 semanticName()을 추가했다. 후자는 raw 명칭, normalizedName, annotations를 제공한다. 원문 node.rawText와 전체 Evidence.rawText도 보존한다.
- 지원 콜론 문법은 Reference의 원재료명: 원산지이며 미국산/중국산/말레이시아산/필리핀산/외국산을 허용한다. 원산지 문자열은 YAML alias가 아니다. 역순, 임의 설명, 다중 콜론, 전각 콜론, 부정, 원산지 뒤 비율은 그대로 남긴다.
- 기존 후행 숫자 % 정리를 재사용한다. 콜론 우측의 원산지 비율을 임의로 지우지 않는다. 일반 설명은 Tree의 구조 자식으로 보존할 뿐 자연어 의미를 추론하지 않는다.
- flavor 부모의 POSSIBLE과 자식의 CERTAIN은 별도 path로 함께 남는다. 레시틴/젤라틴의 독립 자식 대두/돼지는 그 자식만 인식하며 부모 출처를 역확정하지 않는다.
- 실제 Reference의 raw/source/roots/nested paths/reviewedCandidates 30회와 기존 Evidence 16개를 보존했다. 위 전수 검토의 해결된 23회에 해당하는 expectedEvidence만 명시적으로 추가했다. 제품별 예외나 production 출력에서 생성한 정답은 없다.

## 재측정 결과

| 지표 | 3단계 Before | 3.5단계 After | 변화 |
|---|---|---|---|
| Ingredient 구조 | 19/19 | 19/19 | 유지 |
| Evidence coverage | 11/19 (57.9%) | 13/19 (68.4%) | +2제품, +10.5%p |
| Evidence | 16 | 39 | +23 |
| CERTAIN | 15 | 35 | +20 |
| LIKELY | 0 | 0 | 유지 |
| POSSIBLE | 1 | 4 | +3 |
| DIRECT_NAME | 4 | 15 | +11 |
| DERIVED_FROM | 11 | 20 | +9 |
| LEXICAL_HINT | 1 | 4 | +3 |
| 미해결 발생 | 30 | 7 | -23 (76.7% 감소) |
| 미해결 고유 | 24 | 6 | -18 (75.0% 감소) |

Coverage는 POSSIBLE을 포함하는 원재료 근거 보유 제품 비율이며 완전 검출률 또는 안전 coverage가 아니다.
동일 알레르겐이라도 부모 파생 명칭과 자식 직접 명칭은 서로 다른 path의 Evidence로 유지되므로 39개는 39개의 독립 알레르겐/제품을 의미하지 않는다.
Relation별 제품 수는 DIRECT_NAME 10, DERIVED_FROM 9, LEXICAL_HINT 4이며 중복된다.

| 처리 | 고유 표현 | 발생 |
|---|---:|---:|
| RESOLVED_CERTAIN | 12 | 14 |
| RESOLVED_POSSIBLE | 3 | 3 |
| NORMALIZED_ANNOTATION | 3 | 6 |
| UNRESOLVED_AMBIGUOUS | 6 | 7 |
| NON_ALLERGEN_GENERIC | 0 | 0 |

남은 목록: 유당(S008/S011), 레시틴(S011), 젤라틴(S014), 사골엑기스·오뚜기참치간장분말·쇠고기브이용(S017).
NON_ALLERGEN_GENERIC 0은 이 24종 후보 안의 결과다. 일반 설탕/소금/정제수는 원래 후보 분모에 포함하지 않았다.
S020의 REFERENCE_VERSION_CONFLICT, 원본 두 버전, raw=null, 평가 분모 제외는 그대로다.

실측 보고서는 테스트 실행 후 `backend/target/ingredient-reference-report.txt`,
`backend/target/ingredient-candidate-report.txt`에 생성된다. Candidate 회귀는 별도 합성/검토 계약이며 제품 Gold가 아니다.

## 발견된 문제

| 분류 | 표현 | 처리/남은 한계 |
|---|---|---|
| REGISTRY_MISSING | 원유·혼합탈지분유·소맥분·건새우분말·새우추출물분말·전지분유·돼지·전란액·카제인나트륨·산성아황산나트륨·유청·유크림 | 정확한 별칭/파생 12종 보강 |
| ANNOTATION_NORMALIZATION_GAP | 밀: 미국산, 새우: 중국산/미국산 | 주석과 의미 명칭 분리, 원문 보존 |
| RELATION_MODEL_GAP | 유당 | 현재 파생 관계는 CERTAIN 고정. 임의 LIKELY 정책 도입 없이 NEEDS_REVIEW |
| AMBIGUOUS_SOURCE | 레시틴·젤라틴·사골엑기스 | 원료 출처 미상 유지 |
| LEXICAL_HINT_ONLY | 버터향·새우풍미유·비프맛양념 | POSSIBLE만 생성 |
| FALSE_POSITIVE_RISK | 오뚜기참치간장분말·쇠고기브이용, 짧은 명칭 substring | 복합원료 보류, 부분 문자열 fallback 금지 |
| REFERENCE_VERSION_CONFLICT | S020 | 원본 충돌 유지, 분모 제외 |

다음 단계는 Evidence Resolver 설계다. 이번 단계에서는 Evidence Resolver, AllergenFact, Regulatory Coverage Resolver, User Profile Matching, Safety Decision, OCR/API, LLM, frontend, DB 변경을 구현하지 않았다. commit/push/PR도 하지 않았다.

## 검증 기록 — 2026-09-19

이번 단계 변경 파일(모두 저장소 상대 경로):

```text
backend/src/main/resources/allergens/ko-allergens.yaml
backend/src/main/java/com/salus/healthytable/service/allergen/IngredientSemanticName.java
backend/src/main/java/com/salus/healthytable/service/allergen/IngredientNameNormalizer.java
backend/src/main/java/com/salus/healthytable/service/allergen/IngredientNode.java
backend/src/main/java/com/salus/healthytable/service/allergen/IngredientTreeParser.java
backend/src/main/java/com/salus/healthytable/service/allergen/IngredientEvidenceExtractor.java
backend/src/test/resources/allergens/ingredient-candidate-regression.json
backend/src/test/resources/allergens/ingredient-reference.json
backend/src/test/java/com/salus/healthytable/service/allergen/IngredientCandidateRegressionTest.java
backend/src/test/java/com/salus/healthytable/service/allergen/IngredientNameNormalizerTest.java
backend/src/test/java/com/salus/healthytable/service/allergen/IngredientEvidenceExtractorTest.java
backend/src/test/java/com/salus/healthytable/service/allergen/IngredientReferenceTest.java
backend/src/test/java/com/salus/healthytable/service/allergen/IngredientReferenceValidation.java
backend/src/test/java/com/salus/healthytable/service/allergen/IngredientReferenceValidationTest.java
docs/ingredient-candidate-triage.md
docs/allergen-evidence-pipeline.md
docs/ai/SAFETY_RULES.md
docs/ai/PROJECT_CONTEXT.md
```

Java 17에서 다음을 실행했다(backend 기준).

```bash
mvn -q -Dtest='IngredientNameNormalizerTest,IngredientCandidateRegressionTest,IngredientReferenceTest,IngredientReferenceValidationTest,IngredientEvidenceExtractorTest,IngredientTreeParserTest' test
mvn -q -Dtest='Allergen*Test,DeclarationReference*Test,CrossContactReference*Test,ChatSafetyContextServiceTest,ChatServiceSafetyTest' test
mvn -q -Dtest=IngredientCandidateRegressionTest test
mvn -q clean test
```

전체 **834개, 실패 0, 오류 0, skip 1**. 기존 RecipeAccuracyLiveTest의 live LLM 테스트 1개가 비활성 상태다.
추가 47개는 Candidate 26개(24종 개별/모든 원본 발생 경로, 집계, source 독립성)와 Normalizer 21개다.
원산지 3종, 잘못된 설명·substring 14종, 비율/공백/불변 주석, 향 부모+실제 원료 자식 3종을 검증했다.
기존 합성 lexical 25/25, 새 candidate 24/24, Declaration 15/15, Cross-contact 10/10 및 unsupported 0,
Ingredient 구조 19/19를 유지했다. 메밀/땅콩/밀크초콜릿/굴비/완두콩/강낭콩/땅콩호박의 기존 오탐 방어도 통과했다.

`git diff --check`와 신규/untracked 수정 파일 17개의 `git diff --no-index --check /dev/null <file>` 검사 통과.
변경 전 snapshot 대조: 기존 Declaration/Cross-contact/lexical 등 다른 allergen fixture는 byte 동일,
원본 Reference의 raw/source/구조/reviewedCandidates와 S020은 동일, 기존 Evidence 16개를 보존하고 23개만 추가했다.
원본 workbook SHA-256 검증도 기존 Reference 테스트에서 통과했다. 기존 프로필 Matcher용 aliases/derived 목록은 동일하다.
새 직접 명칭 3종이 다른 라벨 parser에서 인식되는 의도적 변경은 candidate 테스트로 검증했다.
DB/API 통합, frontend 빌드, OCR, 실물 라벨 검증, live LLM은 실행하지 않았다.
