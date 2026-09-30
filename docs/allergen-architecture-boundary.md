# Allergy Safety Architecture Boundary

6a, 2026-09-20 working tree 기준이며 6a.1의 profile 보존 및 6b의 typed resolution을 반영했다.
6a는 문서와 test source만 변경했다. 6a.1은 아래 별도 섹션의 ChatSafetyContextService validation만 수정하며
Matcher·Dictionary·Evidence·Regulatory·YAML·API·DB는 변경하지 않는다.
6b는 [Profile Resolution](allergen-profile-resolution.md)과 shellfish parent 3개를 추가하며 기존 runtime consumer는 변경하지 않는다.
6c는 [Shadow Observation](allergen-profile-shadow.md)으로 정규화 결과의 관찰만 연결한다. Safety authority migration = NO.
아래 Java 경로는 `backend/src/main/java/com/salus/healthytable/` 기준이다.
행 번호는 조사 시점의 위치이며 메서드명이 지속적인 탐색 기준이다.

## Current Production Flow

현재 allergy 처리는 하나의 직선 경로가 아니다.

| 경로 | 입력과 실제 호출 | 결과에 미치는 영향 |
| --- | --- | --- |
| 채팅/구조화 생성 | `HealthProfile.allergies` + 요청 `HealthProfileContext` + 현재 메시지 + user 역할 history → `ChatSafetyContextService.build` → `SafetyContext.allergies` → `AllergenMatcher.findConflicts` → `AllergenDictionary.matchTermsFor` | 채팅 차단 응답, 생성 결과 차단, 안전 안내 |
| Recipe Agent (flag에 따라 진입) | `RepositoryUserRecipeContextLoader.loadWithStatus` → DB profile → `UserRecipeContext` → `RecipeAgentOrchestrator` → `RecipePersonalizationPolicyEngine` → `AllergyPolicy` → 같은 Matcher/Dictionary | BLOCK 또는 제거 수정 및 안내, 후속 검증 후 카드 제공 여부 |
| 커뮤니티 추천 | `CommunityController.getRecommendations` → `RecommendationService.getRecommendations` → 저장 추천이 없으면 `generateRecommendations` → DB profile → `calculateScore` | 재료 문자열에 알레르기 문자열 포함 시 -100점; 양수 점수만 추천 저장 |

코드 근거:

- `domain/HealthProfile.java:27`: allergies는 JSON 변환 `List<String>`이며 typed allergen ID 목록이 아니다.
- `controller/HealthProfileController.java:45`의 `saveMyHealthProfile`/`cleanList`,
  `controller/ChatController.java:221`의 `normalizeHealthProfile`/`cleanProfileValues`는
  null·blank 제거, 공백 정리, 중복 제거, 길이/개수 제한을 한다. 문장 의미 정규화는 하지 않는다.
- `service/ChatSafetyContextService.java:49`의 `build`는 요청·대화·저장 프로필을 합친다.
  DB 건강 정보 로드 실패는 `healthContextAvailable=false`로 남는다.
- `service/ChatService.java:113`에서 context를 만들며 인증 사용자 조회 실패 시 제공을 제한한다.
  `buildAllergyConflictReply` 위임은 `:438`, `findAllergyConflicts` 위임은 `:447`이다.
- `service/RecipeGenerationCoordinator.java:171`: 생성한 candidate의 title/ingredients/steps를
  `ChatSafetyContextService.findAllergyConflicts`에 전달하고 충돌이면 `StructuredRecipeOutcome.blocked`를 반환한다.
  그 다음 draft/recipe validator가 별도로 실행된다.
- `service/ChatSafetyContextService.java:194`의 `findAllergyConflicts`는 title/재료/조리 순서에 Matcher를 적용한다.
  등록 알레르기를 요청의 “빼고/제외”로 해제하지 않는다. `buildRecipeSafetyNotes`는 안내 소비 경로다.
- `service/ChatService.java:144`: Agent 라우팅은 flag·요청 조건·승인 레시피 부재에 따라 실행된다.
  `service/recipeagent/RepositoryUserRecipeContextLoader.java:63`은 DB allergies를 직접 읽는다.
  Agent는 채팅의 `SafetyContext.allergies()`를 전달받는 구조가 아니다.
