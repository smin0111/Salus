package com.salus.healthytable.exception;

import com.salus.healthytable.config.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void illegalArgumentReturnsConsistentJsonError() {
        // 예외 타입이 달라도 status/error/message/path 구조가 유지되는지 확인합니다.
        // 프론트엔드는 이 공통 구조를 믿고 오류 UI와 사용자 안내 문구를 만들 수 있습니다.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/community/posts");

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("제목은 필수입니다."),
                request);

        assertErrorResponse(response, 400, "BAD_REQUEST", "제목은 필수입니다.", "/api/community/posts");
    }

    @Test
    void missingRequestParameterReturnsBadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/community/posts/search");

        ResponseEntity<Map<String, Object>> response = handler.handleMissingServletRequestParameter(
                new MissingServletRequestParameterException("keyword", "String"),
                request);

        assertErrorResponse(response, 400, "BAD_REQUEST", "필수 요청 파라미터가 누락되었습니다: keyword",
                "/api/community/posts/search");
    }

    @Test
    void parameterTypeMismatchReturnsBadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/community/posts/popular");

        ResponseEntity<Map<String, Object>> response = handler.handleMethodArgumentTypeMismatch(
                new MethodArgumentTypeMismatchException("abc", Integer.class, "limit", null,
                        new NumberFormatException("For input string: abc")),
                request);

        assertErrorResponse(response, 400, "BAD_REQUEST", "요청 파라미터 형식이 올바르지 않습니다: limit",
                "/api/community/posts/popular");
    }

    @Test
    void malformedJsonBodyReturnsBadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/community/posts");

        ResponseEntity<Map<String, Object>> response = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("raw json parse error"),
                request);

        assertErrorResponse(response, 400, "BAD_REQUEST", "요청 본문 JSON 형식이 올바르지 않습니다.",
                "/api/community/posts");
    }

    @Test
    void responseStatusExceptionReturnsConsistentJsonError() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/fridge/1");

        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 냉장고 항목만 삭제할 수 있습니다."),
                request);

        assertErrorResponse(response, 403, "FORBIDDEN", "본인의 냉장고 항목만 삭제할 수 있습니다.", "/api/fridge/1");
    }

    @Test
    void errorResponseIncludesRequestIdWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-chat-123");

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("메시지는 필수입니다."),
                request);

        assertErrorResponse(response, 400, "BAD_REQUEST", "메시지는 필수입니다.", "/api/chat");
        assertThat(response.getBody()).containsEntry("requestId", "req-chat-123");
    }

    @Test
    void unexpectedExceptionHidesInternalDetails(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");

        // 예상치 못한 예외는 사용자에게 내부 메시지를 보여주지 않아야 합니다.
        // 운영 로그에도 민감한 원문 메시지를 남기지 않는지 함께 검증합니다.
        ResponseEntity<Map<String, Object>> response = handler.handleAllExceptions(
                new RuntimeException("database password leaked here"),
                request);

        assertErrorResponse(response, 500, "INTERNAL_SERVER_ERROR", "요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
                "/api/users/me");
        assertThat(response.getBody()).doesNotContainValue("database password leaked here");
        assertThat(output.getOut())
                .contains("type=java.lang.RuntimeException")
                .contains("path=/api/users/me")
                .doesNotContain("database password leaked here");
    }

    private void assertErrorResponse(ResponseEntity<Map<String, Object>> response, int status, String error,
            String message, String path) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody())
                .containsEntry("status", status)
                .containsEntry("error", error)
                .containsEntry("message", message)
                .containsEntry("path", path);
    }
}
