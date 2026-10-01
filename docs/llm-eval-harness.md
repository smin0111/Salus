# Salus LLM Evaluation Harness v2

로컬 LLM이 레시피와 채팅 답변을 얼마나 제대로 만드는지 **한 번의 명령**으로 재고, 여러 모델을 같은 조건에서 비교해 리포트로 남기는 평가 하네스입니다.

```bash
./eval.sh
```

```bash
./eval.sh --models qwen3:8b,gemma2:latest
```

Ollama가 떠 있으면 실제 모델을 호출하고(`live`), 없으면 녹화된 응답으로 결정적으로 실행합니다(`replay`).

## 설계 원칙: 판정은 새로 만들지 않는다

평가 하네스가 자체 채점 규칙을 갖는 순간 점수와 실제 서비스 동작이 갈라집니다. 하네스는 데이터셋을 흘리고 점수를 모으기만 하고, 판정은 전부 프로덕션 코드가 합니다.

| 단계 | 사용하는 프로덕션 컴포넌트 |
|---|---|
| 프롬프트 | `RecipePromptFactory` |
| 호출·파싱·오류 코드 | `OllamaRecipeGenerationClient`, `OllamaLlmService` |
| 레시피 규칙 (validator v2.0) | `RecipeDraftValidator` |
| 알레르겐 충돌 | `AllergenMatcher` + `AllergenDictionary` |

프로덕션 코드는 한 줄도 고치지 않습니다. 모델 비교를 위해 바꾸는 것은 클라이언트의 **모델 이름 필드 하나**뿐이고(`EvalModelBinder`), 응답 원문은 HTTP 경계에 필터를 얹어 복사해 둡니다(`EvalRawCapture`). 파싱 경로에는 손대지 않습니다.

`replay` 모드도 파싱을 흉내 내지 않습니다. 녹화한 응답을 HTTP 경계에서 주입해 실제 클라이언트의 파싱·`done_reason` 처리·오류 코드 분류를 그대로 지나가게 합니다.

컨텍스트는 LLM 경로에 필요한 빈만 띄웁니다. MySQL·Redis·보안 설정 없이 돕니다.

## 다중 모델 비교에서 고정되는 것

`--models`로 여러 모델을 돌려도 아래는 전부 동일합니다. 모델 이름만 바뀝니다.

- 동일 데이터셋·동일 케이스·동일 RAG 컨텍스트(케이스 파일의 `searchContext`)
- 동일 프롬프트 (`RecipePromptFactory`, **모델별 프롬프트 분기 없음**)
- 동일 샘플링 파라미터 (temperature / top_p / num_predict / num_ctx / timeout — `application.properties` 값 그대로)
- 동일 검증기 (`RecipeDraftValidator` v2.0, 기준 완화 없음)
- 동일 repair 정책 (실패 초안 1회 재호출, 프로덕션과 같은 횟수)

thinking 차단 여부만은 모델 이름에 따라 달라지는데, 이는 프로덕션이 원래 그렇게 동작하기 때문입니다(`OllamaLlmService.thinkingSettingFor`). 하네스가 따로 정하지 않습니다.

한 모델이 실패하거나 타임아웃이 나도 나머지 모델 평가는 계속되고, 해당 결과는 실패로 기록됩니다. 설치되지 않은 모델은 호출 없이 `MODEL_NOT_AVAILABLE`로 기록하고 리포트의 "미설치" 목록과 "아직 판단할 수 없는 항목"에 남습니다.

### 워밍업

모델마다 케이스 실행 전에 짧은 호출을 한 번 합니다. 첫 케이스에 모델 로딩 시간이 통째로 실려 지연 비교가 왜곡되는 것을 막기 위해서이고, 이 호출의 결과와 지연은 어떤 집계에도 넣지 않습니다. `--no-warmup`으로 끕니다.

## 실행 모드