- `service/recipeagent/RecipeAgentDomain.java:36`의 `UserRecipeContext`는 목록을 trim/blank 제거/dedup한다.
  `RecipeAgentOrchestrator.java:63`은 로드한 context와 이전 세션을 조정하고,
  `:85`에서 개인화 flag가 켜져 있으면 정책을 평가한다.
- `service/recipeagent/RecipePersonalizationPolicies.java:118`의 `AllergyPolicy.evaluate`는 title/재료/steps에 Matcher를 적용한다.
  literal match이면서 핵심 재료가 아닌 경우 REMOVE 수정, 나머지 충돌은 BLOCKING이다.
  `RecipePersonalizationPolicyEngine.evaluate/decide`가 이 findings를 기존 Agent 결정으로 합친다.
- `service/recipeagent/RecipeAgentPipeline.java:124`의 `RecipeValidationPipeline.validate`는
  `RecipeCandidate.containsIngredient`로 잔존 알레르기/제외 재료를 별도로 확인한다.
  `RecipeAgentOrchestrator.java:100`은 최종 검증 실패를 BLOCK으로 바꾸며 `:132`에서 카드 제공을 제한한다.
- `controller/CommunityController.java:75`, `service/RecommendationService.java:134,81`:
  추천은 ChatSafetyContextService/AllergenMatcher를 호출하지 않는다. 자체 `normalizeValues`와
  `containsIgnoreCase`로 재료만 검사하며 저장 추천 조회 시 재계산을 항상 수행하는 구조도 아니다.

## Structured Label Evidence Pipeline

입력 domain은 호출자가 구분한 **structured product label fields**다.

```text
allergenDeclaration → AllergenDeclarationParser ───────────┐
crossContactText    → AllergenCrossContactParser ──────────┤
ingredientText     → IngredientTreeParser                 │
                      → IngredientEvidenceExtractor ─────┤
                                                         ↓
                                                AllergenEvidence
                                                         ↓
                                            AllergenEvidenceResolver
                                                         ↓
                                                  AllergenFact
```

각 field의 readable/not-found/unreadable 상태를 보존한다. 채팅 문장, 레시피 자유 텍스트,
임의 음식 설명을 이 parser의 입력이라고 간주하지 않는다.
`AllergenEvidenceResolver.resolve(List<AllergenEvidence>)`는 한 제품의 정확히 같은 ID에 속한
positive Evidence를 요약하며 사용자 정보나 free-text Matcher를 사용하지 않는다.
`PresenceStatus`는 CONFIRMED_PRESENT / POSSIBLE_PRESENT / CROSS_CONTACT_ONLY다.
cross-contact의 CERTAIN은 접촉 가능성을 명시한 문구에 대한 확신이며 함유 확정이 아니다.
빈 목록·누락·침묵은 부재 또는 SAFE가 아니다.

보호 출력 모델은 `AllergenEvidence`, `AllergenFact`, `PresenceStatus`다.
현재 parser/resolver는 Spring component일 수 있지만 **package 밖 production 소비 호출은 없다**.
Bean 등록은 safety authority 연결이 아니다. 실제 참조는 package 내부 구성 및 테스트/Reference 평가에 한정된다.
세부 계약: [Evidence pipeline](allergen-evidence-pipeline.md).

## Regulatory Pipeline

```text
RegulatoryProductContext + allergen ID
    → DeclarationCoverageResolver.assess
    → DeclarationCoverageAssessment
```

특정 관할·실제 적용 시점·제품 맥락에서 해당 allergen이 존재한다면 별도 표시 coverage 대상인지 평가한다.
함유 여부, recipe matching, 사용자 충돌, SAFE/DANGER를 판정하지 않는다.
보호 출력은 `DeclarationCoverageAssessment`, `DeclarationCoverageStatus`다.
상태는 REQUIRED_IF_PRESENT / EXEMPT_IF_PRESENT / NOT_COVERED / UNKNOWN이다.
적용일/근거 누락은 UNKNOWN이며 현재 날짜·웹 확인일로 대체하지 않는다.
규제 scope의 parent lookup은 Evidence/Fact 생성이나 lexical taxonomy 변경이 아니다.

