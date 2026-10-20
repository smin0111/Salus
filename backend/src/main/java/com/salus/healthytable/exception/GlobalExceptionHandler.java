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
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 컨트롤러에서 발생한 예외를 한곳에서 처리해 일관된 JSON 오류 응답으로 바꾸는 클래스입니다.
 *
 * {@code @RestControllerAdvice}가 붙어 있어 모든 컨트롤러에 적용되며,
 * {@code @ExceptionHandler}에 적힌 예외 타입별로 알맞은 HTTP 상태 코드를 정합니다.
 * 응답 형식: {"status", "error", "message", "path", "requestId"}
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        // @Valid DTO 검증 실패는 여기로 모입니다.
        // Controller마다 다른 응답 모양을 만들지 않게 한 곳에서 표준 JSON 오류로 변환합니다.
        String defaultMessage = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", defaultMessage, request);
    }

    // 서비스 코드에서 잘못된 입력을 IllegalArgumentException으로 던지면 400 Bad Request로 응답합니다.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request);
    }

    // 필수 쿼리 파라미터(?date=...)가 빠진 경우
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "필수 요청 파라미터가 누락되었습니다: " + ex.getParameterName(),
                request);
    }

    // 파라미터 타입이 맞지 않는 경우 (예: 숫자 자리에 문자열)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "요청 파라미터 형식이 올바르지 않습니다: " + ex.getName(),
                request);
    }

    // 요청 본문 JSON을 읽을 수 없는 경우 (문법 오류, 타입 불일치 등)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "요청 본문 JSON 형식이 올바르지 않습니다.",
                request);
    }

    // 코드에서 new ResponseStatusException(HttpStatus.X, "메시지")로 던진 예외는 지정한 상태 코드를 그대로 사용합니다.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex,
            HttpServletRequest request) {
        return apiError(ex.getStatusCode(), resolveErrorCode(ex.getStatusCode()), ex.getReason(), request);
    }

    // 필수 헤더(주로 Authorization)가 없으면 로그인이 필요한 것으로 보고 401로 응답합니다.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestHeader(MissingRequestHeaderException ex,
            HttpServletRequest request) {
        return apiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.", request);
    }

    // 레시피 생성 시간이 초과되었거나 비동기 요청이 시간 초과된 경우 504 Gateway Timeout으로 응답합니다.
    @ExceptionHandler({RecipeGenerationTimeoutException.class, AsyncRequestTimeoutException.class})
    public ResponseEntity<Map<String, Object>> handleRecipeGenerationTimeout(
            Exception ex,
            HttpServletRequest request) {
        log.warn("[RecipeGeneration] category=TIMEOUT, exceptionClass={}, path={}",
                ex.getClass().getSimpleName(), request.getRequestURI());
        return apiError(
                HttpStatus.GATEWAY_TIMEOUT,
                "RECIPE_GENERATION_TIMEOUT",
                "레시피 생성 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.",
                request);
    }

    // 위에서 처리하지 못한 나머지 모든 예외. 내부 정보는 숨기고 500 응답만 돌려줍니다.
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
        // 프론트엔드는 status/error/message/path/requestId가 항상 있다고 가정하고 공통 오류 UI를 만들 수 있습니다.
        // requestId는 운영 로그와 사용자 제보를 연결하는 단서라 장애 분석 시간을 줄여 줍니다.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", statusCode.value());
        response.put("error", error);
        response.put("message", message);
        response.put("path", request.getRequestURI());
        addRequestIdIfPresent(response, request);

        return ResponseEntity.status(statusCode).body(response);
    }

    // RequestIdFilter가 요청에 저장해 둔 요청 ID가 있으면 응답에도 포함합니다.
    private void addRequestIdIfPresent(Map<String, Object> response, HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId instanceof String value && !value.isBlank()) {
            response.put("requestId", value);
        }
    }

    // 상태 코드 숫자를 "NOT_FOUND" 같은 이름 문자열로 바꿉니다. 표준에 없는 코드면 숫자 문자열을 그대로 씁니다.
    private String resolveErrorCode(HttpStatusCode statusCode) {
        HttpStatus httpStatus = HttpStatus.resolve(statusCode.value());
        if (httpStatus == null) {
            return statusCode.toString();
        }
        return httpStatus.name();
    }
}
