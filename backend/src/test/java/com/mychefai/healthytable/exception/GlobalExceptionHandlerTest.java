package com.mychefai.healthytable.exception;

import com.mychefai.healthytable.config.RequestIdFilter;
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
