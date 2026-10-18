package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.domain.WebRecipeSource;
import com.salus.healthytable.repository.WebRecipeSourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/*
 * 웹에서 찾은 레시피 근거를 DB(web_recipe_sources)에 보관하고 재사용하는 클래스들입니다.
 *
 * 목적은 같은 요리를 다시 검색하지 않는 것입니다. 2026-10 실측에서 검색이 몰리자 SearXNG 상위 엔진(구글·DuckDuckGo)이
 * 차단됐습니다. 저장은 "신뢰"가 아니라 "어디서 무엇을 가져왔는지"의 기록이고, 모두 UNVERIFIED로 들어갑니다.
 * 꺼내 쓸 때도 새로 가져온 출처와 같은 개인화·알레르기·검증 단계를 거칩니다.
 *
 * 저작권을 고려해 조리 단계는 원문 문장 대신 요약만 저장·표시합니다. 요약할 LLM이 없으면 저장하지 않습니다.
 */

/**
 * 조리 단계를 원문 문장 대신 짧은 요약으로 다시 씁니다. 본문 추출과 같은 로컬 LLM 포트를 씁니다.
 */
@Slf4j
@Component
class RecipeStepSummarizer {

    private static final int MAX_STEPS = 10;
    // 원문이 10단계를 조금 넘으면(곱도리탕 13단계) 10칸에 억지로 맞추다 끝 단계를 버립니다(2026-10-14).
    // 원문 단계 수만큼(최대 15칸) 허용합니다. 아주 긴 원문(잡채 31단계)도 15칸 안으로 줄입니다.
    private static final int MAX_STEPS_LONG = 15;
    // 원문과 반대 상태로 바꾸면 결과가 달라지는 지시입니다(2026-10-14 곱도리탕: "뚜껑을 연 채" → "뚜껑을 덮어").
    // 요약에 한쪽이 새로 생겼는데 원문에는 그쪽이 없고 반대쪽만 있으면 뜻이 뒤집힌 것으로 봅니다.
    private static final List<java.util.regex.Pattern[]> OPPOSITE_INSTRUCTIONS = List.of(
            new java.util.regex.Pattern[]{
                    java.util.regex.Pattern.compile("뚜껑(?:을|를)?\\s*(?:열|연\\s*채|연\\s*상태|오픈)"),
                    java.util.regex.Pattern.compile("뚜껑(?:을|를)?\\s*(?:덮|닫)")},
            new java.util.regex.Pattern[]{
                    java.util.regex.Pattern.compile("센\\s*불|강불|강한\\s*불"),
                    java.util.regex.Pattern.compile("(?<!중)약불|약한\\s*불")},
            new java.util.regex.Pattern[]{
                    java.util.regex.Pattern.compile("찬\\s*물|차가운\\s*물|냉수"),
                    java.util.regex.Pattern.compile("끓는\\s*물|뜨거운\\s*물|온수")});
    // 요약에서 빠지면 안 되는 정보: 단위가 붙은 숫자(온도·시간·분량)와 불 세기 표현입니다.
    private static final java.util.regex.Pattern DETAIL = java.util.regex.Pattern.compile(
            "\\d+(?:[.~\\-]\\d+)?\\s*(?:도|℃|°C|분|초|시간|kg|g|ml|mL|L|개|큰술|작은술|숟가락|컵|스푼|T|t)(?![a-zA-Z])"
                    + "|중약불|중강불|약불|중불|강불");
    // 한글로 풀어 쓴 분량·시간("세 번 큰술", "두 컵", "한 줌")입니다(2026-10-12 "양배추 한 줌"을 "한 잔"으로 바꿈). 원문에 같은 표기가 없으면 바뀐 값으로 봅니다
    // (2026-10-12 골뱅이소면: 원문 숫자를 "고추장 세 번 큰술"로 바꿔 쓰면서 숫자 검사를 피함).
    private static final java.util.regex.Pattern KOREAN_NUMBER_DETAIL = java.util.regex.Pattern.compile(
            "(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|십|이십|삼십|반)\\s*(?:번\\s*)?"
                    + "(?:큰술|작은술|스푼|숟가락|숟갈|컵|분|시간|줌|잔|묶음|장|쪽|알|개|대|국자|꼬집)(?![가-힣])");
    // "STEP 1의 걸러낸 육수"처럼 사이트의 단계 번호를 가리키는 표기입니다. 요약·추출 단계에서는 뜻이 없어 지웁니다.
    private static final java.util.regex.Pattern STEP_REFERENCE = java.util.regex.Pattern.compile(
            "(?i)\\bstep\\s*\\d+\\s*(?:의|에서|에)?\\s*");
    // 빠지면 따라 할 수 없는 정보: 온도, 시간, 불 세기
    private static final java.util.regex.Pattern CRITICAL_DETAIL = java.util.regex.Pattern.compile(
            "(?:도|℃|°C|분|초|시간)$|불$");
    // 후기·권유 문장(조리 동작이 없을 때만 뺍니다): "맛있게 즐깁니다", "아이들과 신랑 모두 좋아하며", "느껴보세요"
    private static final java.util.regex.Pattern REVIEW_LIKE = java.util.regex.Pattern.compile(
            "즐기|즐겨|즐깁|즐길|느껴|좋아하|좋아해|만족|맛있게\\s*드|드셔|추천합|맛보세요|생명|완성된\\s*\\S+을|완성된\\s*\\S+는");
    private static final java.util.regex.Pattern COOKING_ACTION = java.util.regex.Pattern.compile(
            "볶|굽|구워|구운|끓|녹이|녹인|녹여|섞|넣|반죽|성형|썰|갈|붓|부어|식히|식힌|데치|데쳐|튀기|튀겨|바르|발라"
                    + "|뿌리|뿌려|감싸|감싼|굴려|굳히|굳힌|굳혀|찌|쪄|삶");

