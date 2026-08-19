package com.salus.healthytable.service.allergen;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 알레르겐 루트와 그 파생 재료를 담은 사전.
 *
 * <p>사용자가 프로필에 "우유"라고 적어도 레시피에는 버터, 치즈, 생크림으로 등장한다.
 * 문자열 포함만으로는 이런 파생 재료를 잡을 수 없어, 루트마다 파생 재료 목록을
 * 사전으로 관리한다.
 */
@Slf4j
@Component
public class AllergenDictionary {

    private final List<Allergen> allergens = new ArrayList<>();

    // 기본값을 두어 Spring 컨텍스트 없이도 로드된다. 사전 단위 테스트를 위해 필요하다.
    @Value("classpath:allergens/ko-allergens.yaml")
    private Resource source = new ClassPathResource(DEFAULT_PATH);

    private static final String DEFAULT_PATH = "allergens/ko-allergens.yaml";

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        try (InputStream input = source.getInputStream()) {
            Map<String, Object> root = new Yaml().load(input);
            List<Map<String, Object>> entries =
                    (List<Map<String, Object>>) root.getOrDefault("allergens", List.of());
            for (Map<String, Object> entry : entries) {
                allergens.add(new Allergen(
                        String.valueOf(entry.get("id")),
                        String.valueOf(entry.get("name")),
                        normalizeAll((List<String>) entry.getOrDefault("aliases", List.of())),
                        normalizeAll((List<String>) entry.getOrDefault("derived", List.of()))));
            }
            log.info("[AllergenDictionary] category=LOADED, allergenCount={}", allergens.size());
        } catch (Exception error) {
            // 사전을 읽지 못하면 판정이 조용히 느슨해진다. 그 상태로 뜨는 것보다 실패가 낫다.
            throw new IllegalStateException("알레르겐 사전을 불러오지 못했습니다.", error);
        }
    }

    /**
     * 사용자가 선언한 알레르기 표기에 대응하는 탐지 용어를 모두 돌려준다.
     *
     * <p>사전에 없는 표기는 입력값 자체를 용어로 사용한다. 사전에 없다고 판정을
     * 건너뛰면 등록된 알레르기가 조용히 무시되기 때문이다.
     */
    public Set<String> matchTermsFor(String declaredAllergy) {
        String normalized = normalize(declaredAllergy);
        if (normalized.isBlank()) {
            return Set.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (Allergen allergen : allergens) {
            if (allergen.matchesDeclaration(normalized)) {
                terms.addAll(allergen.aliases());
                terms.addAll(allergen.derived());
            }
        }
        if (terms.isEmpty()) {
            terms.add(normalized);
        }
        return terms;
    }

    /** 사전에 등록된 알레르겐인지 여부. 사용자 안내 문구를 나눌 때 쓴다. */
    public boolean isKnown(String declaredAllergy) {
        String normalized = normalize(declaredAllergy);
        return !normalized.isBlank()
                && allergens.stream().anyMatch(allergen -> allergen.matchesDeclaration(normalized));
    }

    public int size() {
        return allergens.size();
    }

    static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]", "");
    }

    private static Set<String> normalizeAll(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String term = normalize(value);
            if (!term.isBlank()) {
                normalized.add(term);
            }
        }
        return normalized;
    }

    record Allergen(String id, String name, Set<String> aliases, Set<String> derived) {

        boolean matchesDeclaration(String normalizedDeclaration) {
            if (aliases.contains(normalizedDeclaration) || derived.contains(normalizedDeclaration)) {
                return true;
            }
            // "우유 알레르기"처럼 수식어가 붙은 표기도 받아준다. 반대로 한 글자 별칭이
            // 긴 선언 안에 우연히 들어간 경우까지 끌어오지 않도록 두 글자 이상만 본다.
            return aliases.stream()
                    .anyMatch(alias -> alias.length() >= 2 && normalizedDeclaration.contains(alias));
        }
    }

    /** 테스트에서 사전을 직접 구성할 때 사용한다. */
    static AllergenDictionary of(Map<String, List<String>> aliasesByName,
            Map<String, List<String>> derivedByName) {
        AllergenDictionary dictionary = new AllergenDictionary();
        Map<String, List<String>> aliases = new LinkedHashMap<>(aliasesByName);
        aliases.forEach((name, aliasList) -> dictionary.allergens.add(new Allergen(
                name, name,
                normalizeAll(aliasList),
                normalizeAll(derivedByName.getOrDefault(name, List.of())))));
        return dictionary;
    }
}
