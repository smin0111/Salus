package com.salus.healthytable.service.recipeagent;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Component
class DefaultSafeWebPageFetcher implements SafeWebPageFetcher {

    private static final List<String> HTML_CONTENT_TYPES = List.of("text/html", "application/xhtml+xml");
    private static final String USER_AGENT = "SalusRecipeAgent/1.0";

    private final HttpClient httpClient;
    private final int maxRedirects;
    private final int maxResponseBytes;
    private final Duration requestTimeout;

    DefaultSafeWebPageFetcher(
            @Value("${recipe.agent.web-fetch.max-redirects:3}") int maxRedirects,
            @Value("${recipe.agent.web-fetch.max-response-bytes:1048576}") int maxResponseBytes,
            @Value("${recipe.agent.web-fetch.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${recipe.agent.web-fetch.request-timeout-seconds:8}") long requestTimeoutSeconds) {
        this(maxRedirects, maxResponseBytes, Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(requestTimeoutSeconds));
    }

    DefaultSafeWebPageFetcher(int maxRedirects, int maxResponseBytes, Duration connectTimeout, Duration requestTimeout) {
        this.maxRedirects = Math.max(0, maxRedirects);
        this.maxResponseBytes = Math.max(1024, maxResponseBytes);
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(8) : requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

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

class SafeWebPageFetchException extends RuntimeException {

    SafeWebPageFetchException(String message) {
        super(message);
    }
}