현재 `DeclarationCoverageResolver`와 Registry가 Spring bean으로 등록되어도 package 밖 production
consumer 연결은 없다. [5단계 문서](allergen-regulatory-coverage.md)의 독립된 범위를 유지한다.

## Profile Normalization Ownership

**CURRENT OWNER (채팅 경로): `ChatSafetyContextService`**.
`appendAllergyMentionsFromText`는 현재 메시지/user history에서 표현을 추출하고,
`appendNormalizedValues/appendNormalizedValue`는 요청·저장 프로필에도 같은 normalization을 적용하되,
6a.1부터 자연어 후보 validation은 별도로 적용한다.

`service/ChatSafetyContextService.java`의 실제 동작:

1. 알러지/알레르기 및 “못 먹/피해야” 등의 대화 패턴에서 allergy 표현 추출.
2. 쉼표, slash, 가운데점, 전각 쉼표, 줄바꿈으로 term 분할.
3. `normalizeHealthProfileTerm`: 특수문자/주어 표현 정리와 공백 정리.
4. `normalizeAllergyTerm`: 알레르기/알러지, 있어요/있음, 주의/금지 등의 부가 표현 및 끝 조사 제거.
5. 구조화된 stored/request profile은 normalized non-blank이면 보존.
   자연어는 `isLikelyExtractedAllergyName`: 기존 2~20자 heuristic 유지,
   1자는 Registry exact DIRECT_NAME의 distinct ID가 하나인 경우만 허용.
6. 순서를 보존한 중복 제거.

**TARGET OWNER:** raw sentence → normalized term 책임은 기존 owner에 유지한다.
향후 명시적인 normalization migration 없이 6b Resolver로 복제하거나 옮기지 않는다.
6b의 별도 책임은 **normalized term → typed allergen ID resolution**이다.

```text
raw input → existing production normalization
          → ShadowObserver → NormalizedProfileAllergenTerm (observer 내부 구성)
          → Profile Resolver
```

6b에 `resolve("저 우유 알레르기 있어요")` 형태의 raw sentence API를 만들지 않는다.
Resolver 안에서 조사/알레르기/문장을 다시 제거하지 않는다. 6a에는 이 production 타입도 구현하지 않는다.
현재 Recommendation의 공백 정리, Agent `UserRecipeContext.clean`, controller sanitation,
Dictionary의 비교용 compact normalization은 별개의 기존 책임이다.
채팅 owner가 모든 consumer의 normalization을 이미 소유한다고 해석해서는 안 된다.
향후 다른 경로의 typed input adapter는 원래 계약을 별도로 검토해야 한다.

6a에서 확인한 한 글자 structured profile 누락은 6a.1에서 수정했다.
자연어 `콩` 같은 LEXICAL_HINT는 여전히 자동 허용하지 않으며, 명시적 profile의 `콩`은 보존한다.

## Legacy Literal Fallback

실제 코드: `service/allergen/AllergenDictionary.java:87`의 `matchTermsFor`.
`normalize`는 lowercase 후 한글/영문/숫자 외 문자를 제거한다.
알레르기 문자열과 legacy alias/derived entry가 일치하면 그 entry의 alias/derived terms를 사용한다.
결과가 비면 `:99`에서 `terms.add(normalized)`로 **문자열 자체를 유지**한다.

그 term은 `AllergenMatcher.findConflicts`로 전달된다. 한 글자 term은 전체 token 일치,
긴 term은 token 내부 포함 검사다. 이것은 typed ID 해석이 아니다.
목적은 dictionary에 없는 사용자 알레르기를 조용히 버리지 않고 기존 free-text 비교를 계속하는 것이다.

6b/6d regression baseline:

```text
키위 → dictionary miss → normalized literal 키위 → recipe "키위 2개" conflict
키위 → dictionary miss → normalized literal 키위 → recipe "사과 2개" no conflict
```

