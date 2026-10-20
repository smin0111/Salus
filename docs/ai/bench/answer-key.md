# 벤치마크 답안 키 (채점자 전용)

- 기준 커밋: `7aaeefc` (main, 2026-08-19). 경로는 `backend/src/main/java/com/salus/healthytable/` 기준 상대 경로이며 줄 번호는 이 커밋 기준이다.
- 이 파일은 벤치마크 worktree로 복사하지 않는다(`bench.sh prepare`가 제외). 에이전트가 이 파일을 읽었다면 해당 run은 무효.
- 채점 규칙은 `docs/ai/AGENT_EVALUATION.md` 7장. 필수 사실의 기본 키는 **파일 + 클래스 + 메서드 + 의미**다. 줄 번호는 보조 정보이며 코드가 바뀌면 달라질 수 있다.
- WIP 주의: 2026-09-17 작업 트리에만 있던 클래스(`AllergenDeclarationParser`, `ApprovedRecipeService`, eval harness 등)를 이 커밋에 "존재한다"고 쓰면 사실 오류. "문서에는 언급되나 이 커밋에는 없음"이라고 확인하면 근거 품질 가점.

---

## A. 채팅 요청 → 구조화 레시피 생성 흐름
관련 범위: `controller/ChatController`, `service/Chat*`, `service/Recipe*`, `service/Ollama*`, `service/allergen/`, `config/LlmConfig`.
기준 도구 호출 수: 30.

| ID | 필수 항목 | 근거 |
|---|---|---|
| A1 | `POST /api/chat/message` → `ChatController.chat` | `controller/ChatController.java:112` |
| A2 | 요청 검증 후 `ChatRateLimitService.checkAllowed` | `controller/ChatController.java:118` |
| A3 | `ChatService.processChat` 진입 | `ChatController.java:119`, `service/ChatService.java:58` |
| A4 | `ChatIntentClassifier.classify`로 의도 분기(RECIPE_REQUEST/MENU_RECOMMENDATION/GENERAL_CHAT) | `ChatService.java:62`, `ChatIntentClassifier.java:17` |
| A5 | `ChatSafetyContextService.build`로 SafetyContext 구성 | `ChatService.java:78`, `ChatSafetyContextService.java:34` |
| A6 | 근거 수집 `RecipeEvidenceService.resolve` (내부 DB/검색 캐시/MFDS/검색엔진) | `ChatService.java:206`, `RecipeEvidenceService.java:44,257,262` |
| A7 | `RecipeGenerationCoordinator.buildCreationRequest` → `buildStructuredRecipeResponse` | `ChatService.java:236,311`, `RecipeGenerationCoordinator.java:29,51` |
| A8 | LLM 호출: `RecipeGenerationClient.generate` 구현체 `OllamaRecipeGenerationClient` + `RecipePromptFactory` (WebClient → Ollama) | `RecipeGenerationCoordinator.java:57`, `OllamaRecipeGenerationClient.java:49-50` |
| A9 | 초안 알레르기 충돌 검사 `findAllergyConflicts` → 충돌 시 차단 응답 | `RecipeGenerationCoordinator.java:78` |
| A10 | `RecipeDraftValidator.validate` 실패 시 `repairOrFail` | `RecipeGenerationCoordinator.java:89,127` |
| A11 | `RecipeReplyFormatter.format` + `RecipeValidator.validateStructured` | `RecipeGenerationCoordinator.java:102-103` |
| A12 | 감사 기록 `GeneratedRecipeLifecycleService.saveGeneratedRecipeAudit` | `RecipeGenerationCoordinator.java:108` |
| A13 | 작업 세션 저장 `RecipeWorkSessionService.saveRecommendation` (Redis) | `RecipeGenerationCoordinator.java:178` |
| A14 | `RecipeResponseSanitizer.buildRecipeCard`로 `ChatDto.Response`(Mono) 반환 | `RecipeGenerationCoordinator.java:183` |

가점: 인증 사용자 건강정보 로드 실패 시 개인화 레시피 거절(`ChatService.java:79`), Recipe Agent 분기는 플래그 기본 false(`ChatService.java:94`), 일반 대화는 `llmService.getChatResponse`(`ChatService.java:257`), `LlmConfig`가 `OllamaLlmService`를 `@Primary`로 등록(`config/LlmConfig.java:22`).
사실 오류(각 −5): LLM SDK(Spring AI, LangChain4j 등) 사용 주장, 채팅 레시피 생성에 Gemini 사용 주장, Recipe Agent가 기본 경로라는 주장, 채팅 메시지를 Redis에 저장한다는 주장(JPA `ChatMessage`), 검증 실패 초안을 그대로 반환한다는 주장.
완료 체크: 호출 순서가 있는 목록, 진입점~응답까지 끊김 없음, 검증 단계 포함.

