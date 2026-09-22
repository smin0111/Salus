package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.dto.ChatDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 답변과 레시피 텍스트를 사용자에게 보여 주기 좋게 정리하는 도우미 클래스입니다.
 *
 * 주요 역할:
 * - 불필요한 인사/자기소개 문장 제거, 중국어·일본어 "적량" 표기 교정
 * - 레시피 답변 텍스트/레시피 카드(ChatDto.RecipeCard) 조립
 * - 초보자용 조리 팁 보강, 무가열 메뉴에 섞인 가열 팁 제거
 * - 재료 제외 요청 시 재료/조리 단계에서 해당 재료 제거
 * 대부분 문자열 치환 규칙이라, 규칙을 추가할 때는 다른 요리 답변에 영향을 주지 않는지 테스트로 확인해야 합니다.
 */
@Service
public class RecipeResponseSanitizer {

    // 레시피 필드 하나를 텍스트로 붙일 때의 최대 길이
    private static final int MAX_RECIPE_FIELD_LENGTH = 900;

    // "안녕하세요, 저는 Salus입니다" 같은 인사/소개 줄을 지우고, 3줄 이상 연속된 빈 줄을 정리합니다.
    String sanitizeRecipeReply(String reply) {
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
                .replace("适量", "적당량")
                .replace("適量", "적당량")
                .trim();
    }