기존 `AllergenMatcherTest.unknownAllergyStillMatchesLiterally`가 두 경우를 검증한다.
단, 이 fallback은 Matcher까지 도달한 term에만 적용된다. 앞 단계 필터에서 누락된 term을 복구하지 않는다.
`UNRESOLVED → drop → match 0`으로 바꾸는 것은 회귀다.

**legacy literal fallback = 기존 free-text compatibility path**다.
Evidence에 literal substring matching을 추가하거나, unresolved 문자열을 fake AllergenId로 만들거나,
literal과 AllergenFact를 비교해 typed UserAllergenMatch를 생성하지 않는다. 이번 이동은 없다.

## Current Safety Authority

CURRENT authority는 실제 결과에 영향을 주는 기존 경로다.
채팅/구조화 생성에서는 ChatSafetyContextService → Matcher/Dictionary의 conflict를
ChatService/RecipeGenerationCoordinator가 차단·안내로 소비한다.
Recipe Agent에서는 AllergyPolicy 및 기존 policy engine/최종 validation/Orchestrator가
수정·차단·카드 제공 여부를 결정한다. 추천 서비스에서는 자체 점수 배제 필터가 작동한다.
현재 allergy 판단 책임은 이렇게 분산되어 있으며 전역 단일 decision authority가 이미 있는 상태가 아니다.

Evidence/Regulatory의 모델이 더 정교하다는 이유만으로 CURRENT authority로 지정하지 않는다.
현재 실제 wiring 근거는 위 Current Production Flow와 전체 production source 경계 검사다.

## Target Safety Authority

```text
all findings → single Safety Decision authority → consumer action
```

이는 7단계 목표이며 이번에 클래스를 만들거나 authority를 이전하지 않는다.
기존 경로와 새 Evidence pipeline은 같은 입력을 평가하는 두 matcher가 아니다.
`new wins`, `legacy wins`, `most severe wins` 같은 시스템 간 winner rule은 정의하지 않는다.
이는 기존 Agent 내부의 conflict 우선순위나 Evidence Resolver 내부의 presence 요약 규칙을 없앤다는 뜻도 아니다.

## Input Domain Boundaries

| 구성 | 허용 입력/책임 | 책임 밖 |
| --- | --- | --- |
| 현재 chat normalization | raw chat/profile 문자열 → normalized allergy strings | typed ID resolution |
| 기존 Matcher/Dictionary | profile strings + recipe/free text | structured-label certain evidence |
| Label parsers/Extractor | 구분된 label fields와 읽기 상태 → 관찰 근거 | chat/recipe 문장 안전 판정 |
| Evidence Resolver | 한 제품의 Evidence → ID별 Fact | user matching, 부재 추론 |
| Regulatory Resolver | 제품 규제 context + ID → declaration coverage | 함유, 사용자 안전 |
| 6b Profile Resolver (6c observation만 연결) | NormalizedProfileAllergenTerm → COMPLETE/PARTIAL → aggregate metrics | raw sentence 재정규화, production safety decision |
| 미래 6d matching | typed resolution + evidence별 provenance | unresolved literal을 fake ID로 승격 |

## Unresolved Profile Policy

**UNRESOLVED != DROP. UNRESOLVED != fake AllergenId.**

6b 구현 계약(6c Observer 내부에서 metric 입력으로만 소비):

```java
sealed interface ProfileResolution
    permits CompleteProfileResolution, PartialProfileResolution {
    // 공통 resolved() 목록 accessor 없음
}
```

- COMPLETE: 전달된 모든 normalized term이 typed ID로 해결됨.
- PARTIAL: 하나 이상의 unresolved term 존재. 일부 또는 전부 미해결인 경우 모두 포함한다.
- `UnresolvedProfileAllergen(input, reason, candidateAllergenIds)`를 1급 결과로 보존한다.
  reason은 REGISTRY_MISS / AMBIGUOUS / NO_PROFILE_ELIGIBLE_ALIAS다.
