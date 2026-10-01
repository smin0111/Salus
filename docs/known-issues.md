# Salus 알려진 이슈

세션 중 코드 추적으로 발견한 문제를 기록합니다. 새 이슈는 아래 형식으로 계속 추가합니다.

- 작성 기준: 2026-09-20 작업 트리 (커밋 `7aaeefc` + WIP)
- 각 항목은 코드 근거(`path:line`)를 반드시 붙입니다. 추측으로 채우지 않습니다.
- **안전 영향** 칸이 "있음"인 이슈는 `docs/ai/SAFETY_RULES.md` 절차를 따릅니다. 수정 시 targeted test가 반드시 동반됩니다.
- 상태: `OPEN` / `IN PROGRESS` / `FIXED` / `WONTFIX`

| # | 제목 | 안전 영향 | 상태 |
|---|---|---|---|
| 1 | 승인 레시피 경로가 재료 제외 요청을 무시 | 없음 | OPEN |
| 2 | 비레시피 답변이 단어 3개 검사로 통째 폐기 | 없음 | OPEN |
| 3 | 신규 가입자 온보딩 유도 부재 | 없음 | OPEN |
| 4 | 요리명 정규화 누락으로 근거 검색 전멸 | 없음 | OPEN |
| 5 | 모델 평가 도구·결과가 저장소 밖에만 존재하고, 기록된 결론이 현재 기본 모델과 모순 | 없음 | IN PROGRESS |

공통 근본 원인: **자연어를 정규식·부분 문자열로 판정**한다. 표현이 조금만 달라지면 결과가 통째로 뒤집힌다. 1·2·4가 모두 같은 계열이다.

---

## 이슈 1 — 승인 레시피 경로가 "~빼고 / 안 들어간" 요청을 무시

| 항목 | 내용 |
|---|---|
| 상태 | OPEN |
| 안전 영향 | 없음 (알레르기 등록자는 정상 차단됨) |
| 심각도 | 중 — 사용자 신뢰 훼손 |

### 증상
알레르기가 등록되지 않은 사용자가 `"계란이 안 들어간 계란찜 레시피 알려줘"`라고 요청하면, **계란이 들어간 승인 계란찜이 그대로 출력**된다. 제외 요청이 조용히 무시된다.

### 원인
승인 카탈로그 매칭이 부정 표현을 보지 않는 단순 부분 문자열 검사다.

`backend/.../service/ApprovedRecipeService.java:124`
```java
String normalizedRequest = normalizeLookup(requestText);   // 공백·기호 제거
return aliases.stream()
        .filter(alias -> normalizedRequest.contains(alias.normalizedAlias()))
```
`"계란이안들어간계란찜레시피알려줘".contains("계란찜")` → true.

제외 재료를 읽을 줄 아는 코드는 존재하지만 도달하지 못한다.

- 제외 추출기: `backend/.../service/ChatRequestParser.java:185` — `([^\s,]+?)(?:이|가)?\s*안\s*들어간` 으로 "계란"을 정확히 추출한다.
- 호출 지점: `backend/.../service/ChatService.java:246`
- 그러나 승인 레시피 블록이 `ChatService.java:189`에서 시작해 `return` 으로 끝난다. 246번째 줄은 실행되지 않는다.

### 대조
| 요청 | 승인 카탈로그 | 제외 처리 | 결과 |
|---|---|---|---|
| 계란 없는 **계란찜** | 있음 | 무시됨 | 계란 든 계란찜 |
| 두부 없는 **두부찌개** | 없음 | 정상 (`RecipeGenerationCoordinator.java:80`) | 두부 뺀 레시피 시도 |

같은 문법인데 메뉴가 승인 목록에 있느냐로 정반대로 동작한다. 카탈로그를 10개에서 늘리면 영향 범위가 같이 커진다.

### 제안
1. (권장) 승인 경로에서 솔직하게 거절 — "계란찜은 계란이 핵심 재료라 빼면 다른 요리가 됩니다. 두부찜을 원하시면 말씀해 주세요." 안전 로직 무변경, 카탈로그 취지(검증본 무변형)와 일치.
2. 승인 매칭 전에 `extractExcludedIngredients` 결과가 해당 레시피 재료에 포함되면 승인 경로를 건너뛰고 LLM 경로로 내려보낸다.