    // 한 줄 안에 섞여 있는 Salus 자기소개 문장 조각을 제거합니다.
    String removeSalusIntroFragments(String line) {
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

    // 영어 근거의 "pastry"를 "파스타"로 잘못 번역한 흔적을 페이스트리로 바로잡습니다.
    String normalizeSearchBasedTranslationArtifacts(String reply, String searchContext, String title) {
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

    /**
     * 상세 설명 답변에서 자주 발견된 오류를 규칙으로 보정합니다.
     * 예) 파이가 아닌 요리의 "파이 크러스트" → 퍼프 페이스트리, 감싸는 요리의 프로슈토/베이컨 수량 보정,
     * 조리 순서에 쓰였지만 재료 목록에 없는 기본 재료(올리브유, 버터, 계란, 소금, 후추) 추가 등
     * 특정 요리(비프 웰링턴 등)에서 관찰된 문제를 고치기 위한 규칙이 많습니다.
     */
    String applyRecipeQualityGuards(String reply, String title) {
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

    // 겉을 감싸는 용도의 재료(프로슈토 등)가 속재료 다지기 단계에 잘못 들어간 문장을 고칩니다.
    String removeWrappingIngredientFromChoppedFilling(String text, String wrappingIngredient) {
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

    // 설명에서 자기소개와 "OO 레시피 알려줘" 같은 사용자 요청 문구가 섞인 부분을 제거합니다.
    String sanitizeRecipeDescription(Recipe recipe) {
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

    /**
     * 내부 레시피 DB의 레시피로 답변 텍스트를 만듭니다.
     * 1인분 열량이 없고 전체 열량만 있으면 "(기준 미확인)"을 붙여 1인분 열량으로 오해하지 않게 합니다.
     */
    String buildTrustedRecipeReply(Recipe recipe, List<String> safetyNotes) {
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
        if (recipe.getBaseServings() != null && recipe.getBaseServings() > 0) {
            summary.add("인분: " + recipe.getBaseServings() + "인분");
        }
        if (recipe.getCaloriesPerServing() != null) {
            summary.add("열량: 1인분당 약 " + recipe.getCaloriesPerServing() + "kcal");
        } else if (recipe.getCalories() != null) {
            summary.add("열량: " + recipe.getCalories() + "kcal (기준 미확인)");
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

        List<String> steps = beginnerFriendlySteps(recipe);
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

    // 생성/변형 레시피로 답변 텍스트를 만듭니다(건강 주의 섹션과 출처 문구 없음).
    String buildGeneratedRecipeReply(Recipe recipe) {
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
        if (recipe.getBaseServings() != null && recipe.getBaseServings() > 0) {
            summary.add("인분: " + recipe.getBaseServings() + "인분");
        }
        if (recipe.getCaloriesPerServing() != null) {
            summary.add("열량: 1인분당 약 " + recipe.getCaloriesPerServing() + "kcal");
        } else if (recipe.getCalories() != null) {
            summary.add("열량: " + recipe.getCalories() + "kcal (기준 미확인)");
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

        List<String> steps = beginnerFriendlySteps(recipe);
        if (!steps.isEmpty()) {
            reply.append("[조리 순서]\n");
            for (int i = 0; i < steps.size(); i++) {
                reply.append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
        }

        return reply.toString().trim();
    }

    /**
     * 화면에 표시할 레시피 카드를 만듭니다.
     * 승인(APPROVED) 레시피는 검수된 조리 순서를 그대로 쓰고, 그 외에는 초보자 팁을 보강한 조리 순서를 씁니다.
     */
    ChatDto.RecipeCard buildRecipeCard(Recipe recipe, List<String> safetyNotes) {
        List<String> steps = recipe.getApprovalStatus() == RecipeApprovalStatus.APPROVED
                ? cleanRecipeValues(recipe.getSteps())
                : beginnerFriendlySteps(recipe);
        return new ChatDto.RecipeCard(
                recipe.getId(),
                recipe.getTitle(),
                sanitizeRecipeDescription(recipe),
                cleanRecipeValues(recipe.getIngredients()),
                steps,
                recipe.getBaseServings(),
                recipe.getCalories(),
                recipe.getCaloriesPerServing(),
                recipe.getDifficulty(),
                recipe.getCookingTime(),
                recipe.getImageUrl(),
                safetyNotes == null ? List.of() : safetyNotes);
    }

    // 빈 값을 제거하고 앞뒤 공백과 "적량" 표기를 정리합니다.
    List<String> cleanRecipeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim()
                        .replace("适量", "적당량")
                        .replace("適量", "적당량"))
                .toList();
    }

    /**
     * 조리 순서를 초보자용으로 다듬습니다.
     * 1) 재료 목록에 없는 선택 재료 제안 문장 제거 → 2) 무가열 메뉴의 가열 팁 제거 → 3) 설명이 부족한 단계에 팁 추가
     */
    List<String> beginnerFriendlySteps(Recipe recipe) {
        if (recipe == null) {
            return List.of();
        }
        List<String> steps = cleanRecipeValues(recipe.getSteps());
        if (steps.isEmpty()) {
            return steps;
        }
        String ingredientText = String.join(" ", cleanRecipeValues(recipe.getIngredients()));
        boolean noHeatRecipe = isNoHeatRecipe(recipe);
        return steps.stream()
                .map(step -> removeUnlistedOptionalIngredientSuggestions(step, ingredientText))
                .map(step -> removeInappropriateHeatTipsForNoHeatRecipe(step, noHeatRecipe))
                .map(step -> enrichBeginnerStep(step, noHeatRecipe))
                .toList();
    }

    // 제목이 화채/샐러드 등이거나, 조리 순서에 가열 표현 없이 섞기/냉장 같은 표현만 있으면 무가열 메뉴로 봅니다.
    boolean isNoHeatRecipe(Recipe recipe) {
        String title = nullToBlank(recipe.getTitle()).replaceAll("\\s+", "");
        if (containsTextAny(title, "화채", "스무디", "요거트", "샐러드", "빙수", "주스", "에이드", "파르페")) {
            return true;
        }

        String stepText = String.join(" ", cleanRecipeValues(recipe.getSteps()));
        boolean hasHeatAction = containsTextAny(stepText,
                "볶", "끓", "삶", "데치", "굽", "구워", "튀", "졸", "조려",
                "오븐", "에어프라이", "중불", "약불", "센불", "강불", "불을", "팬", "냄비");
        boolean hasColdPreparation = containsTextAny(stepText, "섞", "버무", "담", "차갑", "냉장", "얼음");
        return hasColdPreparation && !hasHeatAction;
    }

    // 무가열 메뉴에 자동으로 붙었던 가열용 팁 문장(beginnerTipForStep의 문구)을 제거합니다.
    String removeInappropriateHeatTipsForNoHeatRecipe(String step, boolean noHeatRecipe) {
        String cleaned = nullToBlank(step).trim();
        if (!noHeatRecipe || cleaned.isBlank()) {
            return cleaned;
        }
        return cleaned
                .replace("처음엔 센불로 올리고 큰 거품이 올라오면 중약불로 낮추세요. 국물이 너무 졸면 물을 2~3큰술씩 보충하면 됩니다.", "")
                .replace("불은 중불부터 시작하고, 타는 냄새가 나면 바로 약불로 낮춘 뒤 바닥을 긁듯이 저어주세요.", "")
                .replace("뚜껑을 살짝 덮고 약불을 유지하되 5분마다 바닥을 저어 눌어붙지 않게 하세요. 국물이 자작하게 남으면 완성입니다.", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    // "원한다면 청양고추를 추가해도 좋아요"처럼 재료 목록에 없는 선택 재료를 권하는 문장을 제거합니다.
    String removeUnlistedOptionalIngredientSuggestions(String step, String ingredientText) {
        String trimmed = nullToBlank(step).trim();
        if (trimmed.isBlank()) {
            return trimmed;
        }
        String normalizedIngredients = nullToBlank(ingredientText).toLowerCase();
        List<String> kept = new ArrayList<>();
        String[] sentences = trimmed.split("(?<=[.!?])\\s+");
        for (String sentence : sentences) {
            String compact = sentence.replaceAll("\\s+", "");
            boolean optionalSuggestion = compact.contains("원한다면")
                    || compact.contains("취향에따라")
                    || compact.contains("추가해도")
                    || compact.contains("넣어도좋");
            boolean mentionsUnlistedOptional = optionalSuggestion
                    && containsUnlistedIngredient(sentence, normalizedIngredients,
                    "청양고추", "고추", "고춧가루", "참기름", "깨", "치즈", "버터", "크림", "설탕");
            if (!mentionsUnlistedOptional) {
                kept.add(sentence.trim());
            }
        }
        String cleaned = String.join(" ", kept).trim();
        return cleaned.isBlank() ? trimmed : cleaned;
    }

    // 문장에 언급된 재료 중 재료 목록에 없는 것이 하나라도 있으면 true입니다.
    boolean containsUnlistedIngredient(String sentence, String normalizedIngredients, String... ingredientNames) {
        for (String ingredientName : ingredientNames) {
            if (sentence.contains(ingredientName) && !normalizedIngredients.contains(ingredientName.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // 단계 설명이 충분히 자세하지 않으면 단계 내용에 맞는 초보자 팁 한 문장을 덧붙입니다.
    String enrichBeginnerStep(String step, boolean noHeatRecipe) {
        String trimmed = nullToBlank(step).trim();
        if (trimmed.isBlank() || isBeginnerDetailedStep(trimmed, noHeatRecipe)) {
            return trimmed;
        }

        String tip = beginnerTipForStep(trimmed, noHeatRecipe);
        if (tip.isBlank() || trimmed.contains(tip)) {
            return trimmed;
        }
        return ensureSentence(trimmed) + " " + tip;
    }

    /**
     * 단계가 이미 충분히 자세한지 판단합니다.
     * 가열 메뉴: 45자 이상이면서 불 세기/시간/상태/복구 방법 중 2가지 이상 포함
     * 무가열 메뉴: 45자 이상이면서 손질 기준과 시간/상태 표현 포함
     */
    boolean isBeginnerDetailedStep(String step, boolean noHeatRecipe) {
        if (noHeatRecipe) {
            boolean hasPrepDetail = containsTextAny(step, "한입", "먹기 좋은", "물기", "차갑", "냉장", "얼음", "으깨지");
            boolean hasTimeOrState = step.matches(".*\\d+\\s*(분|초|시간).*")
                    || containsTextAny(step, "직전", "충분히", "고르게", "살짝", "상태");
            return step.length() >= 45 && hasPrepDetail && hasTimeOrState;
        }

        boolean hasHeat = containsTextAny(step, "센불", "강불", "중불", "중약불", "약불", "불을");
        boolean hasTime = step.matches(".*\\d+\\s*(분|초|시간).*")
                || containsTextAny(step, "잠시", "충분히", "노릇", "투명", "자작");
        boolean hasState = containsTextAny(step, "때까지", "상태", "익으면", "끓으면", "줄이고", "노릇", "투명", "자작");
        boolean hasRecovery = containsTextAny(step, "타", "눌어", "싱거", "짜면", "조절");
        int detailScore = 0;
        if (hasHeat) {
            detailScore++;
        }
        if (hasTime) {
            detailScore++;
        }
        if (hasState) {
            detailScore++;
        }
        if (hasRecovery) {
            detailScore++;
        }
        return step.length() >= 45 && detailScore >= 2;
    }

    // 단계에 들어간 키워드(완성, 끓이기, 볶기, 손질 등)에 따라 붙일 초보자 팁 문장을 고릅니다.
    String beginnerTipForStep(String step, boolean noHeatRecipe) {
        if (noHeatRecipe) {
            if (containsTextAny(step, "완성", "마무리")) {
                return "먹기 직전에 한 번만 가볍게 섞고, 과일 물이 많이 생겼으면 차가운 음료 베이스를 조금만 보충하세요.";
            }
            if (containsTextAny(step, "얼음", "차갑", "냉장")) {
                return "얼음은 먹기 직전에 넣어야 녹아서 맛이 묽어지는 것을 줄일 수 있습니다.";
            }
            if (containsTextAny(step, "섞", "담", "버무")) {
                return "재료가 으깨지지 않도록 큰 숟가락으로 아래에서 위로 가볍게 뒤집어 섞으세요.";
            }
            if (containsTextAny(step, "준비", "자르", "썰", "손질")) {
                return "과일 크기는 한입 크기로 맞추고, 물기가 많으면 키친타월로 살짝 눌러 맛이 묽어지지 않게 하세요.";
            }
            return "차갑게 먹는 메뉴라 불은 사용하지 않습니다. 완성 후 냉장고에 10분 정도 두면 더 시원합니다.";
        }

        if (containsTextAny(step, "완성", "불을 끄")) {
            return "마지막에 한 숟가락 맛보고 싱거우면 양념을 아주 조금만 더하고, 짜면 물을 2~3큰술 넣어 중약불에서 1분 더 끓여 조절하세요.";
        }
        if (containsTextAny(step, "약불", "졸", "더 끓", "마무리")) {
            return "뚜껑을 살짝 덮고 약불을 유지하되 5분마다 바닥을 저어 눌어붙지 않게 하세요. 국물이 자작하게 남으면 완성입니다.";
        }
        if (containsTextAny(step, "끓", "국물", "물")) {
            return "처음엔 센불로 올리고 큰 거품이 올라오면 중약불로 낮추세요. 국물이 너무 졸면 물을 2~3큰술씩 보충하면 됩니다.";
        }
        if (containsTextAny(step, "돼지고기", "고기") && containsTextAny(step, "볶", "익", "굽")) {
            return "중불에서 4~5분간 뒤집어가며 익히고, 겉면의 붉은 기가 거의 사라지면 다음 단계로 넘어가세요. 바닥이 타기 시작하면 물을 1~2큰술 넣고 불을 낮추세요.";
        }
        if (containsTextAny(step, "양파", "대파", "파", "채소", "야채") && containsTextAny(step, "볶", "익")) {
            return "중불에서 2~3분간 저어가며 익히고, 양파 가장자리가 살짝 투명해지면 다음 단계로 넘어가세요.";
        }
        if (containsTextAny(step, "김치") && containsTextAny(step, "준비", "자르", "썰")) {
            return "김치가 길면 가위로 3~4cm 길이로 잘라 한 숟가락에 들어오게 맞추세요. 국물이 튈 수 있으니 도마보다 그릇 안에서 자르면 편합니다.";
        }
        if (containsTextAny(step, "준비", "자르", "썰", "손질")) {
            return "크기는 한입에 먹기 좋은 3cm 정도로 맞추고, 물기가 많으면 키친타월로 살짝 눌러 기름 튐을 줄이세요.";
        }
        if (containsTextAny(step, "볶")) {
            return "중불에서 2~3분간 계속 저어가며 볶고, 재료 가장자리에 윤기가 돌면 다음 단계로 넘어가세요.";
        }
        if (containsTextAny(step, "간", "양념", "소금", "간장", "고추장", "된장")) {
            return "간은 한 번에 많이 넣지 말고 1/2큰술씩 넣은 뒤 맛을 보세요. 짜면 물을 2~3큰술 넣어 조절하세요.";
        }
        return "불은 중불부터 시작하고, 타는 냄새가 나면 바로 약불로 낮춘 뒤 바닥을 긁듯이 저어주세요.";
    }

    // 문장 끝에 마침표/느낌표/물음표가 없으면 마침표를 붙입니다.
    String ensureSentence(String value) {
        if (value.endsWith(".") || value.endsWith("!") || value.endsWith("?")) {
            return value;
        }
        return value + ".";
    }

    // 대소문자를 무시하고 키워드 중 하나라도 포함되면 true입니다.
    boolean containsTextAny(String value, String... keywords) {
        String normalized = nullToBlank(value).toLowerCase();
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // 제외 재료가 들어간 재료 줄을 목록에서 뺍니다.
    List<String> removeExcludedIngredients(List<String> ingredients, List<String> excludedIngredients) {
        return cleanRecipeValues(ingredients).stream()
                .filter(ingredient -> !containsExcludedIngredient(ingredient, excludedIngredients))
                .toList();
    }

    // 제목 앞의 "양파 없는 " 같은 기존 제외 접두어를 반복해서 제거합니다(접두어가 중복으로 쌓이지 않게).
    String removeExistingExclusionPrefix(String title, List<String> excludedIngredients) {
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

    /**
     * 조리 단계에서 제외 재료를 제거합니다.
     * 문장에서 재료 언급을 지운 뒤에도 재료가 남아 있으면 그 단계는 통째로 뺍니다.
     * 모든 단계가 사라지거나 마지막 단계가 완성 단계가 아니면 기본 문장을 보충합니다.
     */
    List<String> removeExcludedSteps(List<String> steps, List<String> excludedIngredients) {
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

    // 마크다운 기호와 "/ 불 세기: ..." 같은 뒤쪽 부가 설명을 제거합니다.
    String removeDetailedStepAnnotations(String step) {
        if (step == null) {
            return "";
        }
        return step.replaceAll("[*_`]", "")
                .replaceAll("\\s*/\\s*불\\s*세기:.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // 초보자 실수 정리, 주의사항, 목록 기호로 시작하는 줄처럼 실제 조리 단계가 아닌 문장이면 true입니다.
    boolean isNonCookingStepNote(String step) {
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

    // 문장에서 "A, 양파를" / "양파와 A" 같은 형태의 제외 재료 언급과 조사를 지우고 남은 쉼표/공백을 정리합니다.
    String removeExcludedIngredientMentions(String step, List<String> excludedIngredients) {
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

    // 기호/공백을 무시하고 제외 재료 이름이 텍스트에 포함되어 있으면 true입니다.
    boolean containsExcludedIngredient(String text, List<String> excludedIngredients) {
        String normalizedText = normalizeIngredientForMatching(text);
        for (String ingredient : excludedIngredients) {
            if (!ingredient.isBlank() && normalizedText.contains(normalizeIngredientForMatching(ingredient))) {
                return true;
            }
        }
        return false;
    }

    String normalizeIngredientForMatching(String text) {
        return nullToBlank(text)
                .replaceAll("[^가-힣a-zA-Z0-9]", "")
                .toLowerCase();
    }

    // 값이 있을 때만 "라벨: 값" 줄을 추가합니다. 너무 긴 값은 잘라 냅니다.
    void appendRecipeField(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(label).append(": ").append(truncateRecipeField(value)).append("\n");
    }

    String joinRecipeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    // 목록을 "1. a 2. b" 형태의 한 줄 문자열로 만듭니다.
    String joinNumberedRecipeList(List<String> values) {
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

    String truncateRecipeField(String value) {
        if (value.length() <= MAX_RECIPE_FIELD_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RECIPE_FIELD_LENGTH) + "...";
    }

    String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    // 답변에 kcal/레시피/재료 같은 단어가 있으면 레시피 형태의 답변으로 봅니다(일반 대화 경로에서 사용).
    boolean looksLikeRecipeResponse(String reply) {
        if (reply == null) {
            return false;
        }
        return reply.contains("kcal") || reply.contains("레시피") || reply.contains("재료");
    }

    // OllamaLlmService가 LLM 장애 시 돌려주는 안내 문구인지 확인합니다.
    boolean isLlmUnavailableReply(String reply) {
        if (reply == null) {
            return false;
        }
        return reply.contains("로컬 AI 엔진")
                || reply.contains("AI 엔진")
                || reply.contains("점검 중")
                || reply.contains("답변을 생성하지 못했습니다");
    }

}