| 모드 | 언제 | 무엇을 재나 |
|---|---|---|
| `live` | Ollama 구동 중 | 모델 품질 자체. 첫 초안 유효성, 제약 준수, 지연 |
| `replay` | CI, 오프라인 | 채점·파싱·검증 체인의 회귀. 결정적이라 100% 통과를 요구 |
| `auto`(기본) | — | Ollama 생존을 탐지해 둘 중 하나 선택 |

replay에는 모델 축이 없어 모델 이름이 `replay-fixture` 하나로 고정됩니다.

## 채점 차원

케이스마다 아래 차원을 매기고, 해당 없는 차원은 집계에서 뺍니다.

| 차원 | 통과 조건 | 점수 가중치 |
|---|---|---:|
| `transport` | 생성 호출이 초안을 반환 / 채팅이 엔진 폴백 문구가 아님 | 20 |
| `schema` | 필수 필드·단위·단계 구조 코드 없음 | 20 |
| `validator` | `RecipeDraftValidator` 전체 통과 | 30 |
| `constraint` | 제외·대체 요청 준수 + 케이스별 금지어/필수어 | 15 |
| `noise` | thinking 누출, 빈 출력, 코드펜스, 이모지 없음 | 15 |
| `allergen_safety` | 선언 알레르기와 충돌하는 재료 없음 | **점수 없음 / Hard Fail** |
| `regression` | (replay) 검증기 코드 집합이 케이스 고정값과 정확히 일치 | 점수 없음 |

알레르겐 검사 범위는 프로덕션 `ChatSafetyContextService#findAllergyConflicts`와 같은 제목·재료·조리 단계입니다. `safetyNotes`는 "우유 알레르기 주의"처럼 알레르겐 이름을 정당하게 담으므로 제외합니다.

### 점수 계산

```text
score = (통과한 차원의 가중치 합 / 해당되는 차원의 가중치 합) × 100
```

- 결정적입니다. 같은 차원 결과에서 항상 같은 점수가 나옵니다.
- LLM이나 사람의 주관적 판단("맛있어 보인다")은 점수에 들어가지 않습니다.
- 채팅 스위트처럼 `validator`가 해당 없는 경우 그 가중치는 분모에서 빠집니다.
- **알레르겐은 점수에 넣지 않습니다.** 점수로 환산하면 총점이 높다는 이유로 안전 실패가 묻힙니다. 알레르겐 실패는 `verdict = HARD_FAIL`로만 표시하고, 점수와 무관하게 실패입니다.
- replay 회귀 케이스(일부러 깨뜨린 응답)는 채점이 뒤집히므로, 점수 100은 "채점기가 그 결함을 예상대로 잡아냈다"는 뜻입니다.

### 게이트

- 알레르겐 차원 실패는 모드와 무관하게 **0건**이어야 합니다. 안전 판정 누락은 지표가 아니라 사고입니다.
- 통과율 게이트 기본값은 `replay=1.0`, `live=0`입니다. live는 모델 표본 편차가 있어 기본적으로 지표만 보고합니다.
- 모델 비교 실행에서는 약한 모델이 게이트를 건드릴 수 있으므로 `--no-gate`를 함께 쓰는 편이 자연스럽습니다.

## 저장되는 지표

모델별로 집계합니다.

- 실행 케이스 수 / 최종 통과 수 / Pass Rate / Hard Fail 수
- 최초 초안 통과율 (검증기 대상 실행 기준)
- repair 후 통과 (`repairPassed / repairAttempted`)
- 검증기 실패율, 알레르겐 실패 건수, Schema 실패 건수, Timeout 건수
- 평균 점수
- Latency 평균 / 중앙값 / p95 / 최소 / 최대
- 실패 유형 히스토그램 (프로덕션 검증기 코드 그대로)

`--repeat`가 2 이상이면 같은 (모델, 케이스)의 반복 결과를 전부 개별 보존하고, 평균과 표준편차(점수·지연)를 따로 계산합니다.

실행 1건마다 남는 원본 기록:

