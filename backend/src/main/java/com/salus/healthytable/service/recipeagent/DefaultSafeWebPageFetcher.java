package com.salus.healthytable.service.recipeagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 외부 웹 페이지를 "안전하게" 가져오는 HTTP 클라이언트입니다.
 *
 * 사용자가 조작할 수 있는 URL을 서버가 대신 요청하면, 공격자가 내부망 주소(127.0.0.1, 10.x 등)를 넣어
 * 서버 내부 자원에 접근하는 SSRF 공격이 가능해집니다. 이를 막기 위해:
 * - http/https만 허용하고, URL의 사용자 정보(user@host)와 localhost를 거부
 * - 호스트를 DNS로 조회해 사설/루프백/링크로컬 등 내부 주소면 거부
 * - 리다이렉트는 자동으로 따라가지 않고, 매번 새 주소를 다시 검증(최대 횟수 제한)
 * - HTML만 허용하고, 응답 크기와 요청 시간을 제한
 */
@Slf4j
@Component
class DefaultSafeWebPageFetcher implements SafeWebPageFetcher {

    private static final List<String> HTML_CONTENT_TYPES = List.of("text/html", "application/xhtml+xml");
    private static final String USER_AGENT = "SalusRecipeAgent/1.0";

    private final HttpClient httpClient;
    private final int maxRedirects;
    private final int maxResponseBytes;
    private final Duration requestTimeout;

    // 스프링이 설정값(application.properties)으로 생성할 때 쓰는 생성자입니다.
    @Autowired
    DefaultSafeWebPageFetcher(
            @Value("${recipe.agent.web-fetch.max-redirects:3}") int maxRedirects,
            @Value("${recipe.agent.web-fetch.max-response-bytes:1048576}") int maxResponseBytes,
            @Value("${recipe.agent.web-fetch.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${recipe.agent.web-fetch.request-timeout-seconds:8}") long requestTimeoutSeconds) {
        this(maxRedirects, maxResponseBytes, Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(requestTimeoutSeconds));
    }

    // 테스트에서 값을 직접 넣을 수 있는 생성자입니다. 자동 리다이렉트는 끄고(NEVER) 직접 검증하며 따라갑니다.
    DefaultSafeWebPageFetcher(int maxRedirects, int maxResponseBytes, Duration connectTimeout, Duration requestTimeout) {
        this.maxRedirects = Math.max(0, maxRedirects);
        this.maxResponseBytes = Math.max(1024, maxResponseBytes);
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(8) : requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * URL의 HTML 페이지를 가져옵니다. 안전 규칙에 어긋나거나 실패하면 SafeWebPageFetchException을 던집니다.
     */
    @Override
    public WebPageFetchResult fetch(String url) {
        URI current = validateExternalUri(parseUri(url));
        for (int redirectCount = 0; redirectCount <= maxRedirects; redirectCount++) {
            HttpResponse<InputStream> response = send(current);
            int status = response.statusCode();
            if (isRedirect(status)) {
                Optional<String> location = response.headers().firstValue("location");
                if (location.isEmpty()) {
                    throw new SafeWebPageFetchException("redirect response without Location");
                }
                current = resolveAndValidateRedirect(current, location.get());
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new SafeWebPageFetchException("non-success status: " + status);
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            validateHtmlContentType(contentType);
            byte[] bytes = readLimited(response.body(), maxResponseBytes);
            String body = new String(bytes, StandardCharsets.UTF_8);
            return new WebPageFetchResult(
                    current.toString(),
                    status,
                    contentType,
                    body,
                    LocalDateTime.now(),
                    sha256(bytes));
        }
        throw new SafeWebPageFetchException("redirect limit exceeded");
    }

    // 외부 공개 주소인지 검증합니다. 도메인이 가리키는 모든 IP 중 하나라도 내부 주소면 거부합니다.
    URI validateExternalUri(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new SafeWebPageFetchException("URL must include scheme and host");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SafeWebPageFetchException("unsupported URL scheme");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new SafeWebPageFetchException("URL user info is not allowed");
        }
        String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw new SafeWebPageFetchException("localhost is not allowed");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new SafeWebPageFetchException("host did not resolve");
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new SafeWebPageFetchException("private or local address is not allowed");
                }
            }
        } catch (SafeWebPageFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new SafeWebPageFetchException("host resolution failed");
        }
        return uri.normalize();
    }

    // 리다이렉트 Location(상대 경로 가능)을 절대 URL로 바꾼 뒤 다시 외부 주소인지 검증합니다.
    URI resolveAndValidateRedirect(URI current, String location) {
        if (location == null || location.isBlank()) {
            throw new SafeWebPageFetchException("empty redirect location");
        }
        URI next = current.resolve(location);
        return validateExternalUri(next);
    }

    void validateHtmlContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean html = HTML_CONTENT_TYPES.stream().anyMatch(normalized::contains);
        if (!html) {
            throw new SafeWebPageFetchException("non-html content type");
        }
    }

    // 응답 본문을 최대 limitBytes까지만 읽습니다. 넘으면 즉시 중단해 메모리 과사용을 막습니다.
    byte[] readLimited(InputStream inputStream, int limitBytes) {
        try (InputStream input = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limitBytes) {
                    throw new SafeWebPageFetchException("response body too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (SafeWebPageFetchException e) {
            throw e;
        } catch (IOException e) {
            throw new SafeWebPageFetchException("response body read failed");
        }
    }

    // 압축 해제 우회를 막기 위해 Accept-Encoding: identity(압축하지 않은 본문)로 요청합니다.
    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new SafeWebPageFetchException("request failed");
        }
    }

    private URI parseUri(String url) {
        try {
            return URI.create(url == null ? "" : url.trim());
        } catch (Exception e) {
            throw new SafeWebPageFetchException("invalid URL");
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 내부망/특수 용도 주소인지 확인합니다.
     * IPv4: 0.x, 10.x, 127.x, 169.254.x, 172.16~31.x, 192.168.x, 100.64~127.x(CGNAT), 198.18~19.x(벤치마크용)
     * IPv6: fc00::/7(고유 로컬 주소). 판단할 수 없는 주소 형식은 안전하게 차단합니다.
     */
    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19));
        }
        if (address instanceof Inet6Address) {
            byte first = address.getAddress()[0];
            return (first & 0xfe) == 0xfc;
        }
        return true;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new SafeWebPageFetchException("content hash failed");
        }
    }
}

// 안전한 페이지 가져오기가 거부되거나 실패했음을 나타내는 예외입니다.
class SafeWebPageFetchException extends RuntimeException {

    SafeWebPageFetchException(String message) {
        super(message);
    }
}