- 소비자가 Complete/Partial을 분기하고 Partial을 명시적으로 처리하도록 한다.
  공통 interface의 naked `List<Resolved>` 반환 또는 상태 무시가 쉬운 `result.resolved()` API를 피한다.
  subtype별 목록과 provenance는 [6b 계약](allergen-profile-resolution.md)에 명시했다.
- COMPLETE는 전달된 입력의 resolution 완전성일 뿐, 원래 profile 수집의 완전성이나 섭취 안전을 뜻하지 않는다.
  빈 입력 역시 “안전”이 아니다. normalization에서 누락된 값을 COMPLETE가 복구하지 않는다.
- unresolved는 기존 literal compatibility path를 끊는 근거가 아니다.
  typed 경로의 미해결 결과와 기존 free-text 비교를 함께 보존하되 새 winner rule은 만들지 않는다.

## Parent vs Derived Semantics

`resources/allergens/ko-allergens.yaml`의 기존 parent 두 개는 유지한다.

```text
EGG → EGG_GROUP
QUAIL_EGG → EGG_GROUP
```

6b에서 추가한 taxonomy edge:

```text
OYSTER → SHELLFISH
ABALONE → SHELLFISH
MUSSEL → SHELLFISH
```

parent는 taxonomy / is-a relation이다. derived는 ingredient / lexical relation이다.
굴소스·조개육수 같은 표현은 ingredient/derived 관계이지 taxonomy child가 아니다.
`derived`를 parent hierarchy로 재사용하지 않는다.
별도 `resources/regulations/kr-allergen-labeling.yaml`의 shellfish scope 매핑은
규제 적용 대상 조회용으로 별도 책임이다. 6b에서 lexical YAML에만 parent 3개를 추가했고 규제 YAML은 변경하지 않았다.

## Evidence-level Explicit Child Provenance

`service/allergen/AllergenFact.java:33`의 `explicitChildren()`은
모든 `e.normalizedAllergen().explicitChildren()`을 첫 등장 순서로 distinct union한다.
Fact-level union의 역할은 summary/convenience뿐이다.

```text
Evidence A: SHELLFISH, explicitChildren=[ABALONE]
Evidence B: SHELLFISH, explicitChildren=[OYSTER]
Fact union: [ABALONE, OYSTER]
```

향후 OYSTER UserAllergenMatch의 supporting Evidence는 B여야 한다.
A를 OYSTER 근거로 인용하면 안 된다. 실제 접근 경로는
`AllergenEvidence.normalizedAllergen().explicitChildren()`이며 Evidence 자체에 동명의 직접 accessor는 없다.
rawText/matchedText/source/path와 해당 children을 **동일 Evidence 단위**로 연결한다.
parent hierarchy를 추론해 explicitChildren에 끼워 넣지도 않는다.

## Shadow Strategy

전체 `legacy matcher vs Evidence pipeline` shadow comparison은 현재 성립하지 않는다.
전자는 recipe/free text, 후자는 structured label fields를 받으므로 동일 입력/정답 기준이 없다.

6c 대상은 **Profile Resolution**이다. ChatSafetyContextService가 실제 출력한 동일 normalized terms를
6b Resolver에 전달한다. raw sentence를 재해석하지 않으며 shadow 결과는 production safety 결과에 영향을 주지 않는다.
이 측정만으로 label Evidence 정확도나 전체 allergy recall을 주장하지 않는다.
특히 normalization에서 빠진 값은 shadow denominator에도 들어오지 않는다는 제약을 남긴다.

6c 구현: source/outcome별 term count, source/resolution별 batch count, execution/failure count.
관찰 단위는 source별 batch이고 batch 내부 동일 normalized 값은 한 번만 센다. source 간에는 합치지 않는다.
운영 로그에 raw allergy text를 그대로 기록하는 것을 기본값으로 삼지 않는다.
Observer의 결과는 caller에게 반환하지 않고 enum-only aggregate metric으로만 기록한다.
기존 Micrometer를 사용하며 empty batch는 생략한다. 예외와 metric 장애는 fail-open으로 격리한다.
동일 history는 요청마다 재관찰될 수 있으므로 사용자 고유 분포와 구분한다. 실제 수집 결과를 이번 작업에서 주장하지 않는다.