### 참고 — 알레르기 등록자는 정상 동작
`"계란"`(2글자)은 부분 일치라 토큰 `"계란이"`, `"계란찜"` 모두에 걸려 `ChatService.java:171`에서 차단된다. 요청한 요리가 실제로는 안전해도 거절되는 과차단이지만, 이는 `ChatSafetyContextService.java:209`에 명시된 의도된 fail-closed 정책이다.

---

## 이슈 2 — 비레시피 답변이 단어 3개 검사로 통째 폐기

| 항목 | 내용 |
|---|---|
| 상태 | OPEN |
| 안전 영향 | 없음 (안전 장치의 과잉 발동) |
| 심각도 | 중상 — 첫인상에 직접 타격 |

### 증상
`"저녁메뉴 추천해줘"`(`MENU_RECOMMENDATION`)에 대해 LLM이 만든 답변 전체가 버려지고 아래 고정 문구로 교체된다. **추천 메뉴가 하나도 남지 않는다.**

> 좋아요. 메뉴 추천으로만 짧게 도와드릴게요. 상세 레시피가 필요하면 음식명과 함께 '레시피'나 '만드는 법'이라고 말씀해 주세요.

### 원인
`backend/.../service/RecipeResponseSanitizer.java:711`
```java
boolean looksLikeRecipeResponse(String reply) {
    return reply.contains("kcal") || reply.contains("레시피") || reply.contains("재료");
}
```
`backend/.../service/ChatService.java:360`
```java
if (!isLlmUnavailableReply(reply) && looksLikeRecipeResponse(reply)) {
    responseReply = buildNonRecipeIntentReply(intent, request.getMessage());   // 통째 교체
}
```

메뉴를 추천하면서 저 단어를 피하기 어렵다. "재료가 간단해서", "원하시면 레시피도 알려드릴게요", "약 600kcal" 모두 폐기 조건이다. **친절하게 답할수록 폐기 확률이 올라간다.**

### 의도는 타당
검증 파이프라인을 거치지 않은 레시피가 새어나가는 것을 막는 안전장치다(주석: "레시피 요청이 아닌데 레시피 형태로 답했다면 검증되지 않은 레시피이므로"). 문제는 판정 방식이지 목적이 아니다.

### 제안
- 단어 1개 포함이 아니라 **레시피 구조 신호**(번호 목록 + 계량 단위 + 재료 섹션 등 복수 조건)로 판정한다.
- 교체 시 LLM 답변을 통째로 버리지 말고, 메뉴 후보 부분은 살리고 조리법 섹션만 잘라낸다.
- 최소 조치: `buildNonRecipeIntentReply`(`ChatService.java:542`)가 빈손으로 끝나지 않도록 문구를 보강한다.

---

## 이슈 3 — 신규 가입자 온보딩 유도 부재

| 항목 | 내용 |
|---|---|
| 상태 | OPEN |
| 안전 영향 | 없음 |
| 심각도 | 중 — 핵심 가치 전달 실패 |

### 증상
방금 가입해 건강 프로필·냉장고·검진 기록이 모두 비어 있는 사용자가 첫 질문을 해도, **개인화가 0인 상태라는 사실을 사용자에게 알리지 않는다.** 프로필 등록 유도 문구가 어느 응답 경로에도 없다.

### 배경 (정상 동작인 부분)
"프로필이 비어 있음"과 "프로필을 못 읽음"은 정상적으로 구분된다. 신규 가입자는 전자라 차단되지 않는다.

- `backend/.../service/ChatSafetyContextService.java:67` — `findByUserId(...).ifPresent(...)`. 값이 없으면 아무것도 안 하고 `healthContextAvailable`은 `true` 유지.
- `backend/.../service/ChatSafetyContextService.java:126` — 검진 기록도 동일 패턴, `return true`.

이 구분이 없었다면 모든 신규 가입자가 첫 메시지부터 "건강 정보를 안전하게 확인하지 못해..." 를 받았을 것이다.

### 참고 — 냉장고는 신규 여부와 무관하게 잠겨 있음
`backend/.../service/ChatService.java:286`
```java
// 일반 레시피 정확도 검증이 끝날 때까지 냉장고 조회와 활용은 수행하지 않는다.
if (!isRecipeRequestIntent) {
```
재료를 채워둔 기존 사용자도 마찬가지로 활용되지 않는다. 이슈가 아니라 의도된 잠금이므로 해제 시점은 별도 판단이 필요하다.