    private final ObjectProvider<RecipeTextLlmPort> llmPort;

    RecipeStepSummarizer(ObjectProvider<RecipeTextLlmPort> llmPort) {
        this.llmPort = llmPort;
    }

    boolean enabled() {
        return llmPort.getIfAvailable() != null;
    }

    /**
     * 단계를 요약합니다. 본문 추출로 얻은 단계는 이미 요약이므로 그대로 씁니다.
     * LLM이 없거나 실패하거나 결과가 비정상이면 빈 결과입니다(원문을 대신 저장하지 않습니다).
     */
    Optional<List<String>> summarize(String dishName, RecipeSourceCandidate candidate) {
        return summarizeOutcome(dishName, candidate).summary();
    }

    /**
     * 요약 결과와 함께, 요약을 품질 문제로 버렸는지(REJECTED) LLM이 없거나 실패했는지(UNAVAILABLE)를 알려 줍니다.
     * 버린 경우는 같은 원문·모델로 다시 해도 대개 또 버려지므로 호출하는 쪽이 원문 단계를 저장할 수 있습니다.
     */
    SummaryOutcome summarizeOutcome(String dishName, RecipeSourceCandidate candidate) {
        if (candidate == null || candidate.originalRecipe() == null || candidate.originalRecipe().steps().isEmpty()) {
            return SummaryOutcome.ofUnavailable();
        }
        List<String> steps = candidate.originalRecipe().steps();
        if (candidate.evidence() != null && candidate.evidence().provenance() != null
                && PageTextRecipeExtractor.EXTRACTION_METHOD.equals(candidate.evidence().provenance().extractionMethod())) {
            return SummaryOutcome.ofSummary(steps.stream()
                    .filter(step -> !isReviewOnlyStep(step))
                    .map(RecipeStepSummarizer::withoutStepReference)
                    .toList());
        }
        RecipeTextLlmPort llm = llmPort.getIfAvailable();
        if (llm == null) {
            return SummaryOutcome.ofUnavailable();
        }
        try {
            // 원문의 온도·시간·불 세기·분량을 처음부터 목록으로 알려 줍니다. 빠졌을 때만 다시 묻던 방식은
            // 2026-10-09 실전 평가에서 60초를 넘긴 요청 대부분이 이 재요청(호출 한 번 25~50초) 때문이었습니다.
            List<String> required = missingDetails(steps, List.of());
            List<String> summaries = request(llm, prompt(dishName, steps, required, List.of(), false), stepLimit(steps));
            // 다시 묻는 경우는 온도·시간·불 세기가 빠졌을 때뿐입니다(2026-10-07 두쫀쿠: "약불에서"가 빠져 단계가 부실).
            // 분량(g·개·큰술)만 빠진 것은 재료 목록에 있어 다시 묻지 않습니다(2026-10-09 소금빵: 분량 3개 때문에 101초).
            // 원문에 없는 분량·온도·시간이 생기면(2026-10-12 짬뽕탕: "고추가루 2T"(큰술)를 "2t"(작은술)로 바꿈) 바로 버립니다.
            List<String> missing = criticalMissing(steps, summaries);
            List<String> introduced = changedDetails(steps, summaries);
            List<String> ingredients = candidate.originalRecipe().ingredients();
            // 원문 단계에서 쓰인 재료가 요약 단계에서 사라졌는지 봅니다(2026-10-14 곱도리탕: 마지막 단계의 후추가 사라짐).
            // "끓여" 같은 흔한 동사로 끝 단계를 담았다고 판단하던 검사보다 정확한 증거입니다.
            List<String> lostIngredients = lostIngredients(ingredients, steps, summaries);
            // 단계가 많은 원문을 줄이다 준비 단계로 칸을 채우고 마지막 조리 단계를 빼먹는 경우입니다
            // (2026-10-12 오징어김치전: "팬에 식용유를 두른다"에서 끝나 전을 부치는 단계가 없음).
            boolean lastStepLost = !coversLastStep(steps, summaries) || !lostIngredients.isEmpty();
            // 끝 단계만 빠졌으면 다시 묻지 않고 원문 단계를 씁니다. 재요청(25~37초)이 2026-10-13 실전 평가에서
            // 잡채·스모어쿠키·김치찌개를 60초 넘게 만들었고, 원문 단계는 길어도 빠진 단계가 없습니다.
            // 원문에 없는 값이나 반대 지시가 생긴 요약도 다시 묻지 않고 버립니다. 2026-10-14 서버 로그 집계에서 이 경우
            // 재요청이 36번 중 3번만 성공했고(온도·시간 누락만 있을 때는 11번 중 8번 성공), 매번 25~35초가 들었습니다.
            if (!introduced.isEmpty()) {
                log.info("[RecipeStepSummary] Rejected. reason=INTRODUCED_DETAILS, count={}, oppositeInstructions={}",
                        introduced.size(), oppositeInstructions(steps, summaries));
                return SummaryOutcome.ofRejected();
            }
            if (lastStepLost && missing.isEmpty()) {
                // 재료 이름은 출처 레시피의 것이라 개인정보가 아닙니다. 새 검사가 정상 요약을 버리는지 확인하려고 남깁니다.
                log.info("[RecipeStepSummary] Rejected. reason=LAST_STEP_LOST, lostIngredients={}", lostIngredients);
                return SummaryOutcome.ofRejected();
            }
            if (!missing.isEmpty() || !introduced.isEmpty()) {
                log.info("[RecipeStepSummary] Retrying. missingDetails={}, introducedDetails={}, oppositeInstructions={}, lastStepLost={}, lostIngredients={}",
                        missing.size(), introduced.size(), oppositeInstructions(steps, summaries), lastStepLost, lostIngredients);
                List<String> retried = request(llm, prompt(dishName, steps, missing, introduced, true)
                        + (lastStepLost ? "- 이전 요약이 원문의 마지막 단계까지 가지 못했습니다. 준비 단계를 합쳐서 마지막 단계(\""
                        + lastCookingStep(steps) + "\")까지 빠짐없이 포함하세요.\n" : ""), stepLimit(steps));
                List<String> retriedIntroduced = changedDetails(steps, retried);
                boolean retriedLastStepLost = !coversLastStep(steps, retried) || !lostIngredients(ingredients, steps, retried).isEmpty();
                if (retriedIntroduced.isEmpty() && criticalMissing(steps, retried).size() <= missing.size()
                        && (!retriedLastStepLost || !lastStepLost)) {
                    summaries = retried;
                    introduced = retriedIntroduced;
                    lastStepLost = retriedLastStepLost;
                }
            }
            // 바뀐 값이 남았거나 끝 단계를 빼먹은 요약은 쓰지 않습니다. 호출하는 쪽은 요약 없이 원문 출처를 씁니다.
            if (!introduced.isEmpty() || lastStepLost) {
                log.info("[RecipeStepSummary] Rejected. reason={}, count={}",
                        !introduced.isEmpty() ? "INTRODUCED_DETAILS" : "LAST_STEP_LOST", introduced.size());
                return SummaryOutcome.ofRejected();
            }
            // 같은 문장을 되풀이한 단계는 한 번만 남깁니다(2026-10-11 쇠고기무국 요약에서 같은 문장 4번 반복).
            summaries = summaries.stream()
                    .filter(step -> !isStorageOnlyStep(step) && !isReviewOnlyStep(step))
                    .map(RecipeStepSummarizer::withoutStepReference)
                    .map(String::trim)
                    .filter(step -> !step.isBlank())
                    .distinct()
                    .toList();
            if (summaries.size() < 2) {
                log.info("[RecipeStepSummary] Rejected. reason=TOO_FEW_STEPS");
                return SummaryOutcome.ofRejected();
            }
            // 앞 문장에 기대는 말로 시작하는 단계는 원문 한 문장을 둘로 나누다 앞부분이 빠진 것입니다
            // (2026-10-17 샌드위치: "동안 토마토를 슬라이스해주세요"). 이런 요약은 버리고 원문 단계를 씁니다.
            if (summaries.stream().anyMatch(RecipeStepSummarizer::startsMidSentence)) {
                log.info("[RecipeStepSummary] Rejected. reason=STEP_STARTS_MID_SENTENCE");
                return SummaryOutcome.ofRejected();
            }
            return SummaryOutcome.ofSummary(List.copyOf(summaries));
        } catch (com.salus.healthytable.service.ChatCancellation.ChatCancelledException e) {
            throw e; // 사용자가 멈춘 요청은 실패로 처리하지 않고 그대로 멈춥니다.
        } catch (Exception e) {
            log.warn("[RecipeStepSummary] Summary failed. failureCategory={}", e.getClass().getSimpleName());
            return SummaryOutcome.ofUnavailable();
        }
    }

