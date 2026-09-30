# 근거 등급제 설계 (진행 중)

레시피 생성의 근거를 등급으로 나누어, 높은 등급이 없으면 낮은 등급으로 내려가되 어느 등급인지 사용자에게 알리는 구조입니다. 2026-09-27 기준 오케스트레이터 쪽만 구현됐고 `ChatService` 배선이 남았습니다.

## 왜 필요한가

Recipe Agent는 웹 페이지의 schema.org Recipe 마크업에서 재료와 분량을 구조화된 배열로 가져옵니다. 현재 운영 경로는 검색 스니펫(산문)을 프롬프트에 넣고 LLM이 분량을 추측하게 합니다. 평가에서 `INGREDIENT_AMOUNT_REQUIRED`가 반복된 원인입니다.

그런데 Agent를 켜면 다른 문제가 생깁니다. `ChatService`가 Agent로 요청을 넘기면 일반 생성 경로로 돌아오지 않고, Agent는 근거를 못 찾으면 레시피를 지어내지 않고 거절합니다. 마크업이 없는 요리는 커버리지가 통째로 사라집니다.

실측 (2026-09-26, SearXNG + 도메인 랭킹):

| 항목 | 값 |
|---|---|
| 요리 8개 중 구조화 근거 확보 | 7개 (88%) |
| 페이지 적중률 | 14/40 (35%) |
| 근거를 못 찾은 요리 | 마라샹궈 |

즉 8개 중 1개는 Agent가 거절하게 됩니다.

## 등급 정의

```
① 승인 레시피 카탈로그     검증된 레시피 (approved-recipes.json)
② Agent 구조화 근거        schema.org Recipe — 재료·분량이 배열
③ 스니펫 근거              검색 결과 발췌 — LLM이 산문에서 추출
④ 거절                     근거 없음
```

③으로 내려가도 근거 없이 지어내는 것이 아닙니다. 약한 근거를 쓸 뿐이고 알레르겐 검사와 레시피 검증기는 그대로 적용됩니다. `UNKNOWN`을 `SAFE`로 바꾸지 않는다는 원칙과 충돌하지 않으며, 오히려 확신의 정도를 정직하게 표시하는 방향입니다.

## 구현 상태

### 완료 — `RecipeAgentOrchestrator.handleIfSourceFound`

구조화 출처를 찾았을 때만 응답을 돌려주고, 못 찾으면 `Optional.empty()`를 반환합니다.

- 탐색은 1회만 합니다. 사전 확인과 본 실행으로 두 번 검색하지 않습니다.
- 출처가 없으면 개인화·검증·세션 저장을 모두 건너뜁니다. 빈 세션이 저장되면 다음 요청이 그 상태를 재사용합니다.
- 후속 요청(이전 세션 상태 존재)은 이미 찾아 둔 출처를 재사용하므로 항상 응답합니다.

테스트 3건 (`RecipeAgentPersonalizationTest`):

- `handleIfSourceFoundReturnsEmptyWhenNoReliableSource` — 빈 값 + 세션 미저장
- `handleIfSourceFoundReturnsResponseWhenSourceExists`
- `handleIfSourceFoundReturnsEmptyWhenDiscoveryDisabled`

### 남음 — `ChatService` 배선

`processChat`(84~370행)의 Agent 분기(143~155행)가 조기 반환하므로, 빈 값을 받아도 아래 215행으로 내려갈 수 없습니다.

필요한 변경:

1. Agent 분기 이후 코드를 `continueWithEvidencePath(...)` 같은 메서드로 추출
2. 분기를 다음 형태로 교체

```java
if (agentCondition) {
    return recipeAgentOrchestrator.handleIfSourceFound(userId, sessionId, request)
            .flatMap(found -> found.map(Mono::just)
                    .orElseGet(() -> continueWithEvidencePath(...)));
}
return continueWithEvidencePath(...);
```

3. 플래그 `recipe.agent.evidence-tier-fallback`로 감싸 기존 동작을 보존

**블로킹 우회는 쓰지 않습니다.** Agent 탐색은 네트워크 I/O이며 현재 `subscribeOn(boundedElastic)`으로 요청 스레드를 막지 않습니다. 사전 확인을 위해 `block()`을 쓰면 그 이점을 버리게 됩니다.

이 추출은 핵심 채팅 경로의 대규모 리팩터링이라 별도 승인과 리뷰가 필요합니다.

## 이후 순서

1. `ChatService` 추출 및 배선
2. `recipe.agent.enabled=true`로 전환 — 단 `RecipeAgentPersonalizationTest`가 기본값 false를 고정하고 있으므로 그 테스트의 의도를 먼저 확인해야 합니다
3. 등급별 통과율 측정. 기대는 Agent 단독보다 높고 스니펫 단독보다 품질이 좋은 구간입니다
4. 응답에 근거 등급 표시 — 사용자가 확신의 정도를 알 수 있게
