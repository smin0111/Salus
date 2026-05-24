package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.FridgeItem;
import com.mychefai.healthytable.domain.ChatMessage;
import com.mychefai.healthytable.domain.ChatSession;
import com.mychefai.healthytable.domain.HealthCheckup;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.HealthCheckupAnalysisDTO;
import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.dto.MealLogDTO;
import com.mychefai.healthytable.dto.RecipeWorkSessionDTO;
import com.mychefai.healthytable.repository.ChatMessageRepository;
import com.mychefai.healthytable.repository.ChatSessionRepository;
import com.mychefai.healthytable.repository.FridgeItemRepository;
import com.mychefai.healthytable.repository.HealthCheckupRepository;
import com.mychefai.healthytable.repository.HealthProfileRepository;
import com.mychefai.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        String enhancedMessage = request.getMessage();
        StringBuilder systemContext = new StringBuilder();
        ChatSession chatSession = authenticatedUserId
                .map(userId -> resolveSession(userId, request))
                .orElse(null);

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

                // 4. 냉장고 재료
                List<FridgeItem> fridgeItems = fridgeItemRepository.findByUserIdOrderByExpiryDate(userIdLong);
                if (request.isUseFridge()) {
                    systemContext.append("\n=== 냉장고 모드 ON ===\n");
                    if (!fridgeItems.isEmpty()) {
                        systemContext.append("냉장고 재료 목록:\n");
                        String fridgeInfo = fridgeItems.stream()
                                .map(item -> String.format("- %s (%s, 유통기한: %s)",
                                        item.getName(), item.getQuantity(), item.getExpiryDate()))
                                .collect(Collectors.joining("\n"));
                        systemContext.append(fridgeInfo).append("\n");
                        systemContext.append("\n지시사항: 해당 재료를 적극 활용하며 유통기한 임박 재료 우선 추천.\n");
                    } else {
                        systemContext.append("등록된 냉장고 재료가 없어 일반 레시피로 추천합니다.\n");
                    }
                    systemContext.append("========================\n");
                } else {
                    systemContext.append("\n=== 냉장고 모드 OFF ===\n");
                    systemContext.append("냉장고 내 재료를 배제하고 어울리는 다양한 재료로 추천해 주세요.\n");
                    systemContext.append("======================\n");
                }

            } catch (Exception e) {
                log.error("개인화 컨텍스트 구성 중 오류 발생", e);
            }
        }

        final String finalMessage = systemContext.length() > 0 ? request.getMessage() + systemContext : request.getMessage();
        List<ChatDto.Message> history = resolveHistoryForAi(chatSession, request);
        Long userIdForWork = authenticatedUserId.orElse(null);
        Long sessionIdForWork = chatSession != null ? chatSession.getId() : null;

        // 다형성 LlmService 호출 적용
        return llmService.getChatResponse(finalMessage, history)
                .map(reply -> {
                    if (chatSession != null) {
                        saveChatMessage(chatSession, "model", reply);
                    }
                    boolean active = false;
                    if (userIdForWork != null && sessionIdForWork != null && looksLikeRecipeResponse(reply)) {
                        recipeWorkSessionService.saveRecommendation(userIdForWork, sessionIdForWork, reply);
                        active = true;
                    }
                    return new ChatDto.Response(sessionIdForWork, reply, active, false);
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

    private boolean isSaveToCalendarRequest(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("저장")
                && (message.contains("캘린더") || message.contains("식단") || message.contains("기록"));
    }

    private boolean looksLikeRecipeResponse(String reply) {
        if (reply == null) {
            return false;
        }
        return reply.contains("kcal") || reply.contains("레시피") || reply.contains("재료");
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
}