```json
{
  "runId": "20260828-150411",
  "caseId": "recipe-allergy-milk-potato-soup",
  "suite": "recipe_generation",
  "model": "qwen3:8b",
  "input": "감자스프 만들어줘  (요청 요리: 감자스프)",
  "expectedConditions": ["..."],
  "rawOutput": "<모델 응답 원문>",
  "rawOutputFirstDraft": "<repair 전 초안 원문, repair 없으면 null>",
  "rawEnvelope": "<Ollama 응답 전체 원문>",
  "promptTokens": 1328, "completionTokens": 583, "doneReason": "stop",
  "latencyMs": 130495,
  "timeout": false,
  "validatorPassed": false,
  "validatorCodes": ["COOKING_HEAT_REQUIRED", "COOKING_MINUTES_REQUIRED"],
  "validatorReasons": ["..."],
  "allergenConflicts": [],
  "firstDraftValid": false, "repairAttempted": true, "repairPassed": false,
  "failedDimensions": ["validator", "constraint"],
  "score": 55.0, "verdict": "FAIL"
}
```

`rawOutput`은 모델이 실제로 돌려준 문자열 그대로입니다. 요약하거나 다듬지 않습니다. 토큰 수와 `doneReason`은 Ollama 응답에서 실제로 읽은 값이고, 관측하지 못하면 `null`로 둡니다(추정하지 않습니다).

## 산출물

`backend/target/eval/`에 남습니다.

| 파일 | 내용 |
|---|---|
| `latest.txt` | 터미널 요약 (eval.sh가 출력하는 것) |
| `latest.md` | 리포트 본문 (아래 10개 절) |
| `latest.json` | 전체 원본 |
| `latest-records.json` | 외부 연동용 평면 레코드 |
| `raw/<model>/<caseId>__iter<n>.txt` | 모델 응답 원문 |
| `raw/<model>/<caseId>__iter<n>.envelope.json` | Ollama 응답 전체 원문 |
| `raw/<model>/<caseId>__iter<n>.first-draft.txt` | repair 전 초안 원문 |

실행마다 `report-<타임스탬프>.*`, `records-<타임스탬프>.json` 사본도 함께 남습니다.

Markdown 리포트 구성: 1) 실행 환경 2) 평가 모델 3) 평가 데이터셋 4) 모델별 종합 결과 5) Latency 비교 6) 실패 유형 비교 7) Case별 입력/Raw Output/문제점 8) Hard Fail 목록 9) 모델별 장점과 한계 10) 아직 판단할 수 없는 항목.

9절은 관측 수치만 문장으로 옮깁니다. 측정하지 않은 특성은 쓰지 않고, 10절에 "이번 실행으로는 알 수 없는 것"을 실행 조건에서 기계적으로 뽑아 남깁니다.

### 외부(Notion 등) 연동

`latest-records.json`은 실행 1건당 한 레코드인 평면 배열입니다. Notion API 호출은 포함하지 않았고, 필요한 필드만 안정적으로 노출합니다.

`runId`, `runDate`, `caseId`, `suite`, `model`, `iteration`, `input`, `rawOutput`, `score`, `verdict`, `problemTypes`, `latencyMs`, `ragCondition`, `rawOutputFile`

## Judge 교차평가 (선택)

```bash
./eval.sh --mode live --models qwen3:8b --judge-model gemma2:latest
```

- 생성 모델과 Judge 모델은 **별도 인스턴스**입니다. 벤치마크 모델을 갈아 끼워도 judge 모델은 바뀌지 않습니다.
- Judge 판정은 `judgeModel` / `judgeVerdict` / `judgeRationale` / `judgeAgreement` 필드에만 들어갑니다.
- **프로덕션 검증기 판정을 덮어쓰지 않습니다.** 통과율, 점수, 모델별 지표, 게이트 어디에도 judge 결과가 섞이지 않습니다.
- 기본값은 미사용입니다. 옵션을 주지 않으면 judge 호출 자체를 하지 않습니다.

