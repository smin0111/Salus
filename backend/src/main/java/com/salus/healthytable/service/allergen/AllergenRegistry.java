package com.salus.healthytable.service.allergen;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 기존 사전의 관계 정보를 사용하는 조회 뷰. 레시피 충돌 판정과는 매칭 규칙이 다르다. */
@Component
public class AllergenRegistry {

    private final List<AllergenAlias> aliases;
    private final Map<String, String> parents;

    /**
     * 사전의 관계 정보를 긴 이름 순으로 정렬해 보관합니다.
     * 사전이 비어 있으면 조용히 "알레르겐 없음"으로 동작하지 않도록 시작 시점에 실패시킵니다.
     */
    public AllergenRegistry(AllergenDictionary dictionary) {
        aliases = dictionary.registryAliases().stream()
                .sorted(Comparator.comparingInt((AllergenAlias alias) -> alias.text().length())
                        .reversed().thenComparing(AllergenAlias::text)
                        .thenComparing(AllergenAlias::allergen))
                .toList();
        if (aliases.isEmpty()) {
            throw new IllegalStateException("알레르겐 사전을 먼저 로드해야 합니다.");
        }
        parents = Map.copyOf(dictionary.registryParents());
        Set<String> ids = new HashSet<>(aliases.stream().map(AllergenAlias::allergen).toList());
        parents.forEach((child, parent) -> {
            if (!ids.contains(child) || !ids.contains(parent)) {
                throw new IllegalStateException("등록되지 않은 알레르겐 계층 ID: " + child + " -> " + parent);
            }
            Set<String> visited = new HashSet<>();
            for (String current = child; current != null; current = parents.get(current)) {
                if (!visited.add(current)) {
                    throw new IllegalStateException("알레르겐 계층 순환: " + child);
                }
            }
        });
    }

    /** 향후 토큰 매칭에서도 짧은 별칭을 먼저 선택하지 않도록 최장 순서를 제공한다. */
    public List<AllergenAlias> aliasesLongestFirst() {
        return aliases;
    }

    /**
     * 하나의 완전한 lexical unit만 조회한다. 미등록 긴 토큰을 짧은 substring으로 재시도하지 않는다.
     * 여러 관계가 있으면 모두 보존한다. Evidence 생성이나 문장/원재료 트리 파싱은 하지 않는다.
     */
    public List<AllergenAlias> findExactAliases(String token) {
        String normalized = token.strip().toLowerCase(Locale.ROOT);
        return aliases.stream()
                .filter(alias -> alias.text().toLowerCase(Locale.ROOT).equals(normalized))
                .toList();
    }

    /** 공식 표시에서는 파생어/어휘 힌트를 직접 명칭으로 승격하지 않는다. */
    public Optional<AllergenAlias> findDirectName(String token) {
        return findExactAliases(token).stream()
                .filter(alias -> alias.relation() == AllergenAlias.Relation.DIRECT_NAME).findFirst();
    }

    /** 데이터에 명시한 부모만 조회한다. 부모의 부재는 안전이나 비알레르겐을 뜻하지 않는다. */
    public Optional<String> parentOf(String allergen) {
        return Optional.ofNullable(parents.get(allergen));
    }
}
