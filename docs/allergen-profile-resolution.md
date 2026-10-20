# Allergen Profile Resolution

6b — 2026-09-20 working tree; 6c observation 연결 사항을 반영했다. 구현 위치는
`backend/src/main/java/com/salus/healthytable/service/allergen/`이다.
현재 production safety authority는 [Architecture Boundary](allergen-architecture-boundary.md)의 기존 경로다.

## Purpose

이미 정규화된 사용자 allergy term을 Registry의 명시적 allergen identity 또는 미해결 결과로 해소한다.
`ProfileAllergenResolver`는 side-by-side capability다. 독립 Spring bean으로 등록하지 않으며,
6c부터 [Shadow Observer](allergen-profile-shadow.md) 내부에서만 runtime 호출한다. 기존 safety consumer는 결과를 볼 수 없다.
DB/실사용자 데이터를 조사 목적으로 조회하지 않았으며 API·schema 변경은 없다. 관찰 metric은 aggregate만 기록한다.

**Profile Resolution success != product allergy match.** Resolver는 identity resolver이며 safety matcher가 아니다.

## Input Contract

```java
record NormalizedProfileAllergenTerm(String value, ProfileTermSource source)
ProfileResolution resolve(Collection<NormalizedProfileAllergenTerm> inputs)
```

value와 source는 non-null, value는 non-blank여야 한다. 불변 record는 입력값을 그대로 보존한다.
조사/알레르기 표현 제거, 문장 parsing, 2~20자 heuristic을 다시 수행하지 않는다.
null collection/원소는 계약 위반으로 실패하며 빈 Complete로 바꾸지 않는다.

호출자가 이미 normalization을 거쳤음을 표시하는 타입이지 raw 문장 parser가 아니다.
잘못 감싼 `저 굴 알레르기 있어요`, `우유 먹으면 안 돼요`, `땅콩 조심해야 합니다`는
부분 문자열을 추출하지 않고 exact miss로 남는다. upstream 처리 자체를 타입이 자동 검증하지는 않는다.

## ProfileTermSource

정확히 세 종류만 있다.

| source | 의미 |
| --- | --- |
| CHAT_MESSAGE | 현재 메시지/user history에서 ChatSafetyContextService가 추출·정규화한 term |
| CHAT_REQUEST_PROFILE | chat request의 구조화된 allergy profile 값이 현재 normalization을 통과한 term |
| STORED_HEALTH_PROFILE | DB HealthProfile.allergies 값이 현재 normalization을 통과한 term |

AGENT_CONTEXT/RECOMMENDATION_CONTEXT는 만들지 않았다.
현재 SafetyContext는 출처별 typed term을 출력하지 않는다. 6c에서 기존 정규화 직후 source별 void Observer 메서드에
불변 문자열 목록을 전달하며, Observer 내부에서만 source와 typed input을 구성한다.

## Exact Resolution

1. 입력을 변경하지 않고 `AllergenRegistry.findExactAliases(input.value())`에 전달한다.
2. Registry의 기존 strip/lowercase exact 비교만 사용한다. substring/fuzzy/문장 parsing은 없다.
3. exact row 중 **DIRECT_NAME만** profile identity 후보로 인정한다.
4. eligible distinct ID가 1개면 Resolved, 2개 이상이면 AMBIGUOUS다.
5. row가 없으면 REGISTRY_MISS, row가 있어도 eligible row가 없으면 NO_PROFILE_ELIGIBLE_ALIAS다.

Relation 필터는 모든 term에 적용하는 profile eligibility 정책이며, 복수 DIRECT_NAME ID 사이의 우선순위가 아니다.
예를 들어 `굴`의 DIRECT_NAME OYSTER와 DERIVED_FROM SHELLFISH 중 후자는 profile identity 후보가 아니다.
두 DIRECT_NAME ID가 존재하면 첫 번째/알파벳순/위험도에 따라 임의 선택하지 않는다.
같은 ID에 여러 row가 있다는 이유만으로 AMBIGUOUS 처리하지 않는다.
현재 지원 `ProfileResolutionType`은 EXACT_ALIAS 하나다.