    // "동안 …", "후에 …", "뒤 …", "사이에 …"처럼 앞 문장 없이는 뜻이 안 되는 말로 시작하는지 봅니다(번호·기호는 무시).
    private static final java.util.regex.Pattern MID_SENTENCE_START = java.util.regex.Pattern.compile(
            "^[\\s\\d.)\\-·*]*(?:동안|후에|후|뒤에|뒤|다음에|사이에|사이|만큼|중에|면서)(?:[\\s,]|$)");

    static boolean startsMidSentence(String step) {
        return step != null && MID_SENTENCE_START.matcher(step).find();
    }

    private List<String> request(RecipeTextLlmPort llm, String prompt, int maxSteps) {
        JsonNode response = llm.complete(prompt, schema(maxSteps));
        List<String> summaries = new ArrayList<>();
        response.path("steps").forEach(step -> {
            String text = step.path("text").asText("").trim();
            if (!text.isBlank()) {
                summaries.add(text);
            }
        });
        return summaries;
    }

    /**
     * 요약의 끝 단계들이 원문 마지막 단계를 담고 있는지 봅니다. 원문 마지막 단계 낱말(앞 두 글자) 가운데 하나라도
     * 요약의 마지막 세 단계에 있으면 담은 것으로 봅니다.
     * 원문 끝의 후기·권유·보관 단계("맛있게 드세요")는 요약이 빼는 게 맞으므로 그 앞의 마지막 조리 단계와 비교합니다.
     */
    static boolean coversLastStep(List<String> original, List<String> summaries) {
        // 요약이 원문보다 단계가 적을 때(합치거나 뺐을 때)만 봅니다. 칸이 남아도 끝 단계를 버린 경우가 있습니다(곱도리탕).
        if (original.isEmpty() || summaries.isEmpty() || summaries.size() >= original.size()) {
            return true;
        }
        // "팬에", "냄비에서" 같은 장소 낱말은 준비 단계에도 흔해 비교하지 않습니다
        // (2026-10-12 오징어김치전: "팬에 식용유를 두른다"에서 끝난 요약이 "팬에"만으로 통과할 수 있음).
        List<String> stems = java.util.Arrays.stream(lastCookingStep(original).replaceAll("[^가-힣 ]", " ").split("\\s+"))
                .filter(word -> word.length() >= 2)
                .filter(word -> !word.endsWith("에") && !word.endsWith("에서"))
                .map(word -> word.substring(0, 2))
                .filter(stem -> !List.of("합니", "해주", "주세", "줍니", "넣어", "그리", "이제", "마지", "다음").contains(stem))
                .distinct()
                .toList();
        if (stems.isEmpty()) {
            return true;
        }
        String tail = String.join("", summaries.subList(Math.max(0, summaries.size() - 3), summaries.size())).replaceAll("\\s+", "");
        return stems.stream().anyMatch(tail::contains);
    }

