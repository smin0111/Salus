package com.salus.healthytable.service;

import com.salus.healthytable.dto.ChatDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InterruptedRequestMerger} 테스트입니다. 멈춘 질문과 새 질문을 합칠 때 LLM 결과를 그대로 믿지 않는지 확인합니다.
 */
class InterruptedRequestMergerTest {

    // LLM이 "양파 빼고"를 지키면 정리한 문장을 쓰고, 기록에는 사용자가 보낸 새 메시지를 남깁니다.
    @Test
    void usesModelRewriteWhenItKeepsRequestedExclusions() {
        ChatDto.Request request = request("양파는 빼줘", "김치찌개 레시피 알려줘");

        ChatDto.Request merged = merger("{\"request\": \"양파 빼고 김치찌개 레시피 알려줘\"}").merge(request).block();

        assertThat(merged.getMessage()).isEqualTo("양파 빼고 김치찌개 레시피 알려줘");
        assertThat(merged.getOriginalMessage()).isEqualTo("양파는 빼줘");
    }

    // LLM이 사용자가 뺀 재료를 잃어버리면 정리 결과를 버리고 두 질문을 이어 붙입니다.
    @Test
    void fallsBackToConcatenationWhenModelDropsExclusion() {
        ChatDto.Request request = request("양파는 빼줘", "김치찌개 레시피 알려줘");

        ChatDto.Request merged = merger("{\"request\": \"김치찌개 레시피 알려줘\"}").merge(request).block();

        assertThat(merged.getMessage()).isEqualTo("김치찌개 레시피 알려줘 양파는 빼줘");
    }

    // LLM이 응답하지 않거나 형식이 다르면 이어 붙이기로 답합니다.
    @Test
    void fallsBackToConcatenationWhenModelFailsOrReturnsGarbage() {
        assertThat(merger("이건 JSON이 아니에요").merge(request("2인분으로", "제육볶음 레시피")).block().getMessage())
                .isEqualTo("제육볶음 레시피 2인분으로");
        assertThat(failingMerger().merge(request("2인분으로", "제육볶음 레시피")).block().getMessage())
                .isEqualTo("제육볶음 레시피 2인분으로");
    }

    // 멈춘 질문이 없으면 LLM을 부르지 않고 요청을 그대로 둡니다.
    @Test
    void requestWithoutInterruptedQuestionsIsUnchanged() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘");

        ChatDto.Request merged = failingMerger().merge(request).block();