## Resolved Provenance

`ResolvedProfileAllergen(input, allergenId, resolutionType, matchedAliases)`를 반환한다.
기존 Registry와 동일한 String ID를 쓰며 별도 AllergenId abstraction은 만들지 않았다.

- input: original normalized value 및 source 보존.
- allergenId: 유일한 eligible Registry ID.
- matchedAliases: 이를 지지한 모든 exact DIRECT_NAME entry를 text/ID 순으로 보존한 불변 목록.
  일치 term과 relation은 각 entry에 있고 confidence는 기존 `AllergenAlias.confidence()` metadata다.
  DIRECT_NAME의 CERTAIN은 명칭 관계 확신도이며 사용자 반응 위험도를 새로 계산한 것이 아니다.

후보 입력은 collection의 encounter order를 유지한다. 같은 source/value가 반복돼도 각 occurrence를 보존한다.
Complete의 목록, Partial의 각 목록 안에서 상대적 입력 순서를 유지한다.
입력 collection 자체가 unordered인 경우 호출자가 순서를 제공해야 한다. Registry 순회 순서로 ID를 선택하지 않는다.

## REGISTRY_MISS

`키위`, `김`은 현재 typed Registry에 exact row가 없어 미해결이다.
`UnresolvedProfileAllergen(input, REGISTRY_MISS, empty candidateAllergenIds)`로 보존한다.
**REGISTRY_MISS != no allergy. REGISTRY_MISS != DROP.**
미등록 문자열을 fake ID로 만들어 Resolved에 넣지 않는다.

## AMBIGUOUS

eligible distinct IDs가 2개 이상이면
`UnresolvedProfileAllergen(input, AMBIGUOUS, candidateAllergenIds)`다.
후보 ID는 정렬된 불변 Set으로 남긴다. 하나라도 ambiguous면 전체 결과는 Partial이다.
**AMBIGUOUS != no allergy. AMBIGUOUS != PICK_ONE.**

현재 실제 Reference에는 ambiguous 직접 명칭이 없다. 테스트 전용 `합성명 → MILK, SOY`로 검증하며
production YAML에는 가짜 alias를 추가하지 않았다. 합성 ambiguity는 별도 unit/validation 검사로 구분한다.

## NO_PROFILE_ELIGIBLE_ALIAS

실제 exact row가 존재하는 `굴소스`, `대두레시틴`, `우유향`, `콩`을 REGISTRY_MISS라고 표시하면 부정확하다.
따라서 이 reason을 추가했다. DERIVED_FROM/LEXICAL_HINT만 있는 표현을 profile identity로 승격하지 않는다.
candidateAllergenIds는 해당 exact row의 ID를 진단용으로 보존할 뿐, 해결된 ID 목록이 아니다.
6a.1의 structured profile 보존과 모순되지 않는다. 원래 값은 기존 free-text 경로에 계속 남아 있다.

## Complete vs Partial

```java
sealed interface ProfileResolution
    permits CompleteProfileResolution, PartialProfileResolution {}

record CompleteProfileResolution(List<ResolvedProfileAllergen> resolved)
    implements ProfileResolution {}

record PartialProfileResolution(List<ResolvedProfileAllergen> resolved,
                                List<UnresolvedProfileAllergen> unresolved)
    implements ProfileResolution {}
```

모든 term이 해결되면 Complete, 하나라도 미해결이면 Partial이다.
`우유, 키위, 굴` → resolved MILK/OYSTER와 unresolved 키위/REGISTRY_MISS를 모두 보존한다.
모두 미해결이어도 빈 resolved + non-empty unresolved인 Partial이다. Partial 생성자는 빈 unresolved를 거부한다.
목록은 defensive copy이며 수정할 수 없다.

