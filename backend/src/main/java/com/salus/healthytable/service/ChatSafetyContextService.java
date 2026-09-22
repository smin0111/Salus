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
        String normalized = text.replace("알러지", "알레르기")
                .replaceAll("[,，.?!]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<Pattern> patterns = List.of(
                Pattern.compile("(?:나는|저는|제가|내가|나|저)?\\s*([가-힣a-zA-Z0-9·/+\\s]{1,30})(?:에|에대한|은|는|이|가|을|를)?\\s*알레르기"),
                Pattern.compile("알레르기\\s*(?:가|는|은)?\\s*[:：]?\\s*([가-힣a-zA-Z0-9·/+,\\s]{1,40})"),
                Pattern.compile("(?:나는|저는|제가|내가|나|저)?\\s*([가-힣a-zA-Z0-9]{1,20})(?:을|를|은|는)?\\s*(?:못\\s*먹|먹으면\\s*안|피해야|안\\s*먹)"));
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                appendNormalizedValue(extractedAllergies, matcher.group(1), true, true);
            }
        }
        allergies.addAll(extractedAllergies);
        if (!extractedAllergies.isEmpty()) observeAllergyTerms(() ->
                profileResolutionShadowObserver.observeChatMessage(List.copyOf(extractedAllergies)));
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
            String normalized = allergyValue ? normalizeAllergyTerm(token) : normalizeHealthProfileTerm(token);
            if (!normalized.isBlank() && (!extractedAllergy || isLikelyExtractedAllergyName(normalized))) {
                target.add(normalized);
            }
        }
    }

    // "알레르기", "있어요", "주의" 같은 부가 표현과 끝 조사를 제거해 재료 이름만 남깁니다.
    private String normalizeAllergyTerm(String value) {
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
                .replaceAll("(으로|로|을|를|이|가|은|는|에|의|도|만)$", "")
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
