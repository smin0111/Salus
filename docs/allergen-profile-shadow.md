# Profile Resolution Shadow Observation

6c — 2026-09-22 검증 완료. 기존 safety 경로의 정규화 결과를 관찰하는 instrumentation이다.
[Profile Resolution](allergen-profile-resolution.md)의 identity semantics는 변경하지 않는다.

## Purpose

이미 생성된 normalized allergy terms를 ProfileAllergenResolver에 전달하고,
source별 해결·미해결 분포를 aggregate counter로 기록한다.
실사용자 데이터를 조회하거나 분포를 수집한 작업이 아니다. 현재 상태는 **instrumentation ready**다.

## Observation-only Boundary

```text
ChatSafetyContextService의 기존 normalization + validation
    → 기존 SafetyContext/Matcher/차단·안내
    → ProfileResolutionShadowObserver (void)
         → ProfileAllergenResolver
         → enum-only metrics recorder
         → 기존 Micrometer MeterRegistry
```

**Safety authority migration = NO.** 열린 경계는 observation only다.
Shadow Resolver 결과는 현재 production safety decision에 사용되지 않는다.
호출자는 resolver/result/source enum을 import하지 않고 다음 observer 메서드만 호출한다.

```java
void observeStoredProfile(Collection<String> normalizedTerms)
void observeRequestProfile(Collection<String> normalizedTerms)
void observeChatMessage(Collection<String> normalizedTerms)
```

source는 메서드 내부에서 고정하므로 ProfileTermSource까지 경계 밖에 노출할 필요가 없다.
Observer는 결과·boolean·resolved 목록을 반환하지 않는다. Resolver는 독립 Spring bean이 아니며
observer 안에서 기존 Registry로 구성한다. Observer/recorder만 Spring component로 연결한다.

## Wiring Points

`backend/src/main/java/com/salus/healthytable/service/ChatSafetyContextService.java`:

| source | 위치 | batch 정의 |
| --- | --- | --- |
| STORED_HEALTH_PROFILE | `build`의 DB HealthProfile 처리 | 한 번 읽은 allergies 목록의 정규화·수용 결과 |
| CHAT_REQUEST_PROFILE | `appendRequestHealthProfileValues` | 요청에 포함된 allergies 목록의 정규화·수용 결과 |
| CHAT_MESSAGE | `appendAllergyMentionsFromText` | 현재 메시지 또는 user history 메시지 하나의 추출·수용 결과 |

기존 normalization/validation을 한 번 실행한 값을 source-local LinkedHashSet에 모은다.
기존 safety Set에 먼저 합친 후 불변 복사본을 Observer에 전달한다.
Observer에서 조사 제거·표현 제거·길이 heuristic을 다시 수행하지 않는다.
기존 safety 결과의 정렬·중복 제거와 structured profile 보존은 그대로다.

source-local 중복은 한 번만 센다. 동일 프로필 값의 중복 입력이나 같은 메시지에 대한
여러 extractor 패턴의 중복 hit를 새 term으로 부풀리지 않는다.
동일 값이라도 source 또는 메시지 batch가 다르면 별도로 센다. 전역 safety Set에 이미 존재한다는
이유로 다른 source의 observation을 지우지 않는다.
Observer 자체는 받은 occurrence를 dedup하지 않으며, producer가 확정한 batch를 그대로 처리한다.

한 요청마다 history가 다시 전달되면 각 메시지를 다시 관찰한다. 따라서 지표는
**요청 처리 중 관찰된 source batch/term 수**이며 unique user/unique lifetime term 수가 아니다.
사용자·메시지 식별자를 저장하지 않고 이 의미를 문서로 고정한다.

RecommendationService에는 연결하지 않는다. Agent도 새로운 source나 downstream observation을 추가하지 않는다.
Agent로 라우팅되는 요청이 기존 ChatSafetyContextService를 거칠 때의 관찰만 발생한다.
모든 source에서 수용된 term이 0개면 Observer 호출을 생략한다. Observer도 빈 입력이면 아무 metric을 기록하지 않는다.

## Source Definitions

