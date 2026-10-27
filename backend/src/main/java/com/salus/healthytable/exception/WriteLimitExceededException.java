package com.salus.healthytable.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 피드 작성 제한(429). Retry-After 헤더에는 실제로 넘은 구간이 끝날 때까지 남은 초를 담습니다.
 * GlobalExceptionHandler가 ResponseStatusException의 헤더를 응답에 그대로 붙입니다.
 */
public class WriteLimitExceededException extends ResponseStatusException {

    private final long retryAfterSeconds;

    public WriteLimitExceededException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        return headers;
    }
}