        assertThat(merged.getMessage()).isEqualTo("김치찌개 레시피 알려줘");
        assertThat(merged.getOriginalMessage()).isNull();
    }

    // 앞 질문에서 뺀 재료는 요리를 바꿔도 남아 있어야 합니다(2026-10-17 결정: 새 요리에도 이어 감).
    @Test
    void earlierExclusionSurvivesEvenWhenUserSwitchesDish() {
        List<String> earlier = List.of("새우 빼고 볶음밥 레시피 알려줘");

        assertThat(InterruptedRequestMerger.accept(earlier, "2인분으로", "2인분 볶음밥 레시피 알려줘")).isFalse();
        assertThat(InterruptedRequestMerger.accept(earlier, "2인분으로", "새우 빼고 2인분 볶음밥 레시피 알려줘")).isTrue();
        assertThat(InterruptedRequestMerger.accept(earlier, "아니 김치찌개 레시피 알려줘", "김치찌개 레시피 알려줘")).isFalse();
        assertThat(InterruptedRequestMerger.accept(earlier, "아니 김치찌개 레시피 알려줘", "새우 빼고 김치찌개 레시피 알려줘"))
                .isTrue();
        assertThat(InterruptedRequestMerger.fallback(earlier, "아니 김치찌개로")).isEqualTo("새우 빼고 김치찌개 레시피 알려줘");
        // 새 메시지가 그 재료를 다시 말하면("새우는 넣어도 돼") 앞 빼기 조건을 강요하지 않습니다.
        assertThat(InterruptedRequestMerger.accept(earlier, "새우는 넣어도 돼", "볶음밥 레시피 알려줘")).isTrue();
    }

    // LLM이 알레르기 문장을 빠뜨리거나 "빼줘"를 "넣은"으로 바꾼 문장은 받지 않고 원문을 이어 붙입니다(2026-10-17 리뷰).
    @Test
    void mergeMustKeepAllergyMentionsAndExclusionMeaning() {
        List<String> allergy = List.of("우유 알레르기 있어. 파스타 레시피 알려줘");
        assertThat(InterruptedRequestMerger.accept(allergy, "2인분으로", "2인분 파스타 레시피 알려줘")).isFalse();
        assertThat(InterruptedRequestMerger.accept(allergy, "2인분으로", "우유 알레르기 있어. 2인분 파스타 레시피 알려줘"))
                .isTrue();
        // 요리를 바꿔도 알레르기는 이어집니다.
        assertThat(InterruptedRequestMerger.accept(allergy, "아니 된장찌개로", "된장찌개 레시피 알려줘")).isFalse();
        assertThat(InterruptedRequestMerger.accept(allergy, "아니 된장찌개로", "우유 알레르기, 된장찌개 레시피 알려줘"))
                .isTrue();

        List<String> friedRice = List.of("볶음밥 레시피 알려줘");
        assertThat(InterruptedRequestMerger.accept(friedRice, "새우 빼줘", "새우 넣은 볶음밥 레시피 알려줘")).isFalse();
        assertThat(InterruptedRequestMerger.accept(friedRice, "새우 빼줘", "새우 빼고 볶음밥 레시피 알려줘")).isTrue();
    }

    // 요리를 바꾼 말("아니 된장찌개로")에 앞 요리 이름을 섞은 문장은 받지 않습니다
    // (2026-10-17 실제 Ollama: "우유 알레르기 있어. 된장찌개 파스타 레시피 알려줘").
    @Test
    void dishSwitchMustDropPreviousDishName() {
        List<String> pasta = List.of("우유 알레르기 있어. 파스타 레시피 알려줘");
        assertThat(InterruptedRequestMerger.accept(pasta, "아니 된장찌개로", "우유 알레르기 있어. 된장찌개 파스타 레시피 알려줘"))
                .isFalse();
        assertThat(InterruptedRequestMerger.accept(pasta, "아니 된장찌개로", "우유 알레르기 있어. 된장찌개 레시피 알려줘"))
                .isTrue();
        // 같은 요리를 다시 말하거나 조리 도구만 바꾼 경우는 앞 요리 이름이 남아야 맞습니다.
        assertThat(InterruptedRequestMerger.accept(List.of("마늘빵 레시피"), "아니 에어프라이어로", "에어프라이어 마늘빵 레시피 알려줘"))
                .isTrue();
        assertThat(InterruptedRequestMerger.accept(List.of("김치찌개 레시피 알려줘"), "김치찌개 레시피 2인분으로",
                "김치찌개 레시피 2인분으로 알려줘")).isTrue();
    }

    // LLM 결과를 쓸 수 없을 때 요리를 바꿨으면, 앞 질문의 알레르기·빼기 조건만 남기고 새 요리로 묻습니다.
    @Test
    void dishSwitchFallbackKeepsSafetyConditionsWithoutPreviousDish() {
        assertThat(InterruptedRequestMerger.fallback(List.of("우유 알레르기 있어. 새우 빼고 파스타 레시피 알려줘"), "아니 된장찌개로"))
                .isEqualTo("우유 알레르기 있어. 새우 빼고 된장찌개 레시피 알려줘");
        assertThat(InterruptedRequestMerger.fallback(List.of("우유 알레르기 있어. 파스타 레시피 알려줘"), "김치찌개 레시피 알려줘"))
                .isEqualTo("우유 알레르기 있어. 김치찌개 레시피 알려줘");
        // 요리를 바꾸지 않았거나 조리 도구만 바꾼 말은 지금처럼 이어 붙입니다.
        assertThat(InterruptedRequestMerger.fallback(List.of("마늘빵 레시피"), "아니 에어프라이어로"))
                .isEqualTo("마늘빵 레시피 아니 에어프라이어로");
        assertThat(InterruptedRequestMerger.fallback(List.of("제육볶음 레시피"), "2인분으로"))
                .isEqualTo("제육볶음 레시피 2인분으로");

        ChatDto.Request request = request("아니 된장찌개로", "우유 알레르기 있어. 파스타 레시피 알려줘");
        ChatDto.Request merged = merger("{\"request\": \"우유 알레르기 있어. 된장찌개 파스타 레시피 알려줘\"}")
                .merge(request).block();
        assertThat(merged.getMessage()).isEqualTo("우유 알레르기 있어. 된장찌개 레시피 알려줘");
    }

    @Test
    void allergyMentionsReadTheWordBeforeAllergy() {
        assertThat(InterruptedRequestMerger.allergyMentions("나 우유 알레르기 있고 새우알러지도 있어"))
                .containsExactly("우유", "새우");
        assertThat(InterruptedRequestMerger.allergyMentions("땅콩에 알레르기가 있어")).containsExactly("땅콩");
        assertThat(InterruptedRequestMerger.allergyMentions("파스타 레시피 알려줘")).isEmpty();
    }

    @Test
    void exclusionsReadCommonRemovalPhrases() {
        assertThat(InterruptedRequestMerger.exclusions("양파는 빼줘, 우유 없이 그리고 오이 안 들어간 걸로"))
                .containsExactly("양파", "우유", "오이");
        assertThat(InterruptedRequestMerger.exclusions("이거 말고 다른 레시피")).isEmpty();
        assertThat(InterruptedRequestMerger.exclusions("배 빼줘")).containsExactly("배");
        assertThat(InterruptedRequestMerger.exclusions("좀 빼줘")).isEmpty();
    }

    @Test
    void keepsOnlyLatestThreeInterruptedQuestions() {
        assertThat(InterruptedRequestMerger.cleaned(List.of("a", " ", "b", "c", "d"))).containsExactly("b", "c", "d");
    }

    // 멈춘 뒤 취소하는 말만 보냈을 때만 규칙으로 바로 답합니다. 다른 말이 섞이면 LLM이 판단합니다.
    @Test
    void cancelOnlyMatchesWholeMessageOfCancelWords() {
        for (String message : List.of("아니야", "아니야!", "됐어", "됐어요~", "취소", "취소해줘", "그만해", "아뇨", "ㄴㄴ", "안 할래")) {
            assertThat(InterruptedRequestMerger.isCancelOnly(message)).as(message).isTrue();
        }
        for (String message : List.of("아니야 된장찌개로", "됐고 2인분으로", "아니 양파 빼줘", "취소하고 김치찌개 알려줘",
                "괜찮아", "잠깐", "아니야 그거 말고 다른 레시피", "")) {
            assertThat(InterruptedRequestMerger.isCancelOnly(message)).as(message).isFalse();
        }
    }

    private ChatDto.Request request(String latest, String... interrupted) {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage(latest);
        request.setInterruptedMessages(List.of(interrupted));
        return request;
    }

    private InterruptedRequestMerger merger(String modelContent) {
        String body = "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":"
                + quote(modelContent) + "},\"done\":true}";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(clientRequest -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();
        return configured(new InterruptedRequestMerger(webClient));
    }

    private InterruptedRequestMerger failingMerger() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(clientRequest -> Mono.error(new IllegalStateException("ollama down")))
                .build();
        return configured(new InterruptedRequestMerger(webClient));
    }

    private InterruptedRequestMerger configured(InterruptedRequestMerger merger) {
        ReflectionTestUtils.setField(merger, "model", "qwen3:8b");
        ReflectionTestUtils.setField(merger, "primaryUrl", "http://localhost:11434/api/chat");
        ReflectionTestUtils.setField(merger, "timeoutSeconds", 5L);
        return merger;
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