    // 원문에서 후기·권유·보관 단계를 뺀 마지막 조리 단계입니다. 없으면 원문 마지막 단계입니다.
    static String lastCookingStep(List<String> original) {
        for (int i = original.size() - 1; i >= 0; i--) {
            String step = original.get(i);
            if (step != null && !step.isBlank() && !isReviewOnlyStep(step) && !isStorageOnlyStep(step)) {
                return step;
            }
        }
        return original.get(original.size() - 1);
    }

    // 원문 소제목 번호("오이 초무침①", "시금치 ②")입니다. 번호를 쌍점으로 바꿔 소제목은 남깁니다.
    private static final java.util.regex.Pattern CIRCLED_HEADING = java.util.regex.Pattern.compile(
            "^\\s*([가-힣a-zA-Z ]{1,12}?)\\s*[①-⑳]\\s*");
    private static final java.util.regex.Pattern CIRCLED_NUMBER = java.util.regex.Pattern.compile("\\s*[①-⑳]\\s*");

    /**
     * 보여 줄 단계를 정리합니다.
     * - 요청한 요리 이름 앞에 다른 말이 붙은 이름("김치비빔국수", "간장비빔국수")이 나오는 단계는 다른 변형 레시피 설명이라
     *   뺍니다(2026-10-14 비빔국수 요약 끝에 김치·간장 비빔국수 만드는 법이 섞임). 출처 제목이 그 이름이면 빼지 않습니다.
     * - "오이 초무침① 필러로 …"는 "오이 초무침: 필러로 …"로 바꿉니다(2026-10-14 김밥 원문 단계).
     * 정리 후 두 단계보다 적게 남으면 원래 단계를 그대로 둡니다.
     */
    static List<String> tidySteps(String dishName, String sourceTitle, List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        String dish = dishName == null ? "" : dishName.replaceAll("\\s+", "");
        String title = sourceTitle == null ? "" : sourceTitle.replaceAll("\\s+", "");
        java.util.regex.Pattern variant = dish.length() >= 2
                ? java.util.regex.Pattern.compile("([가-힣]+)" + java.util.regex.Pattern.quote(dish))
                : null;
        // 제목에 요청한 이름이 단독 낱말로 있으면 기본 요리 레시피입니다. 제목에 변형 이름이 함께 나열돼 있어도
        // ("비빔국수 황금레시피 - … 김치비빔국수 간장비빔국수") 변형 단계는 뺍니다. 변형 이름만 있으면("로제떡볶이 만들기") 남깁니다.
        boolean plainDishTitle = dish.length() >= 2 && sourceTitle != null
                && java.util.regex.Pattern.compile("(^|[^가-힣])" + java.util.regex.Pattern.quote(dish)).matcher(sourceTitle).find();
        List<String> tidied = new ArrayList<>();
        for (String step : steps) {
            if (step == null) {
                continue;
            }
            if (variant != null) {
                // 띄어쓰기를 지우면 "그릇에 비빔국수를"의 "그릇에"까지 앞말로 잡히므로 원문 그대로 붙어 있는 말만 봅니다.
                java.util.regex.Matcher matcher = variant.matcher(step);
                boolean otherVariant = false;
                while (matcher.find()) {
                    String name = matcher.group(1) + dish;
                    if (plainDishTitle || !title.contains(name)) {
                        otherVariant = true;
                    }
                }
                if (otherVariant) {
                    continue;
                }
            }
            java.util.regex.Matcher heading = CIRCLED_HEADING.matcher(step);
            String cleaned = heading.find() && !heading.group(1).isBlank()
                    ? heading.group(1).trim() + ": " + step.substring(heading.end())
                    : CIRCLED_NUMBER.matcher(step).replaceAll(" ").trim();
            tidied.add(cleaned.trim());
        }
        return tidied.size() >= 2 ? List.copyOf(tidied) : steps;
    }

    // 재료를 넣는 동사("레몬즙을 섞어", "통깨를 뿌려")와 고명 표현("통깨로 고명")입니다.
    private static final java.util.regex.Pattern ADD_VERB = java.util.regex.Pattern.compile("^(?:넣|섞|뿌|올|곁들|두르|더하|버무)");
    private static final java.util.regex.Pattern GARNISH_WORD = java.util.regex.Pattern.compile("^(?:고명|장식)");
    private static final java.util.regex.Pattern AMOUNT_WORD = java.util.regex.Pattern.compile("^[0-9½⅓¼]");
    // 재료 목록과 비교하지 않을 말(조리 도구·중간 결과물·일반어)입니다.
    private static final java.util.Set<String> NOT_INGREDIENT_WORDS = java.util.Set.of(
            "양념장", "양념", "소스", "재료", "고명", "반죽", "육수", "국물", "냄비", "프라이팬", "팬", "그릇", "접시", "볼",
            "고기", "채소", "야채", "완성", "나머지", "모든", "준비한", "준비된", "썰어둔", "썰어", "다진", "손질한", "기름",
            "물기", "윗면", "가장자리", "바닥", "뚜껑", "새콤달콤", "골고루", "충분히", "한번", "다시", "함께", "위에", "모두");

