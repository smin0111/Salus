package com.salus.healthytable.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 모델 응답 원문을 가로채 보관하는 WebClient 필터.
 *
 * <p>프로덕션 클라이언트는 응답을 파싱해 도메인 객체만 돌려주므로 원문이 남지 않는다.
 * 리포트에 raw output을 그대로 실으려면 원문이 필요한데, 그렇다고 프로덕션 코드에
 * 로깅을 심을 수는 없다. 그래서 HTTP 경계에 필터를 하나 얹어 본문을 복사해 두고
 * 같은 본문으로 응답을 다시 세워 그대로 흘려보낸다. 파싱 경로는 손대지 않는다.
 */
public class EvalRawCapture {

    private final ObjectMapper objectMapper;
    private final AtomicReference<String> lastEnvelope = new AtomicReference<>();

    public EvalRawCapture(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 응답 본문을 복사해 저장한 뒤, 같은 본문으로 응답을 다시 만들어 원래 호출자에게 넘기는 WebClient 필터입니다.
    public ExchangeFilterFunction filter() {
        return ExchangeFilterFunction.ofResponseProcessor(response ->
                response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> {
                            lastEnvelope.set(body);
                            return ClientResponse.create(response.statusCode())
                                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                    .body(body)
                                    .build();
                        }));
    }

    // 다음 호출 측정을 위해 직전 원문을 비웁니다.
    public void reset() {
        lastEnvelope.set(null);
    }

    /** 직전 호출에서 실제로 관측한 값만 채운 telemetry. 관측하지 못한 값은 null·빈 문자열로 둔다. */
    public EvalRun.Telemetry telemetry(
            long latencyMs, boolean timeout, String failureCode, String failureMessage) {
        String envelope = lastEnvelope.get();
        if (envelope == null || envelope.isBlank()) {
            return new EvalRun.Telemetry(
                    failureCode, failureMessage, latencyMs, timeout, "", "", "", null, null, null);
        }
        try {
            JsonNode root = objectMapper.readTree(envelope);
            JsonNode message = root.path("message");
            return new EvalRun.Telemetry(
                    failureCode,
                    failureMessage,
                    latencyMs,
                    timeout,
                    message.path("content").asText(""),
                    message.path("thinking").asText(""),
                    envelope,
                    root.hasNonNull("prompt_eval_count") ? root.get("prompt_eval_count").asInt() : null,
                    root.hasNonNull("eval_count") ? root.get("eval_count").asInt() : null,
                    root.hasNonNull("done_reason") ? root.get("done_reason").asText() : null);
        } catch (Exception e) {
            // 파싱에 실패해도 원문은 그대로 남긴다. 원문 보존이 파싱보다 우선이다.
            return new EvalRun.Telemetry(
                    failureCode, failureMessage, latencyMs, timeout, "", "", envelope, null, null, null);
        }
    }
}