빈 collection은 Complete(empty)다. 미해결 입력이 0개라는 뜻으로,
user has no allergy 또는 SAFE가 아니다. **PARTIAL != safe**, Complete도 안전 판정이 아니다.

## Why ProfileResolution Has No Common resolved() Accessor

공통 interface에는 메서드가 없다. 소비자는 `instanceof CompleteProfileResolution` 또는
`PartialProfileResolution`으로 먼저 분기해야 목록에 접근할 수 있다.
미해결 결과를 인지하지 않고 `result.resolved()`만 호출하는 API를 허용하지 않는다.
sealed permits 목록과 공통 accessor 부재를 reflection unit test로 고정했다.

## Parent vs Derived

parent는 taxonomy/is-a, derived는 ingredient/lexical 관계다.
`굴소스`, `조개육수`는 taxonomy ID나 child node가 아니다.
Profile Resolver는 parent 조회·파생어 확장·literal fallback을 실행하지 않는다.

## Shellfish Taxonomy

`ko-allergens.yaml`의 기존 schema로 정확히 세 edge만 추가했다.

| child | parent | 상태 |
| --- | --- | --- |
| EGG | EGG_GROUP | 유지 |
| QUAIL_EGG | EGG_GROUP | 유지 |
| OYSTER | SHELLFISH | 6b 추가 |
| ABALONE | SHELLFISH | 6b 추가 |
| MUSSEL | SHELLFISH | 6b 추가 |

**OYSTER parent SHELLFISH != SHELLFISH profile entry 자동 생성.** `굴` resolution 결과는 OYSTER 하나다.
Label parser 역시 child evidence의 explicitChildren에 parent를 추가하지 않는다.
규제 YAML은 변경하지 않았으며 regulatory scope lookup은 여전히 독립적이다.
기존 RegulatoryRuleRegistryTest의 OYSTER parent 부재 전제는 새 taxonomy에 맞게 수정하고,
scope 조회 전후 Registry parent 전체가 동일하다는 검증을 유지했다.

## Legacy Literal Fallback Compatibility

Resolver에서 REGISTRY_MISS를 받아도 현재 production safety 경로는 그 결과를 소비하지 않는다. 6c의 사용처는 aggregate metric뿐이다.
`AllergenDictionary.matchTermsFor("키위") → 키위`, `김 → 김` literal fallback이 그대로 동작한다.
`김`은 `김 2장`과 충돌하며 `김치`에는 충돌하지 않는다.

`matchTermsFor("굴")`의 기존 목록은
`조개, 조개류, shellfish, 굴, 전복, 홍합, 바지락, 모시조개, 가리비, 소라, 굴소스, 조개육수`다.
새 parent 3개를 제거한 동일 YAML baseline과 모든 Registry term/미등록 표본의 legacy 탐지 결과를 비교했다.
aliases/derived metadata와 Matcher 결과는 변경되지 않는다.
AllergenDictionary/AllergenMatcher production 코드는 수정하지 않았다.

## Upstream Normalization Boundary

CURRENT OWNER는 ChatSafetyContextService다. 6a.1의 structured non-blank 보존과 chat exact known 1-char 방어는 유지한다.
6c에서는 normalization 결과의 관찰 복사본만 Observer로 보낸다. Caller가 Resolver나 typed 결과를 직접 참조하지 않는다.
`NormalizedProfileAllergenTerm`은 Observer 내부에서만 구성한다.
RecommendationService/RecipePersonalizationPolicies/ChatService에는 새 관찰 호출도 추가하지 않는다.

## Not UserAllergenMatch

제품 AllergenFact/Evidence와 profile을 비교하지 않는다.
explicitChildren, hierarchy risk matching, Safety Decision, SAFE/DANGER/CAUTION,
Negative Evidence, raw allergy logging, API/DB 변경은 범위 밖이다. 6c aggregate instrumentation은 안전 판정이 아니다.