## Migration Sequence

| 단계 | 책임 |
| --- | --- |
| 6a (이번) | Architecture Boundary 문서 + Ownership 계약 + Guard |
| 6b (구현) | Allergen Ontology + Profile Resolution; raw normalization 중복 금지 |
| 6c (관찰 연결) | Profile Resolution Shadow Observation; safety 영향 없음, structured-label API는 미구현 |
| 6d | Evidence-level UserAllergenMatch; supporting Evidence provenance 유지 |
| 7 | Single Safety Decision integration 및 명시적 authority migration |

Negative Evidence는 실제 regulatory applicable date 입력 확보 전까지 보류한다.
표시 침묵 해석, structured-label dev API, 사용자 safety decision은 이번 범위에 없다.

## Architecture Guard

`backend/src/test/java/com/salus/healthytable/service/allergen/AllergenArchitectureBoundaryTest.java`.
JDK Files.walk/Pattern만으로 source를 조사하며 새 dependency는 없다(JUnit/AssertJ는 기존 test 도구).

- 검사 root: `backend/src/main/java`의 `.java` 전체. `src/test`, `target`, `docs`는 root 밖이라 제외한다.
- test class의 code-source 위치에서 상위 Maven module을 찾는다. module/repository root 실행이나
  공백 있는 경로를 지원하며, source root나 보호 모델이 없으면 실패한다. 로컬 절대 경로는 없다.
- 보호: AllergenEvidence, AllergenFact, DeclarationCoverageAssessment, PresenceStatus, DeclarationCoverageStatus.
- 6b 추가 보호: NormalizedProfileAllergenTerm, ProfileTermSource, ProfileAllergenResolver,
  ProfileResolution, CompleteProfileResolution, PartialProfileResolution, ResolvedProfileAllergen,
  UnresolvedProfileAllergen, ProfileResolutionType, ProfileUnresolvedReason. 기존 총 15종은 계속 외부 접근을 금지한다.
- 6c는 ProfileResolutionShadowMetrics/MicrometerProfileResolutionShadowMetrics도 보호한다.
  ProfileResolutionShadowObserver만 정확한 `service/ChatSafetyContextService.java` caller에 import/FQCN을 허용한다.
  공개 surface는 source별 void observe 메서드 3개다. Resolver/result 직접 참조와 다른 caller의 bridge 사용은 실패한다.
- 허용: **정확한** `com.salus.healthytable.service.allergen` package와 대응 source directory.
  subpackage는 자동 허용하지 않는다. 새 경계는 명시적으로 결정한다.
- 검사: explicit/nested/static imports, package wildcard import, fully-qualified usage.
  외부 consumer의 package wildcard import는 보호 모델을 숨길 수 있어 금지한다.
  기존 AllergenMatcher/AllergenDictionary의 명시적 import는 허용한다.
- comments/string/character/text-block의 예시 텍스트를 제외하고 이름 경계를 확인한다.
  오류에는 파일, package, 보호 타입, access 형태와 migration 필요 사유를 표시한다.
- scanner 자체 테스트는 각 보호 타입 import/FQCN, static/nested/wildcard, 공백/주석,
  허용 package/path, utility 허용, 예시 문자열, root 탐색, 검사 범위를 검증한다.

이것은 단순 source guard이며 완전한 Java parser/전이 의존성 분석기가 아니다.
reflection, Unicode escape를 이용한 난독화, wrapper를 통한 간접 노출 등은 보장 범위 밖이고 우회 수단으로 허용하지 않는다.
향후 새로운 출력 모델이 추가되면 보호 목록도 검토한다.
**영구 금지 규칙이 아니다.** 7단계의 의도적 authority migration 때 allowlist/boundary와 문서를
함께 수정한다. 그 변경 자체가 architecture migration 기록이다. 기존 위반이 발견되면 production을
옮기거나 삭제해 숨기지 않고 원인과 충돌을 보고한다.

## Existing Issues Outside 6a