    /**
     * 조리 순서에는 나오지만 재료 목록에는 없는 재료입니다. 원문 자체의 빠짐이라 고칠 수 없으니 사용자에게 알려 줍니다
     * (2026-10-14 배추찜: 레몬즙·홍고추·통깨가 단계에만 있음). 재료가 들어갈 자리만 보고, 최대 4개까지 돌려줍니다.
     */
    static List<String> stepOnlyIngredients(List<String> ingredients, List<String> steps, String title) {
        if (steps == null || steps.isEmpty() || ingredients == null || ingredients.isEmpty()) {
            return List.of();
        }
        List<String> keys = ingredients.stream().map(RecipeStepSummarizer::ingredientKey).filter(key -> key.length() >= 1).toList();
        String ingredientText = String.join(" ", ingredients).replaceAll("\\s+", "");
        String titleText = title == null ? "" : title.replaceAll("\\s+", "");
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        for (String step : steps) {
            if (step == null) {
                continue;
            }
            String[] words = step.replaceAll("[.!?]", " ").trim().split("\\s+");
            for (int i = 0; i + 1 < words.length; i++) {
                String word = words[i];
                String next = words[i + 1];
                String item;
                if (word.matches("[가-힣]{2,6}(?:을|를)") && ADD_VERB.matcher(next).find()) {
                    item = word.substring(0, word.length() - 1);
                } else if (word.matches("[가-힣]{2,6}(?:으로|로)") && GARNISH_WORD.matcher(next).find()) {
                    item = word.replaceFirst("(?:으로|로)$", "");
                } else {
                    continue;
                }
                List<String> items = new ArrayList<>(List.of(item));
                // 쉼표나 "와/과"로 앞에 이어진 재료도 봅니다. "1큰술," 같은 분량 낱말은 건너뜁니다.
                for (int j = i - 1; j >= 0; j--) {
                    String previous = words[j];
                    boolean listed = previous.endsWith(",") || previous.matches("[가-힣]{2,6}(?:와|과)");
                    if (!listed) {
                        break;
                    }
                    String cleaned = previous.replaceAll(",$", "").replaceFirst("(?:와|과)$", "");
                    if (AMOUNT_WORD.matcher(cleaned).find()) {
                        if (j - 1 < 0) {
                            break;
                        }
                        cleaned = words[--j];
                    }
                    // "끼얹고,"처럼 쉼표 앞이 동사면 재료 나열이 끝난 것입니다.
                    if (!cleaned.matches("[가-힣]{2,6}") || cleaned.matches(".*(?:고|며|서|어|아|여|게)$")) {
                        break;
                    }
                    items.add(0, cleaned);
                }
                for (String candidate : items) {
                    if (NOT_INGREDIENT_WORDS.contains(candidate) || ingredientText.contains(candidate) || titleText.contains(candidate)
                            || keys.stream().anyMatch(key -> key.length() >= 2 && (candidate.contains(key) || key.contains(candidate)))) {
                        continue;
                    }
                    found.add(candidate);
                }
            }
        }
        return found.stream().limit(4).toList();
    }

    // 원문에 없는 분량·온도·시간과, 원문과 반대로 바뀐 지시(뚜껑 열기↔덮기 등)입니다.
    static List<String> changedDetails(List<String> original, List<String> summaries) {
        List<String> changed = new ArrayList<>(introducedDetails(original, summaries));
        changed.addAll(oppositeInstructions(original, summaries));
        return changed;
    }

    /**
     * 요약에 새로 생긴 지시가 원문의 반대 상태인지 봅니다. 원문에 그 지시가 없고 반대 지시만 있을 때만 셉니다
     * (원문에 둘 다 있으면 단계마다 다르게 쓰는 것이라 판단하지 않습니다).
     */
    static List<String> oppositeInstructions(List<String> original, List<String> summaries) {
        String source = String.join(" ", original);
        String summary = String.join(" ", summaries);
        List<String> found = new ArrayList<>();
        for (java.util.regex.Pattern[] pair : OPPOSITE_INSTRUCTIONS) {
            for (int side = 0; side < 2; side++) {
                java.util.regex.Pattern introduced = pair[side];
                java.util.regex.Pattern opposite = pair[1 - side];
                java.util.regex.Matcher matcher = introduced.matcher(summary);
                if (matcher.find() && !introduced.matcher(source).find() && opposite.matcher(source).find()) {
                    found.add(matcher.group());
                }
            }
        }
        return found;
    }

    /**
     * 원문 마지막 조리 단계(두 개)에 나온 재료 가운데 요약 단계에 없는 재료입니다. 재료 이름은 분량을 떼고 마지막 낱말만 봅니다
     * ("다진 마늘 1큰술" → 마늘). "가루"와 사이시옷은 떼고 비교합니다("후춧가루" → 후추, "고춧가루" → 고추).
     * 한 글자 재료("물", "파")는 다른 낱말과 겹쳐 비교하지 않습니다.
     */
    static List<String> lostIngredients(List<String> ingredients, List<String> original, List<String> summaries) {
        if (ingredients == null || ingredients.isEmpty()) {
            return List.of();
        }
        // 원문 마지막 조리 단계 두 개에 나온 재료만 봅니다. 앞쪽의 "떡국 재료 - 가래떡 …" 같은 나열 단계는 요약이 빼는 게
        // 맞는데, 원문 전체를 보면 그 재료를 잃었다고 판단해 요약을 버리고 블로그 원문을 그대로 보여 줬습니다(2026-10-14 떡국).
        List<String> cooking = original.stream()
                .filter(step -> step != null && !step.isBlank() && !isReviewOnlyStep(step) && !isStorageOnlyStep(step))
                .toList();
        String source = String.join(" ", cooking.subList(Math.max(0, cooking.size() - 2), cooking.size())).replaceAll("\\s+", "");
        String summary = String.join(" ", summaries).replaceAll("\\s+", "");
        List<String> lost = new ArrayList<>();
        for (String line : ingredients) {
            String key = ingredientKey(line);
            if (key.length() >= 2 && source.contains(key) && !summary.contains(key) && !lost.contains(key)) {
                lost.add(key);
            }
        }
        return lost;
    }

