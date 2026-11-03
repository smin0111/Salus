package com.salus.healthytable.service.ingredient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 저장소에 들어 있는 실제 자료(ko-ingredients.json)로 검색·근거 채택·원문 추출을 확인합니다. */
class IngredientInfoServiceTest {

    private static IngredientCorpus corpus;

    @BeforeAll
    static void loadCorpus() throws Exception {
        try (InputStream in = new ClassPathResource(IngredientInfoService.CORPUS_RESOURCE).getInputStream()) {
            corpus = IngredientCorpus.load(in, new ObjectMapper());
        }
    }

    private static IngredientInfoService serviceWithoutEmbedding() {
        OllamaEmbeddingClient embeddingClient = mock(OllamaEmbeddingClient.class);
        when(embeddingClient.embed(anyList(), any(Duration.class))).thenReturn(Optional.empty());
        return new IngredientInfoService(true, corpus, embeddingClient);
    }

    private static String reply(IngredientInfoService service, String message) {
        return service.answer(message).orElseThrow().reply();
    }

    @Test
    void answersExactNameWithOriginalSentenceAndSource() {
        IngredientInfoService.Answer answer = serviceWithoutEmbedding().answer("무청이 뭐야?").orElseThrow();

        assertThat(answer.reply()).startsWith("무청은 무의 잎과 줄기이다.");
        assertThat(answer.reply()).contains("출처: 위키백과 「무청」 일부 발췌 (CC BY-SA 4.0)")
                .contains("https://ko.wikipedia.org/wiki/무청");
        assertThat(answer.info().getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getTitle()).isEqualTo("무청");
                    assertThat(item.getBasis()).isEqualTo("NAME");
                });
    }

    @Test
    void doesNotAdoptDocumentThatOnlyMentionsTheNameInsideAnotherWord() {
        // 참조기 본문의 "민어과"는 민어를 설명하는 문장이 아닙니다. 민어는 동음이의 문서라 검토된 자료가 없습니다.
        IngredientInfoService.Answer answer = serviceWithoutEmbedding().answer("민어는 어떤 생선이야?").orElseThrow();

        assertThat(answer.reply()).startsWith("확실하지 않아요. ‘민어’는 검토된 자료에서 찾지 못했어요.");
        assertThat(answer.info()).isNull();
    }

    @Test
    void adoptsRelatedNameOnlyWithTheSentenceThatNamesIt() {
        String flounder = reply(serviceWithoutEmbedding(), "광어 정식 이름이 뭐야?");
        assertThat(flounder).startsWith("‘광어’는 위키백과 「넙치」 문서에 이렇게 나와 있어요.")
                .contains("광어라고도 하는데");

        String juvenilePollock = reply(serviceWithoutEmbedding(), "노가리는 뭐야?");
        assertThat(juvenilePollock).contains("어린 개체는 노가리라 부른다.");
    }

    @Test
    void redirectWithoutNamingSentenceIsNotPresentedAsTheSameThing() {
        // 위키백과는 코다리를 명태 문서로 넘기지만, 코다리는 반쯤 말린 명태입니다. 같다고 말하지 않습니다.
        String reply = reply(serviceWithoutEmbedding(), "코다리는 무슨 생선이야?");

        assertThat(reply).startsWith("‘코다리’는 위키백과에서 「명태」 문서로 연결돼요. 같은 것인지는 자료에 적혀 있지 않아요.");
    }

    @Test
    void adoptsTextMentionWhenBodyNamesIt() {
        String reply = reply(serviceWithoutEmbedding(), "조기는 뭐야?");

        assertThat(reply).startsWith("‘조기’는 위키백과 「참조기」 문서에 이렇게 나와 있어요.")
                .contains("흔히 조기라고 불리며");
    }

    @Test
    void comparisonKeepsEachIngredientsSentencesInItsOwnBlock() {
        String reply = reply(serviceWithoutEmbedding(), "시래기랑 우거지 차이가 뭐야?");

        String[] blocks = reply.split("\n\n");
        assertThat(blocks[0]).startsWith("‘시래기’\n").contains("무청이나 배춧잎을 말린 것").doesNotContain("배추의 겉잎");
        assertThat(blocks[1]).startsWith("‘우거지’\n").contains("배추의 겉잎을 말린 것").doesNotContain("무청이나 배춧잎");
        assertThat(reply).contains("「시래기」, 「우거지」 일부 발췌");
    }

    @Test
    void comparisonWithOneSideMissingShowsFoundSideAndSaysTheOtherIsMissing() {
        String reply = reply(serviceWithoutEmbedding(), "민어랑 명태 차이가 뭐야?");

        assertThat(reply).contains("‘명태’\n명태")
                .contains("‘민어’는 검토된 자료에서 찾지 못했어요.")
                .doesNotContain("차이는");
        assertThat(reply(serviceWithoutEmbedding(), "민어랑 황태는 뭐가 달라?"))
                .startsWith("확실하지 않아요. ‘민어’, ‘황태’는 검토된 자료에서 찾지 못했어요.");
    }

    // 자료가 모르는 이름도 일반 대화 LLM에 넘기지 않고 보류합니다. 일반 대화는 지시문을 바꿔도 모르는 재료를 지어냈습니다.
    @Test
    void namesTheCorpusDoesNotKnowAreHeldBackInsteadOfGuessed() {
        assertThat(reply(serviceWithoutEmbedding(), "퀴노아는 뭐야?")).startsWith("확실하지 않아요. ‘퀴노아’는 검토된 자료에서 찾지 못했어요.");
        assertThat(reply(serviceWithoutEmbedding(), "무우청이 뭐야?")).startsWith("확실하지 않아요.");
        // 정체를 묻는 질문 모양이 아니면 이 기능이 답하지 않습니다(기존 경로).
        assertThat(serviceWithoutEmbedding().answer("무청으로 국 끓여줘")).isEmpty();
    }

    @Test
    void disabledServiceNeverAnswers() {
        IngredientInfoService disabled = new IngredientInfoService(false, corpus, mock(OllamaEmbeddingClient.class));

        assertThat(disabled.answer("무청이 뭐야?")).isEmpty();
    }

    @Test
    void embeddingAddsTheSentenceClosestToTheQuestion() {
        OllamaEmbeddingClient embeddingClient = mock(OllamaEmbeddingClient.class);
        // 문장마다 "시래기"가 들어 있으면 [1,0], 아니면 [0,1]. 질문도 [1,0]이라 "시래기" 문장이 골라져야 합니다.
        when(embeddingClient.embed(anyList(), any(Duration.class))).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return Optional.of(texts.stream()
                    .map(text -> text.contains("시래기") || text.contains("뭐야") ? new float[]{1, 0} : new float[]{0, 1})
                    .toList());
        });
        IngredientInfoService service = new IngredientInfoService(true, corpus, embeddingClient);
        service.warmUp();

        String reply = reply(service, "무청이 뭐야?");

        assertThat(reply).startsWith("무청은 무의 잎과 줄기이다. 말려서 시래기로 쓰기도 한다.");
    }

    @Test
    void embeddingFailureKeepsNamingSentencesAndRedirectNotice() {
        IngredientInfoService service = serviceWithoutEmbedding();

        assertThat(reply(service, "노가리는 뭐야?")).contains("어린 개체는 노가리라 부른다.");
        assertThat(reply(service, "코다리는 뭐야?")).contains("같은 것인지는 자료에 적혀 있지 않아요.");
        assertThat(reply(service, "민어랑 명태 차이가 뭐야?")).contains("‘민어’는 검토된 자료에서 찾지 못했어요.");
    }

    // 이름을 묻는 질문은 이름을 적은 문장을 고릅니다. 임베딩이 없어도(문자열 기준) 나옵니다.
    @Test
    void nameQuestionPicksSentenceThatGivesTheName() {
        String reply = reply(serviceWithoutEmbedding(), "양배추 다른 이름은?");

        assertThat(reply).startsWith("양배추는 브라시카 올레라케아의 재배종이다.").contains("감람이라고도 하는");
    }

    // 사람이 읽고 뺀 문서(모순·편집자 메모·장난성 편집)의 재료는 단정하지 않고 보류합니다(Codex 검토 2회차 P2).
    @Test
    void documentsRemovedInReviewAreHeldBack() {
        assertThat(reply(serviceWithoutEmbedding(), "고추냉이는 뭐야?")).startsWith("확실하지 않아요. ‘고추냉이’는");
        assertThat(reply(serviceWithoutEmbedding(), "근대는 무슨 과 채소야?")).startsWith("확실하지 않아요.");
        assertThat(reply(serviceWithoutEmbedding(), "고추냉이랑 겨자는 같은 거야?"))
                .contains("‘고추냉이’는 검토된 자료에서 찾지 못했어요.")
                .doesNotContain("와사비");
    }

    @Test
    void corpusHasNoEditorNotesOrVandalismMarkers() {
        java.util.regex.Pattern suspicious = java.util.regex.Pattern.compile("[ㄱ-ㅎㅏ-ㅣ]{2,}|!!|\\?\\?|근데|ㅋ|ㅎㅎ");
        for (IngredientCorpus.Document document : corpus.documents()) {
            for (String sentence : document.sentences()) {
                assertThat(suspicious.matcher(sentence).find()).as(document.title() + ": " + sentence).isFalse();
            }
        }
    }

    @Test
    void topicParticleFollowsFinalConsonant() {
        assertThat(IngredientInfoService.topicParticle("무청")).isEqualTo("은");
        assertThat(IngredientInfoService.topicParticle("민어")).isEqualTo("는");
    }

    @Test
    void corpusHasNoUnreviewedNameThatIsAlsoATitleOrRelatedTerm() {
        for (IngredientCorpus.Document document : corpus.documents()) {
            assertThat(corpus.isUnreviewed(document.name())).as(document.title()).isFalse();
            assertThat(document.relatedTerms()).noneMatch(corpus::isUnreviewed);
            assertThat(document.url()).startsWith("https://ko.wikipedia.org/wiki/");
        }
        assertThat(corpus.license()).isEqualTo("CC BY-SA 4.0");
        // 사람이 확인한 잘못된 넘겨주기(꼬시래기→풀망둑)는 자료에 들어가지 않습니다.
        assertThat(corpus.byTitle("풀망둑")).isEmpty();
        assertThat(corpus.isUnreviewed("꼬시래기")).isTrue();
    }
}
