package com.salus.healthytable.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 모든 HTTP 요청에 요청 ID(X-Request-Id)를 붙이는 필터입니다.
 *
 * 요청 ID를 응답 헤더와 로그(MDC)에 함께 남기면, 사용자가 오류를 제보했을 때
 * 같은 ID로 서버 로그를 찾아 어떤 요청에서 문제가 났는지 추적할 수 있습니다.
 * {@code @Order(HIGHEST_PRECEDENCE)}로 가장 먼저 실행되어 이후 필터 로그에도 ID가 찍힙니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".REQUEST_ID";
    private static final String MDC_KEY = "requestId";
    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]+");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        // 요청 속성, 응답 헤더, 로그 컨텍스트(MDC) 세 곳에 같은 ID를 저장합니다.
        String requestId = resolveRequestId(request);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_KEY, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 스레드는 풀에서 재사용되므로, 요청이 끝나면 MDC 값을 반드시 지워 다음 요청 로그에 섞이지 않게 합니다.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 클라이언트가 보낸 요청 ID가 안전한 형식이면 그대로 쓰고, 아니면 새 UUID를 만듭니다.
     * 외부 입력을 그대로 로그에 남기면 로그 위조(개행 삽입 등)가 가능하므로 형식을 먼저 검사합니다.
     */
    private String resolveRequestId(HttpServletRequest request) {
        String incomingRequestId = request.getHeader(REQUEST_ID_HEADER);
        if (isSafeRequestId(incomingRequestId)) {
            return incomingRequestId;
        }

        return UUID.randomUUID().toString();
    }

    // 길이 64자 이하이면서 영문/숫자/점/밑줄/하이픈만 포함한 값만 허용합니다.
    private boolean isSafeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            return false;
        }

        return SAFE_REQUEST_ID.matcher(requestId).matches();
    }
}