    static String ingredientKey(String ingredientLine) {
        if (ingredientLine == null) {
            return "";
        }
        String name = ingredientLine.replaceAll("\\(.*?\\)", " ")
                .split("[0-9]|약간|적당량|조금|한\\s|두\\s|세\\s|반\\s|1/")[0]
                .replaceAll("[^가-힣 ]", " ")
                .trim();
        if (name.isEmpty()) {
            return "";
        }
        String[] words = name.split("\\s+");
        String key = words[words.length - 1];
        if (key.endsWith("가루") && key.length() > 2) {
            key = key.substring(0, key.length() - 2);
        }
        char last = key.charAt(key.length() - 1);
        // 받침 ㅅ(사이시옷)을 뗍니다: 후춧 → 후추, 고춧 → 고추.
        if (last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 == 19) {
            key = key.substring(0, key.length() - 1) + (char) (last - 19);
        }
        return key;
    }

    // 요약에서 빠진 온도·시간·불 세기입니다.
    static List<String> criticalMissing(List<String> original, List<String> summaries) {
        return missingDetails(original, summaries).stream()
                .filter(detail -> CRITICAL_DETAIL.matcher(detail).find())
                .toList();
    }

    /**
     * 요약에는 있지만 원문에는 없는 분량·온도·시간·불 세기입니다(바뀌었거나 지어낸 값).
     * 대소문자는 구분합니다("2T"(큰술)와 "2t"(작은술)는 다른 값). ℃·°C는 "도"로, "약한 불"은 "약불"로 맞춰 비교합니다.
     */
    static List<String> introducedDetails(List<String> original, List<String> summaries) {
        String source = comparable(String.join(" ", original));
        List<String> introduced = new ArrayList<>();
        for (java.util.regex.Pattern pattern : List.of(DETAIL, KOREAN_NUMBER_DETAIL)) {
            java.util.regex.Matcher matcher = pattern.matcher(String.join(" ", summaries));
            while (matcher.find()) {
                String detail = comparable(matcher.group());
                if (!source.contains(detail) && !introduced.contains(detail)) {
                    introduced.add(detail);
                }
            }
        }
        return introduced;
    }

    private static String comparable(String text) {
        return text.replaceAll("\\s+", "")
                .replace("℃", "도").replace("°C", "도")
                .replace("약한불", "약불").replace("중간불", "중불").replace("강한불", "강불").replace("센불", "강불");
    }

    static String withoutStepReference(String step) {
        return step == null ? "" : STEP_REFERENCE.matcher(step).replaceAll("").trim();
    }

    // 원문 단계에 있는 온도·시간·분량 숫자와 불 세기 표현 중 요약에 없는 것입니다.
    static List<String> missingDetails(List<String> original, List<String> summaries) {
        String source = String.join(" ", original);
        String summary = String.join(" ", summaries).replaceAll("\\s+", "");
        List<String> missing = new ArrayList<>();
        java.util.regex.Matcher matcher = DETAIL.matcher(source);
        while (matcher.find()) {
            String detail = matcher.group().replaceAll("\\s+", "");
            if (!summary.contains(detail) && !missing.contains(detail)) {
                missing.add(detail);
            }
        }
        return missing;
    }

    // 보관 기간 안내만 있고 조리 동작이 없는 단계입니다("냉장 5일, 냉동 2주 보관").
    // 조리 동작 없이 후기·권유만 있는 단계입니다(2026-10-12 북어국·오징어김치전 요약 마지막 단계).
    static boolean isReviewOnlyStep(String step) {
        if (step == null || !REVIEW_LIKE.matcher(step).find()) {
            return false;
        }
        // "완성된 ○○" 같은 이름 부분은 조리 동작으로 세지 않도록 먼저 지웁니다.
        String withoutFinishedName = step.replaceAll("완성된\\s*\\S+", "").replaceAll("\\s+", "");
        return !COOKING_ACTION.matcher(withoutFinishedName).find();
    }

    static boolean isStorageOnlyStep(String step) {
        String compact = step.replaceAll("\\s+", "");
        return compact.contains("보관") && !COOKING_ACTION.matcher(compact).find();
    }

    // 요약 칸 수: 원문 단계 수만큼, 10~15칸입니다.
    static int stepLimit(List<String> original) {
        return Math.max(MAX_STEPS, Math.min(original.size(), MAX_STEPS_LONG));
    }

