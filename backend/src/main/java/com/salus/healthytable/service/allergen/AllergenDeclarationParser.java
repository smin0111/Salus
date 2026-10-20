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
 * 공식 함유 표시란의 "명칭 목록 함유" 또는 "해당사항 없음"을 Evidence로 변환한다.
 * 전체 라벨, 작성자 메모, 교차접촉 문장은 입력 대상이 아니다.
 * 잘못된 형식은 예외로 알리며, 표시 없음/읽기 실패로 추정하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AllergenDeclarationParser {

    // "우유, 대두, 밀 함유" 형태에서 "함유" 앞의 목록을 캡처합니다.
    private static final Pattern PRESENT = Pattern.compile("(.+?)\\s+함유", Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    private final AllergenRegistry registry;

    // 읽을 수 있는 원문 문자열을 바로 파싱하는 편의 메서드입니다.
    public DeclarationParseResult parse(String rawText) {
        return parse(DeclarationInput.readable(rawText));
    }

    /**
     * 공식 함유 표시란을 파싱합니다.
     * - NOT_FOUND/UNREADABLE 입력: 상태만 기록하고 근거는 만들지 않습니다.
     * - "해당사항 없음": DECLARED_NONE (라벨이 명시적으로 없음을 표시한 경우에만 해당)
     * - "... 함유": 등록된 공식 명칭만 CERTAIN 근거로 만들고, 인식하지 못한 항목은 unparsedTokens에 남깁니다.
     */
    public DeclarationParseResult parse(DeclarationInput input) {
        Objects.requireNonNull(input);
        String raw = input.rawText();
        if (input.availability() != DeclarationInput.Availability.READABLE) {
            DeclarationState state = input.availability() == DeclarationInput.Availability.NOT_FOUND
                    ? DeclarationState.DECLARATION_NOT_FOUND : DeclarationState.UNREADABLE;
            return new DeclarationParseResult(state, raw, null, List.of(), List.of());
        }

        String normalized = raw.replaceAll("(?U)\\s+", " ").strip();
        if (normalized.equals("해당사항 없음")) {
            return new DeclarationParseResult(DeclarationState.DECLARED_NONE, raw, normalized, List.of(), List.of());
        }

        // 원문에서 토큰을 잘라 matchedText의 공백/개행도 실제 라벨 그대로 보존한다.
        Matcher declaration = PRESENT.matcher(AllergenLabelTokens.stripWhitespace(raw));
        if (!declaration.matches()) {
            throw new IllegalArgumentException("지원하지 않는 공식 함유 표시 형식입니다.");
        }
        List<AllergenEvidence> evidence = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        for (String token : AllergenLabelTokens.splitAtTopLevel(declaration.group(1))) {
            Optional<NormalizedAllergenRef> match = AllergenLabelTokens.parseToken(token, registry::findDirectName);
            if (match.isEmpty()) {
                unparsed.add(token);
                continue;
            }
            evidence.add(new AllergenEvidence(AllergenEvidence.EvidenceSource.DECLARATION,
                    raw, token, match.get(), AllergenEvidence.EvidenceType.DIRECT_DECLARATION,
                    AllergenEvidence.MatchConfidence.CERTAIN, List.of()));
        }
        return new DeclarationParseResult(DeclarationState.DECLARED_PRESENT, raw, normalized, evidence, unparsed);
    }

}