### 제안
`SafetyContext.hasAny()`가 false일 때 응답 말미에 프로필 등록 안내를 1줄 덧붙인다. 안전 로직 무변경.

---

## 이슈 4 — 요리명 정규화 누락으로 근거 검색이 전멸

| 항목 | 내용 |
|---|---|
| 상태 | OPEN |
| 안전 영향 | 없음 |
| 심각도 | 상 — 정상 요청이 거절됨. 수정 난이도는 가장 낮음 |

### 증상
`"새우크림파스타 레시피좀 알려줘"` → 레시피가 생성되지 않고 거절된다.

> 죄송합니다. 신뢰할 수 있는 레시피 정보를 찾지 못했습니다. 다른 음식이나 정통 레시피를 물어봐 주세요.

`"좀"` 두 글자를 빼면 정상 생성된다.

### 원인
`backend/.../service/RecipeNormalizer.java:13` 의 제거 목록에 `"좀"`이 없다. 조사 제거 정규식 `(으로|로|을|를|이|가|은|는|에|의|도|만|봐)$` 에도 없고, 문자열이 공백으로 끝나 `$` 앵커도 걸리지 않는다.

```
"새우크림파스타 레시피좀 알려줘"
  "레시피" 제거 → "새우크림파스타 좀 알려줘"
  "알려줘" 제거 → "새우크림파스타 좀 "
  trim         → "새우크림파스타 좀"      ← 오염된 요리명
```

이 값이 그대로 근거 검색에 쓰인다(`RecipeEvidenceService.java:89`). 검색 엔진은 `"좀"`을 무시해 결과를 정상 반환하지만, 신뢰도 필터에서 전멸한다.

`backend/.../service/RecipeEvidenceService.java:243`
```java
String requested = nullToBlank(requestedTitle)
        .replaceAll("[^가-힣a-zA-Z0-9]", "")   // → "새우크림파스타좀"
...
boolean dishMatches = title.contains(requested) || compactSnippet.contains(requested);
if (!dishMatches) return false;
```
웹 어디에도 `"새우크림파스타좀"` 표기는 없으므로 모든 결과가 탈락 → `WEB_SEARCH_UNRELIABLE` → `EMPTY` → `ChatService.java:317`에서 거절. **LLM은 호출조차 되지 않는다.**

### 비결정적이라 재현이 어려움
필터가 공백을 지우고 비교하므로, 블로그에 "새우크림파스타 좀 만들어봤어요" 같은 문장이 있으면 압축 시 우연히 일치해 통과할 수 있다. **성공 여부가 남의 블로그 말투에 달려 있다.**

### 부수 문제 — 캐시되지 않아 매번 재검색
`RecipeEvidenceService.java:115` 의 `WEB_SEARCH_UNRELIABLE` 분기는 `writeNegativeCache`를 호출하지 않는다(`:107`의 `EMPTY` 분기와 대비). 같은 요청을 반복해도 매번 웹 검색을 새로 돌린 뒤 동일하게 거절한다. 느리고 낭비다.

### 부수 문제 — 오염된 이름이 사용자에게 노출
알레르기 차단 문구가 정규화된 제목을 그대로 사용한다(`ChatSafetyContextService.java:180`).
> 확인된 알레르기 정보상 '새우' 알레르기가 있어 '**새우크림파스타 좀**' 레시피는 추천할 수 없습니다.

### 제안
1. (권장) `RecipeNormalizer`와 `ChatRequestParser.RECIPE_QUERY_STOPWORDS`의 불용어 목록을 **하나로 합친다**. 후자에는 이미 `"좀"`이 들어 있다. 목록이 두 벌이라 한쪽만 갱신되는 구조가 근본 원인이다.
2. `RecipeNormalizerTest`에 조사·부사 변형 케이스를 추가한다.
3. 별건으로 `WEB_SEARCH_UNRELIABLE`의 negative cache 정책을 재검토한다.

---

## 이슈 5 — 모델 평가 결과가 저장소 밖에만 있고, 기록된 결론이 현재 기본 모델과 모순

| 항목 | 내용 |
|---|---|
| 상태 | IN PROGRESS |
| 안전 영향 | 없음 |
| 심각도 | 중 — 의사결정 추적 불가 |