    private String prompt(String dishName, List<String> steps, List<String> details, List<String> changed, boolean retry) {
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            numbered.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        return """
                [조리 단계]
                %2$s
                위 '%1$s' 조리 단계를 원문 문장을 그대로 옮기지 말고 다시 쓰세요.
                - 원문의 온도, 시간, 불 세기(약불·중불 등), 분량, 개수, 완료 상태(예: 황금빛이 될 때까지)는 빠짐없이 원문 표기 그대로 남기세요(T는 큰술, t는 작은술이라 바꾸면 안 됩니다).
                - 원문에 없는 시간이나 온도를 추측해 넣지 마세요.
                - 분량·시간·온도 숫자는 원문 표기 그대로(예: 3큰술, 1/3컵, 10분) 쓰고, "세 큰술"처럼 한글 숫자로 바꾸지 마세요.
                - "~하면 ~한다" 같은 조건과 동작의 관계를 바꾸지 마세요(예: "거품이 오르면 찬물을 붓는다"를 "거품이 오르도록 찬물을 붓는다"로 바꾸면 안 됩니다).
                - 각 단계는 80자 이내로, 따라 하기 쉽게 쓰세요.
                - 보관 기간 안내는 단계에 넣지 마세요.
                - 순서를 바꾸지 말고, 원문에 없는 재료나 단계를 추가하지 마세요.
                - 재료 대체 팁, 브랜드 추천, 사전 준비 안내 같은 비조리 설명 문구는 조리 단계에서 제외하세요.
                - 포장·선물·후기·인사, "맛있게 즐겨 보세요" 같은 권유, 가족 반응처럼 요리와 관계없는 문장은 넣지 마세요.
                - 팁이나 앞 단계를 다시 정리하는 문장은 따로 단계로 만들지 말고, 해당 단계에 합치거나 빼세요.
                - "덖다"처럼 흔하지 않은 조리 용어는 쉬운 말로 바꾸세요(예: 마른 팬에 기름 없이 볶기).
                - 가열, 젓기, 반죽, 성형 등 실제 조리 행위가 명확한 동사로 끝나도록 작성하세요.
                - 비슷한 단계는 합쳐서 최대 %3$d개로 만드세요. 준비 단계를 합쳐서라도 원문의 마지막 조리 단계까지 반드시 포함하세요.
                %4$s""".formatted(dishName, numbered, stepLimit(steps),
                details.isEmpty() ? "" : (retry
                        ? "- 이전 요약에서 다음 정보가 빠졌습니다. 해당 단계에 반드시 넣으세요: "
                        : "- 원문에 있는 다음 정보는 해당 단계에 그대로 넣으세요: ") + String.join(", ", details) + "\n")
                + (changed.isEmpty() ? "" : "- 다음 표현은 원문에 없거나 원문과 반대입니다. 원문에 적힌 분량·단위(큰술 T와 작은술 t 구분 포함)와 지시(뚜껑, 불 세기, 물 온도)를 그대로 쓰세요: "
                        + String.join(", ", changed) + "\n");
    }

    private Map<String, Object> schema(int maxSteps) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "object");
        step.put("required", List.of("text"));
        // 글자 수를 문법으로 강제하면 모델이 길게 쓸 때 문장이 중간에서 잘립니다(2026-10-12 골뱅이소면 "…먹기 좋은 크기").
        // 길이는 지시문으로만 요청합니다. 전체 출력은 num_predict로 막히고, 넘치면 응답 전체를 실패로 처리합니다.
        step.put("properties", Map.of("text", Map.of("type", "string")));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("steps", Map.of("type", "array", "maxItems", maxSteps, "items", step));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("steps"));
        schema.put("properties", props);
        return schema;
    }
}

/**
 * 웹 레시피 근거를 저장하고, 기간이 남은 근거를 출처 문서로 돌려줍니다.
 * DB 오류는 검색을 막지 않습니다(캐시를 못 쓰면 새로 검색할 뿐입니다).
 */
@Slf4j
@Service
class WebRecipeSourceStore {

    private final WebRecipeSourceRepository repository;
    private final int cacheDays;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    WebRecipeSourceStore(
            WebRecipeSourceRepository repository,
            @Value("${recipe.agent.web-source-cache-days:7}") int cacheDays) {
        this(repository, cacheDays, Clock.systemDefaultZone());
    }

