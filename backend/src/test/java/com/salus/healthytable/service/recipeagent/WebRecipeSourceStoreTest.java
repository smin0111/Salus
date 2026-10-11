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
        assertThat(summarizer.summarize("두쫀쿠", candidate(List.of("원문 1", "원문 2", "원문 3"))))
                .contains(List.of("버터를 녹인다", "마시멜로우를 섞는다"));
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
                    Map.of("text", "약불에서 마시멜로우를 저으며 녹인다"),
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
        assertThat(result).containsExactly("카다이프면을 오븐 170도에서 황금빛이 될 때까지 굽는다", "약불에서 마시멜로우를 저으며 녹인다");
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
                "두쫀쿠", "", RecipeRequestMode.CREATE, false, false, true, List.of("두쫀쿠 레시피"), 3, 5);

        RecipeSourceDocument stored = new RecipeSourceDocument("web-store:1", RecipeSourceType.GENERAL_WEB,
                "두쫀쿠 레시피", "", "https://cookpick.kr/dujjonku", "title: 두쫀쿠", NOW, 0.8);
        when(mockStore.findFresh(anyString())).thenReturn(Optional.of(stored));
        assertThat(composite.search(plan, UserRecipeContext.empty(1L))).extracting(RecipeSourceDocument::sourceId)
                .contains("web-store:1");
        verify(search, never()).search(anyList(), anyInt());

        RecipeResearchPlan other = new RecipeResearchPlan(
                "버터떡", "", RecipeRequestMode.CREATE, false, false, true, List.of("버터떡 레시피"), 3, 5);
        when(mockStore.findFresh(anyString())).thenReturn(Optional.empty());
        RecipeSourceCandidate found = candidate(List.of("원문 1", "원문 2"));
        when(pages.collect(any(), any())).thenReturn(List.of(found, candidate(List.of("다른 원문 1", "다른 원문 2"))));
        when(summarizer.summarize(eq("버터떡"), eq(found))).thenReturn(Optional.of(List.of("요약 1", "요약 2")));

        List<RecipeSourceDocument> documents = composite.search(other, UserRecipeContext.empty(1L));

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).content()).contains("요약 1").doesNotContain("원문 1");
        verify(mockStore).save(anyString(), eq(found), eq(List.of("요약 1", "요약 2")));
    }

    @SuppressWarnings("unchecked")
    private RecipeStepSummarizer summarizer(RecipeTextLlmPort llm) {
        ObjectProvider<RecipeTextLlmPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);
        return new RecipeStepSummarizer(provider);
    }

    private RecipeSourceCandidate candidate(List<String> steps) {
        return candidate(steps, "SCHEMA_ORG_JSON_LD");
    }

    private RecipeSourceCandidate textCandidate(List<String> steps) {
        return candidate(steps, PageTextRecipeExtractor.EXTRACTION_METHOD);
    }

    private RecipeSourceCandidate candidate(List<String> steps, String method) {
        RecipeCandidate recipe = new RecipeCandidate("두쫀쿠 레시피", "", List.of("마시멜로우 150g", "버터 30g"), steps,
                null, null, null, List.of("마시멜로우"), List.of("버터"), List.of());
        RecipeEvidenceProvenance provenance = new RecipeEvidenceProvenance(
                "https://cookpick.kr/dujjonku", null, "cookpick.kr", method, NOW, "hash", List.of());
        ExtractedRecipeEvidence evidence = new ExtractedRecipeEvidence("두쫀쿠 레시피", null, null, null, null, null, null,
                null, List.of(), List.of(), null, List.of(), provenance);
        RecipeSourceDocument source = new RecipeSourceDocument("web:hash", RecipeSourceType.GENERAL_WEB, "두쫀쿠 레시피",
                "", "https://cookpick.kr/dujjonku", "title: 두쫀쿠 레시피\nsteps:\n" + String.join("\n", steps), NOW, 0.8);
        RecipeSourceQualityScore score = new RecipeSourceQualityScore(0.8, true, true, true, true, true, List.of(), List.of());
        return new RecipeSourceCandidate(source, recipe, score, evidence);
    }
}
