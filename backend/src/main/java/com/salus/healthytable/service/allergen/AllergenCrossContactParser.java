package com.salus.healthytable.service.allergen;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 교차접촉 문구를 Evidence로 변환한다.
 * CERTAIN은 제조사가 가능성을 명시했다는 뜻이며 실제 함유나 최종 안전을 뜻하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AllergenCrossContactParser {
    // DOTALL: 줄바꿈이 있어도 '.'이 매칭되게 함, UNICODE_CHARACTER_CLASS: \s 등이 한글/유니코드 공백까지 인식하게 함
    private static final int FLAGS = Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS;
    // 지원하는 교차접촉 문구 형식 3가지. 캡처 그룹 1이 알레르겐 목록 부분입니다.
    // 예) "땅콩, 대두를 사용한 제품과 같은 제조시설에서 제조", "새우와 같은 제조시설", "메밀 혼입 가능"
    private static final List<Pattern> FORMATS = List.of(
            Pattern.compile("(.+?)(?:[을를]\\s*|\\s+)사용한\\s+제품과\\s+같은\\s+제조시설", FLAGS),
            // '사용하지 않은 제품과 ...'를 짧은 목록 형식으로 재해석하지 않는다.
            Pattern.compile("(.+?)(?<!제품)[와과]\\s+같은\\s+제조시설", FLAGS),
            Pattern.compile("(.+?)\\s+혼입\\s*가능", FLAGS));

    private final AllergenRegistry registry;

    // 읽을 수 있는 원문 문자열을 바로 파싱하는 편의 메서드입니다.
    public CrossContactParseResult parse(String rawText) {
        return parse(CrossContactInput.readable(rawText));
    }

    /**
     * 교차접촉 표시란을 파싱합니다.
     * - 원문이 없거나(NOT_FOUND) 읽을 수 없으면(UNREADABLE) 그 상태를 그대로 결과에 담고, 근거는 만들지 않습니다.
     * - 읽을 수 있으면 목록을 쉼표로 나눠 등록된 알레르겐 이름만 근거로 만들고, 나머지는 unparsedTokens에 남깁니다.
     * 지원하지 않는 문장 형식이면 추측하지 않고 IllegalArgumentException을 던집니다.
     */
    public CrossContactParseResult parse(CrossContactInput input) {
        Objects.requireNonNull(input);
        String raw = input.rawText();
        if (input.availability() != CrossContactInput.Availability.READABLE) {
            CrossContactState state = input.availability() == CrossContactInput.Availability.NOT_FOUND
                    ? CrossContactState.CROSS_CONTACT_NOT_FOUND : CrossContactState.UNREADABLE;
            return new CrossContactParseResult(state, raw, null, List.of(), List.of());
        }

        String listText = extractList(AllergenLabelTokens.stripWhitespace(raw));
        List<AllergenEvidence> evidence = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        for (String token : AllergenLabelTokens.splitAtTopLevel(listText)) {
            Optional<NormalizedAllergenRef> match =
                    AllergenLabelTokens.parseToken(token, registry::findDirectName);
            if (match.isEmpty()) {
                unparsed.add(token);
                continue;
            }
            evidence.add(new AllergenEvidence(AllergenEvidence.EvidenceSource.CROSS_CONTACT,
                    raw, token, match.get(), AllergenEvidence.EvidenceType.CROSS_CONTACT,
                    AllergenEvidence.MatchConfidence.CERTAIN, List.of()));
        }
        return new CrossContactParseResult(CrossContactState.CROSS_CONTACT_PRESENT, raw,
                raw.replaceAll("(?U)\\s+", " ").strip(), evidence, unparsed);
    }

    // 지원 형식 중 하나와 전체가 일치하면 알레르겐 목록 부분만 잘라서 반환합니다.
    private static String extractList(String text) {
        for (Pattern format : FORMATS) {
            Matcher match = format.matcher(text);
            if (match.matches()) {
                return match.group(1);
            }
        }
        throw new IllegalArgumentException("지원하지 않는 교차접촉 표시 형식입니다.");
    }
}