## B. 알레르기 충돌 판정 경로
관련 범위: `service/ChatSafetyContextService`, `service/allergen/`, `resources/allergens/`, `service/ChatService`, `service/RecipeGenerationCoordinator`, `service/recipeagent/RecipePersonalizationPolicies`, 관련 테스트.
기준 도구 호출 수: 25.

| ID | 필수 항목 | 근거 |
|---|---|---|
| B1 | 알레르기 출처: `HealthProfile.allergies`(인증 사용자), 요청의 프로필 값, 메시지/대화 이력 속 알레르기 언급 | `ChatSafetyContextService.java:34-54`, `domain/HealthProfile.java:24` |
| B2 | 사전 데이터 `allergens/ko-allergens.yaml`을 `AllergenDictionary`가 `@PostConstruct`로 로드 | `allergen/AllergenDictionary.java:34,39` |
| B3 | `matchTermsFor`: 해당 알레르겐의 aliases+derived, 미등록이면 입력 문자열 자체 | `AllergenDictionary.java:66` |
| B4 | `AllergenMatcher.findConflicts`: 토큰화 후 매칭 | `allergen/AllergenMatcher.java:30,99` |
| B5 | 한 글자 용어는 토큰 완전 일치, 그 외는 토큰 내 부분 문자열 | `AllergenMatcher.java:95-96` |
| B6 | `findAllergyConflicts`가 제목+재료+단계를 검사하며 "빼고/제외" 표현으로 판정을 끄지 않음 | `ChatSafetyContextService.java:161,177` |
| B7 | 반영 지점 1: 신뢰 레시피 사전 차단 `buildAllergyConflictReply` | `ChatService.java:113`, `ChatSafetyContextService.java:133` |
| B8 | 반영 지점 2: 생성 초안 충돌 시 `buildAllergyBlockedReply` | `RecipeGenerationCoordinator.java:78`, `ChatSafetyContextService.java:151` |
| B9 | 테스트: `AllergenMatcherTest`, `AllergenRemovabilityTest`, `AllergenGapTest`, `ChatSafetyContextServiceTest`, `ChatServiceSafetyTest` 중 3개 이상 | `src/test/java/.../service/...` |

가점: `buildRecipeSafetyNotes`(`ChatSafetyContextService.java:183`), Recipe Agent의 `matchesLiterally` 사용(`recipeagent/RecipePersonalizationPolicies.java:112`), 건강정보 로드 실패 시 fail-closed(`ChatService.java:79`).
사실 오류(각 −5): LLM이 알레르기 충돌을 판정한다는 주장, 이 커밋에 `AllergenDeclarationParser`가 있다는 주장. 안전 치명 오류(−10): "빼고/제외"라고 말하면 충돌 검사가 꺼진다, 판정 불가/빈 결과를 안전으로 처리한다는 주장.
완료 체크: 출처·데이터·규칙·반영 지점·테스트 5요소 모두 포함.

## C. MealLog DB → API 흐름
관련 범위: `db/migration/V1,V3`, `domain/MealLog`, `repository/MealLogRepository`, `service/MealLogService`, `controller/MealLogController`, 테스트 2개.
기준 도구 호출 수: 20.

| ID | 필수 항목 | 근거 |
|---|---|---|
| C1 | 테이블 정의 `CREATE TABLE meal_logs` (V1) | `backend/src/main/resources/db/migration/V1__init_schema.sql:24` |
| C2 | `@Entity @Table(name="meal_logs")`, `User` `@ManyToOne`(user_id) | `domain/MealLog.java:12,21` |
| C3 | `MealLogRepository extends JpaRepository`, `findByUserAndRecordDate` 등 파생 쿼리 | `repository/MealLogRepository.java:13-22` |
| C4 | `MealLogService.saveOrUpdateMealLog` `@Transactional` | `service/MealLogService.java:35-36` |
| C5 | 사용자+날짜로 기존 기록 조회 후 갱신/생성(upsert) 후 `save` | `MealLogService.java:38,100` |
| C6 | 검증: 날짜 필수, 빈 메뉴명/잘못된 칼로리/잘못된 JSON 거부(예외) | `MealLogService.java` (예: `:90`), `MealLogServiceTest` 메서드명 |
| C7 | `MealLogController` `/api/meallogs` GET/POST(+ `/analysis/monthly`) | `controller/MealLogController.java:18,38,45` |
| C8 | 사용자 식별은 `AuthenticatedUserProvider.requireUserId()` + `UserRepository.findById`, 없으면 404 (요청 바디의 id 아님) | `MealLogController.java:26-29` |
| C9 | 테스트 `MealLogServiceTest`, `MealLogControllerTest` | `src/test/java/.../service/MealLogServiceTest.java:22`, `.../controller/MealLogControllerTest.java:25` |

