package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RecipeResponseSanitizer {

    private static final int MAX_RECIPE_FIELD_LENGTH = 900;

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

        List<String> steps = beginnerFriendlySteps(recipe);
        if (!steps.isEmpty()) {
            reply.append("[조리 순서]\n");
            for (int i = 0; i < steps.size(); i++) {
                reply.append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
        }

        return reply.toString().trim();
    }

    ChatDto.RecipeCard buildRecipeCard(Recipe recipe, List<String> safetyNotes) {
        return new ChatDto.RecipeCard(
                recipe.getId(),
                recipe.getTitle(),
                sanitizeRecipeDescription(recipe),
                cleanRecipeValues(recipe.getIngredients()),
                beginnerFriendlySteps(recipe),
                recipe.getCalories(),
                recipe.getDifficulty(),
                recipe.getCookingTime(),
                recipe.getImageUrl(),
                safetyNotes == null ? List.of() : safetyNotes);
    }

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

    boolean containsUnlistedIngredient(String sentence, String normalizedIngredients, String... ingredientNames) {
        for (String ingredientName : ingredientNames) {
            if (sentence.contains(ingredientName) && !normalizedIngredients.contains(ingredientName.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

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

    String ensureSentence(String value) {
        if (value.endsWith(".") || value.endsWith("!") || value.endsWith("?")) {
            return value;
        }
        return value + ".";
    }

    boolean containsTextAny(String value, String... keywords) {
        String normalized = nullToBlank(value).toLowerCase();
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    List<String> removeExcludedIngredients(List<String> ingredients, List<String> excludedIngredients) {
        return cleanRecipeValues(ingredients).stream()
                .filter(ingredient -> !containsExcludedIngredient(ingredient, excludedIngredients))
                .toList();
    }

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

    String removeDetailedStepAnnotations(String step) {
        if (step == null) {
            return "";
        }
        return step.replaceAll("[*_`]", "")
                .replaceAll("\\s*/\\s*불\\s*세기:.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

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

    boolean looksLikeRecipeResponse(String reply) {
        if (reply == null) {
            return false;
        }
        return reply.contains("kcal") || reply.contains("레시피") || reply.contains("재료");
    }

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
