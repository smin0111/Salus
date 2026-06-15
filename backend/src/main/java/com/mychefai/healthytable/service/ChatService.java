package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.FridgeItem;
import com.mychefai.healthytable.domain.ChatMessage;
import com.mychefai.healthytable.domain.ChatSession;
import com.mychefai.healthytable.domain.HealthCheckup;
import com.mychefai.healthytable.domain.HealthProfile;
import com.mychefai.healthytable.domain.Recipe;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.domain.GeneratedRecipe;
import com.mychefai.healthytable.domain.SearchCache;
import com.mychefai.healthytable.dto.HealthCheckupAnalysisDTO;
import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.dto.MealLogDTO;
import com.mychefai.healthytable.dto.RecipeWorkSessionDTO;
import com.mychefai.healthytable.repository.ChatMessageRepository;
import com.mychefai.healthytable.repository.ChatSessionRepository;
import com.mychefai.healthytable.repository.FridgeItemRepository;
import com.mychefai.healthytable.repository.HealthCheckupRepository;
import com.mychefai.healthytable.repository.HealthProfileRepository;
import com.mychefai.healthytable.repository.RecipeRepository;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.repository.GeneratedRecipeRepository;
import com.mychefai.healthytable.repository.SearchCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final LlmService llmService; // GeminiService 대신 인터페이스 다형성 주입 적용
    private final FridgeItemRepository fridgeItemRepository;
    private final HealthProfileRepository healthProfileRepository;
    private final HealthCheckupRepository healthCheckupRepository;
    private final HealthCheckupAnalysisService healthCheckupAnalysisService;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final MealLogService mealLogService;
    private final UserRepository userRepository;
    private final RecipeRepository recipeRepository;

    private final SearchEngine searchEngine;
    private final ChatIntentClassifier chatIntentClassifier;
    private final RecipeNormalizer recipeNormalizer;
    private final RecipeValidator recipeValidator;
    private final SearchCacheRepository searchCacheRepository;
    private final GeneratedRecipeRepository generatedRecipeRepository;

    @Value("${rag.negative-cache-days:7}")
    private int negativeCacheDays;

    private static final int MAX_RAG_RECIPE_COUNT = 3;
    private static final int MAX_RECIPE_FIELD_LENGTH = 900;
    private static final List<String> RECIPE_QUERY_STOPWORDS = List.of(
            "레시피", "요리", "만드는법", "만드는", "만들기", "만들어줘", "알려줘", "추천해줘",
            "추천", "식단", "방법", "조리법", "해줘", "해주세요", "좀", "오늘", "점심", "저녁", "아침",
            "들어간", "넣은", "있는", "없는");
    private static final List<String> RECIPE_CATEGORY_KEYWORDS = List.of(
            "찌개", "국", "탕", "볶음", "구이", "덮밥", "비빔밥", "찜", "조림", "무침", "샐러드", "파스타");

    @Transactional
    public ChatSession resolveSession(Long userId, ChatDto.Request request) {
        if (request.getSessionId() != null) {
            return chatSessionRepository.findByIdAndUserId(request.getSessionId(), userId)
                    .orElseGet(() -> createSession(userId, request.getMessage()));
        }
        return createSession(userId, request.getMessage());
    }

    @Transactional
    public ChatSession createSession(Long userId, String firstMessage) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(resolveTitle(firstMessage));
        return chatSessionRepository.save(session);
    }

    @Transactional
    public void saveChatMessage(ChatSession session, String role, String content) {
        if (session == null || content == null || content.isBlank()) {
            return;
        }
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        chatMessageRepository.save(message);
        session.touch();
        chatSessionRepository.save(session);
    }

    public Mono<ChatDto.Response> processChat(Optional<Long> authenticatedUserId, ChatDto.Request request) {
        StringBuilder systemContext = new StringBuilder();

        // 1. 의도(Intent) 식별
        ChatIntentClassifier.ChatIntent intent = chatIntentClassifier.classify(request.getMessage());
        boolean isDetailFollowUp = isRecipeDetailFollowUp(request.getMessage());
        boolean isRecipeRequestIntent = (intent == ChatIntentClassifier.ChatIntent.RECIPE_REQUEST) && !isDetailFollowUp;

        // 2. 입력 정규화 (자연어 질문에서 핵심 요리명만 추출)
        String normalizedTitle = isRecipeRequestIntent ? recipeNormalizer.normalize(request.getMessage()) : "";

        // 3. DB 조회 (정규화된 제목 기반)
        List<Recipe> trustedRecipes = (isRecipeRequestIntent && !normalizedTitle.isBlank())
                ? findTrustedRecipesSafely(normalizedTitle)
                : List.of();
        boolean hasTrustedRecipe = !trustedRecipes.isEmpty();

        ChatSession chatSession = authenticatedUserId
                .map(userId -> resolveSession(userId, request))
                .orElse(null);

        if (hasTrustedRecipe) {
            appendTrustedRecipeContext(systemContext, trustedRecipes);
        }

        if (authenticatedUserId.isPresent()) {
            try {
                Long userIdLong = authenticatedUserId.get();

                saveChatMessage(chatSession, "user", request.getMessage());

                if (isSaveToCalendarRequest(request.getMessage())) {
                    Optional<ChatDto.Response> savedResponse = saveCurrentRecommendation(userIdLong, chatSession, request.getMessage());
                    if (savedResponse.isPresent()) {
                        saveChatMessage(chatSession, "model", savedResponse.get().getReply());
                        return Mono.just(savedResponse.get());
                    }
                }

                if (isAlternativeExclusionRecipeRequest(request.getMessage())) {
                    Optional<ChatDto.Response> alternativeResponse = buildAlternativeRecipeExcludingIngredients(userIdLong, chatSession, request);
                    if (alternativeResponse.isPresent()) {
                        saveChatMessage(chatSession, "model", alternativeResponse.get().getReply());
                        return Mono.just(alternativeResponse.get());
                    }
                }

                if (isDetailFollowUp) {
                    Optional<ChatDto.Response> detailedResponse = buildDetailedRecipeFollowUp(userIdLong, chatSession, request);
                    if (detailedResponse.isPresent()) {
                        saveChatMessage(chatSession, "model", detailedResponse.get().getReply());
                        return Mono.just(detailedResponse.get());
                    }
                }

                if (hasTrustedRecipe && !isRevisionOrQuestion(request.getMessage())) {
                    Recipe selectedRecipe = trustedRecipes.get(0);
                    List<String> safetyNotes = buildRecipeSafetyNotes(Optional.of(userIdLong), selectedRecipe);
                    String reply = buildTrustedRecipeReply(selectedRecipe, safetyNotes);
                    saveChatMessage(chatSession, "model", reply);
                    if (chatSession != null) {
                        recipeWorkSessionService.saveRecommendation(userIdLong, chatSession.getId(), reply);
                    }
                    ChatDto.Response response = new ChatDto.Response(chatSession != null ? chatSession.getId() : null, reply, true, false);
                    response.setRecipe(buildRecipeCard(selectedRecipe, safetyNotes));
                    return Mono.just(response);
                }

                // 1. 건강 프로필
                healthProfileRepository.findByUserId(userIdLong).ifPresent(profile -> {
                    systemContext.append("\n\n=== 중요: 사용자 건강 정보 (반드시 준수) ===\n");
                    if (profile.getAllergies() != null && !profile.getAllergies().isEmpty()) {
                        systemContext.append("알레르기: ").append(String.join(", ", profile.getAllergies())).append("\n");
                        systemContext.append("이 재료들은 절대 사용하지 마세요.\n");
                    }
                    if (profile.getChronicConditions() != null && !profile.getChronicConditions().isEmpty()) {
                        systemContext.append("만성질환: ").append(String.join(", ", profile.getChronicConditions())).append("\n");
                    }
                    if (profile.getDietaryRestrictions() != null && !profile.getDietaryRestrictions().isEmpty()) {
                        systemContext.append("식단 제한: ").append(String.join(", ", profile.getDietaryRestrictions())).append("\n");
                    }
                    if (profile.getMedications() != null && !profile.getMedications().isEmpty()) {
                        systemContext.append("복용 약물: ").append(String.join(", ", profile.getMedications())).append("\n");
                        systemContext.append("약물과 상호작용할 수 있는 음식을 피해주세요.\n");
                    }
                    if (profile.getGoals() != null && !profile.getGoals().isEmpty()) {
                        systemContext.append("건강 목표: ").append(String.join(", ", profile.getGoals())).append("\n");
                    }
                    systemContext.append("=====================================\n");
                });

                // 2. 건강검진 분석
                healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userIdLong).ifPresent(checkup -> {
                    HealthCheckupAnalysisDTO analysis = healthCheckupAnalysisService.analyze(checkup);
                    systemContext.append("\n=== 최신 건강검진 기반 식단 정책 ===\n");
                    systemContext.append("검진일: ").append(checkup.getCheckupDate()).append("\n");
                    appendMetric(systemContext, "BMI", checkup.getBmi());
                    appendMetric(systemContext, "혈압", formatBloodPressure(checkup));
                    appendMetric(systemContext, "공복혈당", checkup.getFastingGlucose());
                    appendMetric(systemContext, "LDL", checkup.getLdl());
                    appendMetric(systemContext, "중성지방", checkup.getTriglyceride());
                    appendMetric(systemContext, "AST/ALT", formatLiverNumbers(checkup));
                    systemContext.append("분석 요약: ").append(analysis.getSummary()).append("\n");
                    if (!analysis.getRisks().isEmpty()) {
                        systemContext.append("주의 항목: ").append(String.join(", ", analysis.getRisks())).append("\n");
                    }
                    if (!analysis.getRecommendationPolicies().isEmpty()) {
                        systemContext.append("추천 정책: ").append(String.join(" / ", analysis.getRecommendationPolicies())).append("\n");
                    }
                    systemContext.append("주의: 의료 진단처럼 단정하지 말고 식단 참고 정보로만 삼으세요.\n");
                    systemContext.append("====================================\n");
                });

                // 3. 작업 세션
                recipeWorkSessionService.find(userIdLong, chatSession.getId()).ifPresent(workSession -> {
                    systemContext.append("\n=== 현재 수정 중인 추천 결과 ===\n");
                    systemContext.append(workSession.getLastRecommendation()).append("\n");
                    if (workSession.getModifiers() != null && !workSession.getModifiers().isEmpty()) {
                        systemContext.append("수정 요청 사항: ").append(String.join(" / ", workSession.getModifiers())).append("\n");
                    }
                    systemContext.append("이전 추천 레시피 내용을 기준으로 반영하세요.\n");
                    systemContext.append("================================\n");
                });

                if (isRevisionRequest(request.getMessage())) {
                    recipeWorkSessionService.addModifier(userIdLong, chatSession.getId(), request.getMessage());
                }

                // 4. 냉장고 재료: 내부 DB 레시피가 있으면 환각 방지를 위해 냉장고 재료를 섞지 않는다.
                List<FridgeItem> fridgeItems = isRecipeRequestIntent
                        ? fridgeItemRepository.findByUserIdOrderByExpiryDate(userIdLong)
                        : List.of();
                if (isRecipeRequestIntent && request.isUseFridge() && !hasTrustedRecipe) {
                    systemContext.append("\n=== 냉장고 모드 ON ===\n");
                    if (!fridgeItems.isEmpty()) {
                        systemContext.append("등록된 냉장고 재료 목록 (참고용):\n");
                        String fridgeInfo = fridgeItems.stream()
                                .map(item -> String.format("- %s (%s)",
                                        item.getName(), item.getQuantity()))
                                .collect(Collectors.joining("\n"));
                        systemContext.append(fridgeInfo).append("\n\n");
                        systemContext.append("[지시사항]\n");
                        systemContext.append("1. 사용자가 특정 요리를 지정하여 레시피나 만드는 법을 요청한 경우:\n");
                        systemContext.append("   - 반드시 해당 요리의 검색 근거에 맞는 대표 재료와 조리 방식을 우선 사용하십시오.\n");
                        systemContext.append("   - 냉장고 재료를 무리하게 활용하기 위해 요리의 레시피를 왜곡하거나 주재료를 엉뚱하게 변경하지 마십시오.\n");
                        systemContext.append("   - 냉장고 속 재료 중 요청한 레시피에 겹치거나 활용 가능한 재료가 있다면, 레시피 작성이 모두 끝난 맨 마지막에 '냉장고 재료 활용 팁'으로 가볍게 한 줄만 제안해 주십시오.\n");
                        systemContext.append("2. 사용자가 특정 요리를 지정하지 않고 추천을 요청한 경우 (예: '냉장고 재료로 만들 수 있는 요리 추천해줘', '오늘 저녁 추천'):\n");
                        systemContext.append("   - 냉장고 재료 중 서로 잘 어울리는 재료들을 1~4개 선택하여 만들 수 있는 메뉴 3개 이내를 짧게 제안하고, 각 메뉴를 추천하는 이유를 설명하십시오. 사용자가 요리를 선택하기 전까지는 상세 조리 순서를 쓰지 마십시오.\n");
                    } else {
                        systemContext.append("등록된 냉장고 재료가 없어 일반 레시피로 추천합니다.\n");
                    }
                    systemContext.append("========================\n");
                } else if (isRecipeRequestIntent && request.isUseFridge()) {
                    systemContext.append("\n=== 냉장고 모드 보류 ===\n");
                    systemContext.append("내부 DB에서 사용자가 요청한 요리의 정확한 레시피를 찾았으므로 냉장고 재료를 임의로 추가하지 마세요.\n");
                    systemContext.append("사용자가 명시적으로 대체를 요청한 경우에만 건강 정보와 조리 맥락에 맞는 재료로 최소한만 조정하세요.\n");
                    systemContext.append("=====================\n");
                } else if (isRecipeRequestIntent) {
                    systemContext.append("\n=== 냉장고 모드 OFF ===\n");
                    systemContext.append("냉장고 내 재료를 배제하고 어울리는 다양한 재료로 추천해 주세요.\n");
                    systemContext.append("======================\n");
                } else {
                    systemContext.append("\n=== 일반 대화 ===\n");
                    systemContext.append("사용자가 인사, 자기소개 요청, 잡담을 한 경우 레시피를 만들지 말고 Salus를 짧게 소개하며 자연스럽게 답하세요.\n");
                    systemContext.append("사용자가 메뉴 추천만 원하면 조리법을 쓰지 말고 메뉴 후보와 이유만 짧게 답하세요. 사용자가 불만을 말하면 인정하고 더 쉬운 대안으로 전환하세요.\n");
                    systemContext.append("정체를 묻지 않은 일반 질문에는 자기소개를 반복하지 마세요.\n");
                    systemContext.append("================\n");
                }

            } catch (Exception e) {
                log.error("개인화 컨텍스트 구성 중 오류 발생", e);
            }
        }

        if (hasTrustedRecipe && !isRevisionOrQuestion(request.getMessage())) {
            Recipe selectedRecipe = trustedRecipes.get(0);
            List<String> safetyNotes = buildRecipeSafetyNotes(Optional.empty(), selectedRecipe);
            String reply = buildTrustedRecipeReply(selectedRecipe, safetyNotes);
            ChatDto.Response response = new ChatDto.Response(null, reply, true, false);
            response.setRecipe(buildRecipeCard(selectedRecipe, safetyNotes));
            return Mono.just(response);
        }

        // RAG 및 Negative Caching 비동기 체인 구성
        Mono<RAGData> ragDataMono;

        if (isRecipeRequestIntent && !hasTrustedRecipe && !normalizedTitle.isBlank()) {
            // 1. Negative Cache 조회
            Optional<SearchCache> cache = searchCacheRepository.findByQuery(normalizedTitle);
            if (cache.isPresent() && !cache.get().isFound()) {
                LocalDateTime createdAt = cache.get().getCreatedAt();
                long ageDays = createdAt == null ? negativeCacheDays : Duration.between(createdAt, LocalDateTime.now()).toDays();
                if (ageDays < negativeCacheDays) {
                    log.info("[RAG Pipeline] Negative Cache hit for query: '{}' (Age: {} days). Rejecting request.", normalizedTitle, ageDays);
                    String rejectReply = "죄송합니다. 신뢰할 수 있는 레시피 정보를 찾지 못했습니다. 다른 음식이나 정통 레시피를 물어봐 주세요.";
                    saveChatMessage(chatSession, "model", rejectReply);
                    return Mono.just(new ChatDto.Response(chatSession != null ? chatSession.getId() : null, rejectReply, false, false));
                } else {
                    log.info("[RAG Pipeline] Negative Cache expired for query: '{}'. Deleting cache entry.", normalizedTitle);
                    searchCacheRepository.deleteByQuery(normalizedTitle);
                }
            }

            // 2. 실시간 웹 검색 수행
            ragDataMono = searchEngine.search(normalizedTitle)
                    .flatMap(searchResponse -> {
                        if (searchResponse.status() == SearchEngine.SearchStatus.FAILED) {
                            log.warn("[RAG Pipeline] Web search FAILED for query: '{}'. Skipping cache write and falling back to LLM.", normalizedTitle);
                            return Mono.just(new RAGData(SearchEngine.SearchStatus.FAILED, "", "", searchResponse.source()));
                        } else if (searchResponse.status() == SearchEngine.SearchStatus.EMPTY) {
                            log.info("[RAG Pipeline] Web search returned EMPTY for query: '{}'. Adding to negative cache.", normalizedTitle);
                            try {
                                searchCacheRepository.deleteByQuery(normalizedTitle);
                                SearchCache newCache = new SearchCache();
                                newCache.setQuery(normalizedTitle);
                                newCache.setFound(false);
                                searchCacheRepository.save(newCache);
                            } catch (Exception e) {
                                log.error("[RAG Pipeline] Failed to save negative cache", e);
                            }
                            return Mono.just(new RAGData(SearchEngine.SearchStatus.EMPTY, "", "", searchResponse.source()));
                        } else {
                            // 성공
                            StringBuilder contextBuilder = new StringBuilder();
                            contextBuilder.append("검색어: ").append(normalizedTitle).append("\n");
                            for (SearchEngine.SearchResult result : searchResponse.results()) {
                                contextBuilder.append("- ").append(result.title()).append(": ").append(result.snippet()).append("\n");
                            }
                            String rawSearchContext = contextBuilder.toString();
                            String systemContextSnippet = "\n\n=== 외부 검색 결과 자료 (참고용) ===\n" +
                                    "다음은 외부 웹에서 검색된 '" + normalizedTitle + "' 관련 레시피 정보입니다. " +
                                    "이 근거에 반복적으로 등장하는 대표 재료, 조리 방식, 조리 시간을 우선하여 사용자가 요청한 레시피를 구성해 답변해 주세요.\n" +
                                    rawSearchContext +
                                    "검색 결과에 명시되지 않은 뜬금없는 핵심 재료를 임의로 추가하여 요리를 왜곡하거나 퓨전식으로 변경하지 마십시오.\n" +
                                    "검색 근거에 없는 견과류, 과일, 밤, 치즈, 크림, 소스류 같은 선택 재료는 정통 레시피의 핵심이 아니라면 추가하지 마십시오.\n" +
                                    "고기, 생선, 빵 반죽, 페이스트리처럼 감싸거나 덮는 재료는 실제로 감쌀 수 있는 수량으로 작성하십시오. 한 장으로 전체를 감쌀 수 없는 재료를 '1장'으로 쓰지 마십시오.\n" +
                                    "오븐에서 마저 익히는 고기 요리는 팬에서 속까지 익히라고 쓰지 말고, 겉면만 노릇하게 굽는 시어링과 오븐 익힘을 구분하십시오.\n" +
                                    "영문 검색 근거의 pastry, puff pastry, pastry dough는 파스타가 아니라 '페이스트리 생지' 또는 '퍼프 페이스트리'로 번역하십시오.\n" +
                                    "dough는 문맥에 따라 '생지'로 번역하고, pasta와 혼동하지 마십시오.\n" +
                                    "반드시 아래 형식을 그대로 지키십시오. 인사말, 자기소개, 사과문, 검색 결과 설명을 쓰지 마십시오.\n" +
                                    normalizedTitle + " 레시피입니다.\n\n" +
                                    "요리 설명 한 문장\n\n" +
                                    "조리 시간: 숫자분 / 열량: 숫자kcal / 난이도: 1~3\n\n" +
                                    "[재료]\n" +
                                    "- 재료명 양\n\n" +
                                    "[조리 순서]\n" +
                                    "1. 조리 단계\n" +
                                    "=========================================\n";
                            return Mono.just(new RAGData(SearchEngine.SearchStatus.SUCCESS, systemContextSnippet, rawSearchContext, searchResponse.source()));
                        }
                    });
        } else {
            ragDataMono = Mono.just(new RAGData(SearchEngine.SearchStatus.SUCCESS, "", "", ""));
        }

        final Long userIdForWork = authenticatedUserId.orElse(null);
        final Long sessionIdForWork = chatSession != null ? chatSession.getId() : null;

        return ragDataMono.flatMap(ragData -> {
            if (ragData.status() == SearchEngine.SearchStatus.EMPTY) {
                String rejectReply = "죄송합니다. 신뢰할 수 있는 레시피 정보를 찾지 못했습니다. 다른 음식이나 정통 레시피를 물어봐 주세요.";
                saveChatMessage(chatSession, "model", rejectReply);
                return Mono.just(new ChatDto.Response(sessionIdForWork, rejectReply, false, false));
            }

            if (!ragData.systemContextSnippet().isEmpty()) {
                systemContext.append(ragData.systemContextSnippet());
            }

            final String finalMessage = systemContext.length() > 0 ? request.getMessage() + systemContext : request.getMessage();
            List<ChatDto.Message> history = resolveHistoryForAi(chatSession, request);

            return llmService.getChatResponse(finalMessage, history)
                    .map(reply -> {
                        String responseReply = reply;
                        Recipe responseRecipe = null;
                        boolean active = false;

                        if (isLlmUnavailableReply(reply)) {
                            responseReply = reply;
                        } else if (!isRecipeRequestIntent && looksLikeRecipeResponse(reply)) {
                            log.warn("[Intent Guard] Non-recipe intent produced recipe-like reply. Intent: {}, message: '{}'",
                                    intent, request.getMessage());
                            responseReply = buildNonRecipeIntentReply(intent, request.getMessage());
                        } else if (isRecipeRequestIntent && !hasTrustedRecipe && !normalizedTitle.isBlank() && !looksLikeRecipeResponse(reply)) {
                            log.warn("[RAG Pipeline] Recipe request produced non-recipe reply. Query: '{}', Reply: {}",
                                    normalizedTitle, truncateRecipeField(nullToBlank(reply)));
                            responseReply = buildRecipeValidationFailureReply(normalizedTitle, ragData.status());
                        } else if (isRecipeRequestIntent && !hasTrustedRecipe && !normalizedTitle.isBlank() && looksLikeRecipeResponse(reply)) {
                            try {
                                responseReply = sanitizeRecipeReply(reply);
                                responseReply = normalizeSearchBasedTranslationArtifacts(responseReply, ragData.rawSearchContext(), normalizedTitle);
                                responseReply = applyRecipeQualityGuards(responseReply, normalizedTitle);
                                Recipe parsedRecipe = parseRecipeFromReply(normalizedTitle, responseReply);
                                if (parsedRecipe != null) {
                                    RecipeValidator.ValidationResult valResult = recipeValidator.validate(
                                            parsedRecipe, ragData.rawSearchContext(), responseReply);
                                    saveGeneratedRecipeAudit(normalizedTitle, parsedRecipe, ragData.rawSearchContext(), ragData.source(), responseReply, valResult);

                                    if (!valResult.valid()) {
                                        log.warn("[RAG Pipeline] Generated recipe failed validation for query: '{}'. Reasons: {}",
                                                normalizedTitle, valResult.reasons());
                                        responseReply = buildRecipeValidationFailureReply(normalizedTitle, ragData.status());
                                    } else if (!valResult.dataQualityLow()) {
                                        saveToRecipeDbSafely(parsedRecipe);
                                        responseRecipe = parsedRecipe;
                                    } else {
                                        log.info("[RAG Pipeline] Generated recipe served but not promoted due to data quality warnings. Query: '{}', Warnings: {}",
                                                normalizedTitle, valResult.dataQualityWarnings());
                                        responseRecipe = parsedRecipe;
                                    }
                                } else {
                                    log.warn("[RAG Pipeline] Generated recipe-like reply could not be parsed for validation. Query: '{}', Reply: {}",
                                            normalizedTitle, truncateRecipeField(nullToBlank(responseReply)));
                                    responseReply = buildRecipeValidationFailureReply(normalizedTitle, ragData.status());
                                }
                            } catch (Exception e) {
                                log.error("[RAG Pipeline] Failed in validation/audit/promotion sequence", e);
                                responseReply = buildRecipeValidationFailureReply(normalizedTitle, ragData.status());
                            }
                        }

                        if (userIdForWork != null && sessionIdForWork != null && looksLikeRecipeResponse(responseReply)) {
                            recipeWorkSessionService.saveRecommendation(userIdForWork, sessionIdForWork, responseReply);
                            active = true;
                        }
                        if (chatSession != null) {
                            saveChatMessage(chatSession, "model", responseReply);
                        }
                        ChatDto.Response response = new ChatDto.Response(sessionIdForWork, responseReply, active, false);
                        if (responseRecipe != null && looksLikeRecipeResponse(responseReply)) {
                            response.setRecipe(buildRecipeCard(responseRecipe, buildRecipeSafetyNotes(authenticatedUserId, responseRecipe)));
                        }
                        return response;
                    });
        });
    }

    public List<ChatDto.Message> resolveHistoryForAi(ChatSession session, ChatDto.Request request) {
        if (session == null) {
            return request.getHistory();
        }
        List<ChatMessage> persisted = new ArrayList<>(chatMessageRepository.findTop12BySessionOrderByCreatedAtDesc(session));
        persisted.sort(Comparator.comparing(ChatMessage::getCreatedAt));
        if (!persisted.isEmpty()) {
            ChatMessage last = persisted.get(persisted.size() - 1);
            if ("user".equals(last.getRole()) && last.getContent().equals(request.getMessage())) {
                persisted.remove(persisted.size() - 1);
            }
        }
        return persisted.stream()
                .map(message -> new ChatDto.Message(message.getRole(), message.getContent()))
                .toList();
    }

    private Optional<ChatDto.Response> buildDetailedRecipeFollowUp(Long userId, ChatSession chatSession, ChatDto.Request request) {
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getLastRecommendation() == null
                || workSession.get().getLastRecommendation().isBlank()) {
            return Optional.empty();
        }

        String prompt = """
                다음은 사용자가 직전에 받은 레시피입니다.
                사용자는 이 레시피의 조리법을 더 자세히 알고 싶어합니다.

                [직전 레시피]
                %s

                [사용자 요청]
                %s

                [응답 지침]
                - 인사, 자기소개, Salus 소개를 쓰지 마세요.
                - 새 레시피를 만들거나 다른 요리로 바꾸지 마세요.
                - 직전 레시피의 재료와 조리 흐름을 유지하세요.
                - 직전 레시피에 수량이 비현실적인 재료, 조리 원리와 충돌하는 설명, 애매한 재료 사용이 있으면 조용히 바로잡아 설명하세요.
                - 단순히 괄호 안에 "풍미를 더합니다" 같은 추상 설명만 붙이지 마세요.
                - 요리를 거의 안 해본 사람도 따라할 수 있게 각 단계를 "무엇을 / 불 세기 / 몇 분 / 어떤 상태까지 / 왜 하는지 / 실패하면 어떻게 복구하는지"로 설명하세요.
                - 재료가 어느 단계에 들어가는지 애매하게 쓰지 마세요. 예: 버터/된장/미소가 감자에 들어가는지, 고기 조림장에 들어가는지 명확히 구분하세요.
                - 곁들임이나 퓌레처럼 따로 준비하는 요소가 있으면 '고기 조림'과 '곁들임 준비'를 별도 단계로 나누고, 마지막에 접시에 어떻게 담는지 설명하세요.
                - 고기를 오븐에서 마저 익히는 요리는 팬에서 속까지 익히라고 쓰지 말고, 겉면만 노릇하게 굽는 시어링과 최종 익힘을 구분하세요.
                - 불 세기, 시간, 익힘 확인법, 실패했을 때 복구 방법을 포함하세요.
                - 마지막에 초보자 실수 3가지를 "실수 / 왜 문제인지 / 해결법" 형태로 짧게 정리하세요.
                """.formatted(sanitizeRecipeReply(workSession.get().getLastRecommendation()), request.getMessage());

        List<ChatDto.Message> history = resolveHistoryForAi(chatSession, request);
        Long sessionId = chatSession.getId();
        return Optional.of(llmService.getChatResponse(prompt, history)
                .map(reply -> {
                    String sanitized = sanitizeRecipeReply(reply);
                    String title = extractRecipeTitle(workSession.get().getLastRecommendation());
                    sanitized = applyRecipeQualityGuards(sanitized, title);
                    recipeWorkSessionService.saveRecommendation(userId, sessionId, sanitized);
                    return new ChatDto.Response(sessionId, sanitized, true, false);
                })
                .block());
    }

    private Optional<ChatDto.Response> buildAlternativeRecipeExcludingIngredients(Long userId, ChatSession chatSession, ChatDto.Request request) {
        if (chatSession == null) {
            return Optional.empty();
        }
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getLastRecommendation() == null
                || workSession.get().getLastRecommendation().isBlank()) {
            return Optional.empty();
        }

        List<String> excludedIngredients = extractExcludedIngredients(request.getMessage());
        if (excludedIngredients.isEmpty()) {
            return Optional.empty();
        }

        String lastRecommendation = sanitizeRecipeReply(workSession.get().getLastRecommendation());
        String baseTitle = removeExistingExclusionPrefix(
                extractFollowUpRecipeTitle(lastRecommendation),
                excludedIngredients);
        Recipe baseRecipe = parseRecipeFromReply(baseTitle, lastRecommendation);
        if (baseRecipe == null) {
            return Optional.empty();
        }

        Recipe variant = new Recipe();
        String excludedText = String.join(", ", excludedIngredients);
        variant.setTitle(excludedText + " 없는 " + baseTitle);
        variant.setDescription(excludedText + " 없이 기본 양념과 조리 흐름은 유지한 " + baseTitle + "입니다.");
        variant.setIngredients(removeExcludedIngredients(baseRecipe.getIngredients(), excludedIngredients));
        variant.setSteps(removeExcludedSteps(baseRecipe.getSteps(), excludedIngredients));
        variant.setCalories(baseRecipe.getCalories());
        variant.setDifficulty(baseRecipe.getDifficulty());
        variant.setCookingTime(baseRecipe.getCookingTime());

        String reply = buildGeneratedRecipeReply(variant);
        recipeWorkSessionService.saveRecommendation(userId, chatSession.getId(), reply);

        ChatDto.Response response = new ChatDto.Response(chatSession.getId(), reply, true, false);
        response.setRecipe(buildRecipeCard(variant, buildRecipeSafetyNotes(Optional.of(userId), variant)));
        return Optional.of(response);
    }

    @Transactional
    public Optional<ChatDto.Response> saveCurrentRecommendation(Long userId, ChatSession chatSession, String saveRequest) {
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getLastRecommendation() == null) {
            return Optional.of(new ChatDto.Response(
                    chatSession.getId(),
                    "저장할 추천 결과를 찾지 못했습니다. 레시피를 추천받은 후 저장해 주세요.",
                    false,
                    false));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        String recommendation = workSession.get().getLastRecommendation();
        MealSlot slot = resolveMealSlot(saveRequest + "\n" + recommendation);
        String title = extractRecipeTitle(recommendation);

        MealLogDTO dto = new MealLogDTO();
        dto.setRecordDate(resolveTargetDate(saveRequest + "\n" + recommendation));
        if ("breakfast".equals(slot.fieldName())) {
            dto.setBreakfast(title);
            dto.setBreakfastCalories(extractCalories(recommendation));
            dto.setIsAiBreakfast(true);
            dto.setMealDetails("{\"breakfast\":{\"fullText\":" + quoteJson(recommendation) + "}}");
        } else if ("dinner".equals(slot.fieldName())) {
            dto.setDinner(title);
            dto.setDinnerCalories(extractCalories(recommendation));
            dto.setIsAiDinner(true);
            dto.setMealDetails("{\"dinner\":{\"fullText\":" + quoteJson(recommendation) + "}}");
        } else {
            dto.setLunch(title);
            dto.setLunchCalories(extractCalories(recommendation));
            dto.setIsAiLunch(true);
            dto.setMealDetails("{\"lunch\":{\"fullText\":" + quoteJson(recommendation) + "}}");
        }

        mealLogService.saveOrUpdateMealLog(user, dto);
        recipeWorkSessionService.clear(userId, chatSession.getId());

        String reply = String.format("%s %s 식단에 '%s'를 저장했습니다.",
                dto.getRecordDate(),
                slot.koreanName(),
                title);
        return Optional.of(new ChatDto.Response(chatSession.getId(), reply, false, true));
    }

    private void appendMetric(StringBuilder builder, String label, Object value) {
        if (value != null) {
            builder.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    private String resolveTitle(String message) {
        if (message == null || message.isBlank()) {
            return "새 대화";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 35 ? normalized.substring(0, 35) + "..." : normalized;
    }

    private boolean isRevisionRequest(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("바꿔") || message.contains("수정") || message.contains("변경")
                || message.contains("줄여") || message.contains("늘려") || message.contains("매운");
    }

    private boolean isAlternativeExclusionRecipeRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.replaceAll("\\s+", "");
        boolean asksAlternative = normalized.contains("또다른")
                || normalized.contains("다른")
                || normalized.contains("버전")
                || normalized.contains("레시피");
        boolean excludesIngredient = normalized.contains("안들어간")
                || normalized.contains("없는")
                || normalized.contains("빼고")
                || normalized.contains("제외")
                || normalized.contains("말고");
        return asksAlternative && excludesIngredient && !extractExcludedIngredients(message).isEmpty();
    }

    private boolean isRecipeDetailFollowUp(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.replaceAll("\\s+", "");
        boolean asksForMoreDetail = normalized.contains("자세하게")
                || normalized.contains("자세히")
                || normalized.contains("상세하게")
                || normalized.contains("상세히")
                || normalized.contains("더알려")
                || normalized.contains("더자세")
                || normalized.contains("조금더")
                || normalized.contains("풀어서")
                || normalized.contains("초보자")
                || normalized.contains("왜하는지")
                || normalized.contains("조리법을더")
                || normalized.contains("만드는법을더");
        boolean hasNewFoodName = normalized.contains("레시피")
                && normalized.length() > 18
                && !normalized.startsWith("조리법")
                && !normalized.startsWith("만드는법");
        return asksForMoreDetail && !hasNewFoodName;
    }

    private boolean isRevisionOrQuestion(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        if (isRevisionRequest(message)) {
            return true;
        }

        String normalized = message.replaceAll("\\s+", "");
        return normalized.contains("?")
                || normalized.contains("야?")
                || normalized.contains("까?")
                || normalized.contains("요?")
                || normalized.contains("대신")
                || normalized.contains("대체")
                || normalized.contains("빼고")
                || normalized.contains("제외")
                || normalized.contains("추가")
                || normalized.contains("넣어")
                || normalized.contains("들어")
                || normalized.contains("칼로리")
                || normalized.contains("열량")
                || normalized.contains("영양")
                || normalized.contains("시간")
                || normalized.contains("분걸려")
                || normalized.contains("어려워")
                || normalized.contains("쉬워");
    }

    private boolean isSaveToCalendarRequest(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("저장")
                && (message.contains("캘린더") || message.contains("식단") || message.contains("기록"));
    }

    private String sanitizeRecipeReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        return reply.lines()
                .map(this::removeSalusIntroFragments)
                .filter(line -> {
                    String compact = line.replaceAll("\\s+", "");
                    return !(compact.startsWith("안녕하세요")
                            || compact.contains("저는Salus입니다")
                            || compact.contains("Salus입니다")
                            || compact.contains("건강한식탁을위한Salus"));
                })
                .collect(Collectors.joining("\n"))
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String removeSalusIntroFragments(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        return line
                .replaceAll("안녕하세요[,!\\s]*저는\\s*Salus입니다\\.\\s*요리와\\s*식단에\\s*도움을\\s*드릴\\s*수\\s*있습니다\\.?", "")
                .replaceAll("안녕하세요[,!\\s]*건강한\\s*식탁을\\s*위한\\s*Salus입니다\\.?", "")
                .replaceAll("저는\\s*Salus입니다\\.\\s*요리와\\s*식단에\\s*도움을\\s*드릴\\s*수\\s*있습니다\\.?", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String normalizeSearchBasedTranslationArtifacts(String reply, String searchContext, String title) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        String normalizedContext = nullToBlank(searchContext).toLowerCase();
        String normalizedTitle = nullToBlank(title).replaceAll("\\s+", "").toLowerCase();
        boolean pastryEvidence = normalizedContext.contains("pastry")
                || normalizedContext.contains("puff pastry")
                || normalizedContext.contains("페이스트리");
        boolean nonPastaRecipe = !normalizedTitle.contains("파스타") && !normalizedTitle.contains("pasta");
        if (!pastryEvidence && !nonPastaRecipe) {
            return reply;
        }

        return reply
                .replace("파스타 생지", "퍼프 페이스트리")
                .replace("파스타 도우", "퍼프 페이스트리")
                .replace("파스타 반죽", "페이스트리 생지");
    }

    private String applyRecipeQualityGuards(String reply, String title) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        String normalizedTitle = nullToBlank(title).replaceAll("\\s+", "");
        String cleaned = reply;

        boolean nonPieRecipe = !normalizedTitle.contains("파이")
                && !normalizedTitle.contains("타르트")
                && !normalizedTitle.contains("키슈");
        if (nonPieRecipe) {
            cleaned = cleaned
                    .replace("파이 크러스트 생지", "퍼프 페이스트리")
                    .replace("파이 크러스트", "퍼프 페이스트리");
        }

        boolean hasWrappingContext = cleaned.contains("감싸")
                || cleaned.contains("말아")
                || cleaned.contains("깔고")
                || cleaned.contains("덮어");
        if (hasWrappingContext) {
            cleaned = cleaned
                    .replace("프로슈토 1장", "프로슈토 6장")
                    .replace("프로슈토 적당량", "프로슈토 6장")
                    .replace("베이컨 1장", "베이컨 6장")
                    .replace("베이컨 적당량", "베이컨 6장");
            cleaned = removeWrappingIngredientFromChoppedFilling(cleaned, "프로슈토");
            cleaned = removeWrappingIngredientFromChoppedFilling(cleaned, "하몽");
            cleaned = removeWrappingIngredientFromChoppedFilling(cleaned, "베이컨");
        }

        if (cleaned.contains("프로슈토") && cleaned.contains("하몽")) {
            cleaned = cleaned.replaceAll("(?m)^-\\s*하몽\\s+.*\\R?", "");
            cleaned = cleaned.replace("하몽으로", "프로슈토로");
            cleaned = cleaned.replace("하몽을", "프로슈토를");
            cleaned = cleaned.replace("하몽", "프로슈토");
        }

        if (cleaned.contains("기름을 두르고")
                && !cleaned.contains("올리브유") && !cleaned.contains("올리브 오일") && !cleaned.contains("식용유")) {
            cleaned = cleaned.replace("[조리 순서]", "- 올리브유 1큰술\n[조리 순서]");
        }
        if (cleaned.contains("버터") && !java.util.regex.Pattern.compile("(?m)^-\\s*버터\\b").matcher(cleaned).find()) {
            cleaned = cleaned.replace("[조리 순서]", "- 버터 1큰술\n[조리 순서]");
        }
        if ((cleaned.contains("계란 노른자") || cleaned.contains("계란물"))
                && !cleaned.contains("계란 1개") && !cleaned.contains("달걀 1개")) {
            cleaned = cleaned.replace("[조리 순서]", "- 계란 1개\n[조리 순서]");
        }
        if ((cleaned.contains("달걀 노른자") || cleaned.contains("달걀물"))
                && !cleaned.contains("계란 1개") && !cleaned.contains("달걀 1개")) {
            cleaned = cleaned.replace("[조리 순서]", "- 달걀 1개\n[조리 순서]");
        }
        if (cleaned.contains("소금") && !java.util.regex.Pattern.compile("(?m)^-\\s*소금").matcher(cleaned).find()) {
            cleaned = cleaned.replace("[조리 순서]", "- 소금 약간\n[조리 순서]");
        }
        if (cleaned.contains("후추")
                && !cleaned.contains("소금/후추")
                && !java.util.regex.Pattern.compile("(?m)^-\\s*후추").matcher(cleaned).find()) {
            cleaned = cleaned.replace("[조리 순서]", "- 후추 약간\n[조리 순서]");
        }
        if (cleaned.contains("25~30분") && cleaned.contains("조리 시간: 30분")) {
            cleaned = cleaned.replace("조리 시간: 30분", "조리 시간: 60분");
        }

        return cleaned
                .replaceAll("(퍼프\\s*){2,}페이스트리", "퍼프 페이스트리")
                .replaceAll("(?m)^-\\s*퍼프 페이스트리\\s*$", "- 퍼프 페이스트리 1장")
                .replaceAll("(?m)^-\\s*머스터드\\s*$", "- 홀그레인 머스터드 2큰술")
                .replaceAll("(?m)^-\\s*올리브오일\\s*$", "- 올리브오일 1큰술")
                .replaceAll("(?m)^-\\s*올리브 오일\\s*$", "- 올리브오일 1큰술")
                .replace("페이스트리 생지", "퍼프 페이스트리")
                .replaceAll("(?m)^-\\s*퍼프 페이스트리\\s*$", "- 퍼프 페이스트리 1장")
                .replace("머스터드 적당량", "홀그레인 머스터드 2큰술")
                .replace("마늘, 밤을", "마늘을")
                .replace("마늘과 밤을", "마늘을")
                .replace("밤을 푸드 프로세서에 넣고", "푸드 프로세서에 넣고")
                .replace("밤을 잘게 다져", "")
                .replace("소고기를 충분히 구우지 않아서 안전하지 않게 되는 경우",
                        "팬에서 소고기 속까지 익히려고 오래 구워 겉이 질겨지는 경우")
                .replace("소고기를 충분히 구우지 않아서 안전하지 않은 경우",
                        "팬에서 소고기 속까지 익히려고 오래 구워 겉이 질겨지는 경우")
                .replace("래스팅한", "식힌")
                .replace("높은 온도에서", "강불에서")
                .replace("시어링한 소고기를 머스터드를 바른다", "시어링한 소고기에 머스터드를 바른다")
                .replace("시어링한 소고기를 홀그레인 머스터드를 바른다", "시어링한 소고기에 홀그레인 머스터드를 바른다")
                .replace("오븐에서 200도로 예열된 오븐에서", "200도로 예열한 오븐에서")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String removeWrappingIngredientFromChoppedFilling(String text, String wrappingIngredient) {
        if (text == null || text.isBlank() || wrappingIngredient == null || wrappingIngredient.isBlank()) {
            return text;
        }
        return text
                .replaceAll("양송이버섯,\\s*마늘,\\s*" + wrappingIngredient + "(을|를)\\s*푸드 프로세서에 넣고",
                        "양송이버섯과 마늘을 푸드 프로세서에 넣고")
                .replaceAll("버섯,\\s*마늘,\\s*" + wrappingIngredient + "(을|를)\\s*푸드 프로세서에 넣고",
                        "버섯과 마늘을 푸드 프로세서에 넣고")
                .replaceAll("양송이버섯,\\s*" + wrappingIngredient + "(을|를)\\s*푸드 프로세서에 넣고",
                        "양송이버섯을 푸드 프로세서에 넣고")
                .replaceAll("버섯,\\s*" + wrappingIngredient + "(을|를)\\s*푸드 프로세서에 넣고",
                        "버섯을 푸드 프로세서에 넣고");
    }

    private String sanitizeRecipeDescription(Recipe recipe) {
        if (recipe == null || recipe.getDescription() == null || recipe.getDescription().isBlank()) {
            return "";
        }

        String description = removeSalusIntroFragments(recipe.getDescription());
        String title = nullToBlank(recipe.getTitle()).trim();
        if (!title.isBlank()) {
            description = description.replaceFirst("^" + java.util.regex.Pattern.quote(title) + "\\s*레시피(도)?\\s*알려줘\\s*", "");
        }
        return description
                .replaceFirst("^레시피(도)?\\s*알려줘\\s*", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private boolean isRecipeRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.replaceAll("\\s+", "");
        return normalized.contains("요리")
                || normalized.contains("레시피")
                || normalized.contains("만들")
                || normalized.contains("추천")
                || normalized.contains("식단")
                || normalized.contains("먹")
                || normalized.contains("카레")
                || normalized.contains("찌개")
                || normalized.contains("국")
                || normalized.contains("탕")
                || normalized.contains("볶음")
                || normalized.contains("구이")
                || normalized.contains("덮밥")
                || normalized.contains("파스타")
                || normalized.contains("샐러드")
                || normalized.contains("냉장고");
    }

    private void appendTrustedRecipeContext(StringBuilder systemContext, List<Recipe> recipes) {
        try {
            if (recipes.isEmpty()) {
                return;
            }

            systemContext.append("\n=== 신뢰 가능한 내부 레시피 DB 자료 (최우선) ===\n");
            systemContext.append("답변 첫 문장은 인사나 자기소개 없이 요청한 요리명과 레시피 안내로 바로 시작하세요.\n");
            systemContext.append("아래 자료가 사용자의 요청 음식과 맞으면 이 재료와 조리 순서를 최우선으로 사용하세요.\n");
            systemContext.append("DB 자료와 충돌하는 재료나 조리법을 새로 지어내지 말고, 냉장고 재료도 임의로 섞지 마세요.\n");
            systemContext.append("건강 정보 때문에 바꿔야 하는 경우에만 이유를 짧게 설명하세요.\n");
            for (int i = 0; i < recipes.size(); i++) {
                Recipe recipe = recipes.get(i);
                systemContext.append("\n[레시피 ").append(i + 1).append("]\n");
                systemContext.append("요리명: ").append(nullToBlank(recipe.getTitle())).append("\n");
                appendRecipeField(systemContext, "설명", recipe.getDescription());
                appendRecipeField(systemContext, "재료", joinRecipeList(recipe.getIngredients()));
                appendRecipeField(systemContext, "조리 순서", joinNumberedRecipeList(recipe.getSteps()));
                if (recipe.getCalories() != null) {
                    systemContext.append("열량: ").append(recipe.getCalories()).append(" kcal\n");
                }
                if (recipe.getCookingTime() != null) {
                    systemContext.append("조리 시간: ").append(recipe.getCookingTime()).append("분\n");
                }
                if (recipe.getDifficulty() != null) {
                    systemContext.append("난이도: ").append(recipe.getDifficulty()).append("\n");
                }
            }
            systemContext.append("========================================\n");
        } catch (Exception e) {
            log.warn("레시피 DB 컨텍스트 구성 중 오류 발생: {}", e.getMessage());
        }
    }

    private List<Recipe> findTrustedRecipes(String message) {
        Map<Long, Recipe> matched = new LinkedHashMap<>();
        List<String> keywords = extractRecipeKeywords(message);
        for (String keyword : keywords) {
            recipeRepository.findByTitleContaining(keyword).stream()
                    .forEach(recipe -> matched.putIfAbsent(recipe.getId(), recipe));
        }
        return matched.values().stream()
                .filter(recipe -> isReliableRecipeMatch(recipe, message, keywords))
                .sorted((left, right) -> Integer.compare(scoreRecipe(right, keywords), scoreRecipe(left, keywords)))
                .limit(MAX_RAG_RECIPE_COUNT)
                .toList();
    }

    private boolean isReliableRecipeMatch(Recipe recipe, String requestedTitle, List<String> keywords) {
        String title = nullToBlank(recipe.getTitle()).replaceAll("\\s+", "").toLowerCase();
        String request = nullToBlank(requestedTitle).replaceAll("\\s+", "").toLowerCase();
        if (title.isBlank()) {
            return false;
        }

        if (title.equals(request)) {
            return true;
        }

        if (!request.isBlank() && request.contains(title) && request.length() - title.length() <= 2) {
            return true;
        }

        String bestFoodKeyword = keywords.stream()
                .map(keyword -> keyword.replaceAll("\\s+", "").toLowerCase())
                .filter(keyword -> keyword.length() >= 3 && keyword.length() >= request.length() - 1)
                .filter(keyword -> title.contains(keyword))
                .max(Comparator.comparingInt(String::length))
                .orElse("");

        if (bestFoodKeyword.isBlank()) {
            return false;
        }

        if (title.equals(bestFoodKeyword)) {
            return true;
        }

        int extraLength = title.length() - bestFoodKeyword.length();
        boolean genericDishKeyword = RECIPE_CATEGORY_KEYWORDS.stream().anyMatch(bestFoodKeyword::endsWith);
        if (genericDishKeyword || extraLength > 2) {
            return false;
        }

        return true;
    }

    private int scoreRecipe(Recipe recipe, List<String> keywords) {
        String title = nullToBlank(recipe.getTitle()).toLowerCase();
        String description = nullToBlank(recipe.getDescription()).toLowerCase();
        String ingredients = joinRecipeList(recipe.getIngredients()).toLowerCase();
        int score = 0;

        for (String keyword : keywords) {
            String normalized = keyword.toLowerCase();
            if (title.equals(normalized)) {
                score += 100;
            }
            if (title.contains(normalized)) {
                score += RECIPE_CATEGORY_KEYWORDS.contains(normalized) ? 25 : 8;
            }
            if (description.contains(normalized)) {
                score += 3;
            }
            if (ingredients.contains(normalized)) {
                score += 2;
            }
        }
        return score;
    }

    private List<Recipe> findTrustedRecipesSafely(String message) {
        try {
            return findTrustedRecipes(message);
        } catch (Exception e) {
            log.warn("레시피 DB 검색 중 오류 발생: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildTrustedRecipeReply(Recipe recipe, List<String> safetyNotes) {
        StringBuilder reply = new StringBuilder();
        reply.append(nullToBlank(recipe.getTitle())).append(" 레시피입니다.\n\n");

        String description = sanitizeRecipeDescription(recipe);
        if (!description.isBlank()) {
            reply.append(description).append("\n\n");
        }

        List<String> summary = new ArrayList<>();
        if (recipe.getCookingTime() != null) {
            summary.add("조리 시간: " + recipe.getCookingTime() + "분");
        }
        if (recipe.getCalories() != null) {
            summary.add("열량: " + recipe.getCalories() + "kcal");
        }
        if (recipe.getDifficulty() != null) {
            summary.add("난이도: " + recipe.getDifficulty());
        }
        if (!summary.isEmpty()) {
            reply.append(String.join(" / ", summary)).append("\n\n");
        }

        if (safetyNotes != null && !safetyNotes.isEmpty()) {
            reply.append("[건강 주의]\n");
            safetyNotes.forEach(note -> reply.append("- ").append(note).append("\n"));
            reply.append("\n");
        }

        List<String> ingredients = cleanRecipeValues(recipe.getIngredients());
        if (!ingredients.isEmpty()) {
            reply.append("[재료]\n");
            ingredients.forEach(ingredient -> reply.append("- ").append(ingredient).append("\n"));
            reply.append("\n");
        }

        List<String> steps = cleanRecipeValues(recipe.getSteps());
        if (!steps.isEmpty()) {
            reply.append("[조리 순서]\n");
            for (int i = 0; i < steps.size(); i++) {
                reply.append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
            reply.append("\n");
        }

        reply.append("위 내용은 Salus 내부 레시피 DB에 저장된 자료를 기준으로 안내한 것입니다.");
        return reply.toString().trim();
    }

    private String buildGeneratedRecipeReply(Recipe recipe) {
        StringBuilder reply = new StringBuilder();
        reply.append(nullToBlank(recipe.getTitle())).append(" 레시피입니다.\n\n");

        String description = sanitizeRecipeDescription(recipe);
        if (!description.isBlank()) {
            reply.append(description).append("\n\n");
        }

        List<String> summary = new ArrayList<>();
        if (recipe.getCookingTime() != null) {
            summary.add("조리 시간: " + recipe.getCookingTime() + "분");
        }
        if (recipe.getCalories() != null) {
            summary.add("열량: " + recipe.getCalories() + "kcal");
        }
        if (recipe.getDifficulty() != null) {
            summary.add("난이도: " + recipe.getDifficulty());
        }
        if (!summary.isEmpty()) {
            reply.append(String.join(" / ", summary)).append("\n\n");
        }

        List<String> ingredients = cleanRecipeValues(recipe.getIngredients());
        if (!ingredients.isEmpty()) {
            reply.append("[재료]\n");
            ingredients.forEach(ingredient -> reply.append("- ").append(ingredient).append("\n"));
            reply.append("\n");
        }

        List<String> steps = cleanRecipeValues(recipe.getSteps());
        if (!steps.isEmpty()) {
            reply.append("[조리 순서]\n");
            for (int i = 0; i < steps.size(); i++) {
                reply.append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
        }

        return reply.toString().trim();
    }

    private ChatDto.RecipeCard buildRecipeCard(Recipe recipe, List<String> safetyNotes) {
        return new ChatDto.RecipeCard(
                recipe.getId(),
                recipe.getTitle(),
                sanitizeRecipeDescription(recipe),
                cleanRecipeValues(recipe.getIngredients()),
                cleanRecipeValues(recipe.getSteps()),
                recipe.getCalories(),
                recipe.getDifficulty(),
                recipe.getCookingTime(),
                recipe.getImageUrl(),
                safetyNotes == null ? List.of() : safetyNotes);
    }

    private List<String> buildRecipeSafetyNotes(Optional<Long> authenticatedUserId, Recipe recipe) {
        if (authenticatedUserId.isEmpty()) {
            return List.of();
        }

        List<String> notes = new ArrayList<>();
        String ingredientText = String.join(" ", cleanRecipeValues(recipe.getIngredients())).toLowerCase();
        Long userId = authenticatedUserId.get();

        healthProfileRepository.findByUserId(userId).ifPresent(profile ->
                appendHealthProfileSafetyNotes(notes, profile, ingredientText));

        healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId).ifPresent(checkup ->
                appendCheckupSafetyNotes(notes, checkup, ingredientText));

        return notes.stream().distinct().toList();
    }

    private void appendHealthProfileSafetyNotes(List<String> notes, HealthProfile profile, String ingredientText) {
        if (profile.getAllergies() != null) {
            for (String allergy : profile.getAllergies()) {
                if (allergy != null && !allergy.isBlank() && ingredientText.contains(allergy.trim().toLowerCase())) {
                    notes.add("등록된 알레르기 재료인 '" + allergy.trim() + "'가 포함되어 있습니다. 이 재료는 반드시 제외하거나 안전한 대체 재료를 사용하세요.");
                }
            }
        }

        if (containsAny(profile.getChronicConditions(), "고혈압", "혈압")) {
            appendHighBloodPressureNote(notes, ingredientText);
        }
        if (containsAny(profile.getChronicConditions(), "당뇨", "혈당")) {
            appendDiabetesNote(notes, ingredientText);
        }
        if (containsAny(profile.getChronicConditions(), "고지혈", "콜레스테롤", "지질", "중성지방")) {
            appendLipidNote(notes, ingredientText);
        }
        if (containsAny(profile.getDietaryRestrictions(), "채식", "비건", "육류 제외")) {
            if (containsIngredientAny(ingredientText, "돼지고기", "소고기", "쇠고기", "닭고기", "생선", "오징어", "새우")) {
                notes.add("식단 제한에 채식 또는 육류 제한이 있습니다. 고기와 해산물 재료는 두부, 버섯, 콩류 등으로 바꾸는 것이 좋습니다.");
            }
        }
    }

    private void appendCheckupSafetyNotes(List<String> notes, HealthCheckup checkup, String ingredientText) {
        if ((checkup.getSystolicBp() != null && checkup.getSystolicBp() >= 130)
                || (checkup.getDiastolicBp() != null && checkup.getDiastolicBp() >= 80)) {
            appendHighBloodPressureNote(notes, ingredientText);
        }
        if (checkup.getFastingGlucose() != null && checkup.getFastingGlucose() >= 100) {
            appendDiabetesNote(notes, ingredientText);
        }
        if ((checkup.getLdl() != null && checkup.getLdl() >= 130)
                || (checkup.getTriglyceride() != null && checkup.getTriglyceride() >= 150)) {
            appendLipidNote(notes, ingredientText);
        }
    }

    private void appendHighBloodPressureNote(List<String> notes, String ingredientText) {
        if (containsIngredientAny(ingredientText, "김치", "된장", "고추장", "간장", "국간장", "소금", "젓갈")) {
            notes.add("혈압 관리가 필요하면 김치, 된장, 고추장, 간장, 소금 양을 줄이고 국물은 적게 드세요.");
        }
    }

    private void appendDiabetesNote(List<String> notes, String ingredientText) {
        if (containsIngredientAny(ingredientText, "설탕", "올리고당", "꿀", "떡", "밥", "면", "감자", "고구마")) {
            notes.add("혈당 관리가 필요하면 당류와 탄수화물 재료의 양을 줄이고 단백질과 채소를 함께 드세요.");
        }
    }

    private void appendLipidNote(List<String> notes, String ingredientText) {
        if (containsIngredientAny(ingredientText, "돼지고기", "삼겹살", "베이컨", "버터", "크림", "튀김")) {
            notes.add("지질 관리가 필요하면 기름진 부위와 튀김 조리를 줄이고 살코기나 두부로 대체하는 것이 좋습니다.");
        }
    }

    private boolean containsAny(List<String> values, String... keywords) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        String joined = String.join(" ", values).toLowerCase();
        for (String keyword : keywords) {
            if (joined.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIngredientAny(String ingredientText, String... keywords) {
        for (String keyword : keywords) {
            if (ingredientText.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<String> cleanRecipeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private List<String> extractExcludedIngredients(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        String normalized = message.replaceAll("[,，.?!]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        java.util.Set<String> ingredients = new java.util.LinkedHashSet<>();
        List<java.util.regex.Pattern> patterns = List.of(
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:이|가)?\\s*안\\s*들어간"),
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:이|가)?\\s*없는"),
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:을|를)?\\s*빼고"),
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:을|를)?\\s*제외"),
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:은|는)?\\s*말고")
        );

        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                String ingredient = normalizeExcludedIngredientName(matcher.group(1));
                if (isLikelyIngredientName(ingredient)) {
                    ingredients.add(ingredient);
                }
            }
        }

        if (ingredients.isEmpty()) {
            String compact = message.replaceAll("\\s+", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(.+?)(?:이|가)?안들어간")
                    .matcher(compact);
            if (matcher.find()) {
                String ingredient = normalizeExcludedIngredientName(matcher.group(1)
                        .replaceAll(".*(알려줘|알려주세요|레시피|다른|또다른|버전)", ""));
                if (isLikelyIngredientName(ingredient)) {
                    ingredients.add(ingredient);
                }
            }
        }

        return new ArrayList<>(ingredients);
    }

    private String normalizeExcludedIngredientName(String ingredient) {
        if (ingredient == null) {
            return "";
        }
        return ingredient.replaceAll("[^가-힣a-zA-Z0-9]", "")
                .replaceAll("(으로|로|을|를|이|가|은|는|도|만)$", "")
                .trim();
    }

    private boolean isLikelyIngredientName(String ingredient) {
        if (ingredient == null || ingredient.isBlank()) {
            return false;
        }
        String compact = ingredient.replaceAll("\\s+", "");
        if (compact.length() > 20) {
            return false;
        }
        return !(compact.contains("레시피")
                || compact.contains("다른")
                || compact.contains("또")
                || compact.contains("있으면")
                || compact.contains("알려"));
    }

    private List<String> removeExcludedIngredients(List<String> ingredients, List<String> excludedIngredients) {
        return cleanRecipeValues(ingredients).stream()
                .filter(ingredient -> !containsExcludedIngredient(ingredient, excludedIngredients))
                .toList();
    }

    private String removeExistingExclusionPrefix(String title, List<String> excludedIngredients) {
        String cleaned = nullToBlank(title).trim();
        for (String ingredient : excludedIngredients) {
            String quoted = java.util.regex.Pattern.quote(ingredient);
            String previous;
            do {
                previous = cleaned;
                cleaned = cleaned
                        .replaceFirst("^" + quoted + "\\s*없는\\s*", "")
                        .replaceFirst("^" + quoted + "\\s*없이\\s*", "")
                        .trim();
            } while (!previous.equals(cleaned));
        }
        return cleaned.isBlank() ? "AI 추천 식단" : cleaned;
    }

    private List<String> removeExcludedSteps(List<String> steps, List<String> excludedIngredients) {
        List<String> cleanedSteps = cleanRecipeValues(steps).stream()
                .map(this::removeDetailedStepAnnotations)
                .filter(step -> !isNonCookingStepNote(step))
                .map(step -> removeExcludedIngredientMentions(step, excludedIngredients))
                .filter(step -> !containsExcludedIngredient(step, excludedIngredients))
                .filter(step -> !step.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));

        if (cleanedSteps.isEmpty()) {
            cleanedSteps.add("재료를 손질한 뒤 양념과 함께 볶아 완성합니다.");
            return cleanedSteps;
        }
        String lastStep = cleanedSteps.get(cleanedSteps.size() - 1);
        if (!lastStep.contains("완성")) {
            cleanedSteps.add("전체 재료가 익고 양념이 고르게 배면 불을 끄고 완성합니다.");
        }
        return cleanedSteps;
    }

    private String removeDetailedStepAnnotations(String step) {
        if (step == null) {
            return "";
        }
        return step.replaceAll("[*_`]", "")
                .replaceAll("\\s*/\\s*불\\s*세기:.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isNonCookingStepNote(String step) {
        if (step == null || step.isBlank()) {
            return true;
        }
        String compact = step.replaceAll("[\\s*_`]", "");
        return compact.contains("실수/왜문제인지/해결법")
                || compact.contains("초보자실수")
                || compact.startsWith("-")
                || compact.startsWith("실수")
                || compact.startsWith("주의");
    }

    private String removeExcludedIngredientMentions(String step, List<String> excludedIngredients) {
        String cleaned = nullToBlank(step);
        for (String ingredient : excludedIngredients) {
            String quoted = java.util.regex.Pattern.quote(ingredient);
            cleaned = cleaned
                    .replaceAll("\\s*(,|와|과|및)\\s*" + quoted + "(을|를|이|가|은|는)?", "")
                    .replaceAll(quoted + "(을|를|이|가|은|는)?\\s*(,|와|과|및)\\s*", "")
                    .replaceAll(quoted + "(을|를|이|가|은|는)?", "");
        }
        return cleaned
                .replaceAll("\\s+,", ",")
                .replaceAll(",\\s*,", ",")
                .replaceAll("\\(\\s*\\)", "")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+:", ":")
                .trim();
    }

    private boolean containsExcludedIngredient(String text, List<String> excludedIngredients) {
        String normalizedText = normalizeIngredientForMatching(text);
        for (String ingredient : excludedIngredients) {
            if (!ingredient.isBlank() && normalizedText.contains(normalizeIngredientForMatching(ingredient))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeIngredientForMatching(String text) {
        return nullToBlank(text)
                .replaceAll("[^가-힣a-zA-Z0-9]", "")
                .toLowerCase();
    }

    private List<String> extractRecipeKeywords(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }

        String compact = message.replaceAll("[^가-힣a-zA-Z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<String> keywords = new ArrayList<>();
        addKeywordWithVariants(keywords, removeRecipeStopwords(compact));

        for (String token : compact.split("\\s+")) {
            String cleaned = removeRecipeStopwords(token);
            addKeywordWithVariants(keywords, cleaned);
            addKeywordWithVariants(keywords, stripCommonRecipeSuffix(cleaned));
        }

        return keywords;
    }

    private String removeRecipeStopwords(String text) {
        String cleaned = text;
        for (String stopword : RECIPE_QUERY_STOPWORDS) {
            cleaned = cleaned.replace(stopword, " ");
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private String stripCommonRecipeSuffix(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.replaceAll("(으로|로|을|를|이|가|은|는|에|의|도|만)$", "").trim();
    }

    private void addKeywordWithVariants(List<String> keywords, String keyword) {
        addKeyword(keywords, keyword);
        addKeyword(keywords, normalizeCommonRecipeTypo(keyword));
    }

    private String normalizeCommonRecipeTypo(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword
                .replace("찌게", "찌개")
                .replace("된장찌게", "된장찌개")
                .replace("김치찌게", "김치찌개")
                .replace("순두부찌게", "순두부찌개")
                .replace("부대찌게", "부대찌개");
    }

    private void addKeyword(List<String> keywords, String keyword) {
        if (keyword == null) {
            return;
        }
        String cleaned = keyword.replaceAll("\\s+", "").trim();
        if (cleaned.length() < 2 || RECIPE_QUERY_STOPWORDS.contains(cleaned) || keywords.contains(cleaned)) {
            return;
        }
        keywords.add(cleaned);
    }

    private void appendRecipeField(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(label).append(": ").append(truncateRecipeField(value)).append("\n");
    }

    private String joinRecipeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private String joinNumberedRecipeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> cleaned = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cleaned.size(); i++) {
            if (i > 0) {
                builder.append(" ");
            }
            builder.append(i + 1).append(". ").append(cleaned.get(i));
        }
        return builder.toString();
    }

    private String truncateRecipeField(String value) {
        if (value.length() <= MAX_RECIPE_FIELD_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RECIPE_FIELD_LENGTH) + "...";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private boolean looksLikeRecipeResponse(String reply) {
        if (reply == null) {
            return false;
        }
        return reply.contains("kcal") || reply.contains("레시피") || reply.contains("재료");
    }

    private boolean isLlmUnavailableReply(String reply) {
        if (reply == null) {
            return false;
        }
        return reply.contains("로컬 AI 엔진")
                || reply.contains("AI 엔진")
                || reply.contains("점검 중")
                || reply.contains("답변을 생성하지 못했습니다");
    }

    private LocalDate resolveTargetDate(String text) {
        if (text != null && text.contains("내일")) {
            return LocalDate.now().plusDays(1);
        }
        return LocalDate.now();
    }

    private MealSlot resolveMealSlot(String text) {
        if (text != null && text.contains("아침")) {
            return new MealSlot("breakfast", "아침");
        }
        if (text != null && text.contains("저녁")) {
            return new MealSlot("dinner", "저녁");
        }
        return new MealSlot("lunch", "점심");
    }

    private String extractRecipeTitle(String text) {
        if (text == null || text.isBlank()) {
            return "AI 추천 식단";
        }
        String firstLine = text.lines()
                .map(line -> line.replaceAll("[#*`]", "").trim())
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("AI 추천 식단");
        firstLine = firstLine.replace("요리 이름:", "").replace("메뉴:", "").trim();
        return firstLine.length() > 40 ? firstLine.substring(0, 40) + "..." : firstLine;
    }

    private String extractFollowUpRecipeTitle(String text) {
        if (text == null || text.isBlank()) {
            return "AI 추천 식단";
        }
        String firstLine = text.lines()
                .map(line -> line.replaceAll("[#*`]", "").trim())
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("AI 추천 식단");

        firstLine = firstLine
                .replace("요리 이름:", "")
                .replace("메뉴:", "")
                .replaceFirst("\\s*레시피입니다\\.?\\s*$", "")
                .replaceFirst("\\s*레시피\\s*입니다\\.?\\s*$", "")
                .replaceFirst("\\s*조리\\s*시간:.*$", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (firstLine.isBlank()) {
            return "AI 추천 식단";
        }

        String[] tokens = firstLine.split(" ");
        int maxPrefix = Math.min(tokens.length, 5);
        for (int size = maxPrefix; size >= 1; size--) {
            String prefix = joinTokens(tokens, size);
            String lastToken = tokens[size - 1];
            if (looksLikeDishTitleToken(lastToken)) {
                return prefix;
            }
        }

        for (int size = 1; size <= maxPrefix; size++) {
            String prefix = joinTokens(tokens, size);
            String rest = firstLine.length() > prefix.length()
                    ? firstLine.substring(prefix.length()).trim()
                    : "";
            if (!rest.isBlank() && rest.contains(prefix)) {
                return prefix;
            }
        }

        if (tokens.length >= 2 && isShortForeignTitleToken(tokens[0]) && isShortForeignTitleToken(tokens[1])) {
            return tokens[0] + " " + tokens[1];
        }
        return tokens[0];
    }

    private String joinTokens(String[] tokens, int size) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < size && i < tokens.length; i++) {
            values.add(tokens[i]);
        }
        return String.join(" ", values).trim();
    }

    private boolean looksLikeDishTitleToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = token.replaceAll("[^가-힣a-zA-Z0-9]", "");
        if (normalized.isBlank()) {
            return false;
        }
        for (String keyword : RECIPE_CATEGORY_KEYWORDS) {
            if (normalized.endsWith(keyword)) {
                return true;
            }
        }
        return normalized.endsWith("밥")
                || normalized.endsWith("면")
                || normalized.endsWith("죽")
                || normalized.endsWith("튀김")
                || normalized.endsWith("전")
                || normalized.endsWith("김치")
                || normalized.endsWith("스테이크")
                || normalized.endsWith("웰링턴")
                || normalized.endsWith("카레")
                || normalized.endsWith("커리")
                || normalized.endsWith("피자")
                || normalized.endsWith("라면")
                || normalized.endsWith("국수")
                || normalized.endsWith("수프")
                || normalized.endsWith("스프")
                || normalized.endsWith("샌드위치")
                || normalized.endsWith("버거");
    }

    private boolean isShortForeignTitleToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = token.replaceAll("[^가-힣a-zA-Z0-9]", "");
        return normalized.length() >= 2 && normalized.length() <= 12;
    }

    private Integer extractCalories(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\s*(kcal|칼로리)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private String quoteJson(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String formatBloodPressure(HealthCheckup checkup) {
        if (checkup.getSystolicBp() == null && checkup.getDiastolicBp() == null) {
            return null;
        }
        return (checkup.getSystolicBp() != null ? checkup.getSystolicBp() : "?") + "/" + (checkup.getDiastolicBp() != null ? checkup.getDiastolicBp() : "?");
    }

    private String formatLiverNumbers(HealthCheckup checkup) {
        if (checkup.getAst() == null && checkup.getAlt() == null) {
            return null;
        }
        return (checkup.getAst() != null ? checkup.getAst() : "?") + "/" + (checkup.getAlt() != null ? checkup.getAlt() : "?");
    }

    private record MealSlot(String fieldName, String koreanName) {
    }

    private record RAGData(SearchEngine.SearchStatus status, String systemContextSnippet, String rawSearchContext, String source) {
    }

    private Recipe parseRecipeFromReply(String title, String reply) {
        try {
            if (reply == null || reply.isBlank()) {
                return null;
            }
            if (!reply.contains("[재료]") || !reply.contains("[조리 순서]")) {
                return null;
            }

            Recipe recipe = new Recipe();
            recipe.setTitle(title);

            String[] lines = reply.split("\n");
            StringBuilder descriptionBuilder = new StringBuilder();
            List<String> ingredients = new ArrayList<>();
            List<String> steps = new ArrayList<>();
            Integer calories = null;
            Integer difficulty = 2; // 보통 기본값
            Integer cookingTime = null;

            boolean inIngredients = false;
            boolean inSteps = false;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // 조리 시간, 열량, 난이도 추출
                if (trimmed.startsWith("조리 시간:") || trimmed.contains("열량:") || trimmed.contains("난이도:")) {
                    java.util.regex.Matcher timeMatcher = java.util.regex.Pattern.compile("조리\\s*시간:\\s*(\\d+)분").matcher(trimmed);
                    if (timeMatcher.find()) {
                        cookingTime = Integer.parseInt(timeMatcher.group(1));
                    }
                    java.util.regex.Matcher calMatcher = java.util.regex.Pattern.compile("열량:\\s*(\\d+)kcal").matcher(trimmed);
                    if (calMatcher.find()) {
                        calories = Integer.parseInt(calMatcher.group(1));
                    }
                    java.util.regex.Matcher diffMatcher = java.util.regex.Pattern.compile("난이도:\\s*(\\S+)").matcher(trimmed);
                    if (diffMatcher.find()) {
                        String diffStr = diffMatcher.group(1);
                        if (diffStr.contains("쉬움") || diffStr.contains("1")) {
                            difficulty = 1;
                        } else if (diffStr.contains("어려움") || diffStr.contains("어려") || diffStr.contains("3")) {
                            difficulty = 3;
                        } else {
                            difficulty = 2;
                        }
                    }
                    continue;
                }

                // 섹션 구분자 체크
                if (trimmed.equals("[재료]")) {
                    inIngredients = true;
                    inSteps = false;
                    continue;
                }
                if (trimmed.equals("[조리 순서]")) {
                    inIngredients = false;
                    inSteps = true;
                    continue;
                }
                if (trimmed.startsWith("[건강 주의]") || trimmed.startsWith("위 내용은 Salus") || trimmed.contains("Salus AI 가이드")) {
                    inIngredients = false;
                    inSteps = false;
                    continue;
                }
                if (inSteps && isNonCookingStepNote(trimmed)) {
                    inSteps = false;
                    continue;
                }

                // 데이터 수집
                if (inIngredients) {
                    if (trimmed.startsWith("-")) {
                        ingredients.add(trimmed.substring(1).trim());
                    } else if (!trimmed.startsWith("[")) {
                        ingredients.add(trimmed);
                    }
                }
                if (inSteps) {
                    if (java.util.regex.Pattern.compile("^\\d+\\s*\\.\\s*").matcher(trimmed).find()) {
                        steps.add(trimmed.replaceFirst("^\\d+\\s*\\.\\s*", ""));
                    } else if (!trimmed.startsWith("[")) {
                        steps.add(trimmed);
                    }
                }

                // 소개글 수집 (섹션이 활성화되지 않은 상태의 텍스트)
                if (!inIngredients && !inSteps && !trimmed.startsWith("조리 시간:")
                        && !trimmed.endsWith("레시피입니다.") && !trimmed.endsWith("레시피입니다")
                        && !trimmed.equals(title)
                        && !trimmed.startsWith("[건강") && !trimmed.startsWith("-")) {
                    descriptionBuilder.append(trimmed).append(" ");
                }
            }

            recipe.setDescription(descriptionBuilder.toString().trim());
            recipe.setIngredients(ingredients);
            recipe.setSteps(steps);
            recipe.setCalories(calories);
            recipe.setDifficulty(difficulty);
            recipe.setCookingTime(cookingTime);

            return recipe;
        } catch (Exception e) {
            log.error("[RAG Pipeline] Failed to parse recipe from reply", e);
            return null;
        }
    }

    private String formatValidationDetailsJson(RecipeValidator.ValidationResult result) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"valid\":").append(result.valid()).append(",");
        json.append("\"formatValid\":").append(result.formatValid()).append(",");
        json.append("\"hasForbidden\":").append(result.hasForbidden()).append(",");
        json.append("\"confidenceScore\":").append(result.confidenceScore()).append(",");
        json.append("\"matchedKeywords\":").append(result.matchedKeywords()).append(",");
        json.append("\"totalKeywords\":").append(result.totalKeywords()).append(",");
        json.append("\"dataQualityLow\":").append(result.dataQualityLow()).append(",");
        json.append("\"dataQualityWarnings\":[");
        for (int i = 0; i < result.dataQualityWarnings().size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(result.dataQualityWarnings().get(i))).append("\"");
        }
        json.append("],");
        json.append("\"reasons\":[");
        for (int i = 0; i < result.reasons().size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(result.reasons().get(i))).append("\"");
        }
        json.append("]");
        json.append("}");
        return json.toString();
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void saveGeneratedRecipeAudit(String title, Recipe parsedRecipe, String searchContext, String source, String aiResponse,
                                          RecipeValidator.ValidationResult valResult) {
        GeneratedRecipe genRecipe = new GeneratedRecipe();
        genRecipe.setTitle(title);
        genRecipe.setDescription(parsedRecipe.getDescription());
        genRecipe.setIngredients(parsedRecipe.getIngredients());
        genRecipe.setSteps(parsedRecipe.getSteps());
        genRecipe.setCalories(parsedRecipe.getCalories());
        genRecipe.setDifficulty(parsedRecipe.getDifficulty());
        genRecipe.setCookingTime(parsedRecipe.getCookingTime());
        genRecipe.setSearchQuery(title);
        genRecipe.setSearchContext(searchContext);
        genRecipe.setAiResponse(aiResponse);
        genRecipe.setSource(source == null || source.isBlank() ? "unknown" : source);
        genRecipe.setConfidenceScore(valResult.confidenceScore());
        genRecipe.setHasForbiddenIngredients(valResult.hasForbidden());
        genRecipe.setValid(valResult.valid());
        genRecipe.setValidationReason(String.join(", ", valResult.reasons()));
        genRecipe.setValidationDetails(formatValidationDetailsJson(valResult));
        genRecipe.setValidatorVersion("v1.0");
        generatedRecipeRepository.save(genRecipe);
    }

    private String buildRecipeValidationFailureReply(String title, SearchEngine.SearchStatus searchStatus) {
        if (searchStatus == SearchEngine.SearchStatus.FAILED) {
            return "지금은 외부 레시피 자료 확인이 원활하지 않아 '" + title + "' 레시피를 신뢰 기준에 맞게 검증하지 못했습니다. 잠시 후 다시 요청해 주세요.";
        }
        return "'" + title + "' 레시피를 생성했지만 신뢰 검증을 통과하지 못해 제공하지 않았습니다. 다른 음식명을 더 구체적으로 입력해 주세요.";
    }

    private String buildNonRecipeIntentReply(ChatIntentClassifier.ChatIntent intent, String message) {
        if (intent == ChatIntentClassifier.ChatIntent.MENU_RECOMMENDATION) {
            return "좋아요. 메뉴 추천으로만 짧게 도와드릴게요. 상세 레시피가 필요하면 음식명과 함께 '레시피'나 '만드는 법'이라고 말씀해 주세요.";
        }
        if (intent == ChatIntentClassifier.ChatIntent.COOKING_QUESTION) {
            return "요리 관련 질문으로 이해했어요. 상세 레시피가 필요하면 '레시피'나 '만드는 법'을 붙여 요청해 주세요.";
        }
        if (message != null && message.replaceAll("\\s+", "").contains("알려줘")) {
            return "어떤 점이 궁금한지 조금만 더 말해 주세요. 상세 레시피가 필요하면 음식명과 함께 '레시피 알려줘'처럼 요청해 주세요.";
        }
        return "자연스럽게 도와드릴게요. 상세 레시피가 필요하면 음식명과 함께 '레시피'나 '만드는 법'이라고 말씀해 주세요.";
    }

    private void saveToRecipeDbSafely(Recipe parsedRecipe) {
        try {
            // UNIQUE 제약조건으로 인한 Duplicate Key Exception 대응 방어 코드
            List<Recipe> existing = recipeRepository.searchByKeyword(parsedRecipe.getTitle(), 1);
            boolean exists = existing.stream().anyMatch(r -> r.getTitle().equalsIgnoreCase(parsedRecipe.getTitle()));

            if (!exists) {
                parsedRecipe.setAverageRating(0.0);
                parsedRecipe.setCreatedAt(java.time.LocalDateTime.now());
                recipeRepository.save(parsedRecipe);
                log.info("[Self-growing KB] Successfully promoted new recipe to DB: {}", parsedRecipe.getTitle());
            } else {
                log.info("[Self-growing KB] Recipe already exists in DB (promotion skipped): {}", parsedRecipe.getTitle());
            }
        } catch (Exception e) {
            log.error("[Self-growing KB] Database insert failed during recipe promotion", e);
        }
    }
}
