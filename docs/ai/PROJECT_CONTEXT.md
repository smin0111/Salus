# Salus project context (agent map)

Read the section you need; do not read this file end to end for a narrow task.
Verified against commit `7aaeefc` (main, 2026-08-19) and the working tree of 2026-09-17.
`(WIP)` = existed only as uncommitted work on 2026-09-17: confirm with `git status` / `rg` before relying on it.
Human-oriented overview, local setup and product policy: `README.md`. Security policy: `SECURITY.md`.

## Workspace boundaries
- Repo root is `Salus/`. Sibling folders `../Salus-*` are other git worktrees or backups; never search or edit them unless asked (running `rg` from the parent folder scans all of them).
- Ignore unless the task is about them: `backend/target/`, `node_modules/`, `frontend/ios/Pods/`, `frontend/android/build/`, `frontend/dist/`, `admin/dist/`, `backend/tools/**/results/`, `poc/`, `tmp/`, tracked stale logs `backend/*.log`, local `backend/scratch_*.py`.
- Root `BEGINNER_CODE_REVIEW_NOTES.md`, `GITHUB_MANAGEMENT_GUIDE.md`, `SERVICE_READINESS_REVIEW.md` are gitignored personal notes, not project rules.

## Modules
Three independently built subprojects; there is no root `package.json` or Makefile, so run commands inside each module.

| Module | Stack (verified) | Entry points |
|---|---|---|
| `backend/` | Java 17, Spring Boot 3.2.1 (MVC + WebFlux `WebClient`), Security + JJWT 0.11.5, Data JPA, Flyway (mysql), Data Redis, jsoup, Lombok, Actuator | `HealthyTableApplication`, `controller/*`, `application.properties` |
| `frontend/` | Expo ~54, React Native 0.81.5, React 19.1 | `App.js`, `src/navigation/AppNavigator.js`, `src/screens/*`, `src/api/*` |
| `admin/` | Vite 6, React 19, react-router-dom 7 | `src/App.jsx`, `src/pages/*` |

## Backend packages (`backend/src/main/java/com/salus/healthytable/`)
- `controller/` REST APIs: `/api/chat`, `/api/recipes`, `/api/meallogs`, `/api/fridge`, `/api/health-checkups`, `/api/users/me`, `/api/users/me/health-profile`, `/api/community`, `/api/payments`, `/api/activities`, `/api/admin/dashboard`; `AuthController` maps `/api/auth/{google,kakao,naver,apple}` per method; (WIP) users are identified by `social_accounts(provider, provider_user_id)` via `SocialLoginService`, not by email (same email on another provider = separate user); `OAuthService` checks Google `aud` / Kakao `app_id`, `AppleIdentityTokenVerifier` checks Apple JWKS signature/iss/aud/exp/nonce; all fail closed when `oauth.*` config is empty.
- (WIP) Three `SecurityFilterChain`s in `SecurityConfig`: `/api/monitor/**` display token (`DisplayTokenFilter`, `MONITOR_DISPLAY_TOKEN_HASHES`, ROLE_MONITOR, stats only), `/api/admin/**` admin auth (`service/adminauth/`: `admin_accounts` password+TOTP, `admin_sessions` idle 30 min / max 8 h, `AdminTokenProvider` with separate `JWT_ADMIN_SECRET`, roles ADMIN_VIEWER/ADMIN; accounts created only via `AdminAccountCliRunner`), everything else = user app. Auth filters are not servlet-registered (registration disabled or built inside the chain) so they never run on another chain.
- `security/` JWT filter/provider (access token 30 min; (WIP) a presented but expired/invalid Bearer token gets 401 `TOKEN_EXPIRED`/`UNAUTHORIZED` even on public APIs, except `/api/auth/**`; refresh tokens are hashed in `refresh_tokens` and rotated by `RefreshTokenService` via `/api/auth/refresh`, revoked via `/api/auth/logout`), `IpWhitelistFilter` (admin), `AuthenticatedUserProvider` (current user id).
- `domain/` JPA entities; `repository/` one Spring Data JPA repository per entity; `dto/` request/response types.
- `service/` flat business logic. Subpackages: `service/allergen/` (allergen dictionary/matching), `service/recipeagent/` (flag-gated Recipe Agent: web/YouTube/medication sources).
- `config/` security, `WebClient`, `LlmConfig` (`@Primary LlmService` = `OllamaLlmService`), request-id and COOP filters.

