package com.salus.healthytable.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 녹화된 모델 출력을 HTTP 응답으로 되돌려주는 replay 소스.
 *
 * <p>파싱·오류 코드 판정을 하네스가 흉내 내면 프로덕션과 갈라지므로, 응답을 HTTP 경계에서
 * 주입해 {@code OllamaRecipeGenerationClient}의 실제 파싱 경로를 그대로 태운다.
 *
 * <p>고정 파일은 {@code src/test/resources/eval/replay/&lt;caseId&gt;.json}이며 세 가지 형태를 지원한다.
 * <ul>
 *   <li>{@code response}: Ollama 응답 봉투 원본. 빈 응답·비 JSON·토큰 한도 같은 이상 케이스용</li>
 *   <li>{@code draft}: 정상 레시피 JSON. 하네스가 봉투로 감싼다</li>
 *   <li>{@code chatContent}: 채팅 스위트용 본문 문자열</li>
 * </ul>
 */
public class EvalReplaySource {

    private static final String FIXTURE_PATH = "/eval/replay/";

    private final ObjectMapper objectMapper;
    private final AtomicReference<String> boundBody = new AtomicReference<>();

    public EvalReplaySource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 해당 케이스의 녹화 파일이 클래스패스에 있는지 확인합니다.
    public boolean hasFixture(String caseId) {
        return EvalReplaySource.class.getResource(FIXTURE_PATH + caseId + ".json") != null;
    }

    /** 고정 파일을 Ollama 응답 본문 문자열로 변환한다. */
    public Optional<String> bodyFor(String caseId) {
        try (InputStream stream = EvalReplaySource.class.getResourceAsStream(FIXTURE_PATH + caseId + ".json")) {
            if (stream == null) {
                return Optional.empty();
            }
            JsonNode fixture = objectMapper.readTree(stream);
            if (fixture.hasNonNull("response")) {
                return Optional.of(objectMapper.writeValueAsString(fixture.get("response")));
            }
            String content;
            if (fixture.hasNonNull("draft")) {
                content = objectMapper.writeValueAsString(fixture.get("draft"));
            } else if (fixture.hasNonNull("chatContent")) {
                content = fixture.get("chatContent").asText();
            } else {
                throw new IllegalStateException(
                        caseId + " 고정 파일에 response, draft, chatContent 중 하나가 필요합니다.");
            }
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("model", fixture.path("model").asText("replay"));
            envelope.put("done", true);
            envelope.put("done_reason", fixture.path("doneReason").asText("stop"));
            envelope.set("message", objectMapper.createObjectNode()
                    .put("role", "assistant")
                    .put("content", content));
            return Optional.of(objectMapper.writeValueAsString(envelope));
        } catch (IOException e) {
            throw new UncheckedIOException(caseId + " replay 고정 파일 읽기 실패", e);
        }
    }

    // 다음 HTTP 호출에서 돌려줄 응답 본문을 지정합니다.
    public void bind(String body) {
        boundBody.set(body);
    }

    // 실제 네트워크 대신 지정된 본문을 Ollama 응답처럼 반환하는 가짜 HTTP 교환 함수를 만듭니다.
    public ExchangeFunction exchangeFunction() {
        return request -> {
            String body = boundBody.get();
            if (body == null) {
                return Mono.error(new IllegalStateException("replay 고정 응답이 바인딩되지 않았습니다."));
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
    }
}