    WebRecipeSourceStore(WebRecipeSourceRepository repository, int cacheDays, Clock clock) {
        this.repository = repository;
        this.cacheDays = Math.max(1, cacheDays);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Optional<RecipeSourceDocument> findFresh(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }
        try {
            // 요약이 있는 행만 대표 레시피가 될 수 있습니다(비교 출처 행은 재료와 링크만 있음).
            return repository.findByCacheKeyAndReviewStatusNotAndExpiresAtAfterOrderByQualityScoreDesc(
                            cacheKey, RecipeApprovalStatus.REJECTED, LocalDateTime.now(clock))
                    .stream()
                    .filter(row -> row.getStepSummaries() != null && !row.getStepSummaries().isEmpty())
                    .findFirst()
                    .map(this::toDocument);
        } catch (com.salus.healthytable.service.ChatCancellation.ChatCancelledException e) {
            throw e; // 사용자가 멈춘 요청은 실패로 처리하지 않고 그대로 멈춥니다.
        } catch (Exception e) {
            log.warn("[WebRecipeSourceStore] Lookup failed. failureCategory={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 대표 근거와 함께 비교한 출처(최대 2곳)를 문서로 돌려줍니다. 대표가 없으면 빈 목록입니다.
     * 비교 출처는 재료와 링크만 있고(조리 단계 없음), 대표보다 앞에 서지 않도록 신뢰도를 대표보다 낮춥니다.
     */
    @Transactional(readOnly = true)
    List<RecipeSourceDocument> findFreshWithSupporting(String cacheKey) {
        Optional<RecipeSourceDocument> primary = findFresh(cacheKey);
        if (primary.isEmpty()) {
            return List.of();
        }
        List<RecipeSourceDocument> documents = new ArrayList<>(List.of(primary.get()));
        try {
            repository.findByCacheKeyAndReviewStatusNotAndExpiresAtAfterOrderByQualityScoreDesc(
                            cacheKey, RecipeApprovalStatus.REJECTED, LocalDateTime.now(clock))
                    .stream()
                    .filter(row -> !row.getSourceUrl().equals(primary.get().url()))
                    .limit(2)
                    .map(this::toDocument)
                    .map(document -> belowPrimary(document, primary.get().sourceReliability()))
                    .forEach(documents::add);
        } catch (com.salus.healthytable.service.ChatCancellation.ChatCancelledException e) {
            throw e; // 사용자가 멈춘 요청은 실패로 처리하지 않고 그대로 멈춥니다.
        } catch (Exception e) {
            log.warn("[WebRecipeSourceStore] Supporting lookup failed. failureCategory={}", e.getClass().getSimpleName());
        }
        return documents;
    }

    static RecipeSourceDocument belowPrimary(RecipeSourceDocument document, double primaryReliability) {
        return new RecipeSourceDocument(document.sourceId(), document.sourceType(), document.title(), document.creatorName(),
                document.url(), document.content(), document.publishedAt(),
                Math.min(document.sourceReliability(), primaryReliability - 0.01));
    }

    /**
     * 대표 레시피와 비교한 출처를 저장합니다. 조리 단계는 저장하지 않고 재료(사실 정보)와 링크만 남깁니다.
     * 같은 주소가 이미 대표로 저장돼 있으면 요약을 지우지 않습니다.
     */
    @Transactional
    void saveSupporting(String cacheKey, RecipeSourceCandidate candidate) {
        saveRow(cacheKey, candidate, List.of());
    }

    /**
     * 근거를 저장합니다. 같은 요리·같은 URL이면 내용을 갱신하고 기간을 다시 늘립니다.
     * 사람이 REJECTED로 표시한 행은 되살리지 않습니다.
     */
    @Transactional
    void save(String cacheKey, RecipeSourceCandidate candidate, List<String> stepSummaries) {
        if (stepSummaries == null || stepSummaries.isEmpty()) {
            return;
        }
        saveRow(cacheKey, candidate, stepSummaries);
    }

    private void saveRow(String cacheKey, RecipeSourceCandidate candidate, List<String> stepSummaries) {
        if (cacheKey == null || cacheKey.isBlank() || candidate == null || candidate.source() == null
                || candidate.source().url() == null || candidate.source().url().isBlank()) {
            return;
        }
        try {
            String url = truncate(candidate.source().url(), 1000);
            WebRecipeSource row = repository.findByCacheKeyAndSourceUrl(cacheKey, url).orElseGet(WebRecipeSource::new);
            if (row.getReviewStatus() == RecipeApprovalStatus.REJECTED) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            RecipeEvidenceProvenance provenance = candidate.evidence() == null ? null : candidate.evidence().provenance();
            row.setCacheKey(cacheKey);
            row.setSourceUrl(url);
            row.setSourceDomain(truncate(provenance == null ? domain(url) : blank(provenance.sourceDomain(), domain(url)), 200));
            row.setSourceTitle(truncate(blank(candidate.source().title(), blank(candidate.originalRecipe().title(), "웹 레시피")), 300));
            row.setExtractionMethod(provenance == null ? "UNKNOWN" : truncate(provenance.extractionMethod(), 40));
            row.setIngredients(List.copyOf(candidate.originalRecipe().ingredients()));
            row.setServings(candidate.evidence() == null ? null : RecipeServingsScaler.validServings(candidate.evidence().servings()));
            if (!stepSummaries.isEmpty() || row.getStepSummaries() == null || row.getStepSummaries().isEmpty()) {
                row.setStepSummaries(List.copyOf(stepSummaries));
            }
            row.setContentHash(provenance == null ? null : truncate(provenance.contentHash(), 128));
            row.setQualityScore(candidate.qualityScore() == null ? 0.0 : candidate.qualityScore().totalScore());
            row.setFetchedAt(provenance == null || provenance.fetchedAt() == null ? now : provenance.fetchedAt());
            row.setExpiresAt(now.plusDays(cacheDays));
            repository.save(row);
        } catch (com.salus.healthytable.service.ChatCancellation.ChatCancelledException e) {
            throw e; // 사용자가 멈춘 요청은 실패로 처리하지 않고 그대로 멈춥니다.
        } catch (Exception e) {
            log.warn("[WebRecipeSourceStore] Save failed. failureCategory={}", e.getClass().getSimpleName());
        }
    }

    // CandidateBuilder가 읽는 출처 문서 형식으로 바꿉니다. 저장된 근거는 모두 검토 전 웹 출처입니다.
    private RecipeSourceDocument toDocument(WebRecipeSource row) {
        String content = """
                title: %s
                description:
                creator:
                sourceUrl: %s
                canonicalUrl:
                servings: %s
                ingredients:
                %s
                steps:
                %s
                """.formatted(
                RecipeDisplayTitle.of(row.getSourceTitle(), dishFromKey(row.getCacheKey())),
                row.getSourceUrl(),
                row.getServings() == null ? "" : row.getServings(),
                String.join("\n", row.getIngredients() == null ? List.of() : row.getIngredients()),
                String.join("\n", row.getStepSummaries() == null ? List.<String>of()
                        : row.getStepSummaries().stream().map(RecipeStepSummarizer::withoutStepReference).toList()));
        return new RecipeSourceDocument(
                "web-store:" + row.getId(),
                RecipeSourceType.GENERAL_WEB,
                row.getSourceTitle(),
                "",
                row.getSourceUrl(),
                content,
                row.getFetchedAt(),
                row.getQualityScore() == null ? 0.0 : row.getQualityScore());
    }

    // 조회 키("요리|작성자|모드|ko")의 첫 부분이 요청 요리 이름입니다(공백이 빠진 형태).
    private String dishFromKey(String cacheKey) {
        return cacheKey == null ? "" : cacheKey.split("\\|", 2)[0];
    }

    private String domain(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (com.salus.healthytable.service.ChatCancellation.ChatCancelledException e) {
            throw e; // 사용자가 멈춘 요청은 실패로 처리하지 않고 그대로 멈춥니다.
        } catch (Exception e) {
            return "";
        }
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

// 요약 결과입니다. REJECTED는 요약을 품질 문제로 버린 경우, UNAVAILABLE은 LLM이 없거나 호출이 실패한 경우입니다.
record SummaryOutcome(Optional<List<String>> summary, boolean rejected) {
    static SummaryOutcome ofSummary(List<String> steps) {
        return new SummaryOutcome(Optional.of(steps), false);
    }

    static SummaryOutcome ofRejected() {
        return new SummaryOutcome(Optional.empty(), true);
    }

    static SummaryOutcome ofUnavailable() {
        return new SummaryOutcome(Optional.empty(), false);
    }
}
