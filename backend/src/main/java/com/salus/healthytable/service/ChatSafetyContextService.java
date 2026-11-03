package com.salus.healthytable.service;

import com.salus.healthytable.domain.HealthCheckup;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.HealthCheckupAnalysisDTO;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import com.salus.healthytable.service.allergen.AllergenAlias;
import com.salus.healthytable.service.allergen.AllergenRegistry;
import com.salus.healthytable.service.allergen.ProfileResolutionShadowObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅/레시피 생성에서 지켜야 할 사용자 건강 조건(SafetyContext)을 모으고, 알레르기 충돌을 판정하는 서비스입니다.
 *
 * 건강 조건은 세 곳에서 모읍니다.
 * 1) 요청에 함께 보낸 건강 정보  2) 메시지/이전 사용자 메시지 속 "나 땅콩 알레르기 있어" 같은 문장
 * 3) 로그인 사용자의 DB 건강 프로필
 * 알레르기 판정은 반드시 {@link AllergenMatcher} 한 곳을 거칩니다(판정 규칙이 경로마다 달라지지 않게).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSafetyContextService {

    private final HealthProfileRepository healthProfileRepository;
    private final HealthCheckupRepository healthCheckupRepository;
    private final HealthCheckupAnalysisService healthCheckupAnalysisService;
    private final AllergenMatcher allergenMatcher;
    private final AllergenRegistry allergenRegistry;
    private final ProfileResolutionShadowObserver profileResolutionShadowObserver;

    /**
     * 사용자 건강 조건을 모아 SafetyContext를 만듭니다.
     *
     * DB 프로필 조회에 실패하면 healthContextAvailable=false로 표시합니다.
     * "알레르기 정보를 못 읽음"을 "알레르기 없음"으로 취급하면 위험하므로,
     * 호출하는 쪽(ChatService)은 이 값이 false면 개인화 레시피 생성을 거부합니다(fail closed).
     */
    @Transactional(readOnly = true)
    public SafetyContext build(Optional<Long> authenticatedUserId, ChatDto.Request request) {
        Set<String> allergies = new LinkedHashSet<>();
        Set<String> chronicConditions = new LinkedHashSet<>();
        Set<String> dietaryRestrictions = new LinkedHashSet<>();
        Set<String> medications = new LinkedHashSet<>();
        Set<String> goals = new LinkedHashSet<>();

        appendRequestHealthProfileValues(
                request, allergies, chronicConditions, dietaryRestrictions, medications, goals);
        // 현재 메시지와 이전 "사용자" 메시지에서 알레르기 언급을 찾아 추가합니다(AI 답변은 제외).
        appendAllergyMentionsFromText(allergies, request == null ? null : request.getMessage());
        // 서버가 고쳐 쓴 message에는 사용자가 말한 알레르기가 빠질 수 있으므로 원문도 읽습니다
        // (2026-10-17: "우유 알레르기 있어. 1번으로 해줘"가 "계란말이 레시피 알려줘"로 바뀌며 우유가 빠짐).
        if (request != null) {
            for (List<String> texts : java.util.Arrays.asList(request.getSentUserTexts(), request.getInterruptedMessages())) {
                if (texts != null) {
                    texts.forEach(text -> appendAllergyMentionsFromText(allergies, text));
                }
            }
            appendAllergyMentionsFromText(allergies, request.getOriginalMessage());
        }
        if (request != null && request.getHistory() != null) {
            request.getHistory().stream()
                    .filter(message -> message != null && "user".equals(message.getRole()))
                    .forEach(message -> appendAllergyMentionsFromText(allergies, message.getContent()));
        }

        boolean healthContextAvailable = true;
        if (authenticatedUserId.isPresent()) {
            try {
                healthProfileRepository.findByUserId(authenticatedUserId.get()).ifPresent(profile -> {
                    Set<String> storedAllergies = new LinkedHashSet<>();
                    appendNormalizedValues(storedAllergies, profile.getAllergies(), true);
                    allergies.addAll(storedAllergies);
                    if (!storedAllergies.isEmpty()) observeAllergyTerms(() ->
                            profileResolutionShadowObserver.observeStoredProfile(List.copyOf(storedAllergies)));
                    appendNormalizedValues(chronicConditions, profile.getChronicConditions(), false);
                    appendNormalizedValues(dietaryRestrictions, profile.getDietaryRestrictions(), false);
                    appendNormalizedValues(medications, profile.getMedications(), false);
                    appendNormalizedValues(goals, profile.getGoals(), false);
                });
            } catch (RuntimeException error) {
                healthContextAvailable = false;
                log.warn("[ChatSafetyContext] category=HEALTH_CONTEXT_LOAD_FAILED, exceptionClass={}",
                        error.getClass().getSimpleName());
            }
        }

        return new SafetyContext(
                List.copyOf(allergies),
                List.copyOf(chronicConditions),
                List.copyOf(dietaryRestrictions),
                List.copyOf(medications),
                List.copyOf(goals),
                healthContextAvailable);
    }

    /**
     * LLM 시스템 프롬프트에 사용자 건강 정보를 "반드시 준수" 섹션으로 덧붙입니다.
     * 프롬프트 지시는 LLM이 어길 수 있으므로, 생성 후 별도의 알레르기 검사도 반드시 수행합니다.
     */
    public void appendPromptContext(StringBuilder systemContext, SafetyContext safetyContext) {
        if (safetyContext == null || !safetyContext.hasAny()) {
            return;
        }
        systemContext.append("\n\n=== 중요: 사용자 건강 정보 (반드시 준수) ===\n");
        if (!safetyContext.allergies().isEmpty()) {
            systemContext.append("알레르기: ").append(String.join(", ", safetyContext.allergies())).append("\n");
            systemContext.append("이 재료들은 절대 사용하지 마세요. 요청한 음식명 자체가 알레르기 재료를 포함하면 레시피를 만들지 말고 안전한 대체 방향만 제안하세요.\n");
        }
        if (!safetyContext.chronicConditions().isEmpty()) {
            systemContext.append("만성질환: ").append(String.join(", ", safetyContext.chronicConditions())).append("\n");
        }
        if (!safetyContext.dietaryRestrictions().isEmpty()) {
            systemContext.append("식단 제한: ").append(String.join(", ", safetyContext.dietaryRestrictions())).append("\n");
        }
        if (!safetyContext.medications().isEmpty()) {
            systemContext.append("복용 약물: ").append(String.join(", ", safetyContext.medications())).append("\n");
            systemContext.append("약물과 상호작용할 수 있는 음식을 피해주세요.\n");
        }
        if (!safetyContext.goals().isEmpty()) {
            systemContext.append("건강 목표: ").append(String.join(", ", safetyContext.goals())).append("\n");
        }
        systemContext.append("=====================================\n");
    }

    /**
     * 최신 건강검진 수치와 분석 결과를 LLM 프롬프트에 덧붙입니다.
     * 조회에 실패하면 false를 반환해 호출자가 실패를 알 수 있게 합니다. 검진 기록이 없는 경우는 true입니다.
     */
    @Transactional(readOnly = true)
    public boolean appendLatestCheckupContext(StringBuilder systemContext, Long userId) {
        try {
            healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId).ifPresent(checkup -> {
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
                    systemContext.append("추천 정책: ")
                            .append(String.join(" / ", analysis.getRecommendationPolicies())).append("\n");
                }
                systemContext.append("주의: 의료 진단처럼 단정하지 말고 식단 참고 정보로만 삼으세요.\n");
                systemContext.append("====================================\n");
            });
            return true;
        } catch (RuntimeException error) {
            log.warn("[ChatSafetyContext] category=HEALTH_CHECKUP_CONTEXT_LOAD_FAILED, exceptionClass={}",
                    error.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 요청한 요리 이름이나 신뢰 레시피(DB 레시피)가 알레르기와 충돌하면 차단 안내 문구를 반환합니다.
     * 요리 이름에서 먼저 확인하고, 충돌이 없으면 각 신뢰 레시피의 재료/조리 단계까지 확인합니다.
     */
    public Optional<String> buildAllergyConflictReply(
            String requestedTitle,
            List<Recipe> trustedRecipes,
            SafetyContext safetyContext,
            String requestMessage) {
        List<String> conflicts = findAllergyConflicts(safetyContext, requestedTitle, null, requestMessage);
        if (conflicts.isEmpty() && trustedRecipes != null) {
            conflicts = trustedRecipes.stream()
                    .flatMap(recipe -> findAllergyConflicts(
                            safetyContext, recipe.getTitle(), recipe, requestMessage).stream())
                    .distinct()
                    .toList();
        }
        return conflicts.isEmpty()
                ? Optional.empty()
                : Optional.of(buildAllergyBlockedReply(requestedTitle, conflicts));
    }

    // 알레르기 때문에 레시피를 제공할 수 없다는 사용자 안내 문구를 만듭니다.
    public String buildAllergyBlockedReply(String requestedTitle, List<String> conflicts) {
        String foodName = nullToBlank(requestedTitle).isBlank() ? "요청하신 메뉴" : requestedTitle.trim();
        String conflictText = conflicts == null || conflicts.isEmpty()
                ? "알레르기 재료"
                : String.join(", ", conflicts);
        return "확인된 알레르기 정보상 '" + conflictText + "' 알레르기가 있어 '" + foodName
                + "' 레시피는 추천할 수 없습니다.\n\n"
                + "알레르기 재료를 제외한 안전한 메뉴로 바꿔야 합니다. 먹을 수 있는 과일이나 재료를 알려주시면 그 범위 안에서 대체 레시피를 만들어드릴게요.";
    }

    /**
     * 제목과 레시피(재료, 조리 단계)에서 사용자 알레르기와 충돌하는 항목을 찾습니다.
     * requestMessage는 판정에 사용하지 않습니다(아래 주석 참고).
     */
    public List<String> findAllergyConflicts(
            SafetyContext safetyContext,
            String title,
            Recipe recipe,
            String requestMessage) {
        if (safetyContext == null || safetyContext.allergies().isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            texts.add(title);
        }
        if (recipe != null) {
            texts.addAll(cleanRecipeValues(recipe.getIngredients()));
            texts.addAll(cleanRecipeValues(recipe.getSteps()));
        }
        // requestMessage의 "빼고", "제외" 같은 표현으로는 판정을 끄지 않는다.
        // 등록된 알레르기는 사용자의 그때그때 진술보다 우선한다.
        return allergenMatcher.findConflicts(safetyContext.allergies(), texts);
    }

    // "땅콩 먹어도 돼?", "새우깡 먹어도 될까요", "우유 먹으면 안 돼?"처럼 먹어도 되는지 묻는 표현
    private static final Pattern EATING_PERMISSION_QUESTION = Pattern.compile(
            "먹어도|먹으면\\s*(?:안|돼|되|괜찮)|먹을\\s*수\\s*있(?:어|나|을까|나요)|먹여도|드셔도|섭취해도");
    // "땅콩 알레르기인데 땅콩버터"처럼 앞에 붙은 사정 설명과 묻는 음식을 가르는 연결 표현.
    // 쉼표는 음식 나열("땅콩, 우유")이라 나누지 않습니다(마지막 음식만 검사하면 땅콩 충돌을 놓침, Codex 검토 P2).
    private static final Pattern CLAUSE_BREAK = Pattern.compile("(?:인데|는데|은데|지만|있고|\\.)\\s+");
    // 표시용으로만 떼는 조사. '이/가'는 음식 이름 끝과 겹쳐("오이", "송이") 떼지 않습니다(Codex 검토 3회차 P2).
    private static final Pattern TRAILING_PARTICLE = Pattern.compile("(?:은|는|을|를|도|만)$");

    /**
     * "X 먹어도 돼?"에 LLM 대신 정해진 규칙으로 답합니다. 일반 대화 LLM은 "대부분 괜찮아요"처럼 안전하다고 답했습니다(2026-11-01).
     *
     * <p>Salus는 먹어도 되는지 판단하지 않으며, 어떤 경우에도 '안전하다', '괜찮다'고 말하지 않습니다.
     * 등록 알레르기와 겹치면 드시지 말라고 하고, 겹치지 않아도 이름만 비교했다는 한계를 밝히며,
     * 알레르기 정보가 없으면 판단할 기준이 없다고 말합니다. 판정은 다른 경로와 같은 AllergenMatcher를 씁니다.
     *
     * @return 먹어도 되는지 묻는 질문이 아니면 빈 값
     */
    public Optional<String> buildEatingPermissionReply(SafetyContext safetyContext, String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher question = EATING_PERMISSION_QUESTION.matcher(message);
        if (!question.find()) {
            return Optional.empty();
        }
        String phrase = askedPhrase(message.substring(0, question.start()));
        String food = TRAILING_PARTICLE.matcher(phrase).replaceAll("").strip();
        // "이거 식혜에 넣어"처럼 음식 이름으로 보기 어려운 긴 구절은 따옴표로 되풀이하지 않습니다(판정은 그 구절 전체로 함).
        boolean nameLike = !food.isBlank() && food.split("\\s+").length <= 2;
        String foodLabel = nameLike ? "‘" + food + "’" : "말씀하신 음식";
        List<String> allergies = safetyContext == null ? List.of() : safetyContext.allergies();

        StringBuilder reply = new StringBuilder();
        List<String> conflicts = allergies.isEmpty() ? List.of()
                // 조사를 떼기 전 구절도 함께 넘겨, 표시용 정리 때문에 원문에서 보이는 충돌을 놓치지 않게 합니다.
                : allergenMatcher.findConflicts(allergies, phrase.isBlank() ? List.of(message) : List.of(phrase, food));
        if (!conflicts.isEmpty()) {
            reply.append(foodLabel).append(nameLike ? topicParticle(food) : "은").append(" 확인된 알레르기(").append(String.join(", ", conflicts))
                    .append(")와 관련된 음식이에요. 드시지 않는 것을 권해요. 이미 드셨거나 증상이 있으면 의료진과 상담해 주세요.");
        } else if (!allergies.isEmpty()) {
            reply.append("Salus는 먹어도 되는지 판단하지 않아요. ").append(foodLabel)
                    .append(nameLike ? " 이름에서" : "에서").append(" 확인된 알레르기(").append(String.join(", ", allergies))
                    .append(")와 겹치는 것을 찾지 못했지만, 이름만 비교한 것이라 안전하다는 뜻은 아니에요. 원재료 표시를 꼭 확인해 주세요.");
        } else {
            reply.append("Salus는 먹어도 되는지 판단하지 않아요. 등록된 알레르기 정보가 없어 확인할 기준도 없어요. ")
                    .append("알레르기가 있다면 건강 정보에 등록해 주시고, 원재료 표시를 확인해 주세요.");
        }
        if (safetyContext != null && (!safetyContext.chronicConditions().isEmpty() || !safetyContext.medications().isEmpty())) {
            reply.append("\n\n등록하신 질환이나 복용 중인 약과의 관계도 Salus가 판단하지 않아요. 의사나 약사와 상담해 주세요.");
        }
        return Optional.of(reply.toString());
    }

    // 받침이 있으면 '은', 없으면 '는'
    private static String topicParticle(String word) {
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "은(는)";
        }
        return (last - 0xAC00) % 28 == 0 ? "는" : "은";
    }

    // "나 땅콩 알레르기인데 땅콩버터는 " → "땅콩버터는"(조사는 표시할 때만 뗌)
    private static String askedPhrase(String beforeQuestion) {
        String[] clauses = CLAUSE_BREAK.split(beforeQuestion.strip());
        return clauses.length == 0 ? "" : clauses[clauses.length - 1].strip();
    }

    /**
     * 일반 대화·메뉴 추천 답변에 등록 알레르겐이 나오면 주의 문구를 덧붙입니다.
     *
     * <p>이 경로의 답변은 구조화 레시피가 아니라서 검증기를 거치지 않습니다. 그렇다고 답변을
     * 막으면 "새우 알레르기 없이 즐길 수 있는 전"처럼 알레르기를 피해 가는 정상 답변까지
     * 막히므로, 차단 대신 확인을 요청하는 문구를 붙입니다. 판정은 레시피 경로와 같은
     * AllergenMatcher를 씁니다. 등록 알레르기가 없으면 답변을 그대로 돌려줍니다.
     */
    public String appendAllergyCautionIfMentioned(SafetyContext safetyContext, String reply) {
        if (safetyContext == null || safetyContext.allergies().isEmpty() || reply == null || reply.isBlank()) {
            return reply;
        }
        List<String> conflicts = allergenMatcher.findConflicts(safetyContext.allergies(), List.of(reply));
        if (conflicts.isEmpty()) {
            return reply;
        }
        return reply + "\n\n※ 등록하신 알레르기(" + String.join(", ", conflicts)
                + ")와 관련된 재료나 메뉴가 답변에 언급되어 있습니다. 드시기 전에 실제 재료와 원재료 표시를 꼭 확인해 주세요.";
    }

    /**
     * 레시피 카드에 표시할 주의 문구를 만듭니다.
     * 건강 프로필(알레르기, 만성질환, 식단 제한)과 최신 검진 수치를 재료 목록과 비교합니다.
     */
    @Transactional(readOnly = true)
    public List<String> buildRecipeSafetyNotes(
            Optional<Long> authenticatedUserId,
            SafetyContext safetyContext,
            Recipe recipe) {
        List<String> notes = new ArrayList<>();
        String ingredientText = String.join(" ", cleanRecipeValues(recipe.getIngredients())).toLowerCase();
        appendHealthProfileSafetyNotes(notes, safetyContext, ingredientText);
        authenticatedUserId.ifPresent(userId ->
                healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId)
                        .ifPresent(checkup -> appendCheckupSafetyNotes(notes, checkup, ingredientText)));
        return notes.stream().distinct().toList();
    }

    // 요청 본문에 포함된 건강 정보를 각 목록에 추가합니다.
    private void appendRequestHealthProfileValues(
            ChatDto.Request request,
            Set<String> allergies,
            Set<String> chronicConditions,
            Set<String> dietaryRestrictions,
            Set<String> medications,
            Set<String> goals) {
        if (request == null || request.getHealthProfile() == null) {
            return;
        }
        ChatDto.HealthProfileContext profile = request.getHealthProfile();
        Set<String> requestAllergies = new LinkedHashSet<>();
        appendNormalizedValues(requestAllergies, profile.getAllergies(), true);
        allergies.addAll(requestAllergies);
        if (!requestAllergies.isEmpty()) observeAllergyTerms(() ->
                profileResolutionShadowObserver.observeRequestProfile(List.copyOf(requestAllergies)));
        appendNormalizedValues(chronicConditions, profile.getChronicConditions(), false);
        appendNormalizedValues(dietaryRestrictions, profile.getDietaryRestrictions(), false);
        appendNormalizedValues(medications, profile.getMedications(), false);
        appendNormalizedValues(goals, profile.getGoals(), false);
    }

    /**
     * 자연어 문장에서 알레르기 재료 이름을 추출합니다.
     * 예) "저는 새우 알레르기가 있어요", "알레르기: 땅콩, 우유", "복숭아는 못 먹어요"
     */
    private void appendAllergyMentionsFromText(Set<String> allergies, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Set<String> extractedAllergies = new LinkedHashSet<>();
        // 쉼표는 남겨 둡니다. 공백으로 바꾸면 "알레르기: 먹태, 땅콩"이 "먹태 땅콩" 한 덩어리가 되어,
        // 사전에 없는 먹태는 충돌 검사에서 빠졌습니다(2026-11-01). 나누기는 appendNormalizedValue가 쉼표로 합니다.
        String normalized = text.replace("알러지", "알레르기")
                .replace('，', ',')
                .replaceAll("[.?!]", " ")
                .replaceAll("\\s*,\\s*", ", ")
                .replaceAll("\\s+", " ")
                .trim();
        List<Pattern> patterns = List.of(
                Pattern.compile("(?:나는|저는|제가|내가|나|저)?\\s*([가-힣a-zA-Z0-9·/+,\\s]{1,30})(?:에|에대한|은|는|이|가|을|를)?\\s*알레르기"),
                Pattern.compile("알레르기\\s*(?:가|는|은)?\\s*[:：]?\\s*([가-힣a-zA-Z0-9·/+,\\s]{1,40})"),
                AVOIDED_FOOD_PATTERN);
        for (int index = 0; index < patterns.size(); index++) {
            Matcher matcher = patterns.get(index).matcher(normalized);
            while (matcher.find()) {
                // "알레르기 뒤 목록" 패턴만 문장의 나머지까지 잡을 수 있어 그 패턴에만 절단을 적용합니다.
                String captured = index == 1 ? beforeClauseContinuation(matcher.group(1))
                        : index == 2 ? avoidedFood(matcher) : matcher.group(1);
                appendNormalizedValue(extractedAllergies, captured, true, true);
            }
        }
        allergies.addAll(extractedAllergies);
        if (!extractedAllergies.isEmpty()) observeAllergyTerms(() ->
                profileResolutionShadowObserver.observeChatMessage(List.copyOf(extractedAllergies)));
    }

    // "X 못 먹어", "X는 안 먹어요"처럼 피하는 음식을 밝힌 문장입니다.
    // 1) 목적어와 부정 사이에 낱말이 하나 끼면("우유를 절대 안 먹어") 그 낱말이 아니라 목적어를 잡습니다.
    //    예전에는 바로 앞 낱말만 잡아 '절대'를 알레르기로 넣고 우유를 놓쳤습니다(2026-11-01).
    // 2) "안 먹을 때/경우/거면", "안 먹으면"은 피한다는 말이 아니라 상황을 묻는 말이라 잡지 않습니다.
    //    "나물을 바로 안 먹을 때 보관법"에서 '바로'가 알레르기로 들어갔습니다(2026-11-01). "못 먹을 때"는 그대로 잡습니다.
    //    "안 먹을래요", "안 먹을 거예요", "안 먹으니까"처럼 피하겠다는 말은 계속 잡습니다(Codex 검토 1회차 P2).
    private static final Pattern AVOIDED_FOOD_PATTERN = Pattern.compile(
            "(?:나는|저는|제가|내가|나|저)?\\s*(?:([가-힣a-zA-Z0-9]{1,20})(?:을|를|은|는)\\s+)?"
                    + "([가-힣a-zA-Z0-9]{1,20}?)(을|를|은|는)?\\s*(?:못\\s*먹|먹으면\\s*안|피해야|안\\s*먹(?!을\\s*(?:때|땐|경우|거면|거라면)|으면|으려면))");

    // 바로 앞 낱말에 조사가 없고 그 앞에 목적어(X을/를/은/는)가 있으면, 앞 낱말은 꾸밈말이고 목적어가 피하는 음식입니다.
    private static String avoidedFood(Matcher matcher) {
        String object = matcher.group(1);
        boolean nearestHasParticle = matcher.group(3) != null;
        return object != null && !nearestHasParticle ? object : matcher.group(2);
    }

    // "알레르기" 뒤 목록 패턴이 문장의 나머지까지 잡지 않게, 낱말 전체가 연결 어미인 곳에서 끊습니다.
    // "새우 알레르기인데 새우깡 먹어도 될까"가 "인데 새우깡 먹어도 될까"라는 알레르기 이름을 만들었습니다(2026-11-01).
    // 서술어(먹~, 돼~ 등)로는 자르지 않습니다. 음식 이름과 겹쳐("돼지고기", "먹태") 진짜 알레르기를 지웠습니다(Codex 검토 P1 두 번).
    // 연결 어미 낱말은 음식 이름이 될 수 없습니다. 서술어가 남는 경우는 예전처럼 넓게 잡히는데, 이는 알레르기를 더 잡는 쪽입니다.
    private static final Pattern CLAUSE_CONTINUATION_WORD = Pattern.compile(
            "^(?:인데|는데|은데|라서|이라서|니까|이니까|있는데|있어서|있고|있으니까|이고|이라)$");

    private static String beforeClauseContinuation(String captured) {
        if (captured == null) {
            return null;
        }
        String[] words = captured.strip().split("\\s+");
        StringBuilder kept = new StringBuilder();
        for (String word : words) {
            if (CLAUSE_CONTINUATION_WORD.matcher(word).matches()) {
                break;
            }
            kept.append(kept.length() == 0 ? "" : " ").append(word);
        }
        return kept.toString();
    }

    // Observer boundary가 예기치 않게 실패해도 DB 조회 실패나 요청 실패로 전파하지 않는다.
    private void observeAllergyTerms(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            log.warn("Profile resolution shadow bridge failed; production safety context retained");
        }
    }

    // 명시적인 profile 값은 정규화 후 non-blank이면 보존한다. 자연어 후보 heuristic을 적용하지 않는다.
    private void appendNormalizedValues(Set<String> target, List<String> values, boolean allergyValue) {
        if (values != null) {
            values.forEach(value -> appendNormalizedValue(target, value, allergyValue, false));
        }
    }

    // "땅콩, 우유/새우"처럼 한 값에 여러 항목이 있으면 구분자로 나눠 각각 추가합니다.
    private void appendNormalizedValue(Set<String> target, String value, boolean allergyValue, boolean extractedAllergy) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : value.split("[,/·，\\n]")) {
            if (!allergyValue) {
                String normalized = normalizeHealthProfileTerm(token);
                if (!normalized.isBlank()) {
                    target.add(normalized);
                }
                continue;
            }
            // 등록된 값은 입력 그대로 씁니다. 끝 글자를 조사로 보고 떼면 "오이"→"오", "포도"→"포"가 되어
            // 한 글자 낱말 규칙 때문에 오이무침·포도 주스와의 충돌을 놓쳤습니다(2026-11-01 발견).
            String term = removeAllergyFillerWords(token);
            if (!extractedAllergy) {
                if (!term.isBlank()) {
                    target.add(term);
                }
                continue;
            }
            // 메시지에서 읽은 값은 조사가 붙어 있을 수 있어 뗀 형태와 떼지 않은 형태를 모두 후보로 둡니다.
            // 한 글자 후보("오")는 아래 검사에서 사전에 정확히 있을 때만 남습니다.
            String withoutParticle = TRAILING_ALLERGY_PARTICLE.matcher(term).replaceAll("").trim();
            for (String candidate : new LinkedHashSet<>(List.of(withoutParticle, term))) {
                if (!candidate.isBlank() && isLikelyExtractedAllergyName(candidate)) {
                    target.add(candidate);
                }
            }
        }
    }

    private static final Pattern TRAILING_ALLERGY_PARTICLE = Pattern.compile("(으로|로|을|를|이|가|은|는|에|의|도|만)$");

    // "알레르기", "있어요", "주의" 같은 부가 표현을 제거합니다. 끝 조사는 떼지 않습니다(호출하는 쪽이 정함).
    private String removeAllergyFillerWords(String value) {
        return normalizeHealthProfileTerm(value)
                .replace("알레르기", " ")
                .replace("알러지", " ")
                .replace("있습니다", " ")
                .replace("있어요", " ")
                .replace("있어", " ")
                .replace("있음", " ")
                .replace("주의", " ")
                .replace("금지", " ")
                .replace("못먹음", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // 특수문자와 "나는/저는" 같은 주어 표현을 제거하고 공백을 정리합니다.
    private String normalizeHealthProfileTerm(String value) {
        return value == null ? "" : value
                .replaceAll("[^가-힣a-zA-Z0-9\\s]", " ")
                .replaceAll("\\b(나는|저는|제가|내가|나|저|혹시)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // 자연어 후보만 검사한다. 한 글자는 exact DIRECT_NAME의 고유 ID가 하나일 때만 허용한다.
    private boolean isLikelyExtractedAllergyName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.replaceAll("\\s+", "");
        if (compact.length() == 1) {
            return allergenRegistry.findExactAliases(compact).stream()
                    .filter(alias -> alias.relation() == AllergenAlias.Relation.DIRECT_NAME)
                    .map(AllergenAlias::allergen)
                    .distinct()
                    .count() == 1;
        }
        return compact.length() >= 2
                && compact.length() <= 20
                && !compact.contains("없음")
                && !compact.contains("없어요")
                && !compact.contains("건강정보")
                && !compact.contains("알려주");
    }



    // 건강 프로필 기준 주의 문구: 알레르기 충돌, 고혈압/당뇨/고지혈 관련 재료, 채식 제한과 맞지 않는 재료
    private void appendHealthProfileSafetyNotes(
            List<String> notes, SafetyContext safetyContext, String ingredientText) {
        for (String allergy : allergenMatcher.findConflicts(
                safetyContext.allergies(), List.of(ingredientText))) {
            notes.add("확인된 알레르기 재료인 '" + allergy
                    + "'가 포함되어 있습니다. 이 재료는 반드시 제외하거나 안전한 대체 재료를 사용하세요.");
        }
        if (containsAny(safetyContext.chronicConditions(), "고혈압", "혈압")) {
            appendHighBloodPressureNote(notes, ingredientText);
        }
        if (containsAny(safetyContext.chronicConditions(), "당뇨", "혈당")) {
            appendDiabetesNote(notes, ingredientText);
        }
        if (containsAny(safetyContext.chronicConditions(), "고지혈", "콜레스테롤", "지질", "중성지방")) {
            appendLipidNote(notes, ingredientText);
        }
        if (containsAny(safetyContext.dietaryRestrictions(), "채식", "비건", "육류 제외")
                && containsIngredientAny(ingredientText,
                "돼지고기", "소고기", "쇠고기", "닭고기", "생선", "오징어", "새우")) {
            notes.add("식단 제한에 채식 또는 육류 제한이 있습니다. 고기와 해산물 재료는 두부, 버섯, 콩류 등으로 바꾸는 것이 좋습니다.");
        }
    }

    // 최신 건강검진 수치가 기준을 넘으면 관련 재료에 대한 주의 문구를 추가합니다.
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

    // 짠 재료(장류, 김치, 소금 등)가 있으면 혈압 관련 주의 문구를 추가합니다.
    private void appendHighBloodPressureNote(List<String> notes, String ingredientText) {
        if (containsIngredientAny(ingredientText, "김치", "된장", "고추장", "간장", "국간장", "소금", "젓갈")) {
            notes.add("혈압 관리가 필요하면 김치, 된장, 고추장, 간장, 소금 양을 줄이고 국물은 적게 드세요.");
        }
    }

    // 당류/탄수화물 재료가 있으면 혈당 관련 주의 문구를 추가합니다.
    private void appendDiabetesNote(List<String> notes, String ingredientText) {
        if (containsIngredientAny(ingredientText, "설탕", "올리고당", "꿀", "떡", "밥", "면", "감자", "고구마")) {
            notes.add("혈당 관리가 필요하면 당류와 탄수화물 재료의 양을 줄이고 단백질과 채소를 함께 드세요.");
        }
    }

    // 기름진 재료가 있으면 지질 관련 주의 문구를 추가합니다.
    private void appendLipidNote(List<String> notes, String ingredientText) {
        if (containsIngredientAny(ingredientText, "돼지고기", "삼겹살", "베이컨", "버터", "크림", "튀김")) {
            notes.add("지질 관리가 필요하면 기름진 부위와 튀김 조리를 줄이고 살코기나 두부로 대체하는 것이 좋습니다.");
        }
    }

    private boolean containsAny(List<String> values, String... keywords) {
        String joined = values == null ? "" : String.join(" ", values).toLowerCase();
        for (String keyword : keywords) {
            if (joined.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // 재료 텍스트에 키워드 중 하나라도 포함되면 true입니다. (주의 문구용 단순 비교이며 알레르기 판정에는 쓰지 않습니다)
    private boolean containsIngredientAny(String ingredientText, String... keywords) {
        for (String keyword : keywords) {
            if (ingredientText.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // 빈 값을 제거하고, LLM이 섞어 쓴 중국어/일본어 "적량" 표기를 한국어 "적당량"으로 바꿉니다.
    private List<String> cleanRecipeValues(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().replace("适量", "적당량").replace("適量", "적당량"))
                .toList();
    }


    private void appendMetric(StringBuilder builder, String label, Object value) {
        if (value != null) {
            builder.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    // "130/85" 형태로 만들며, 한쪽 값이 없으면 "?"로 표시합니다.
    private String formatBloodPressure(HealthCheckup checkup) {
        if (checkup.getSystolicBp() == null && checkup.getDiastolicBp() == null) {
            return null;
        }
        return (checkup.getSystolicBp() != null ? checkup.getSystolicBp() : "?") + "/"
                + (checkup.getDiastolicBp() != null ? checkup.getDiastolicBp() : "?");
    }

    private String formatLiverNumbers(HealthCheckup checkup) {
        if (checkup.getAst() == null && checkup.getAlt() == null) {
            return null;
        }
        return (checkup.getAst() != null ? checkup.getAst() : "?") + "/"
                + (checkup.getAlt() != null ? checkup.getAlt() : "?");
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * 레시피 생성 시 반드시 지켜야 할 사용자 건강 조건 묶음입니다.
     * healthContextAvailable=false는 "조건 없음"이 아니라 "DB 건강 정보를 확인하지 못함"을 뜻합니다.
     */
    public record SafetyContext(
            List<String> allergies,
            List<String> chronicConditions,
            List<String> dietaryRestrictions,
            List<String> medications,
            List<String> goals,
            boolean healthContextAvailable) {

        // 건강 조건이 하나라도 있으면 true입니다.
        public boolean hasAny() {
            return !allergies.isEmpty()
                    || !chronicConditions.isEmpty()
                    || !dietaryRestrictions.isEmpty()
                    || !medications.isEmpty()
                    || !goals.isEmpty();
        }
    }
}