### 증상
저장소만 보면 `qwen3:8b`를 기본 모델로 고른 근거가 커밋 `9185907`의 메시지 한 줄뿐이다. 그보다 앞선 체계적인 모델 평가가 실제로 존재하지만, 저장소 어디에도 없고 **결론이 정반대**다.

### 사실관계
평가 도구: `backend/tools/recipe-model-eval/` (`run.sh`, `cases.json` 20케이스, 스모크 5케이스)

- `git log --oneline --all -- backend/tools/recipe-model-eval` → **결과 없음.** 한 번도 커밋되지 않았다.
- 현재 작업 트리에는 **빈 디렉터리 구조만** 남아 있다 (`du -sh` = 0B).
- 실제 파일은 `../Salus-untracked-quarantine-20260730-ntHi0E/files/backend/tools/recipe-model-eval/`에 있다. 2026-07-30 미추적 파일 격리 작업 때 옮겨진 것으로 보인다(같은 폴더의 `untracked-files.txt` 340줄).

비교된 모델: `gemma2:latest`, `llama3:latest`, `qwen3:8b`(thinking on/off), `gemma3:12b`(다운로드 안 되어 미실행)

스모크 5케이스 결과 (`results/qwen3-8b-no-thinking-smoke-v2/comparison-all-models.md`, 2026-07-16):

| 모델 | 생성 성공 | 타임아웃 | 최종 노출 가능 |
|---|---:|---:|---:|
| gemma2 (수정 후) | 2/5 | 3/5 | 1/5 |
| llama3 | 4/5 | 1/5 | 0/5 |
| qwen3 thinking 켬 | 0/5 | 5/5 | 0/5 |
| qwen3 `think=false` | 0/5 | 5/5 | 0/5 |

당시 결론 원문: *"Do not promote qwen3:8b to the Salus production recipe model."*

5주 뒤 커밋 `9185907`(2026-08-20)은 `RecipeAccuracyLiveTest` 3건(gemma2 0/3, qwen3 3/3)을 근거로 **qwen3:8b를 기본 모델로 승격**했다. 이전 평가를 언급하지 않는다.

### 왜 문제인가
- 뒤집힌 이유가 어디에도 기록되어 있지 않다. 두 평가는 케이스·temperature(0.15 vs 0.0)·top_p(0.8 vs 0.3)·`num_predict`(1200 vs 900)가 모두 다르지만, 어느 요인이 결정적이었는지는 근거가 없다.
- llama3의 실측 약점(한국어 작업에 영어로 응답 → 한국어 필수 재료 매칭·RAG 대조 실패)처럼 재사용 가치가 큰 관찰이 저장소 밖에 있어 검색되지 않는다.
- `docs/llm-eval-harness.md`의 신규 하네스(`eval.sh`)는 이 도구를 대체하는 것으로 보이나, 두 도구의 관계가 어디에도 서술되어 있지 않다.

### 제안
1. 격리 폴더의 `results/*.md` 중 비교 결론 파일을 `docs/`로 옮겨 커밋한다(원본 JSON/CSV는 용량을 보고 판단).
2. `docs/llm-eval-harness.md`에 "이전 도구 `recipe-model-eval`과의 관계, 그리고 7월 결론이 8월에 뒤집힌 경위"를 한 절로 남긴다.
3. `./eval.sh --mode live --models qwen3:8b,gemma2:latest --repeat 3 --no-gate`로 현재 파라미터에서 재측정해 결론을 갱신한다.
4. `backend/tools/recipe-model-eval/`의 빈 디렉터리를 정리하거나, 도구를 되살릴지 결정한다.

### 후속 관찰 (2026-09-20) — 평가 후보와 설치 모델이 엇갈림

`ollama list` 기준 현재 로컬에 설치된 모델은 `qwen3:8b`(5.2GB)와 `gemma3:4b`(3.3GB) 둘뿐이다.

| 모델 | 평가 기록 | 현재 설치 |
|---|---|---|
| `gemma2` | 있음 (스모크 2/5 생성, 1/5 노출 가능) | 없음 |
| `llama3` | 있음 (스모크 4/5 생성, 0/5 노출 가능) | 없음 |
| `qwen3:8b` | 있음 | 있음 |
| `gemma3:12b` | 후보 목록에만 있고 미다운로드로 미실행 | 없음 |
| `gemma3:4b` | **후보에도 없었음. 평가 기록 전무** | 있음 |