가점: V3의 `meal_details` 조건부 정렬(`V3__align_existing_schema.sql:19-27`), `ddl-auto=validate`로 Flyway가 스키마 기준(`application.properties`), 월간 분석은 `GeminiService` 사용(`MealLogService.java:103-105`).
사실 오류(각 −5): MyBatis/XML mapper 주장, Redis 캐시 사용 주장, DTO의 userId로 사용자 식별 주장, Hibernate가 스키마를 생성한다는 주장.
완료 체크: 5계층(Flyway/Entity/Repository/Service/API) + 사용자 식별 + 검증 + 트랜잭션 + 테스트.

## D. 테스트 탐색
관련 범위: `service/allergen/AllergenMatcher`, `src/test/.../service/allergen/AllergenMatcherTest`, `backend/pom.xml`.
기준 도구 호출 수: 12.

| ID | 필수 항목 | 근거 |
|---|---|---|
| D1 | `AllergenMatcherTest#singleSyllableAllergensDoNotFalselyMatch` ("밀" vs "밀크티 1잔") | `AllergenMatcherTest.java:50-51` |
| D2 | 검증 대상 `AllergenMatcher.matches`(한 글자 완전 일치) / `conflicts` | `AllergenMatcher.java:95-96` |
| D3 | 명령 `cd backend && mvn -Dtest='AllergenMatcherTest#singleSyllableAllergensDoNotFalselyMatch' test` (동등 형태 인정) | Surefire 규약 |
| D4 | 전제: JDK 17(Enforcer `[17,18)`), Maven 3.9+, `mvnw` 없음 | `backend/pom.xml:150-151` |

가점: 짝 테스트 `singleSyllableAllergensMatchExactToken`(`:43`), DB/Redis 불필요한 단위 테스트라는 설명.
사실 오류(각 −5): 없는 `./mvnw` 사용, 전체 `mvn test`를 가장 좁은 명령으로 제시, Gradle 명령 제시.
제약 위반(완료 0점): 테스트 실제 실행.

## S1 (보조, MAIN 제외). thinking 모드 결정 로직 변경 영향
관련 범위: `service/OllamaLlmService`, `service/OllamaRecipeGenerationClient`, 관련 테스트, git 이력.
기준 도구 호출 수: 15.

| ID | 필수 항목 | 근거 |
|---|---|---|
| E1 | 결정 지점 `OllamaLlmService.thinkingSettingFor(model)`: `qwen3`로 시작(대소문자 무시)이면 `FALSE`, 그 외/null이면 `null` | `service/OllamaLlmService.java:50` |
| E2 | 채팅 경로 사용: `getChatResponse`에서 요청 생성 시 호출 | `OllamaLlmService.java:58,74` |
| E3 | 레시피 생성 경로 사용: `OllamaRecipeGenerationClient.thinkValue` → 같은 메서드에 위임 | `OllamaRecipeGenerationClient.java:69,116-117` |
| E4 | `think` 필드는 `@JsonInclude(NON_NULL)`이라 null이면 요청에서 생략(모델 기본값) | `OllamaLlmService.java:242-243` |
| E5 | 테스트 `OllamaThinkingSettingTest`(qwen3 false, 기타 null) | `src/test/.../service/OllamaThinkingSettingTest.java:13-29` |
| E6 | 제약: 결정 지점을 하나로 유지(경로별 분기 추가 금지). 이유: qwen3 thinking이 `num_predict` 예산을 소모해 빈 응답 발생 | `OllamaLlmService.java:42-46` 주석, 커밋 `be415ea` |

가점: `OllamaRecipeGenerationClientTest#ollamaMessageDeserializesThinkingSeparatelyFromContent`(`:62`), `ollama.chat-model`/`ollama.recipe-model` 기본값이 모두 qwen3:8b라 두 경로 모두 영향.
사실 오류(각 −5): Ollama 서버 설정/Modelfile로 제어한다는 주장, 채팅 경로에만 적용된다는 주장, Gemini 경로에 영향 주장.
