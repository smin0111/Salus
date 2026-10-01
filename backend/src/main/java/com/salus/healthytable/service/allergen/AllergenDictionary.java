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

    // YAML에서 읽은 알레르겐 목록과, 라벨 파서용 관계 정보(registryAliases)
    private final List<Allergen> allergens = new ArrayList<>();
    private List<AllergenAlias> registryAliases = List.of();
    private Map<String, String> registryParents = Map.of();

    // 기본값을 두어 Spring 컨텍스트 없이도 로드된다. 사전 단위 테스트를 위해 필요하다.
    @Value("classpath:allergens/ko-allergens.yaml")
    private Resource source = new ClassPathResource(DEFAULT_PATH);

    private static final String DEFAULT_PATH = "allergens/ko-allergens.yaml";

    /**
     * ko-allergens.yaml을 읽어 사전을 채웁니다.
     * {@code @PostConstruct}: 스프링이 Bean을 만들고 의존성 주입을 마친 직후 한 번 자동으로 호출합니다.
     */
    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        try (InputStream input = source.getInputStream()) {
            Map<String, Object> root = new Yaml().load(input);
            List<Map<String, Object>> entries =
                    (List<Map<String, Object>>) root.getOrDefault("allergens", List.of());
            List<AllergenAlias> registryEntries = new ArrayList<>();
            for (Map<String, Object> entry : entries) {
                allergens.add(new Allergen(
                        String.valueOf(entry.get("id")),
                        String.valueOf(entry.get("name")),
                        normalizeAll((List<String>) entry.getOrDefault("aliases", List.of())),
                        normalizeAll((List<String>) entry.getOrDefault("derived", List.of()))));
            }
            // 라벨 전용 ID/별칭/계층은 기존 프로필 Matcher의 탐지 범위를 바꾸지 않는다.
            List<Map<String, Object>> registryDefinitions = new ArrayList<>(entries);
            registryDefinitions.addAll((List<Map<String, Object>>)
                    root.getOrDefault("declarationAllergens", List.of()));
            Map<String, String> parents = new LinkedHashMap<>();
            for (Map<String, Object> entry : registryDefinitions) {
                addRegistryAliases(registryEntries, entry);
                if (entry.containsKey("parent")) {
                    parents.put(String.valueOf(entry.get("id")), String.valueOf(entry.get("parent")));
                }
            }
            registryAliases = List.copyOf(registryEntries);
            registryParents = Map.copyOf(parents);
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

    // 로드된 알레르겐 개수
    public int size() {
        return allergens.size();
    }

    /** 직접 명칭/파생어/어휘 힌트의 관계를 보존한 읽기 전용 registry 데이터. */
    public List<AllergenAlias> registryAliases() {
        return registryAliases;
    }

    /** 명칭의 부모 관계만 저장한다. 사용자 충돌 판정이나 explicitChildren 추론에는 사용하지 않는다. */
    public Map<String, String> registryParents() {
        return registryParents;
    }

    @SuppressWarnings("unchecked")
    // YAML 항목 하나에서 이름/공식 별칭/파생어/어휘 힌트를 관계 정보와 함께 모읍니다.
    private static void addRegistryAliases(List<AllergenAlias> target, Map<String, Object> entry) {
        String id = String.valueOf(entry.get("id"));
        target.add(new AllergenAlias(String.valueOf(entry.get("name")), AllergenAlias.Relation.DIRECT_NAME, id));
        // 프로필용 aliases에는 글루텐/유제품 등 넓은 표현이 있어 공식 직접 명칭과 구분한다.
        for (String name : (List<String>) entry.getOrDefault("declarationAliases", List.of())) {
            target.add(new AllergenAlias(name, AllergenAlias.Relation.DIRECT_NAME, id));
        }
        for (String name : (List<String>) entry.getOrDefault("derived", List.of())) {
            target.add(new AllergenAlias(name, AllergenAlias.Relation.DERIVED_FROM, id));
        }
        for (String name : (List<String>) entry.getOrDefault("registryDerived", List.of())) {
            target.add(new AllergenAlias(name, AllergenAlias.Relation.DERIVED_FROM, id));
        }
        for (String name : (List<String>) entry.getOrDefault("lexicalHints", List.of())) {
            target.add(new AllergenAlias(name, AllergenAlias.Relation.LEXICAL_HINT, id));
        }
    }

    // 비교용 정규화: 소문자로 바꾸고 한글/영문 소문자/숫자 외 문자(공백, 기호)를 모두 제거합니다.
    static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]", "");
    }

    // 목록 전체를 정규화하고 빈 값은 버립니다.
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

    // 사전의 알레르겐 한 항목 (ID, 대표 이름, 별칭들, 파생 재료들)
    record Allergen(String id, String name, Set<String> aliases, Set<String> derived) {

        // 사용자가 적은 알레르기 표기가 이 알레르겐을 가리키는지 확인합니다.
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