기존 ProfileTermSource의 CHAT_MESSAGE / CHAT_REQUEST_PROFILE / STORED_HEALTH_PROFILE 세 종류만 사용한다.
model history, 일반 음식 언급, 거부된 자연어 한 글자 후보는 관찰 대상이 아니다.
DB 조회 실패는 stored observation을 만들지 않으며 기존 healthContextAvailable=false를 유지한다.
UNKNOWN 값을 빈 Complete나 “알레르기 없음”으로 바꾸지 않는다.

## Term Metrics

이름: `salus.allergen.profile_resolution.shadow.terms`

| label | 허용 값 |
| --- | --- |
| source | 위 세 source |
| outcome | RESOLVED / REGISTRY_MISS / AMBIGUOUS / NO_PROFILE_ELIGIBLE_ALIAS |

Complete의 resolved occurrence와 Partial의 resolved/unresolved occurrence를 각각 센다.
예: `우유, 키위` → RESOLVED +1, REGISTRY_MISS +1.
미해결 reason을 바꾸거나 Registry를 보강하지 않는다.

## Batch Metrics

이름: `salus.allergen.profile_resolution.shadow.batches`

- labels: source, resolution.
- resolution: COMPLETE / PARTIAL.
- 성공적으로 기록한 observer 호출당 batch 1개. term counter 기록 뒤 batch counter를 기록한다.
- empty source에는 COMPLETE batch를 대량 생성하지 않는다.

## Execution and Failure Metrics

- `salus.allergen.profile_resolution.shadow.executions`: 비어 있지 않은 observer 실행 시도.
- `salus.allergen.profile_resolution.shadow.failures`: shadow 예외 발생 시 best-effort 증가.
- 두 metric의 label은 source 하나뿐이다.

프로젝트에 이미 `spring-boot-starter-actuator`가 있어 Micrometer를 재사용했다.
새 dependency/Prometheus/OpenTelemetry/endpoint는 추가하지 않았다.
현재 `application.properties`의 web exposure는 health,info다. 이 변경만으로 metrics endpoint가
외부에 열리거나 장기 저장/export가 설정되는 것은 아니다. 운영 수집 경로는 별도 환경 설정이 필요하다.
프로세스 내 registry의 counter는 재시작·registry lifecycle에 영향을 받는다.

## Privacy

`ProfileResolutionShadowMetrics` API는 source/outcome/resolution enum만 받는다.
String term, allergen ID, alias, 사용자/계정/session/chat/profile ID, email, message, recipe/product name을 받지 않는다.
Observer는 normalized 문자열을 일시적으로 resolver에 전달하지만 저장·로그·metric label에 넣지 않는다.

label key는 source/outcome/resolution뿐이다. 이 component가 만드는 최대 조합은
term 12 + batch 6 + execution 3 + failure 3 = **24 series**다.
Micrometer counter에 실제 생성된 tag 집합과 recorder signature를 테스트한다.

Resolver/recorder exception의 message·stack trace·Throwable 인자를 로그에 전달하지 않는다.
failure metric조차 실패하면 `source=<enum>`만 포함한 고정 로그를 남긴다.
caller의 최종 격리 로그도 고정 문자열이며 예외 객체를 포함하지 않는다.
민감 문자열을 넣은 예외를 발생시켜 formatted message, argument array, throwable proxy를 확인한다.

## Fail-open Behavior

Shadow failure는 production request failure를 유발하지 않는다.
Observer boundary가 입력 wrapper 생성, Resolver, outcome 기록 중 RuntimeException을 격리한다.
failure counter를 한 번 시도하고, recorder 실패 시 재귀적으로 재시도하지 않는다.
Resolver 결과가 null인 예상 밖 경우도 failure로 처리한다.

ChatSafetyContextService에도 마지막 bridge 격리를 둔다. Observer 구현 자체가 예외를 던져도
이미 만들어진 safety 값은 남고, stored profile 조회 실패로 오인되지 않는다.
기존 DB/실제 safety 검사 실패는 이 shadow catch로 감추지 않는다.
JVM 종료/OOM 같은 복구 불가능한 Error를 catch하는 설계는 아니다.

