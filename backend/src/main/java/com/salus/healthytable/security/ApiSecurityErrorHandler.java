package com.salus.healthytable.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ApiSecurityErrorHandler {

    private final ObjectMapper objectMapper;

    public void handleAuthenticationException(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        writeError(response, request, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    }

    public void handleAccessDeniedException(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        writeError(response, request, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.");
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, int status, String error,
            String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), errorBody(status, error, message, request));
    }

    private Map<String, Object> errorBody(int status, String error, String message, HttpServletRequest request) {
        // Spring Security 필터에서 막힌 요청도 GlobalExceptionHandler와 비슷한 JSON 구조로 내려줍니다.
        // 그래야 프론트엔드가 인증 오류만 특별한 문자열 파싱으로 처리하지 않아도 됩니다.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        body.put("path", request.getRequestURI());

        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId instanceof String value && !value.isBlank()) {
            body.put("requestId", value);
        }

        return body;
    }
}
