package com.salus.healthytable.service;

import com.salus.healthytable.domain.HealthCheckup;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.HealthCheckupAnalysisDTO;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSafetyContextService {

    private final HealthProfileRepository healthProfileRepository;
    private final HealthCheckupRepository healthCheckupRepository;
    private final HealthCheckupAnalysisService healthCheckupAnalysisService;

    @Transactional(readOnly = true)
    public SafetyContext build(Optional<Long> authenticatedUserId, ChatDto.Request request) {
        Set<String> allergies = new LinkedHashSet<>();
        Set<String> chronicConditions = new LinkedHashSet<>();
        Set<String> dietaryRestrictions = new LinkedHashSet<>();
        Set<String> medications = new LinkedHashSet<>();
        Set<String> goals = new LinkedHashSet<>();

        appendRequestHealthProfileValues(
                request, allergies, chronicConditions, dietaryRestrictions, medications, goals);
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
                    appendNormalizedValues(allergies, profile.getAllergies(), true);
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

    public String buildAllergyBlockedReply(String requestedTitle, List<String> conflicts) {
        String foodName = nullToBlank(requestedTitle).isBlank() ? "요청하신 메뉴" : requestedTitle.trim();
        String conflictText = conflicts == null || conflicts.isEmpty()
                ? "알레르기 재료"
                : String.join(", ", conflicts);
        return "확인된 알레르기 정보상 '" + conflictText + "' 알레르기가 있어 '" + foodName
                + "' 레시피는 추천할 수 없습니다.\n\n"
                + "알레르기 재료를 제외한 안전한 메뉴로 바꿔야 합니다. 먹을 수 있는 과일이나 재료를 알려주시면 그 범위 안에서 대체 레시피를 만들어드릴게요.";
    }

    public List<String> findAllergyConflicts(
            SafetyContext safetyContext,
            String title,
            Recipe recipe,
            String requestMessage) {
        if (safetyContext == null || safetyContext.allergies().isEmpty()) {
            return List.of();
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (String allergy : safetyContext.allergies()) {
            String normalizedAllergy = normalizeIngredientForMatching(allergy);
            if (normalizedAllergy.length() < 2) {
                continue;
            }
            if (recipe == null && isIngredientExplicitlyExcluded(requestMessage, allergy)) {
                continue;
            }
            if (containsAllergyTerm(title, allergy)) {
                conflicts.add(allergy);
                continue;
            }
            if (recipe == null) {
                continue;
            }
            boolean ingredientConflict = cleanRecipeValues(recipe.getIngredients()).stream()
                    .anyMatch(ingredient -> containsAllergyTerm(ingredient, allergy));
            boolean stepConflict = cleanRecipeValues(recipe.getSteps()).stream()
                    .anyMatch(step -> containsAllergyTerm(step, allergy));
            if (ingredientConflict || stepConflict) {
                conflicts.add(allergy);
            }
        }
        return List.copyOf(conflicts);
    }

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
        appendNormalizedValues(allergies, profile.getAllergies(), true);
        appendNormalizedValues(chronicConditions, profile.getChronicConditions(), false);
        appendNormalizedValues(dietaryRestrictions, profile.getDietaryRestrictions(), false);
        appendNormalizedValues(medications, profile.getMedications(), false);
        appendNormalizedValues(goals, profile.getGoals(), false);
    }

    private void appendAllergyMentionsFromText(Set<String> allergies, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
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
                appendNormalizedValue(allergies, matcher.group(1), true);
            }
        }
    }

    private void appendNormalizedValues(Set<String> target, List<String> values, boolean allergyValue) {
        if (values != null) {
            values.forEach(value -> appendNormalizedValue(target, value, allergyValue));
        }
    }

    private void appendNormalizedValue(Set<String> target, String value, boolean allergyValue) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : value.split("[,/·，\\n]")) {
            String normalized = allergyValue ? normalizeAllergyTerm(token) : normalizeHealthProfileTerm(token);
            if ((!allergyValue || isLikelyAllergyName(normalized)) && !normalized.isBlank()) {
                target.add(normalized);
            }
        }
    }

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

    private String normalizeHealthProfileTerm(String value) {
        return value == null ? "" : value
                .replaceAll("[^가-힣a-zA-Z0-9\\s]", " ")
                .replaceAll("\\b(나는|저는|제가|내가|나|저|혹시)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isLikelyAllergyName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.replaceAll("\\s+", "");
        return compact.length() >= 2
                && compact.length() <= 20
                && !compact.contains("없음")
                && !compact.contains("없어요")
                && !compact.contains("건강정보")
                && !compact.contains("알려주");
    }

    private boolean containsAllergyTerm(String text, String allergy) {
        String normalizedText = normalizeIngredientForMatching(text);
        String normalizedAllergy = normalizeIngredientForMatching(allergy);
        return !normalizedText.isBlank()
                && !normalizedAllergy.isBlank()
                && normalizedText.contains(normalizedAllergy)
                && !isIngredientExplicitlyExcluded(text, allergy);
    }

    private boolean isIngredientExplicitlyExcluded(String text, String allergy) {
        String normalizedText = normalizeIngredientForMatching(text);
        String normalizedAllergy = normalizeIngredientForMatching(allergy);
        if (normalizedText.isBlank() || normalizedAllergy.isBlank()) {
            return false;
        }
        return normalizedText.contains(normalizedAllergy + "없는")
                || normalizedText.contains(normalizedAllergy + "없이")
                || normalizedText.contains(normalizedAllergy + "빼고")
                || normalizedText.contains(normalizedAllergy + "제외")
                || normalizedText.contains(normalizedAllergy + "말고")
                || normalizedText.contains(normalizedAllergy + "안들어간");
    }

    private void appendHealthProfileSafetyNotes(
            List<String> notes, SafetyContext safetyContext, String ingredientText) {
        for (String allergy : safetyContext.allergies()) {
            if (allergy != null && !allergy.isBlank()
                    && normalizeIngredientForMatching(ingredientText)
                    .contains(normalizeIngredientForMatching(allergy))) {
                notes.add("확인된 알레르기 재료인 '" + allergy.trim()
                        + "'가 포함되어 있습니다. 이 재료는 반드시 제외하거나 안전한 대체 재료를 사용하세요.");
            }
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
        String joined = values == null ? "" : String.join(" ", values).toLowerCase();
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
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().replace("适量", "적당량").replace("適量", "적당량"))
                .toList();
    }

    private String normalizeIngredientForMatching(String text) {
        return nullToBlank(text).toLowerCase().replaceAll("[^가-힣a-z0-9]", "");
    }

    private void appendMetric(StringBuilder builder, String label, Object value) {
        if (value != null) {
            builder.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

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

    public record SafetyContext(
            List<String> allergies,
            List<String> chronicConditions,
            List<String> dietaryRestrictions,
            List<String> medications,
            List<String> goals,
            boolean healthContextAvailable) {

        public boolean hasAny() {
            return !allergies.isEmpty()
                    || !chronicConditions.isEmpty()
                    || !dietaryRestrictions.isEmpty()
                    || !medications.isEmpty()
                    || !goals.isEmpty();
        }
    }
}