## LLM recipe flow (chat -> structured recipe), verified at `7aaeefc`
1. `POST /api/chat/message` -> `ChatController.chat`: input validation -> `ChatRateLimitService.checkAllowed` (in-memory counters) -> `ChatService.processChat`.
2. `ChatIntentClassifier.classify`: `RECIPE_REQUEST`, `MENU_RECOMMENDATION`, `GENERAL_CHAT`.
3. `ChatSafetyContextService.build` -> `SafetyContext` (allergies from request values, message/history mentions and `HealthProfile.allergies`). If an authenticated user's health context fails to load, `processChat` refuses personalized recipes (fail closed).
4. Recipe Agent branch only when `recipe.agent.enabled` and routing flags are true (all default false): `RecipeAgentOrchestrator.handle`.
5. Trusted-recipe allergy pre-check: `ChatSafetyContextService.buildAllergyConflictReply`.
6. Evidence: `RecipeEvidenceService.resolve` -> trusted internal DB recipes (`internal-db`) -> `SearchCache` (negative cache, `rag.negative-cache-days`) -> `MfdsRecipeSearchClient` / `SearchEngine` (`DuckDuckGoSearchEngine` default; `TavilySearchEngine` when `search.provider=tavily`). At `7aaeefc` Tavily results prefer `raw_content` and fall back to `content`; (WIP) the pre-harness CLAUDE.md describes the reverse (prefer Tavily's extracted `content` to avoid menu/ad noise, raw only when empty) - check the file before relying on either.
7. `RecipeGenerationCoordinator.buildCreationRequest` -> `buildStructuredRecipeResponse`:
   1. `RecipeGenerationClient.generate` = `OllamaRecipeGenerationClient` (prompt: `RecipePromptFactory`; thinking flag: `OllamaLlmService.thinkingSettingFor`).
   2. `RecipeDraftMapper.toRecipe`.
   3. `ChatSafetyContextService.findAllergyConflicts` -> `AllergenMatcher.findConflicts`; conflict -> `buildAllergyBlockedReply`.
   4. `RecipeDraftValidator.validate` (structured rules, excluded ingredients) -> `repairOrFail` (repair attempt, else failure).
   5. `RecipeReplyFormatter.format` + `RecipeValidator.validateStructured(...)` (evidence/search-context checks).
   6. `GeneratedRecipeLifecycleService.saveGeneratedRecipeAudit` (`generated_recipes`, `validator_version`) and `saveToRecipeDbSafely`.
   7. `RecipeWorkSessionService.saveRecommendation` (Redis, TTL 6h, in-memory fallback).
   8. `RecipeResponseSanitizer.buildRecipeCard` -> `ChatDto.Response`.
8. General chat: `LlmService.getChatResponse` (`OllamaLlmService`).
9. Follow-ups (modify, detail, save to meal calendar): `ChatFollowUpService` with `RecipeReplyParser`, `RecipeWorkSessionService`, `MealLogService`.

Ollama settings live in `application.properties` (`ollama.*`: model `qwen3:8b`, recipe temperature 0.0, `num_predict`, `num_ctx`, timeouts). qwen3 thinking mode must stay decided in one place: `OllamaLlmService.thinkingSettingFor(model)` returns `FALSE` for names starting with `qwen3`, otherwise `null`; `OllamaRecipeGenerationClient` delegates to it (see commit `be415ea`).

(WIP) changes touching this flow: approved recipe catalog (`ApprovedRecipeService`, `ApprovedRecipeCatalogDefinition`, `domain/RecipeApprovalStatus`, `resources/recipes/approved-recipes.json`, Flyway V6-V8; rules in `README.md` "승인 레시피 카탈로그"), generation timeouts (`recipe.generation.*-timeout-seconds`, `RecipeGenerationTimeoutException`), validator `v2.0` semantic rules, Tavily content selection changes, and an LLM eval harness (`eval.sh`, `backend/src/test/java/com/salus/healthytable/eval/`, `docs/llm-eval-harness.md`).

## Allergen flow, verified at `7aaeefc`
- Data: `resources/allergens/ko-allergens.yaml` loaded by `AllergenDictionary.load()` (`@PostConstruct`); `matchTermsFor(allergy)` returns aliases + derived terms of matching entries, or the normalized declared text itself when the allergy is not in the dictionary (unknown allergies are still matched literally).
- Matching: `AllergenMatcher.findConflicts(declaredAllergies, texts)` tokenizes texts on non-`[가-힣a-z0-9]` characters; a one-character term must equal a whole token, longer terms match as substrings of a token. `matchesLiterally` separates "allergen word present" from "derived ingredient only" (used by `RecipePersonalizationPolicies`).
- Callers: `ChatSafetyContextService` (chat and structured generation) and `recipeagent/RecipePersonalizationPolicies.AllergyPolicy` share the Matcher. RecommendationService independently filters ingredient substrings; Agent fridge/final validation also has separate literal checks. ChatSafetyContextService owns chat sentence/profile-term normalization, while Agent loads DB/previous context separately. These are existing differences, not new matching paths to copy. See [6a production boundary/ownership](../allergen-architecture-boundary.md).
- (WIP) Label declaration parser: `AllergenDeclarationParser`, `AllergenRegistry`, `AllergenEvidence`, `DeclarationState`, `DeclarationInput`, `DeclarationParseResult` in `service/allergen/`; spec and limits in `docs/allergen-declaration-parser.md`. It records label evidence only and makes no safety decision.
- (WIP, 2026-09-18) `AllergenCrossContactParser` adds cross-contact evidence with `CrossContactInput`, `CrossContactState`, `CrossContactParseResult`. Both label parsers share `AllergenLabelTokens` and the registry's exact direct-name lookup. Registry metadata distinguishes EGG_GROUP, EGG and QUAIL_EGG; `findExactAliases` exposes lexical relations/confidence and `parentOf` exposes parents without safety resolution. Synthetic guards: `AllergenLexicalRegressionTest`, `AllergenHierarchyTest`.
- (WIP, stage 3.5) `IngredientTreeParser` -> `IngredientTree`/`IngredientNode` -> `IngredientEvidenceExtractor` records INGREDIENT evidence via that same Registry. `IngredientNameNormalizer` separates limited origin annotations for exact lookup; raw/context/structural path are retained. Candidate triage: `docs/ingredient-candidate-triage.md`. S020 is excluded pending version resolution. Metrics and limits: `docs/allergen-evidence-pipeline.md`.
- (WIP, stage 4) `AllergenEvidenceResolver.resolve(List<AllergenEvidence>)` groups one product's positive observations by exact String allergen ID into immutable `AllergenFact` records. `PresenceStatus`: CONFIRMED_PRESENT / POSSIBLE_PRESENT / CROSS_CONTACT_ONLY. Exact-record dedup only; original evidence and explicit children remain. No Registry dependency, taxonomy expansion, unresolved-source backfill, absence inference or final safety/user decision. Guards: `AllergenEvidenceResolverTest`, `ResolverReferenceTest`, `ResolverReferenceValidationTest`.
- Rules for changing any of this: `docs/ai/SAFETY_RULES.md`.
- (WIP, stage 5) `DeclarationCoverageResolver` + `RegulatoryRuleRegistry` evaluate versioned KR declaration scope from `RegulatoryProductContext` and an allergen ID. Config: `resources/regulations/kr-allergen-labeling.yaml`; EFFECTIVE and PROPOSED are separate. Supplied applicable date/basis, query-specific verified name identity, exemption context, sulfite addition/concentration are required where relevant; missing context stays UNKNOWN. No system clock, presence mutation or declaration-silence inference. Docs: `docs/allergen-regulatory-coverage.md`. Guards: `DeclarationCoverageResolverTest`, `RegulatoryRuleRegistryTest`, `RegulatoryReferenceTest`.
- (WIP, stage 6a/6b) Structured-label Evidence, Regulatory and typed Profile output models have no consumers outside `service/allergen` in production. Bean registration does not transfer safety authority. `AllergenArchitectureBoundaryTest` protects 15 types via production imports/FQCN checks; migration must explicitly update its boundary. 6c shadows profile resolution, not the whole label-vs-recipe pipeline.
- (WIP, stage 6b) `ProfileAllergenResolver` accepts `NormalizedProfileAllergenTerm` with three source values and uses exact DIRECT_NAME aliases only. Sealed Complete/Partial results preserve resolved/unresolved occurrences and provenance, with no common resolved() accessor. No direct safety-consumer wiring or fallback removal; 6c adds observation only. OYSTER/ABALONE/MUSSEL now have SHELLFISH taxonomy parents; legacy alias/derived matching is unchanged. Docs: [Profile Resolution](../allergen-profile-resolution.md). Synthetic fixture: `profile-reference.json` (separate from product Reference).
- (WIP, stage 6c) Profile Resolver now runs only inside `ProfileResolutionShadowObserver`, which ChatSafetyContextService calls with already accepted source-local normalized terms. Three void methods keep ProfileTermSource/results internal. Existing Actuator Micrometer records enum-only source/outcome/resolution counters; empty batches are skipped, observer/recorder errors fail open. No decision authority migration, Recommendation/Agent wiring, or endpoint exposure. Guard retains all output protections and permits only the exact ChatSafetyContextService observer bridge. Docs: [Shadow Observation](../allergen-profile-shadow.md).

## Data layer
- Entities: `User`, `HealthProfile`, `HealthCheckup`, `FridgeItem`, `MealLog`, `Recipe`, `RecipeStats`, `RecipeShare`, `GeneratedRecipe`, `Recommendation`, `SearchCache`, `ChatSession`, `ChatMessage`, `CommunityPost`, `PostComment`, `PostLike`, `Payment`, `ActivityLog` (+ enums `UserRole`, `UserGrade`; `JsonStringListConverter` for JSON list columns).
- Typical chain: `domain/X` -> `repository/XRepository` (derived queries) -> `service/XService` (`@Transactional` writes) -> `controller/XController`. Example: `MealLog` -> `MealLogRepository` -> `MealLogService.saveOrUpdateMealLog` -> `MealLogController` (`/api/meallogs`).
- Flyway `db/migration`: V1 init schema, V2 users/payments hardening, V3 align existing schema, V4 recipe share visibility, V5 user foreign keys; (WIP) V6 approved recipe catalog, V7 servings/calories, V8 generation attempt audit, V9 social accounts (drops `users.email` UNIQUE), V10 refresh tokens, V11 admin accounts/sessions. `backend/migrations/*.sql` are legacy manual scripts, not run by Flyway.
- Redis: `RecipeWorkSessionService` only (JSON per user+chat session, TTL 6h, in-memory fallback). Chat rate limiting is in-memory, not Redis.
- `spring.jpa.open-in-view=false`: load what you need inside services.

## Clients
- `frontend/src/`: `screens/` (large: `ChatScreen.js` ~70KB), `api/` (HTTP client modules), `navigation/`, `context/AuthContext.js`, `components/`, `theme/`. Local `src/secrets.js` comes from `secrets.example.js`.
- `admin/src/`: `pages/Dashboard.jsx`, `pages/Login.jsx` (admin id/password + TOTP steps), `pages/Monitor.jsx` (`/monitor` unattended wall display, display token from `#display-token=`), `components/StatsBoard.jsx`; build-time `VITE_API_BASE_URL`.

## Commands
| Purpose | Command |
|---|---|
| One backend test | `cd backend && mvn -Dtest='AllergenMatcherTest#singleSyllableAllergensMatchExactToken' test` |
| Backend test class(es) | `cd backend && mvn -Dtest='RecipeDraftValidatorTest,RecipeValidatorStructuredTest' test` |
| Full backend suite (CI parity) | `cd backend && mvn -q clean test` |
| JDK 17 on macOS (Homebrew) | prefix `JAVA_HOME=/opt/homebrew/opt/openjdk@17` |
| Run backend | `set -a; source .env; set +a; cd backend && mvn spring-boot:run` (needs MySQL, Redis, secrets) |
| Live recipe accuracy (needs Ollama, off by default) | `mvn -Dtest=RecipeAccuracyLiveTest -D레시피정확도실행=true test` |
| (WIP) LLM eval harness | `./eval.sh --mode replay` (no Ollama) / `./eval.sh` |
| Frontend web export | `cd frontend && npm ci && cp src/secrets.example.js src/secrets.js && npx expo export --platform web --output-dir dist` |
| Admin build | `cd admin && npm ci && npm run build` |
| Compose/image checks | `docker compose --env-file .env.example -f docker-compose.prod.yml config`; `docker build ./backend` |

CI (`.github/workflows/ci.yml`): `backend-test` (`mvn test`, Temurin 17), `admin-build` (npm ci, audit moderate, build), `frontend-web-export` (audit policy script, expo export), `frontend-android-gradle-check`, `docker-build` (backend runs as uid 10001, admin non-root).

## Test map (`backend/src/test/java/com/salus/healthytable/`)
- Allergen/safety: `service/allergen/AllergenMatcherTest`, `service/allergen/AllergenRemovabilityTest`, `service/AllergenGapTest`, `service/ChatSafetyContextServiceTest`, `service/ChatServiceSafetyTest`; (WIP) `AllergenDeclarationParserTest`, `AllergenRegistryTest`, `DeclarationReferenceTest`, `DeclarationReferenceValidationTest`.
- LLM pipeline: `RecipeDraftValidatorTest`, `RecipeValidatorStructuredTest`, `RecipePromptFactoryTest`, `RecipeReplyFormatterTest`, `RecipeEvidenceServiceTest`, `OllamaRecipeGenerationClientTest`, `OllamaLlmServiceTest`, `OllamaThinkingSettingTest`, `OllamaModelDefaultTest`, `RecipeWorkSessionServiceTest`, `RecipeAccuracyCatalogTest`; (WIP) `RecipeGenerationCoordinatorTimeoutTest`, `GeneratedRecipeLifecycleServiceTest`, `RecipeDraftMapperTest`, `RecipeReplyParserTest`, `ApprovedRecipeServiceTest`.
- Recipe Agent: `service/recipeagent/*Test` (fixtures in `src/test/resources/recipe-agent-fixtures/`).
- Web/security: `controller/*ControllerTest`, `controller/*SecurityTest`, `security/*Test`, `config/*FilterTest`.

## Large files: read by range, never whole by default
`recipeagent/MedicationInformationApiAdapters.java` (~79KB), `recipeagent/MedicationInteractionProcessing.java` (~58KB), `RecipeDraftValidator.java` (~37KB), `RecipeResponseSanitizer.java` (~32KB), `recipeagent/YouTubeRecipeSourceProcessing.java` (~27KB), `RecipeValidator.java` (~26KB), `recipeagent/RecipeAgentDomain.java` (~25KB), `ChatService.java` (~25KB), (WIP) `ApprovedRecipeService.java` (~35KB), `frontend/src/screens/ChatScreen.js` (~70KB), tests `ChatServiceSafetyTest.java` (~48KB), `recipeagent/MfdsDrugProductPermitAdapterTest.java` (~45KB).