| 분류 | 실제 위치 | 관찰 및 이후 검토 |
| --- | --- | --- |
| normalization 누락 (6a.1 해결) | `service/ChatSafetyContextService.java`의 `appendNormalizedValue` | stored/request profile을 자연어 heuristic에서 분리. normalized non-blank 값 보존 |
| profile source 분리 | `service/recipeagent/RepositoryUserRecipeContextLoader.java:63`, `RecipeAgentOrchestrator.java:63` | Agent는 DB/이전 세션 context 사용. 채팅이 합친 request/history allergies를 그대로 소비하지 않음 |
| matching 의미 차이 | `service/RecommendationService.java:81` | 재료만 대소문자 무시 substring 검사. dictionary/derived/한 글자 token 규칙과 다름; 저장 추천은 항상 재검사하지 않음 |
| 보조 필터 의미 차이 | `service/recipeagent/RecipePersonalizationPolicies.java:394`, `RecipeAgentPipeline.java:124` | 냉장고 후보 필터와 최종 잔존 검증은 normalized substring 계열. AllergyPolicy의 Matcher 호출과 별개 |

위 항목은 6a에서는 수정하지 않았다. 6a.1에서는 첫 항목만 수정했다.
나머지는 정책 승인이나 안전성 보증이 아닌 기존 경로 차이의 기록이다.

## Production Safety Fix — Structured Profile Term Preservation

6a.1은 새로운 allergy inference나 typed Profile Resolver를 추가하지 않는다.
사용자가 명시적으로 제공한 allergy profile value가 기존 Matcher에 도달하기 전에 삭제되는 것을 방지한다.

문제: `HealthProfile.allergies`와 request `HealthProfileContext.allergies`, 자연어 추출 후보가
모두 `appendNormalizedValue`의 `isLikelyAllergyName`을 통과했다. 신뢰도가 다른 세 입력에
같은 2~20자 heuristic을 적용하면서 명시적으로 등록한 `굴`, 미등록 `김`까지 버렸다.

해결: 기존 normalization은 유지하고 validation만 source별로 분리했다.

| 입력 | 6a.1 정책 |
| --- | --- |
| STORED_HEALTH_PROFILE | normalized non-blank이면 길이·Registry 등록 여부와 무관하게 보존 |
| CHAT_REQUEST_PROFILE | stored profile과 동일. Registry 조회를 보존 조건으로 요구하지 않음 |
| CHAT_MESSAGE / user history | 기존 추출 패턴과 2자 이상 heuristic 유지. 1자는 exact DIRECT_NAME의 distinct ID가 정확히 하나일 때만 허용 |

`AllergenDictionary.isKnown`은 legacy aliases/derived와 긴 alias의 포함 검사까지 사용하므로
자연어 1자 후보의 identity 검사에 쓰지 않는다. `AllergenRegistry.findDirectName`도 첫 결과만
반환하므로 ambiguity 검증에 충분하지 않다. 기존 read-only `findExactAliases` 결과에서
DIRECT_NAME만 고른 뒤 ID를 distinct하여 하나인지 확인한다. DERIVED_FROM/LEXICAL_HINT만으로는 통과하지 않는다.
`굴`은 DIRECT_NAME OYSTER 하나이므로 허용한다. 동시에 존재하는 DERIVED_FROM SHELLFISH는
직접 identity 후보로 세지 않는다. 두 DIRECT_NAME ID가 있으면 자동 허용하지 않는다.
이 검사는 후보 수용 여부만 반환하며 typed resolution 결과나 Evidence를 만들지 않는다.

`굴 알레르기 있어요`는 보존하지만 `오늘 굴 먹었는데`는 새 allergy로 추출하지 않는다.
미등록 `김 알레르기 있어요`와 hint-only `콩 알레르기 있어요`는 거부한다.
반면 명시적 stored/request profile의 `김`/`콩`은 보존한다. model 역할 history는 계속 제외한다.