Architecture Guard는 기존 출력 5종에 신규 10종(입력·source·Resolver·sealed 결과·상세 결과·enum)을 더해
총 15종의 package 밖 production import/FQCN을 금지한다. 6c에서는 recorder 2종도 추가 보호한다.
ChatSafetyContextService에만 void ProfileResolutionShadowObserver bridge를 허용하며 기존 결과 타입은 계속 차단한다.
단순 Utility Registry의 기존 사용은 유지한다.
실제 authority migration에서만 경계를 명시적으로 변경한다.

## Profile Reference and Test Metrics

`backend/src/test/resources/allergens/profile-reference.json`은 synthetic profile contract fixture다.
제품 Reference나 실사용자 분포가 아니다. 테스트 외 runtime 계측은 없다.
`ProfileReferenceValidation`은 ID 및 term/source 중복, blank, source, expectation 완전성,
unknown ID를 검사하고 실제 resolution 및 provenance와 비교한다.
동일 term/different source는 중복 오류로 취급하지 않는다.
에러/불일치를 분모에서 제외하지 않으며 resolution rate와 기대 계약 정확도를 구분한다.

| 지표 | 값 |
| --- | --- |
| total terms | 20 |
| resolved | 14 |
| unresolved | 6 |
| resolution rate | 70.0% |
| REGISTRY_MISS | 2 |
| AMBIGUOUS | 0 (별도 synthetic 테스트에서 검증) |
| NO_PROFILE_ELIGIBLE_ALIAS | 4 |
| source distribution | CHAT_MESSAGE 1 / CHAT_REQUEST_PROFILE 1 / STORED_HEALTH_PROFILE 18 |
| contract exact cases | 20/20 |

테스트 report: `backend/target/profile-reference-report.txt`.
실제 사용자 profile이나 production DB는 조회하지 않았다.

## Verification — 6b

- JDK 17.0.14 / Maven 3.9.9.
- Target: `mvn -q -Dtest='ProfileAllergenResolverTest,ProfileReferenceTest,ProfileReferenceValidationTest,AllergenHierarchyTest,AllergenArchitectureBoundaryTest,AllergenMatcherTest,ChatSafetyContextServiceTest,RegulatoryRuleRegistryTest' test`
  — 146 tests, failures/errors/skipped 0.
- Full: `mvn -q clean test` — 1064 tests, failures 0, errors 0, skipped 1 (`RecipeAccuracyLiveTest`).
  외부 live/LLM 검증은 실행하지 않았다.
- Guard: 기존 18개 검증을 유지하고 신규 10종을 더 검사해 28 tests, violations 0.
- Declaration 15/15, Cross-contact 10/10, Ingredient 19/19, Evidence Resolver 19/19,
  lexical 25/25, candidate 24/24, unresolved ingredient 7회/6종 유지.
- Regulatory unit 46개·Reference 23개 통과. 적용일 미확인 551건은 UNKNOWN 유지.
- 6a.1 stored/request 굴·structured 김 보존, chat known/unknown 1-char, 키위/김 literal fallback 회귀 통과.
- 기존 runtime/config/build 196개 파일 중 변경은 lexical YAML의 parent 3줄뿐이다.
  기존 Java·규제 YAML·POM은 작업 전후 SHA-256 동일. 신규 내부 Java 타입 10개만 추가했다.
- repository root `git diff --check`와 변경한 untracked 파일 별도 whitespace 검사 통과.
  commit/push/PR/branch 변경 없음.

## Next Step

6c instrumentation은 [Profile Resolution Shadow Observation](allergen-profile-shadow.md)에 기록했다.
다음은 6d 설계 전 Shadow Resolution 분포 해석이다. 실제 분포는 운영 관찰이 필요하며
위 6b 합성 fixture의 70%를 실사용 지표로 재사용하지 않는다.