metric 기록은 transaction이 아니다. recorder가 중간에 실패하면 일부 counter만 증가할 수 있으며
failure counter도 기록되지 않을 수 있다. failure와 execution을 함께 보고 부분 관찰을 해석해야 한다.
자동 재시도는 하지 않아 중복 증가를 피한다. 텔레메트리 장애를 안전 판정으로 보상하지 않는다.

## Architecture Guard

기존 protected Profile/Evidence/Regulatory 15종은 계속 package 밖 사용을 금지한다.
recorder interface/implementation 2종도 내부로 보호한다.
유일한 신규 bridge는 ProfileResolutionShadowObserver이며 정확한 caller 경로
`com/salus/healthytable/service/ChatSafetyContextService.java`와 package에만 허용한다.

Guard는 다음을 검증한다.

- ChatSafetyContextService의 Observer import/FQCN 허용.
- 같은 caller의 ProfileAllergenResolver/ProfileResolution/상세 결과 직접 import 금지.
- 다른 caller의 Observer 사용과 package wildcard 금지.
- Observer 공개 API는 Collection을 받는 void 메서드 3개뿐.

Registry/Dictionary/Matcher의 기존 utility 사용은 유지한다. Authority migration을 허용한 변경이 아니다.

## What Shadow Does NOT Do

UserAllergenMatch, Evidence/Profile matching, explicitChildren matching, Safety Decision,
차단/경고 추가, structured-label API, Negative Evidence, DB/schema 변경,
자동 alias 보강, production authority migration을 구현하지 않는다.
기존 Matcher/추천/Agent policy와 Resolver semantics/YAML은 그대로다.

## Difference from profile-reference 70%

6b `profile-reference.json`의 14/20 = 70%는 합성 계약 fixture의 resolution rate다.
**실사용 resolution rate와 별개이며 이번 작업에서 실사용 분포를 측정하지 않았다.**
테스트는 성공/미해결/모호성/실패 계수와 관찰 경계를 확인한 결과만 제공한다.
실제 해석 시 source별 관찰 단위, history 재관찰, failure에 따른 누락을 함께 고려해야 한다.

## Verification — 6c

- Target: `mvn -q -Dtest='ProfileResolutionShadowObserverTest,MicrometerProfileResolutionShadowMetricsTest,ProfileResolutionShadowIntegrationTest,AllergenArchitectureBoundaryTest,ChatSafetyContextServiceTest,ChatServiceSafetyTest,AllergenGapTest' test`
  — 104 tests, failures/errors/skipped 0.
- Full: `mvn -q clean test` — 1090 tests, failures 0, errors 0, skipped 1 (`RecipeAccuracyLiveTest`).
  외부 live/LLM 검증은 실행하지 않았다.
- Observer 12개, Micrometer/privacy 3개, production source wiring/invariance/Spring integration 7개 통과.
  Guard 32개 통과, violations 0.
- Declaration 15/15, Cross-contact 10/10, Ingredient 19/19, Evidence Resolver 19/19,
  lexical 25/25, candidate 24/24, unresolved ingredient 7회/6종 유지.
- Regulatory unit 46개·Reference 23개 및 6b Profile Reference 20/20 통과.
- 기존 runtime/config/build 206개 중 ChatSafetyContextService의 관찰 연결과
  ProfileAllergenResolver의 설명 주석만 변경했다. Resolver 실행 코드, 두 YAML, POM은 그대로다.
  새 production Java는 Observer/recorder interface/Micrometer recorder 3개다.
- root `git diff --check` 및 변경한 untracked 파일 별도 공백 검사 통과.
  Commit/push/PR/branch 변경 없음. 실제 운영 분포는 측정하지 않았다.

## Next Step

6d 설계 전 Shadow Resolution 분포 해석.
운영 aggregate 수집 경로와 관찰 기간을 먼저 확인하고 source별 미해결 분포를 검토한다.
미해결이 많다고 raw term telemetry를 자동 추가하거나 Registry를 즉시 변경하지 않는다.