Legacy literal fallback은 그대로다. 실데이터에서 `김`은 Dictionary/Registry 미등록이며,
Matcher에 전달되면 `김 2장`에는 충돌하고 `김치 100g`에는 충돌하지 않는다. 기존 `키위` fallback도 유지한다.
`matchTermsFor("굴")`의 실제 기존 결과는
`[조개, 조개류, shellfish, 굴, 전복, 홍합, 바지락, 모시조개, 가리비, 소라, 굴소스, 조개육수]`다.
이는 legacy shellfish derived 확장이며 OYSTER taxonomy를 새로 구현한 것이 아니다.
Matcher 변경 없이 `굴`, `굴소스`, `조개육수` 충돌과 무관한 `소금` 비충돌을 직접 검증한다.

범위 밖 제약: 기존 normalization 자체(조사/표현 제거)는 그대로이므로 normalization 결과가 빈 값이면
추가되지 않는다. 자연어 전체 추출 정밀도 개선, 추천과 Matcher의 모든 의미 통일, taxonomy migration은 수행하지 않는다.
Evidence/Regulatory 출력 모델 연결 및 YAML 변경은 없고 6a Architecture Guard를 유지한다.

6a.1 검증 (JDK 17.0.14 / Maven 3.9.9):

- production 변경 전에 Matcher의 `굴`/`김` 회귀와 기존 `키위` fallback 테스트 2개 통과.
- `mvn -q -Dtest='ChatSafetyContextServiceTest,ChatServiceSafetyTest,AllergenMatcherTest,AllergenRemovabilityTest,AllergenGapTest,RecommendationServiceTest,AllergenArchitectureBoundaryTest' test`:
  88 tests, failures/errors/skipped 0.
- `mvn -q clean test`: 992 tests, failures 0, errors 0, skipped 1 (`RecipeAccuracyLiveTest`).
- Architecture Guard 18개 통과. Declaration 15/15, Cross-contact 10/10, Ingredient 19/19,
  Evidence Resolver 19/19, lexical 25/25, candidate 24/24, unresolved ingredient 7회/6종 유지.
  Regulatory unit 46개·Reference 23개 통과; 실제 context 551건은 적용일 미확인 UNKNOWN 유지.
- runtime/config/build 196개 파일 비교에서 ChatSafetyContextService만 변경됨.
  기존 생성자 호출 테스트 두 파일은 Registry 의존성 주입만 보완했다.
  `git diff --check` 및 untracked 문서 공백 검사 통과. Commit/push/PR/branch 변경 없음.

## Verification — 6a

JDK 17.0.14 / Maven 3.9.9에서 실행했다.

- `cd backend && mvn -q -Dtest=AllergenArchitectureBoundaryTest test`: 18 tests, failures/errors/skipped 0.
  현재 package 밖 보호 타입 참조 violation 0.
- `cd backend && mvn -q clean test`: 970 tests, failures 0, errors 0, skipped 1
  (`RecipeAccuracyLiveTest`; live 외부/LLM 검증은 실행하지 않음).
- 기존 Reference: Declaration 15/15, Cross-contact 10/10, Ingredient Structural 19/19,
  Resolver 19/19. Synthetic lexical 25/25, candidate 24/24, unresolved ingredient 7회/6종 유지.
- Regulatory resolver/registry unit 46개와 Reference 23개 통과.
  실제 Reference context는 적용일 미확인으로 551/551 UNKNOWN이며 이를 안전 또는 부재로 해석하지 않는다.
- repository root `git diff --check` 통과. 변경한 untracked 5개 파일도 별도 no-index whitespace 검사에서 오류 없음.
- 작업 전후 production Java 전체 + lexical/regulatory YAML + POM 총 196개 SHA-256 동일,
  새 production Java 파일 없음. 기존 runtime 동작 변경 없음.
- 변경 파일: 이 문서, `AllergenArchitectureBoundaryTest.java`, 문서 링크/현재 flow 설명을 보완한
  `docs/ai/PROJECT_CONTEXT.md`, `docs/ai/SAFETY_RULES.md`, `docs/allergen-evidence-pipeline.md`.
  commit/push/PR/branch 변경 없음. 기존 WIP는 유지했다.
