package com.salus.healthytable.service.allergen;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 알레르겐 충돌 판정의 단일 진입점.
 *
 * <p>채팅 경로와 Recipe Agent 경로가 각자 판정 규칙을 갖고 있으면 같은 사용자·같은
 * 요리에 서로 다른 결론이 나온다. 안전 판정은 한 곳에서만 내린다.
 */
@Component
@RequiredArgsConstructor
public class AllergenMatcher {

    private final AllergenDictionary dictionary;

    /**
     * 주어진 텍스트들에서 선언된 알레르기와 충돌하는 항목을 찾는다.
     *
     * @param declaredAllergies 사용자 프로필에 등록된 알레르기 표기
     * @param texts             레시피 제목, 재료, 조리 단계 등 검사 대상
     * @return 충돌한 알레르기 표기 목록. 비어 있으면 충돌 없음
     */
    public List<String> findConflicts(List<String> declaredAllergies, List<String> texts) {
        if (declaredAllergies == null || declaredAllergies.isEmpty()) {
            return List.of();
        }
        List<String> tokens = tokenize(texts);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (String allergy : declaredAllergies) {
            if (allergy == null || allergy.isBlank()) {
                continue;
            }
            if (matchesAny(dictionary.matchTermsFor(allergy), tokens)) {
                conflicts.add(allergy.trim());
            }
        }
        return List.copyOf(conflicts);
    }

    /** 텍스트 하나가 특정 알레르기와 충돌하는지 확인한다. */
    public boolean conflicts(String declaredAllergy, String text) {
        return !findConflicts(List.of(declaredAllergy), List.of(text)).isEmpty();
    }

    /**
     * 선언한 알레르기 표기 자체가 텍스트에 등장하는지 확인한다.
     *
     * <p>파생 재료로만 걸린 경우와 구분하기 위해 쓴다. "들깨"처럼 재료명이 그대로
     * 등장하면 그 재료만 빼서 제공할 수 있지만, "우유"가 버터·치즈로만 등장하면
     * 이름을 지운다고 알레르겐이 사라지지 않는다.
     */
    public boolean matchesLiterally(String declaredAllergy, List<String> texts) {
        String term = AllergenDictionary.normalize(declaredAllergy);
        if (term.isBlank()) {
            return false;
        }
        for (String token : tokenize(texts)) {
            if (matches(term, token)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAny(Set<String> terms, List<String> tokens) {
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            for (String token : tokens) {
                if (matches(term, token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 한 글자 용어는 토큰 전체가 같을 때만 인정한다.
     *
     * <p>"밀"이 "밀크"에, "게"가 "돌게이"에 걸리는 오탐을 막기 위해서다. 한 글자
     * 알레르겐의 실제 탐지는 사전의 파생 재료 목록(밀가루, 국수, 게살 등)이 담당한다.
     */
    private boolean matches(String term, String token) {
        return term.length() == 1 ? token.equals(term) : token.contains(term);
    }

    private List<String> tokenize(List<String> texts) {
        Set<String> tokens = new LinkedHashSet<>();
        if (texts == null) {
            return List.of();
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String normalized = text.toLowerCase(Locale.ROOT);
            for (String raw : normalized.split("[^가-힣a-z0-9]+")) {
                String token = raw.trim();
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
        }
        return List.copyOf(tokens);
    }
}
