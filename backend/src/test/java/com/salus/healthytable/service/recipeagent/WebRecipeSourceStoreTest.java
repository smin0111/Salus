package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.domain.WebRecipeSource;
import com.salus.healthytable.repository.WebRecipeSourceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 웹 레시피 근거 저장({@link WebRecipeSourceStore}), 단계 요약({@link RecipeStepSummarizer}),
 * 그리고 저장 근거가 있으면 다시 검색하지 않는 흐름을 확인합니다. DB와 Ollama는 쓰지 않습니다.
 */
class WebRecipeSourceStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final String KEY = "두쫀쿠||create|ko";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebRecipeSourceRepository repository = mock(WebRecipeSourceRepository.class);
    private final WebRecipeSourceStore store = new WebRecipeSourceStore(repository, 7, CLOCK);

    // 저장은 검토 전(UNVERIFIED) 근거로 들어가고, 원문 단계가 아니라 요약 단계만 담습니다.
    @Test
    void savesUnverifiedEvidenceWithStepSummariesOnly() {
        when(repository.findByCacheKeyAndSourceUrl(eq(KEY), anyString())).thenReturn(Optional.empty());

        store.save(KEY, candidate(List.of("원문 1단계 문장 그대로", "원문 2단계 문장 그대로")), List.of("요약 1", "요약 2"));

        ArgumentCaptor<WebRecipeSource> saved = ArgumentCaptor.forClass(WebRecipeSource.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getReviewStatus()).isEqualTo(RecipeApprovalStatus.UNVERIFIED);
        assertThat(saved.getValue().getStepSummaries()).containsExactly("요약 1", "요약 2");
        assertThat(saved.getValue().getIngredients()).containsExactly("마시멜로우 150g", "버터 30g");
        assertThat(saved.getValue().getSourceUrl()).isEqualTo("https://cookpick.kr/dujjonku");
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusDays(7));
    }

    // 사람이 제외(REJECTED)한 근거는 다시 저장해도 되살리지 않습니다.
    @Test
    void doesNotReviveRejectedEvidence() {
        WebRecipeSource rejected = new WebRecipeSource();
        rejected.setReviewStatus(RecipeApprovalStatus.REJECTED);
        when(repository.findByCacheKeyAndSourceUrl(eq(KEY), anyString())).thenReturn(Optional.of(rejected));

        store.save(KEY, candidate(List.of("a", "b")), List.of("요약 1", "요약 2"));

        verify(repository, never()).save(any());
    }

    // 요약이 없으면 저장하지 않습니다(원문 단계를 대신 저장하지 않음).
    @Test
    void doesNotSaveWithoutSummaries() {
        store.save(KEY, candidate(List.of("a", "b")), List.of());

        verify(repository, never()).save(any());
    }

    // 저장된 근거는 외부 웹 출처 문서로, 요약 단계와 원문 링크를 담아 돌아옵니다.
    @Test
    void findFreshReturnsStoredEvidenceAsWebDocument() {
        WebRecipeSource row = new WebRecipeSource();
        row.setId(3L);
        row.setSourceTitle("두쫀쿠 레시피");
        row.setSourceUrl("https://cookpick.kr/dujjonku");
        row.setIngredients(List.of("마시멜로우 150g"));
        row.setStepSummaries(List.of("요약 1", "요약 2"));
        row.setQualityScore(0.8);
        row.setFetchedAt(NOW.minusDays(1));
        when(repository.findByCacheKeyAndReviewStatusNotAndExpiresAtAfterOrderByQualityScoreDesc(
                KEY, RecipeApprovalStatus.REJECTED, NOW)).thenReturn(List.of(row));

        RecipeSourceDocument document = store.findFresh(KEY).orElseThrow();

        assertThat(document.sourceType()).isEqualTo(RecipeSourceType.GENERAL_WEB);
        assertThat(document.url()).isEqualTo("https://cookpick.kr/dujjonku");
        assertThat(document.content()).contains("마시멜로우 150g").contains("요약 1").contains("요약 2");
    }

    // DB 오류는 검색을 막지 않습니다. 캐시를 못 쓰면 새로 검색할 뿐입니다.
    @Test
    void lookupFailureFallsBackToFreshSearch() {
        when(repository.findByCacheKeyAndReviewStatusNotAndExpiresAtAfterOrderByQualityScoreDesc(any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(store.findFresh(KEY)).isEmpty();
    }

    // 본문 추출 단계는 이미 요약이라 그대로 쓰고, 구조화 출처 단계는 LLM으로 요약합니다.
    @Test
    void summarizerPassesThroughTextExtractionAndSummarizesStructuredSteps() {
        List<String> prompts = new ArrayList<>();
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> {
            prompts.add(prompt);
            return objectMapper.valueToTree(Map.of("steps", List.of(Map.of("text", "버터를 녹인다"), Map.of("text", "마시멜로우를 섞는다"))));
        });

        assertThat(summarizer.summarize("두쫀쿠", textCandidate(List.of("요약된 단계 1", "요약된 단계 2"))))
                .contains(List.of("요약된 단계 1", "요약된 단계 2"));
        assertThat(prompts).isEmpty();
        // 원문 단계 수와 같은 요약입니다(단계를 줄이면 끝 단계 검사를 하므로, 이 테스트는 통과 경로만 봅니다).
        assertThat(summarizer.summarize("두쫀쿠", candidate(List.of("원문 1", "원문 2"))))
                .contains(List.of("버터를 녹인다", "마시멜로우를 섞는다"));
        assertThat(prompts).hasSize(1);
    }

    // 끝 단계만 빠진 요약은 다시 묻지 않고 버립니다(원문 단계 사용). 2026-10-13 실전 평가에서 이 재요청이 25~37초씩 걸려
    // 잡채·스모어쿠키·김치찌개가 60초를 넘김.
    @Test
    void summarizerDropsSummaryWithoutRetryWhenOnlyLastStepIsLost() {
        List<String> steps = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            steps.add("재료 " + i + "를 손질한다.");
        }
        steps.add("팬에 반죽을 얇게 펴서 노릇하게 부친다.");
        List<String> prompts = new ArrayList<>();
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> {
            prompts.add(prompt);
            List<Map<String, String>> summary = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                summary.add(Map.of("text", "재료 " + i + "를 손질한다."));
            }
            return objectMapper.valueToTree(Map.of("steps", summary));
        });

        assertThat(summarizer.summarize("김치전", candidate(steps))).isEmpty();
        assertThat(prompts).hasSize(1);
    }

    // 원문의 온도·시간·불 세기를 처음부터 알려 주고, 그래도 빠지면 빠진 값을 알려 한 번 다시 요약합니다. 보관 안내 단계는 뺍니다.
    @Test
    void summarizerRetriesWhenDetailsAreLostAndDropsStorageSteps() {
        List<String> prompts = new ArrayList<>();
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> {
            prompts.add(prompt);
            List<Map<String, String>> steps = prompts.size() == 1
                    ? List.of(Map.of("text", "카다이프면을 오븐에 굽는다"), Map.of("text", "마시멜로우를 녹인다"))
                    : List.of(Map.of("text", "카다이프면을 오븐 170도에서 황금빛이 될 때까지 굽는다"),
                    Map.of("text", "약불에서 버터와 마시멜로우를 저으며 녹인다"),
                    Map.of("text", "냉장 5일, 냉동 2주 보관"));
            return objectMapper.valueToTree(Map.of("steps", steps));
        });

        List<String> result = summarizer.summarize("두쫀쿠", candidate(List.of(
                "카다이프면을 오븐 170도에서 황금빛이 될 때까지 바삭하게 굽는다",
                "냄비에 버터를 녹이고 마시멜로우를 약불에서 저어가며 녹인다",
                "냉장 5일 냉동 2주 가능"))).orElseThrow();

        assertThat(prompts).hasSize(2);
        // 첫 요청부터 원문의 온도·불 세기를 반드시 넣을 정보로 알려 줍니다(재요청을 줄이기 위해).
        assertThat(prompts.get(0)).contains("다음 정보는 해당 단계에 그대로 넣으세요: 170도, 약불");
        assertThat(prompts.get(1)).contains("이전 요약에서 다음 정보가 빠졌습니다").contains("170도").contains("약불");
        assertThat(result).containsExactly("카다이프면을 오븐 170도에서 황금빛이 될 때까지 굽는다", "약불에서 버터와 마시멜로우를 저으며 녹인다");
    }

    // 분량만 빠졌으면 다시 묻지 않습니다(재료 목록에 이미 있음). 온도·시간·불 세기가 빠졌을 때만 다시 묻습니다.
    @Test
    void summarizerDoesNotRetryForAmountsOnly() {
        List<String> prompts = new ArrayList<>();
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> {
            prompts.add(prompt);
            return objectMapper.valueToTree(Map.of("steps", List.of(
                    Map.of("text", "반죽에 버터를 넣고 치댄다"), Map.of("text", "200도 오븐에서 15분 굽는다"))));
        });

        List<String> result = summarizer.summarize("소금빵", candidate(List.of(
                "반죽에 버터 20g과 소금 3g을 넣고 치댄다", "200도 오븐에서 15분 굽는다"))).orElseThrow();

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("20g").contains("200도").contains("15분");
        assertThat(result).hasSize(2);
    }

    // 모델이 같은 문장을 되풀이하면 한 번만 남깁니다.
    @Test
    void summarizerDropsRepeatedSteps() {
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> objectMapper.valueToTree(Map.of("steps", List.of(
                Map.of("text", "소고기를 참기름에 볶는다"), Map.of("text", "국물이 끓으면 대파를 넣는다"),
                Map.of("text", "국물이 끓으면 대파를 넣는다"), Map.of("text", " 국물이 끓으면 대파를 넣는다 ")))));

        assertThat(summarizer.summarize("소고기무국", candidate(List.of("원문 1", "원문 2", "원문 3"))).orElseThrow())
                .containsExactly("소고기를 참기름에 볶는다", "국물이 끓으면 대파를 넣는다");
    }

    // 원문에 없는 분량이 생기면(짬뽕탕: 원문 "고추가루2T"(큰술)를 "2t"(작은술)로 씀) 다시 묻지 않고 요약을 버립니다
    // (호출하는 쪽이 원문 단계를 씀). 2026-10-14 집계에서 이 경우 재요청은 36번 중 3번만 성공했습니다.
    @Test
    void summarizerRejectsChangedAmountsWithoutRetry() {
        List<String> prompts = new ArrayList<>();
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> {
            prompts.add(prompt);
            return objectMapper.valueToTree(Map.of("steps", List.of(
                    Map.of("text", "고추가루 2t와 굴소스 2t를 넣고 섞는다"), Map.of("text", "육수를 붓고 끓인다"))));
        });

        SummaryOutcome outcome = summarizer.summarizeOutcome("짬뽕탕", candidate(List.of(
                "볶아졌다면 이제 고추가루2T, 굴소스2T 넣어줍니다.", "육수를 부어 끓여줍니다.")));
        assertThat(outcome.summary()).isEmpty();
        assertThat(outcome.rejected()).isTrue();
        assertThat(prompts).hasSize(1);
    }

    // 원문 한 문장을 둘로 나누다 앞부분이 빠진 단계("동안 토마토를 …")가 있는 요약은 버리고 원문 단계를 씁니다
    // (2026-10-17 샌드위치).
    @Test
    void summarizerRejectsStepsCutMidSentence() {
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> objectMapper.valueToTree(Map.of("steps", List.of(
                Map.of("text", "식빵을 토스트한다"),
                Map.of("text", "동안 토마토를 슬라이스한다"),
                Map.of("text", "재료를 쌓아 샌드위치를 만든다")))));

        SummaryOutcome outcome = summarizer.summarizeOutcome("샌드위치", candidate(List.of(
                "식빵을 토스트하는 동안 토마토를 슬라이스해주세요.", "재료를 쌓아 샌드위치를 만들어 줍니다.")));
        assertThat(outcome.summary()).isEmpty();
        assertThat(outcome.rejected()).isTrue();

        assertThat(RecipeStepSummarizer.startsMidSentence("동안 토마토를 썬다")).isTrue();
        assertThat(RecipeStepSummarizer.startsMidSentence("2. 후에 소스를 바른다")).isTrue();
        assertThat(RecipeStepSummarizer.startsMidSentence("식빵을 굽는 동안 토마토를 썬다")).isFalse();
        assertThat(RecipeStepSummarizer.startsMidSentence("후추를 뿌린다")).isFalse();
        assertThat(RecipeStepSummarizer.startsMidSentence("뒤집어서 굽는다")).isFalse();
    }

    // 원문 표기를 지킨 요약은 그대로 쓰고, 사이트 단계 번호("STEP 1의")는 지웁니다.
    @Test
    void summarizerKeepsOriginalAmountsAndDropsStepReferences() {
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> objectMapper.valueToTree(Map.of("steps", List.of(
                Map.of("text", "고추가루 2T와 굴소스 2T를 넣고 섞는다"),
                Map.of("text", "STEP 1의 육수를 붓고 끓인다")))));

        assertThat(summarizer.summarize("짬뽕탕", candidate(List.of(
                "볶아졌다면 이제 고추가루2T, 굴소스2T 넣어줍니다.", "육수를 부어 끓여줍니다."))).orElseThrow())
                .containsExactly("고추가루 2T와 굴소스 2T를 넣고 섞는다", "육수를 붓고 끓인다");
    }

    @Test
    void introducedDetailsKeepCaseAndNormalizeEquivalentNotation() {
        assertThat(RecipeStepSummarizer.introducedDetails(List.of("고추가루2T"), List.of("고추가루 2t"))).containsExactly("2t");
        assertThat(RecipeStepSummarizer.introducedDetails(List.of("170℃에서 굽고 약한 불로 줄인다"), List.of("170도에서 굽고 약불로 줄인다")))
                .isEmpty();
        assertThat(RecipeStepSummarizer.introducedDetails(List.of("오븐에 굽는다"), List.of("오븐 180도에서 15분 굽는다")))
                .containsExactly("180도", "15분");
        // 한글로 풀어 쓴 분량도 원문에 없으면 바뀐 값입니다(골뱅이소면: "고추장 3큰술" → "고추장 세 번 큰술").
        assertThat(RecipeStepSummarizer.introducedDetails(List.of("고추장 3큰술(1/3컵)"), List.of("고추장 세 번 큰술(⅓컵)")))
                .contains("세번큰술");
        assertThat(RecipeStepSummarizer.introducedDetails(List.of("깻잎 한 묶음과 마늘 한 스푼"), List.of("깻잎 한 묶음, 마늘 한 스푼")))
                .isEmpty();
        assertThat(RecipeStepSummarizer.introducedDetails(List.of("양배추 한 줌"), List.of("양배추 한 잔"))).containsExactly("한잔");
    }

    // 조리 동작 없이 후기·권유만 있는 단계는 뺍니다(2026-10-12 북어국·오징어김치전 요약 마지막 단계).
    // 요약이 칸을 준비 단계로 채우고 마지막 조리 단계를 빼먹으면 잡습니다(2026-10-12 오징어김치전).
    // 원문 끝의 후기·권유 단계는 요약이 빼는 게 맞으므로 그 앞의 조리 단계와 비교합니다.
    @Test
    void coversLastStepComparesWithLastCookingStep() {
        List<String> original = new java.util.ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            original.add("재료 " + i + "를 손질한다.");
        }
        original.add("팬에 반죽을 얇게 펴서 노릇하게 부친다.");
        List<String> prepOnly = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            prepOnly.add("재료 " + i + "를 손질한다.");
        }
        assertThat(RecipeStepSummarizer.coversLastStep(original, prepOnly)).isFalse();
        // "팬에" 같은 장소 낱말 하나만 겹치는 준비 단계로는 통과하지 않습니다.
        List<String> endsAtOil = new java.util.ArrayList<>(prepOnly.subList(0, 9));
        endsAtOil.add("팬에 식용유를 두른다.");
        assertThat(RecipeStepSummarizer.coversLastStep(original, endsAtOil)).isFalse();
        // 마무리 단계를 다른 말로 줄여도 핵심 낱말 하나가 남으면 담은 것으로 봅니다.
        List<String> japchae = new java.util.ArrayList<>(original.subList(0, 11));
        japchae.add("참기름, 깨를 넣고 버무려 완성한다.");
        List<String> japchaeSummary = new java.util.ArrayList<>(prepOnly.subList(0, 9));
        japchaeSummary.add("참기름과 깨를 뿌려 마무리합니다.");
        assertThat(RecipeStepSummarizer.coversLastStep(japchae, japchaeSummary)).isTrue();

        List<String> withLast = new java.util.ArrayList<>(prepOnly.subList(0, 9));
        withLast.add("팬에 반죽을 펴서 노릇하게 부친다.");
        assertThat(RecipeStepSummarizer.coversLastStep(original, withLast)).isTrue();

        List<String> endsWithReview = new java.util.ArrayList<>(original);
        endsWithReview.add("완성된 오징어 김치전을 접시에 담아 맛있게 즐깁니다.");
        assertThat(RecipeStepSummarizer.lastCookingStep(endsWithReview)).isEqualTo("팬에 반죽을 얇게 펴서 노릇하게 부친다.");
        assertThat(RecipeStepSummarizer.coversLastStep(endsWithReview, withLast)).isTrue();
    }

    // 2026-10-14 곱도리탕(원문 13단계): 10칸에 맞추다 마지막 두 단계(곱창 자르기, 대파·청양고추·후추 넣기)를 버렸고,
    // "끓여" 같은 흔한 동사 때문에 끝 단계 검사도 통과했습니다. 뚜껑도 "연 채"를 "덮어"로 바꿨습니다.
    // 아래 단계는 원문 구조만 따른 테스트용 문장입니다(제3자 원문은 저장소에 두지 않습니다).
    private static final List<String> STEW_INGREDIENTS = List.of("닭고기 500g", "곱창 200g", "대파 2대", "청양고추 4개",
            "간장 120ml", "다진 마늘 1큰술", "후춧가루 약간", "물 500ml");
    private static final List<String> STEW_STEPS = List.of(
            "닭고기를 쌀뜨물에 담가 둔다.", "두꺼운 부위에 칼집을 낸다.", "대파와 청양고추를 썬다.",
            "팬에 닭고기를 볶아 기름을 낸다.", "나온 기름을 닦는다.", "물을 붓고 뚜껑을 연 채 센 불로 끓인다.",
            "기름을 걷어 내며 끓인다.", "감자를 넣고 15분 끓인다.", "간장과 다진 마늘을 넣고 5분 끓인다.",
            "고춧가루를 넣고 섞는다.", "곱창을 넣고 15분 끓인다.", "곱창을 먹기 좋게 자른다.",
            "대파와 청양고추, 후추를 넣고 한소끔 더 끓여 마무리한다.");

    @Test
    void summaryChecksCatchLostLastStepIngredientsAndOppositeLidInstruction() {
        List<String> summary = List.of(
                "닭고기를 쌀뜨물에 담가 둔다.", "두꺼운 부위에 칼집을 낸다.", "대파와 청양고추를 썬다.",
                "닭고기를 볶아 기름을 내고 닦는다.", "물을 붓고 뚜껑을 덮어 센 불로 끓인다.", "기름을 걷어 내며 끓인다.",
                "감자를 넣고 15분 끓인다.", "간장과 다진 마늘을 넣고 5분 끓인다.", "고춧가루를 넣고 섞는다.",
                "곱창을 넣고 15분 끓여 익힌다.");

        assertThat(RecipeStepSummarizer.stepLimit(STEW_STEPS)).isEqualTo(13);
        assertThat(RecipeStepSummarizer.stepLimit(List.of("a", "b"))).isEqualTo(10);
        assertThat(RecipeStepSummarizer.stepLimit(java.util.Collections.nCopies(31, "a"))).isEqualTo(15);
        assertThat(RecipeStepSummarizer.lostIngredients(STEW_INGREDIENTS, STEW_STEPS, summary)).containsExactly("후추");
        assertThat(RecipeStepSummarizer.oppositeInstructions(STEW_STEPS, summary)).containsExactly("뚜껑을 덮");
        assertThat(RecipeStepSummarizer.changedDetails(STEW_STEPS, summary)).contains("뚜껑을 덮");

        // 원문 표현을 지키면 잡지 않습니다(오탐 방지): 같은 뚜껑 지시, 재료를 다른 말 없이 유지, 원문에 둘 다 있는 불 세기.
        List<String> faithful = new java.util.ArrayList<>(summary);
        faithful.set(4, "물을 붓고 뚜껑을 연 채 센 불로 끓인다.");
        faithful.set(9, "곱창을 넣고 15분 끓인 뒤 자르고, 대파·청양고추·후추를 넣어 한소끔 끓인다.");
        assertThat(RecipeStepSummarizer.lostIngredients(STEW_INGREDIENTS, STEW_STEPS, faithful)).isEmpty();
        assertThat(RecipeStepSummarizer.oppositeInstructions(STEW_STEPS, faithful)).isEmpty();
        assertThat(RecipeStepSummarizer.oppositeInstructions(
                List.of("센 불로 끓이다가 약불로 줄인다."), List.of("약불로 줄여 끓인다."))).isEmpty();
        assertThat(RecipeStepSummarizer.oppositeInstructions(
                List.of("중약불에서 볶는다."), List.of("센 불에서 볶는다."))).isEmpty();
        // 앞쪽 재료 나열 단계에만 나온 재료는 요약이 빼도 잃었다고 보지 않습니다(2026-10-14 떡국).
        assertThat(RecipeStepSummarizer.lostIngredients(List.of("가래떡 2인분", "참기름 1스푼", "대파 조금"),
                List.of("떡국 재료 - 가래떡, 소고기, 대파", "소고기 밑간 - 참기름, 다진마늘", "육수를 붓고 떡국떡을 넣어 끓인다.",
                        "대파를 넣고 한소끔 끓여 완성한다."),
                List.of("소고기에 밑간을 한다.", "육수에 떡국떡을 넣고 끓인다.", "대파를 넣고 한소끔 끓인다."))).isEmpty();
        assertThat(RecipeStepSummarizer.ingredientKey("후춧가루 약간")).isEqualTo("후추");
        assertThat(RecipeStepSummarizer.ingredientKey("다진 마늘 1큰술")).isEqualTo("마늘");
        assertThat(RecipeStepSummarizer.ingredientKey("고춧가루 3큰술")).isEqualTo("고추");
        assertThat(RecipeStepSummarizer.ingredientKey("삼겹살 200g (또는 목살)")).isEqualTo("삼겹살");
    }

    // 끝 단계 재료가 빠진 요약만 남으면(다른 문제 없음) 다시 묻지 않고 버려 원문 단계를 씁니다.
    @Test
    void summarizerRejectsSummaryThatDropsIngredientsOfLastSteps() {
        List<String> prompts = new ArrayList<>();
        RecipeStepSummarizer summarizer = summarizer((prompt, schema) -> {
            prompts.add(prompt);
            return objectMapper.valueToTree(Map.of("steps", List.of(
                    Map.of("text", "닭고기를 쌀뜨물에 담갔다가 칼집을 낸다."), Map.of("text", "대파와 청양고추를 썬다."),
                    Map.of("text", "닭고기를 볶아 기름을 닦고 물을 부어 뚜껑을 연 채 센 불로 끓인다."),
                    Map.of("text", "감자를 넣고 15분, 간장과 다진 마늘을 넣고 5분 끓인다."),
                    Map.of("text", "곱창을 넣고 15분 끓인다."))));
        });

        RecipeSourceCandidate stew = candidateWithIngredients(STEW_INGREDIENTS, STEW_STEPS);
        assertThat(summarizer.summarizeOutcome("곱도리탕", stew).rejected()).isTrue();
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("최대 13개");
    }

    // 다른 변형 레시피 단계는 빼고, 원문 소제목 번호는 쌍점으로 정리합니다(2026-10-14 비빔국수·김밥).
    @Test
    void tidyStepsDropsOtherVariantsAndCleansCircledHeadings() {
        assertThat(RecipeStepSummarizer.tidySteps("비빔국수", "비빔국수 황금레시피", List.of(
                "양념장을 만든다.", "소면을 삶아 헹군다.", "그릇에 비빔국수를 담는다.",
                "김치비빔국수는 기본 양념에 김치를 넣어 비빈다.", "간장, 참기름으로 담백한 간장비빔국수 양념을 만든다.")))
                .containsExactly("양념장을 만든다.", "소면을 삶아 헹군다.", "그릇에 비빔국수를 담는다.");
        // 제목에 기본 요리 이름이 단독으로 있으면 제목에 변형 이름이 나열돼 있어도 변형 단계는 뺍니다(요리픽 비빔국수 제목).
        assertThat(RecipeStepSummarizer.tidySteps("비빔국수", "비빔국수 황금레시피 - 양념장 만드는법 김치비빔국수 간장비빔국수", List.of(
                "양념장을 만든다.", "소면을 삶는다.", "김치비빔국수는 김치를 넣어 비빈다.")))
                .containsExactly("양념장을 만든다.", "소면을 삶는다.");
        // 출처 제목이 변형 이름이면 그 요리 단계입니다.
        assertThat(RecipeStepSummarizer.tidySteps("떡볶이", "로제떡볶이 만들기", List.of(
                "떡을 불린다.", "로제떡볶이 소스를 끓인다.")))
                .containsExactly("떡을 불린다.", "로제떡볶이 소스를 끓인다.");
        assertThat(RecipeStepSummarizer.tidySteps("김밥", "김밥 만들기", List.of(
                "오이 초무침① 필러로 오이 껍질을 벗긴다.", "시금치② 데친 시금치를 무친다.", "③ 김 위에 밥을 편다.")))
                .containsExactly("오이 초무침: 필러로 오이 껍질을 벗긴다.", "시금치: 데친 시금치를 무친다.", "김 위에 밥을 편다.");
        // 정리 후 너무 적게 남으면 원래 단계를 둡니다.
        assertThat(RecipeStepSummarizer.tidySteps("국수", "국수", List.of("잔치국수 육수를 낸다.", "비빔국수 양념을 만든다.")))
                .containsExactly("잔치국수 육수를 낸다.", "비빔국수 양념을 만든다.");
    }

    // 조리 순서에만 나오는 재료를 찾습니다(2026-10-14 배추찜 원문: 레몬즙·홍고추·통깨가 재료 목록에 없음).
    @Test
    void findsIngredientsThatAppearOnlyInSteps() {
        List<String> ingredients = List.of("알배기 배추 1통", "대패 삼겹살 300g", "숙주 1줌", "화이트 와인 1/2컵", "소금 1/2작은술",
                "후춧가루 1꼬집", "간장 3큰술", "식초 2큰술", "설탕 1큰술", "쪽파 2대");
        List<String> steps = List.of(
                "숙주 위에 배추와 대패 삼겹살을 켜켜이 쌓고, 한 층마다 소금과 후추를 뿌린다.",
                "간장 3큰술, 식초 2큰술, 설탕 1큰술, 레몬즙을 섞어 새콤달콤 양념장을 만든다.",
                "완성된 배추찜 위에 양념장을 끼얹고, 쪽파, 홍고추, 통깨로 고명을 올린다.");

        assertThat(RecipeStepSummarizer.stepOnlyIngredients(ingredients, steps, "삼겹살 배추찜"))
                .containsExactly("레몬즙", "홍고추", "통깨");
        // 재료 목록에 다 있으면 비어 있습니다.
        assertThat(RecipeStepSummarizer.stepOnlyIngredients(List.of("소면 200g", "고추장 2큰술", "참기름 1큰술"),
                List.of("고추장과 참기름을 섞어 양념장을 만든다.", "소면에 양념장을 넣어 비빈다."), "비빔국수")).isEmpty();
    }

    @Test
    void reviewOnlyStepsAreDropped() {
        assertThat(RecipeStepSummarizer.isReviewOnlyStep("완성된 북어국은 아이들과 신랑 모두 좋아하며, 개운한 맛으로 만족감을 얻습니다.")).isTrue();
        assertThat(RecipeStepSummarizer.isReviewOnlyStep("완성된 오징어 김치전을 접시에 담아 맛있게 즐깁니다.")).isTrue();
        assertThat(RecipeStepSummarizer.isReviewOnlyStep("바삭함이 생명인 김치전을 만들어 쫄깃하고 고소한 맛을 느껴보세요.")).isTrue();
        assertThat(RecipeStepSummarizer.isReviewOnlyStep("약불에서 끓여 따뜻하게 즐기세요.")).isFalse();
        assertThat(RecipeStepSummarizer.isReviewOnlyStep("반죽을 팬에 얇게 펴서 굽는다.")).isFalse();
    }

    @Test
    void detectsMissingDetailsAndStorageOnlySteps() {
        assertThat(RecipeStepSummarizer.missingDetails(
                List.of("오븐 170도에서 10분 굽고 약불에서 녹인다"), List.of("오븐 170도에서 굽는다")))
                .containsExactly("10분", "약불");
        assertThat(RecipeStepSummarizer.isStorageOnlyStep("냉장 5일, 냉동 2주 보관")).isTrue();
        assertThat(RecipeStepSummarizer.isStorageOnlyStep("냉동실에 보관해 30분간 굳힌다")).isFalse();
    }

    // 요약 LLM이 없거나 실패하면 요약 없음입니다.
    @Test
    void summarizerReturnsEmptyWithoutLlmOrOnFailure() {
        assertThat(summarizer(null).summarize("두쫀쿠", candidate(List.of("a", "b")))).isEmpty();
        assertThat(summarizer((prompt, schema) -> {
            throw new IllegalStateException("timeout");
        }).summarize("두쫀쿠", candidate(List.of("a", "b")))).isEmpty();
    }

    // 저장된 근거가 있으면 웹 검색을 하지 않습니다. 없으면 검색하고, 요약된 출처 하나만 쓰고 저장합니다.
    @Test
    void compositeUsesStoredEvidenceBeforeSearchingAndStoresSummarizedResult() {
        InternalRecipeSourceDiscoveryAdapter internal = mock(InternalRecipeSourceDiscoveryAdapter.class);
        YouTubeRecipeSourceDiscoveryAdapter youtube = mock(YouTubeRecipeSourceDiscoveryAdapter.class);
        WebRecipeSearchPort search = mock(WebRecipeSearchPort.class);
        StructuredRecipePageAdapter pages = mock(StructuredRecipePageAdapter.class);
        WebRecipeSourceStore mockStore = mock(WebRecipeSourceStore.class);
        RecipeStepSummarizer summarizer = mock(RecipeStepSummarizer.class);
        when(internal.search(any(), any())).thenReturn(List.of());
        when(youtube.search(any(), any())).thenReturn(List.of());
        CompositeRecipeSourceDiscoveryAdapter composite = new CompositeRecipeSourceDiscoveryAdapter(
                internal, search, pages, new InMemoryRecipeSourceCache(), youtube, mockStore, summarizer);
        org.springframework.test.util.ReflectionTestUtils.setField(composite, "webSourceEnabled", true);
        RecipeResearchPlan plan = new RecipeResearchPlan(
                "두쫀쿠", "", RecipeRequestMode.CREATE, false, true, List.of("두쫀쿠 레시피"), 3, 5);

        RecipeSourceDocument stored = new RecipeSourceDocument("web-store:1", RecipeSourceType.GENERAL_WEB,
                "두쫀쿠 레시피", "", "https://cookpick.kr/dujjonku", "title: 두쫀쿠", NOW, 0.8);
        when(mockStore.findFreshWithSupporting(anyString())).thenReturn(List.of(stored));
        assertThat(composite.search(plan, UserRecipeContext.empty(1L))).extracting(RecipeSourceDocument::sourceId)
                .contains("web-store:1");
        verify(search, never()).search(anyList(), anyInt());
        // 저장된 레시피로 답할 때는 유튜브 할당량을 쓰지 않습니다(2026-10-14 저장분 응답마다 유튜브 검색 3회).
        verify(youtube, never()).search(any(), any());

        RecipeResearchPlan other = new RecipeResearchPlan(
                "버터떡", "", RecipeRequestMode.CREATE, false, true, List.of("버터떡 레시피"), 3, 5);
        when(mockStore.findFreshWithSupporting(anyString())).thenReturn(List.of());
        RecipeSourceCandidate found = candidate(List.of("원문 1", "원문 2"));
        when(pages.collect(any(), any())).thenReturn(List.of(found, candidate(List.of("다른 원문 1", "다른 원문 2"))));
        when(summarizer.summarizeOutcome(eq("버터떡"), eq(found))).thenReturn(SummaryOutcome.ofSummary(List.of("요약 1", "요약 2")));

        List<RecipeSourceDocument> documents = composite.search(other, UserRecipeContext.empty(1L));

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).content()).contains("요약 1").doesNotContain("원문 1");
        verify(mockStore).save(anyString(), eq(found), eq(List.of("요약 1", "요약 2")));
    }

    // 요약을 품질 문제로 버렸으면 원문 단계를 그대로 저장해, 서버를 재시작해도 같은 요약(70~90초)을 되풀이하지 않습니다
    // (2026-10-13 실전 평가: 잡채·참치김치찌개·차돌된장찌개). LLM이 없거나 실패했으면 저장하지 않습니다.
    @Test
    void compositeStoresOriginalStepsOnlyWhenSummaryWasRejected() {
        InternalRecipeSourceDiscoveryAdapter internal = mock(InternalRecipeSourceDiscoveryAdapter.class);
        YouTubeRecipeSourceDiscoveryAdapter youtube = mock(YouTubeRecipeSourceDiscoveryAdapter.class);
        WebRecipeSearchPort search = mock(WebRecipeSearchPort.class);
        StructuredRecipePageAdapter pages = mock(StructuredRecipePageAdapter.class);
        WebRecipeSourceStore mockStore = mock(WebRecipeSourceStore.class);
        RecipeStepSummarizer summarizer = mock(RecipeStepSummarizer.class);
        when(internal.search(any(), any())).thenReturn(List.of());
        when(youtube.search(any(), any())).thenReturn(List.of());
        when(mockStore.findFreshWithSupporting(anyString())).thenReturn(List.of());
        CompositeRecipeSourceDiscoveryAdapter composite = new CompositeRecipeSourceDiscoveryAdapter(
                internal, search, pages, new InMemoryRecipeSourceCache(), youtube, mockStore, summarizer);
        org.springframework.test.util.ReflectionTestUtils.setField(composite, "webSourceEnabled", true);

        RecipeSourceCandidate rejected = candidate(List.of("원문 1", "원문 2"));
        when(pages.collect(any(), any())).thenReturn(List.of(rejected));
        when(summarizer.summarizeOutcome(anyString(), eq(rejected))).thenReturn(SummaryOutcome.ofRejected());
        List<RecipeSourceDocument> documents = composite.search(new RecipeResearchPlan(
                "잡채", "", RecipeRequestMode.CREATE, false, true, List.of("잡채 레시피"), 3, 5), UserRecipeContext.empty(1L));
        assertThat(documents.get(0).content()).contains("원문 1").contains("원문 2");
        verify(mockStore).save(anyString(), eq(rejected), eq(List.of("원문 1", "원문 2")));

        RecipeSourceCandidate unavailable = candidate(List.of("다른 원문 1", "다른 원문 2"));
        when(pages.collect(any(), any())).thenReturn(List.of(unavailable));
        when(summarizer.summarizeOutcome(anyString(), eq(unavailable))).thenReturn(SummaryOutcome.ofUnavailable());
        composite.search(new RecipeResearchPlan(
                "버터떡", "", RecipeRequestMode.CREATE, false, true, List.of("버터떡 레시피"), 3, 5), UserRecipeContext.empty(1L));
        verify(mockStore, never()).save(anyString(), eq(unavailable), anyList());
    }

    // 대표 레시피와 함께 다른 사이트의 출처(최대 2곳)를 재료와 링크만으로 비교 출처로 담고 저장합니다.
    // 비교 출처는 조리 단계를 담지 않고, 대표보다 앞에 서지 않습니다(2026-10-12 사용자 요청: 2~3곳에서 찾아 답하기).
    @Test
    void compositeKeepsUpToTwoSupportingSourcesBehindTheSummarizedPrimary() {
        InternalRecipeSourceDiscoveryAdapter internal = mock(InternalRecipeSourceDiscoveryAdapter.class);
        YouTubeRecipeSourceDiscoveryAdapter youtube = mock(YouTubeRecipeSourceDiscoveryAdapter.class);
        WebRecipeSearchPort search = mock(WebRecipeSearchPort.class);
        StructuredRecipePageAdapter pages = mock(StructuredRecipePageAdapter.class);
        WebRecipeSourceStore mockStore = mock(WebRecipeSourceStore.class);
        RecipeStepSummarizer summarizer = mock(RecipeStepSummarizer.class);
        when(internal.search(any(), any())).thenReturn(List.of());
        when(youtube.search(any(), any())).thenReturn(List.of());
        when(mockStore.findFreshWithSupporting(anyString())).thenReturn(List.of());
        CompositeRecipeSourceDiscoveryAdapter composite = new CompositeRecipeSourceDiscoveryAdapter(
                internal, search, pages, new InMemoryRecipeSourceCache(), youtube, mockStore, summarizer);
        org.springframework.test.util.ReflectionTestUtils.setField(composite, "webSourceEnabled", true);
        RecipeSourceCandidate primary = candidate(List.of("원문 1", "원문 2"), "SCHEMA_ORG_JSON_LD", "https://cookpick.kr/dujjonku");
        RecipeSourceCandidate sameSite = candidate(List.of("같은 사이트 1", "같은 사이트 2"), "SCHEMA_ORG_JSON_LD", "https://cookpick.kr/other");
        RecipeSourceCandidate otherSite = candidate(List.of("다른 사이트 1", "다른 사이트 2"), "SCHEMA_ORG_JSON_LD", "https://www.10000recipe.com/recipe/1");
        RecipeSourceCandidate third = candidate(List.of("세 번째 1", "세 번째 2"), "SCHEMA_ORG_JSON_LD", "https://wtable.co.kr/recipes/1");
        when(pages.collect(any(), any())).thenReturn(List.of(primary, sameSite, otherSite, third));
        when(summarizer.summarizeOutcome(anyString(), eq(primary))).thenReturn(SummaryOutcome.ofSummary(List.of("요약 1", "요약 2")));
        RecipeResearchPlan plan = new RecipeResearchPlan(
                "두쫀쿠", "", RecipeRequestMode.CREATE, false, true, List.of("두쫀쿠 레시피"), 3, 5);

        List<RecipeSourceDocument> documents = composite.search(plan, UserRecipeContext.empty(1L));

        assertThat(documents).extracting(RecipeSourceDocument::url).containsExactly(
                "https://cookpick.kr/dujjonku", "https://www.10000recipe.com/recipe/1", "https://wtable.co.kr/recipes/1");
        assertThat(documents.get(0).content()).contains("요약 1");
        assertThat(documents.get(1).content()).contains("마시멜로우 150g").doesNotContain("다른 사이트 1");
        assertThat(documents.get(1).sourceReliability()).isLessThan(documents.get(0).sourceReliability());
        verify(mockStore).saveSupporting(anyString(), eq(otherSite));
        verify(mockStore).saveSupporting(anyString(), eq(third));
        verify(mockStore, never()).saveSupporting(anyString(), eq(sameSite));

        // 같은 요청을 다시 하면 메모리 캐시에서 대표와 비교 출처를 함께 돌려줍니다.
        assertThat(composite.search(plan, UserRecipeContext.empty(1L))).hasSize(3);
    }

    @SuppressWarnings("unchecked")
    private RecipeStepSummarizer summarizer(RecipeTextLlmPort llm) {
        ObjectProvider<RecipeTextLlmPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);
        return new RecipeStepSummarizer(provider);
    }

    private RecipeSourceCandidate candidateWithIngredients(List<String> ingredients, List<String> steps) {
        RecipeSourceCandidate base = candidate(steps);
        RecipeCandidate recipe = new RecipeCandidate("곱도리탕", "", ingredients, steps,
                null, null, null, List.of("닭고기"), List.of(), List.of());
        return new RecipeSourceCandidate(base.source(), recipe, base.qualityScore(), base.evidence());
    }

    private RecipeSourceCandidate candidate(List<String> steps) {
        return candidate(steps, "SCHEMA_ORG_JSON_LD");
    }

    private RecipeSourceCandidate textCandidate(List<String> steps) {
        return candidate(steps, PageTextRecipeExtractor.EXTRACTION_METHOD);
    }

    private RecipeSourceCandidate candidate(List<String> steps, String method) {
        return candidate(steps, method, "https://cookpick.kr/dujjonku");
    }

    private RecipeSourceCandidate candidate(List<String> steps, String method, String url) {
        RecipeCandidate recipe = new RecipeCandidate("두쫀쿠 레시피", "", List.of("마시멜로우 150g", "버터 30g"), steps,
                null, null, null, List.of("마시멜로우"), List.of("버터"), List.of());
        RecipeEvidenceProvenance provenance = new RecipeEvidenceProvenance(
                url, null, "cookpick.kr", method, NOW, "hash", List.of());
        ExtractedRecipeEvidence evidence = new ExtractedRecipeEvidence("두쫀쿠 레시피", null, null, null, null, null, null,
                null, List.of(), List.of(), null, List.of(), provenance);
        RecipeSourceDocument source = new RecipeSourceDocument("web:hash", RecipeSourceType.GENERAL_WEB, "두쫀쿠 레시피",
                "", url, "title: 두쫀쿠 레시피\nsteps:\n" + String.join("\n", steps), NOW, 0.8);
        RecipeSourceQualityScore score = new RecipeSourceQualityScore(0.8, true, true, true, true, true, List.of(), List.of());
        return new RecipeSourceCandidate(source, recipe, score, evidence);
    }
}