한계: judge 호출에 프로덕션 채팅 경로를 그대로 쓰기 때문에 Salus 어시스턴트 system instruction이 함께 들어갑니다. 전용 judge 채널이 아니라는 점을 감안해야 합니다.

## 데이터셋

`backend/src/test/resources/eval/cases/salus-core.jsonl` — 한 줄에 케이스 하나(JSONL, `#`은 주석).

```json
{"id":"recipe-exclude-pork-kimchi-jjigae","suite":"recipe_generation","mode":"EXCLUDE",
 "requestedTitle":"김치찌개","userMessage":"김치찌개인데 돼지고기는 빼줘",
 "excludedIngredients":["돼지고기"],"expect":{"forbiddenTerms":["돼지고기","삼겹살"]}}
```

주요 필드

- `suite` — `recipe_generation` | `chat_reply`
- `mode` — `CREATE` | `DETAIL` | `SUBSTITUTE` | `EXCLUDE`
- `allergies`, `dietaryRestrictions`, `excludedIngredients`, `substitutions`, `fridgeItems`, `searchContext`
- `replayOnly` — 일부러 깨뜨린 응답을 검사하는 케이스. live 실행에서 제외
- `expect.expectedFailedDimensions` — **실패해야** 정상인 차원. 판정이 뒤집혀 실패해야 통과로 집계됩니다
- `expect.expectedFailureCodes` — replay 회귀 고정값

### 녹화 응답

`backend/src/test/resources/eval/replay/<caseId>.json`. 세 형태를 지원합니다.

- `draft` — 정상 레시피 JSON. 하네스가 Ollama 응답 봉투로 감쌉니다
- `chatContent` — 채팅 본문 문자열
- `response` — Ollama 응답 봉투 원본. 빈 응답, 비 JSON, `done_reason: length` 같은 이상 케이스용

현재 녹화된 회귀 케이스: 기준 정상 응답, 비 JSON 출력, 출력 토큰 한도 절단, 알레르겐(버터) 잔존, thinking 블록 누출, 빈 응답. 뒤의 두 개는 커밋 `be415ea`에서 실제로 터졌던 qwen3 thinking 사고의 상시 감시 케이스입니다.

## 옵션

```bash
./eval.sh --mode replay                        # Ollama 없이 결정적 실행 (CI)
./eval.sh --models qwen3:8b,gemma2:latest      # 다중 모델 비교
./eval.sh --model qwen3:8b                     # 단일 모델 덮어쓰기
./eval.sh --judge-model gemma2:latest          # Judge 교차평가 (별도 필드로만 기록)
./eval.sh --mode live --repeat 3               # 케이스당 3회, 편차 측정
./eval.sh --suite chat_reply                   # 스위트 필터
./eval.sh --case recipe-no-heat-oi-muchim      # 케이스 하나만
./eval.sh --repair false                       # 첫 초안 품질만 측정
./eval.sh --no-warmup                          # 모델 워밍업 생략
./eval.sh --min-pass-rate 0.8                  # 통과율 게이트
./eval.sh --no-gate                            # 지표만 수집
./eval.sh --help
```

## 알아둘 점

- 일반 `mvn test`는 하네스를 수집하지 않습니다. 클래스명(`SalusLlmEvalHarness`)에 Test 접미사가 없어 Surefire 기본 include에 걸리지 않습니다.
- Maven Enforcer가 JDK 17만 허용합니다. `eval.sh`는 `JAVA_HOME`이 비어 있으면 macOS에서 JDK 17을 찾아 맞춥니다.
- live 실행은 로컬 모델 속도에 좌우됩니다. 8~9B 모델 기준 레시피 케이스 하나가 repair 포함 1~3분입니다. 모델 수 × 케이스 수 × 반복 횟수만큼 곱해집니다.
- Maven·Spring 로그는 `backend/target/eval-run.log`로 빠집니다.

## CI에 붙이려면

`.github/workflows/ci.yml`에 아래 잡을 추가하면 Ollama 없이 결정적으로 돕니다.

```yaml
  llm-eval-replay:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: ./eval.sh --mode replay
```
