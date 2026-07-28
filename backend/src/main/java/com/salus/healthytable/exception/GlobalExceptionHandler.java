package com.salus.healthytable.exception;

import com.salus.healthytable.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String defaultMessage = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", defaultMessage, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "필수 요청 파라미터가 누락되었습니다: " + ex.getParameterName(),
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "요청 파라미터 형식이 올바르지 않습니다: " + ex.getName(),
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "요청 본문 JSON 형식이 올바르지 않습니다.",
                request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex,
            HttpServletRequest request) {
        return apiError(ex.getStatusCode(), resolveErrorCode(ex.getStatusCode()), ex.getReason(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestHeader(MissingRequestHeaderException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAllExceptions(Exception ex, HttpServletRequest request) {
        // 기본 운영 로그에는 예외 메시지/스택을 남기지 않아 민감정보 노출 가능성을 줄입니다.
        log.error("[서버 오류] 예상치 못한 예외 발생: type={}, path={}",
                ex.getClass().getName(),
                request.getRequestURI());
        log.debug("[서버 오류 상세]", ex);

        return apiError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", request);
    }

    private ResponseEntity<Map<String, Object>> apiError(HttpStatusCode statusCode, String error, String message,
            HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", statusCode.value());
        response.put("error", error);
        response.put("message", message);
        response.put("path", request.getRequestURI());
        addRequestIdIfPresent(response, request);

        return ResponseEntity.status(statusCode).body(response);
    }

    private void addRequestIdIfPresent(Map<String, Object> response, HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId instanceof String value && !value.isBlank()) {
            response.put("requestId", value);
        }
    }

    private String resolveErrorCode(HttpStatusCode statusCode) {
        HttpStatus httpStatus = HttpStatus.resolve(statusCode.value());
        if (httpStatus == null) {
            return statusCode.toString();
        }
        return httpStatus.name();
    }
}