- 평가 도구의 기본 후보는 `gemma3:12b`였다 (`RecipeModelEvaluationRunner.java:62`, 격리 폴더). 설치된 `gemma3:4b`와 다른 모델이다.
- 평가된 두 모델(`gemma2`, `llama3`)은 로컬에서 삭제되어 재현이 불가능하다.
- `docs/llm-eval-harness.md`의 예시 명령 `./eval.sh --models qwen3:8b,gemma2:latest`는 현재 환경에서 `gemma2`가 `MODEL_NOT_AVAILABLE`로 기록된다.

추가 제안: `gemma3:4b`는 설치되어 있고 하네스도 준비되어 있으므로 현재 파라미터에서 바로 측정 가능하다.

```bash
./eval.sh --mode live --models qwen3:8b,gemma3:4b --repeat 3 --no-gate
```

### 확인 방법
```bash
ollama list
git log --oneline --all -- backend/tools/recipe-model-eval          # 비어 있음
du -sh backend/tools/recipe-model-eval                               # 0B
ls ../Salus-untracked-quarantine-20260730-ntHi0E/files/backend/tools/recipe-model-eval/results
```

---

### 후속 조치 (2026-09-24) — 6개 모델 재측정으로 결론 모순 해소

`docs/reference/model-eval-20260924.md`에 전체 기록.

현재 파라미터(temperature 0.0, top_p 0.3, num_predict 900)에서 live 12케이스로 6개 모델을 비교했다. 오탐 보정 후 통과율:

| 모델 | 보정 통과 | 점수 |
|---|---:|---:|
| qwen3:8b | 7/12 | 82.5 |
| gemma3:4b | 7/12 | 78.0 |
| qwen2.5:7b | 4/12 | 74.6 |
| gemma2:9b | 4/12 | 67.5 |
| aya-expanse:8b | 4/12 | 76.3 |
| exaone3.5:7.8b | 2/12 | 67.9 |

**해소된 것**

- 2026-08-20 커밋 `9185907`의 qwen3 승격은 정당했다. gemma2:9b는 동일 케이스에서 재료 5개 전부의 분량 필드를 비워 `INGREDIENT_AMOUNT_REQUIRED`가 반복된다.
- 2026-07-16 평가가 gemma2를 추천한 것은 성능 우위가 아니라 당시 유일한 실행 가능 후보였기 때문이다(원본 리포트 문구로 확인).
- `gemma2`는 `gemma2:9b`로 재설치해 재현 가능 상태로 복구했다. `gemma3:4b`도 처음 측정했다.
- `gemma3:8b`는 존재하지 않는다(Gemma 3는 1b/4b/12b/27b).

**남은 것**

- 격리 폴더(`../Salus-untracked-quarantine-20260730-ntHi0E/`)의 원본 평가 결과는 아직 저장소로 옮기지 않았다.
- `backend/tools/recipe-model-eval/`의 빈 디렉터리는 그대로다. 도구를 되살릴지 미정.
- `docs/llm-eval-harness.md`에 구 도구와 `eval.sh`의 관계가 여전히 서술되어 있지 않다.
- 표본이 케이스당 1회다. qwen3:8b와 gemma3:4b의 동률을 확정하려면 반복 측정이 필요하다.

**측정 중 발견한 별도 문제**

- 장시간 실행에 절전 차단이 없으면 결과가 오염된다. 1차 실행은 시스템이 74회 sleep/wake해 지연 측정이 무효가 됐고 타임아웃도 동작하지 않았다.
- 모델 언로드 없이 순차 실행하면 3번째 모델부터 지연이 계단식으로 저하된다(RAM 16GB, 5GB급 모델 중복 상주).
- 위 두 가지는 `eval.sh`에 반영되지 않았다.
## 변경 이력
| 날짜 | 변경 |
|---|---|
| 2026-09-20 | 초판. 이슈 1~4 등록 |
| 2026-09-20 | 이슈 5 등록 (모델 평가 기록 소재·결론 모순) |
| 2026-09-20 | 이슈 5에 후속 관찰 추가: 평가 후보(`gemma3:12b`)와 설치 모델(`gemma3:4b`) 불일치 |
| 2026-09-24 | 이슈 5 후속 조치: 6개 모델 재측정으로 결론 모순 해소, 상태 IN PROGRESS |
