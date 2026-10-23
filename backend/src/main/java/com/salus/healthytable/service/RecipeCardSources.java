package com.salus.healthytable.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.dto.ChatDto;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 레시피 카드의 출처 정보(chat_recipe_cards.sources_json)를 만듭니다.
 *
 * <p>역할(role)은 다음 중 하나입니다.
 * REGISTERED(승인 레시피에 등록된 출처), PRIMARY(레시피를 추출한 원본), COMPARED(추출하며 비교한 문서),
 * INHERITED(대화로 고친 카드가 이어받아 보여 주는 직전 카드의 출처), UNCLASSIFIED(역할을 확인할 수 없는 표시 출처),
 * RETRIEVED(AI 생성 때 검색되어 LLM에 제공된 자료. 실제로 인용했다는 증거가 아니므로 사용자에게 출처로 표시하지 않음).
 */
final class RecipeCardSources {

    static final int MAX_RETRIEVED = 5;
    static final int MAX_URL_LENGTH = 500;
    static final int MAX_TITLE_LENGTH = 200;

    // 이름에 이런 낱말이 들어간 쿼리 파라미터는 토큰·개인정보일 수 있어 저장하지 않습니다.
    private static final Set<String> SENSITIVE_PARAM_PARTS = Set.of(
            "token", "key", "sig", "auth", "session", "sess", "code", "password", "passwd", "pwd",
            "email", "secret", "credential", "ticket");

    private RecipeCardSources() {
    }

    /** sources_json 한 줄입니다. 값이 없는 필드는 JSON에 넣지 않습니다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Entry(
            String role,
            String sourceType,
            String url,
            String title,
            String originId,
            Integer rank,
            String provider,
            LocalDateTime retrievedAt) {
    }

    /** AI 생성에 제공된 근거 한 건입니다. 웹 검색 결과면 url, 살루스 DB 레시피면 originId가 있습니다. */
    record Retrieved(String sourceType, String title, String url, String originId) {
    }

    static List<Entry> build(
            RecipeCardProvenance provenance,
            List<ChatDto.SourceLink> shownSources,
            List<Retrieved> retrieved,
            String provider,
            LocalDateTime now) {
        List<Entry> entries = new ArrayList<>();
        List<ChatDto.SourceLink> shown = shownSources == null ? List.of() : shownSources;
        for (int i = 0; i < shown.size(); i++) {
            ChatDto.SourceLink link = shown.get(i);
            if (link == null) {
                continue;
            }
            String sourceType = provenance == RecipeCardProvenance.APPROVED_CATALOG
                    ? "APPROVED_SOURCE"
                    : (link.isReviewed() ? "INTERNAL_DB" : "WEB");
            entries.add(new Entry(shownRole(provenance, i), sourceType, sanitizeUrl(link.getUrl()),
                    truncate(link.getTitle()), null, null, null, null));
        }
        int rank = 0;
        for (Retrieved item : retrieved == null ? List.<Retrieved>of() : retrieved) {
            if (rank >= MAX_RETRIEVED) {
                break;
            }
            if (item == null) {
                continue;
            }
            String url = sanitizeUrl(item.url());
            if (url == null && item.originId() == null) {
                // 링크도 DB ID도 없으면 무엇이 제공됐는지 가리킬 수 없으므로 남기지 않습니다.
                continue;
            }
            rank++;
            entries.add(new Entry("RETRIEVED", item.sourceType(), url, truncate(item.title()), item.originId(),
                    rank, provider, now));
        }
        return entries;
    }

    private static String shownRole(RecipeCardProvenance provenance, int index) {
        if (provenance == null) {
            return "UNCLASSIFIED";
        }
        return switch (provenance) {
            case APPROVED_CATALOG -> "REGISTERED";
            case WEB_EXTRACTED, INTERNAL_DB_EXTRACTED -> index == 0 ? "PRIMARY" : "COMPARED";
            case VARIANT -> "INHERITED";
            case AI_FROM_INTERNAL_DB, AI_FROM_WEB_SEARCH -> "UNCLASSIFIED";
        };
    }

    /**
     * 저장할 URL을 정리합니다. http/https만 받고, #뒤 조각과 토큰·개인정보로 보이는 쿼리 파라미터를 지웁니다.
     * 나머지 파라미터(블로그 글 번호 등)는 링크가 깨지지 않게 남깁니다. 정리할 수 없거나 너무 길면 null입니다.
     */
    static String sanitizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getRawAuthority() == null) {
            return null;
        }
        StringBuilder url = new StringBuilder(scheme).append("://").append(uri.getRawAuthority());
        if (uri.getRawPath() != null) {
            url.append(uri.getRawPath());
        }
        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) {
            List<String> kept = new ArrayList<>();
            for (String pair : query.split("&")) {
                String name = decodedParamName(pair.split("=", 2)[0]);
                if (name != null && !name.isBlank() && SENSITIVE_PARAM_PARTS.stream().noneMatch(name::contains)) {
                    kept.add(pair);
                }
            }
            if (!kept.isEmpty()) {
                url.append('?').append(String.join("&", kept));
            }
        }
        // 사용자 정보(user:password@host)가 들어간 주소는 저장하지 않습니다.
        if (uri.getRawUserInfo() != null || url.length() > MAX_URL_LENGTH) {
            return null;
        }
        return url.toString();
    }

    /**
     * 파라미터 이름을 디코딩해 소문자로 돌려줍니다("to%6ben" → "token"). 이중 인코딩도 풀리도록 바뀌지 않을 때까지
     * 몇 번 더 디코딩하고, 잘못된 인코딩이거나 끝까지 풀리지 않으면 null(= 저장하지 않음)입니다.
     */
    private static String decodedParamName(String rawName) {
        String name = rawName;
        for (int i = 0; i < 3; i++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (decoded.equals(name)) {
                return decoded.toLowerCase(Locale.ROOT);
            }
            name = decoded;
        }
        return null;
    }

    private static String truncate(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String trimmed = title.trim();
        return trimmed.length() > MAX_TITLE_LENGTH ? trimmed.substring(0, MAX_TITLE_LENGTH) : trimmed;
    }
}
