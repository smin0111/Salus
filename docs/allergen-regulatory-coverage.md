# KR Regulatory Declaration Coverage — 5단계

## Purpose

`DeclarationCoverageResolver.assess(RegulatoryProductContext, allergenId)`는 해당 원료가 존재한다고
가정했을 때 제품·적용 시점의 별도 알레르기 표시 coverage만 반환한다.
기존 Evidence → AllergenFact 흐름과 독립이며 DeclarationState/AllergenFact/사용자 Context를 입력받지 않는다.
현재 구현은 KR 버전 설정을 사용한 내부 모듈이고 API/DB/화면에는 연결하지 않았다.

## Legal source

2026-09-20 공식 자료를 확인했다.

- [국가법령정보센터 시행규칙 본문](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=267855&chrClsCd=010202&urlMode=lsInfoP&efYd=20260101&ancYnChk=0):
  「식품 등의 표시ㆍ광고에 관한 법률 시행규칙」, 총리령 제2004호(2024-12-30 개정), 2026-01-01 시행본.
- [별표 2 소비자 안전을 위한 표시사항](https://www.law.go.kr/LSW/flDownload.do?bylClsCd=110201&flSeq=156389449):
  I.1의 알레르기 범주, 원료/추출 성분/이를 포함한 식품의 표시 대상, 별도 표시 방법과 예외,
  첨가한 아황산류의 최종 SO2 조건을 확인했다.
- [식약처공고 제2026-386호 입법예고](https://www.mfds.go.kr/brd/m_209/view.do?seq=44285):
  2026-08-07 등록된 일부개정령안. 4종 추가 제안이며 2026-09-20 검토 시점에서 현행 규칙으로 취급하지 않는다.

별표의 다른 표시제도, 법령 전체의 적용 제외/경과조치/위반 여부를 평가하는 법률 엔진은 아니다.
입력 applicableDate는 호출자가 해당 제품/라벨의 적용 시점임을 확인한 날짜여야 한다.
각 법령 전환기의 제조·수입·선적·유통 유예를 자동 해석하지 않는다.

## Versioned Rule Sets

별도 `backend/src/main/resources/regulations/kr-allergen-labeling.yaml`에 저장한다.
lexical `ko-allergens.yaml`은 변경하지 않았다.

| ID | Lifecycle | effectiveFrom | effectiveTo | 기록 범위 |
|---|---|---|---|---|
| KR_EFFECTIVE_2026_01_01 | EFFECTIVE | 2026-01-01 | null | 현행 시행본의 19범주 |
| KR_PROPOSED_2026_08_07 | PROPOSED | null | null | 추가 제안 4종만 기록한 delta |

2026-01-01은 이 구현이 지원하는 시행본의 시작일이며 표시제도 최초 도입일이 아니다.
이전 적용일의 규칙을 아직 등록하지 않았으므로 과거 날짜는 RULESET_NOT_FOUND다.
기간 양 끝은 포함한다. 종료일 null은 고정 설정에서 후속 종료 버전이 미등록임을 뜻하며
미래 법령을 자동으로 확인하거나 영구 유효성을 보증하지 않는다.
source title/authority/version/url/publishedAt/verifiedAt/notes를 보존하며 verifiedAt는 적용일 선택에 사용하지 않는다.

RuleSet ID 중복, 필수 lifecycle/시작일 누락, 잘못된 날짜 구간, 같은 관할의 EFFECTIVE 기간 중첩,
빈/중복 allergen ID, 잘못된 조건 설정, 계층 순환/미도달/기존 parent와의 충돌,
중복 YAML key 및 임의 객체 태그는 로딩/구성 단계에서 실패한다.
시작 실패를 빈 규칙으로 복구하지 않는다.

## Effective vs Proposed

선택 기준은 관할 + 외부 적용일 + lifecycle EFFECTIVE다.
공고일, 의견제출 종료일, 파일 정렬 순서, 오늘 날짜로 PROPOSED를 활성화하지 않는다.
PROPOSED에 실수로 날짜가 입력되어도 EFFECTIVE selection에 포함되지 않는다.
proposedOnly는 그 날짜에 이미 공고된 제안의 존재를 설명하는 reason용 조회다.
결과 ruleSetId/lifecycle은 항상 선택된 EFFECTIVE를 가리킨다.

## Current KR Effective Scope

19개 regulatory category:

```text
EGG_GROUP MILK BUCKWHEAT PEANUT SOY WHEAT MACKEREL CRAB SHRIMP
PORK PEACH TOMATO SULFITE WALNUT CHICKEN BEEF SQUID SHELLFISH PINE_NUT
```

18종은 ALWAYS, SULFITE는 FINAL_SO2_MIN_MG_PER_KG 조건이다.
규칙은 Java allergen별 if문에 분산하지 않는다. category와 threshold는 YAML에서 읽는다.
APPLE 등 이 버전의 규정 범위 밖 ID는 적용일/규칙이 확인된 경우 NOT_COVERED다.

## Proposed 2026 Additions

참깨 SESAME, 들깨 PERILLA, 아몬드 ALMOND, 캐슈너트 CASHEW를 PROPOSED delta에만 기록했다.
검증된 2026-09-20 적용일을 가진 합성 query에서 4종 모두 NOT_COVERED / PROPOSED_ONLY다.
적용일이 없으면 proposal 여부도 앞질러 단정하지 않고 APPLICABLE_DATE_UNKNOWN이다.
Parser alias나 사용자 알레르기 사전에는 이 단계에서 항목을 추가하지 않았다.

## Coverage Status

| 상태 | 의미 |
|---|---|
| REQUIRED_IF_PRESENT | 실제 존재할 경우 이 버전의 별도 표시 coverage 대상 |
| EXEMPT_IF_PRESENT | 명시적으로 확인한 해당 제품 예외로 별도 표시 생략 가능 범위 |
| NOT_COVERED | 해당 rule set의 별도 표시 범위 밖이거나 조건 미충족 |
| UNKNOWN | 규칙/적용일/조건을 결정할 정보 부족 |

Assessment는 query allergen, jurisdiction, applicableDate, dateBasis, ruleSetId, ruleLifecycle,
regulatoryParent, status, stable reason을 보존한다. 적용 규칙이 선택되지 않으면 ruleSetId/lifecycle/parent는 null이다.
주요 reason은 LISTED_IN_EFFECTIVE_RULE, NOT_IN_EFFECTIVE_RULE, PROPOSED_ONLY,
EXEMPT_SINGLE_INGREDIENT_NAME_MATCH, EXEMPT_MEAT_NAME_MATCH,
APPLICABLE_DATE_UNKNOWN, RULESET_NOT_FOUND, PRODUCT_CONTEXT_INSUFFICIENT,
SULFITE_THRESHOLD_MET/NOT_MET/UNKNOWN, SULFITE_NOT_ADDED, SULFITE_ADDITION_UNKNOWN이다.

## Applicable Date

immutable RegulatoryProductContext에서 날짜와 basis를 제공한다.
basis: MANUFACTURED_AT / LABEL_APPLICABLE_AT / IMPORTED_OR_SHIPPED_AT / UNKNOWN.
날짜가 null이거나 basis가 UNKNOWN이면 APPLICABLE_DATE_UNKNOWN으로 반환한다.
시스템 시계/LocalDate.now/Instant.now/Clock에 의존하지 않는다.
Reference 검증일·이미지 업로드일을 전용하거나 소비기한에서 제조일을 역산하지 않는다.
적용일에 해당하는 EFFECTIVE가 없으면 RULESET_NOT_FOUND다.

## Exemptions

현행 별표의 두 제품 유형 예외를 버전별 exemptions 설정으로 관리한다.
`singleIngredient 또는 packagedOrImportedMeat`가 true이고
해당 query allergen의 제품명 동일 여부가 true일 때만 각각의 EXEMPT reason을 반환한다.
Boolean null과 map의 누락 항목은 미확인이다.

이름 동일 여부는 `Map<String, Boolean> productNameMatchesAllergenName`으로 입력받는다.
MILK의 true를 SOY나 regulatory parent/child에 재사용하지 않는다.
제품명 문자열의 contains/유사도/브랜드 제거 등은 하지 않는다.

- 이름 false이면 예외 불성립: 다른 유형 값이 미확인이어도 REQUIRED_IF_PRESENT 가능.
- 두 제품 유형 모두 false이면 이름이 미확인이어도 예외 불성립.
- 이름 true + 유형 중 하나 true이면 해당 예외.
- 나머지 불확실한 조합은 PRODUCT_CONTEXT_INSUFFICIENT.
- 두 예외가 모두 확인되면 single ingredient reason을 먼저 기록한다.

## Sulfite Conditional Rule

별표 I.1.가의 조건대로 **첨가 여부 true AND 최종 SO2 ≥10 mg/kg**를 확인한다.
정확한 경계 계산은 BigDecimal.compareTo를 사용하며 음수 농도는 잘못된 입력으로 거부한다.

| 정보 | 결과 |
|---|---|
| 농도 <10 | NOT_COVERED / SULFITE_THRESHOLD_NOT_MET |
| 첨가 여부 false | NOT_COVERED / SULFITE_NOT_ADDED |
| 농도 미확인, 첨가 false도 아님 | UNKNOWN / SULFITE_THRESHOLD_UNKNOWN |
| 농도 ≥10, 첨가 미확인 | UNKNOWN / SULFITE_ADDITION_UNKNOWN |
| 농도 ≥10, 첨가 true | 제품 예외 평가 후 REQUIRED 또는 EXEMPT 또는 context UNKNOWN |

농도와 첨가 조건을 먼저 확인하고, 충족하면 별도 표시 예외를 평가한다.
예외가 불성립하는 합성 제품에서 20과 10.000은 REQUIRED, 5와 9.999는 NOT_COVERED다.
Ingredient의 산성아황산나트륨 Evidence/Fact는 최종 농도를 증명하지 않는다.
법문에 있는 첨가 조건을 누락하지 않기 위해 예시 context에 nullable sulfiteAdded를 추가했다.

## Regulatory Hierarchy

기존 Registry의 EGG/QUAIL_EGG → EGG_GROUP을 재사용한다.
기존 Parser Registry에는 OYSTER/ABALONE/MUSSEL parent가 없으므로
이 법규 버전의 regulatoryParents에 세 ID → SHELLFISH를 추가했다.
Parser Registry 자체와 Evidence/Fact는 변경하지 않는다.

직접 scope가 없을 때만 parent chain을 따라가며 결과에 regulatoryParent를 남긴다.
EGG_GROUP/SHELLFISH 직접 query는 parent null, child query는 해당 범주를 기록한다.
가금류 범위 밖 알/미등록 종으로 계층을 넓히지 않는다.
이 조회는 새로운 parent/child Fact를 생성하지 않는다.

## UNKNOWN Strategy

평가 순서: 적용일/basis → EFFECTIVE 선택 → 범주 → 조건부 threshold/첨가 → 제품 예외.
reason은 먼저 막힌 조건을 기록하므로 실제 날짜 미확인 제품에서 농도나 예외가 충분하다는 뜻은 아니다.
NOT_COVERED/EXEMPT/UNKNOWN을 presence status로 변환하지 않는다.
DeclarationState를 읽지 않으며 표시 누락이나 법 위반을 평가하지 않는다.

## S001-S019 Metrics

`regulatory-context-reference.json`은 기존 원문/출처를 대조한 context 검토 fixture다.
원본 세 Reference와 Resolver fixture를 변경하지 않았다.
실제 제조/라벨 적용 날짜는 **19제품 모두 미확인**이므로 null/UNKNOWN을 기록했다.

- S001/S010/S013/S015: 원문에 단일 원료 명시, singleIngredient=true.
- 나머지 평가 제품: 원문에 복수 원료 명시, singleIngredient=false.
- S006의 대두 100%는 제품 전체 단일 원료를 뜻하지 않는다. 응고제·유지가 기재되어 false다.
- 식육 법적 유형과 allergen별 이름 동일 여부는 미확인으로 보존했다.
- S009만 산성아황산나트륨 첨가 명칭을 확인하여 sulfiteAdded=true. 최종 SO2는 전 제품 null.
- S020: REFERENCE_VERSION_CONFLICT, 평가에서 제외. 어느 구성/버전도 선택하지 않았다.

분모는 **19제품 × 동일한 29개 가정 query = 551 assessments**다.
29개는 19규정 범주 + EGG/QUAIL_EGG/OYSTER/ABALONE/MUSSEL + APPLE + proposed 4종이다.
이 query 목록은 제품에 29개 원료가 존재한다는 주장이나 새로운 제품 Gold가 아니다.
Fact가 없는 제품도 동일한 context 검증을 받는다.
합성 unit test의 날짜/농도/예외 값은 실제 제품 context에 섞지 않는다.

| 지표 | 결과 |
|---|---|
| Regulatory Coverage Determinacy | **0/551 = 0.0%** |
| REQUIRED_IF_PRESENT | 0건 / 0제품 |
| EXEMPT_IF_PRESENT | 0건 / 0제품 |
| NOT_COVERED | 0건 / 0제품 |
| UNKNOWN | **551건 / 19제품** |
| APPLICABLE_DATE_UNKNOWN | **551건** |
| PRODUCT_CONTEXT_INSUFFICIENT | 0건 |
| SULFITE_THRESHOLD_UNKNOWN | 0건 |
| RULESET_NOT_FOUND | 0건 |
| 기타 UNKNOWN reason | 0건 |
| 실제 Reference regulatory parent 조회 완료 | **0건** |

모든 query가 날짜 단계에서 멈췄으므로 parent scope도 선택하지 않았다.
합성 테스트에서는 EGG/QUAIL_EGG → EGG_GROUP 2종, OYSTER/ABALONE/MUSSEL → SHELLFISH 3종을 확인한다.
0.0%는 날짜를 발명하지 않은 결과이며 파싱 정확도나 안전 coverage가 아니다.
실측 보고서: `backend/target/regulatory-coverage-report.txt`.

## What This Does NOT Mean

REQUIRED_IF_PRESENT는 해당 allergen이 실제 존재할 경우 별도 표시 coverage 대상이라는 뜻이다.
NOT_COVERED는 현 rule set의 별도 allergen 표시 coverage 밖이라는 뜻이다.
두 결과 모두 알레르겐의 실제 존재/부재 또는 섭취 안전성을 의미하지 않는다.
EXEMPT도 원료 부재가 아니며 UNKNOWN도 안전을 뜻하지 않는다.
Declaration silence 해석, negative Evidence, 사용자 profile matching, 최종 섭취 판정,
규정 위반/의료 판단, OCR/API, LLM, frontend, DB 리팩터링은 구현하지 않았다.
다음 단계는 Declaration Silence / Negative Evidence 설계 여부 검토 또는 User Profile Matching 설계다.

## Verification — 2026-09-20

Java 17에서 다음을 순서대로 실행했다(backend 기준).

```bash
mvn -q -DskipTests compile
mvn -q -Dtest=DeclarationCoverageResolverTest test
mvn -q -Dtest='DeclarationCoverageResolverTest,RegulatoryRuleRegistryTest' test
mvn -q -Dtest='DeclarationCoverageResolverTest,RegulatoryRuleRegistryTest,RegulatoryReferenceTest' test
mvn -q -Dtest='Allergen*Test,Declaration*Test,CrossContact*Test,Ingredient*Test,Resolver*Test,Regulatory*Test,ChatSafetyContextServiceTest,ChatServiceSafetyTest' test
mvn -q clean test
```

전체 **952개, failures 0, errors 0, skipped 1**.
skip은 기존 opt-in RecipeAccuracyLiveTest다. 신규 **69개**는
Coverage Resolver 35개(명시 시나리오 29개 포함), Rule Registry 11개, 실제 Reference 23개다.
합성 규제 테스트는 두 unit 클래스의 **46개 모두 통과**했다.
필수 16개 주제를 포함해 날짜 경계/gap/overlap, PROPOSED 누출 방지, threshold 경계/첨가 조건,
이름 동일 여부의 query 격리, tri-state 예외, 계층/Fact 분리, 설정 실패와 불변성을 검증했다.
테스트 작성 중 AssertJ의 DATE 이름과 테스트 날짜 상수의 wildcard import 충돌을
명시적 assertion import로 수정한 뒤 관련 테스트와 전체 테스트를 통과했다.

기존 회귀: Declaration **15/15**, Cross-contact **10/10**, Ingredient 구조 **19/19**,
Evidence Resolver **19/19**, lexical **25/25**, candidate **24/24**, unresolved **7회/6종** 유지.
작업 전 snapshot과 비교해 기존 allergen production **26파일**, 기존 fixture **6파일**,
lexical YAML **1파일**이 byte 동일하다. PresenceStatus/AllergenFact/Evidence Resolver를 변경하지 않았다.
새 production 코드에서 부재/안전 판정 enum 및 시스템 시계/HTTP 의존성이 없는 것도 확인했다.
`git diff --check`와 신규/untracked 작업 파일별 공백 검사를 통과했다.

API/DB 통합, frontend 빌드, OCR/외부 API 동작, live LLM, 실물 라벨 검증은 실행하지 않았다.
공식 법령 웹 조회는 개발 시 source 확인에만 사용했으며 runtime 웹 조회를 추가하지 않았다.
commit / push / PR 없음.

## Findings

| 분류 | 확인한 문제/한계 | 처리 |
|---|---|---|
| APPLICABLE_DATE_UNKNOWN | 실제 19제품 모두 제조/법 적용일 미확인 | 551 assessments UNKNOWN, 날짜 발명 없음 |
| EXEMPTION_CONTEXT_UNKNOWN | 식육 유형·알레르겐별 이름 동일 여부 미확인 | nullable/빈 map 보존, 이름 fuzzy 추론 없음 |
| CONDITIONAL_THRESHOLD_UNKNOWN | 실제 최종 SO2 농도 전부 미확인 | 농도 null 유지; 실제 결과 reason은 더 앞선 날짜 미확인 |
| REGULATORY_HIERARCHY_GAP | 기존 Registry에 shellfish 자식 parent 없음 | 해당 규정 버전의 scope parent로만 보충 |
| RULESET_NOT_FOUND | 2026 시행본 이전 규정 미등록 | 소급하지 않고 UNKNOWN |
| RULESET_OVERLAP / PROPOSED_RULE_LEAK | 잘못된 중첩/예고 활성화 가능성 | 구성 검증과 자동 회귀로 차단 |

## Changed files

```text
backend/src/main/java/com/salus/healthytable/service/allergen/RegulatoryLifecycle.java
backend/src/main/java/com/salus/healthytable/service/allergen/RegulatoryDateBasis.java
backend/src/main/java/com/salus/healthytable/service/allergen/DeclarationCoverageStatus.java
backend/src/main/java/com/salus/healthytable/service/allergen/DeclarationCoverageReason.java
backend/src/main/java/com/salus/healthytable/service/allergen/RegulatoryProductContext.java
backend/src/main/java/com/salus/healthytable/service/allergen/DeclarationCoverageAssessment.java
backend/src/main/java/com/salus/healthytable/service/allergen/RegulatoryAllergenRuleSet.java
backend/src/main/java/com/salus/healthytable/service/allergen/RegulatoryRuleRegistry.java
backend/src/main/java/com/salus/healthytable/service/allergen/DeclarationCoverageResolver.java
backend/src/main/resources/regulations/kr-allergen-labeling.yaml
backend/src/test/java/com/salus/healthytable/service/allergen/DeclarationCoverageResolverTest.java
backend/src/test/java/com/salus/healthytable/service/allergen/RegulatoryRuleRegistryTest.java
backend/src/test/java/com/salus/healthytable/service/allergen/RegulatoryReferenceTest.java
backend/src/test/resources/allergens/regulatory-context-reference.json
docs/allergen-regulatory-coverage.md
docs/allergen-evidence-pipeline.md
docs/ai/PROJECT_CONTEXT.md
docs/ai/SAFETY_RULES.md
```
