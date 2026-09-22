package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.salus.healthytable.service.allergen.RegulatoryLifecycle.*;

/** 오프라인 버전 설정. 시작 시 잘못된 범위/중복/계층을 거부한다. */
@Component
public class RegulatoryRuleRegistry {
    record Config(List<RegulatoryAllergenRuleSet> ruleSets) {}
    private final List<RegulatoryAllergenRuleSet> ruleSets;
    private final AllergenRegistry allergens;

    @Autowired
    public RegulatoryRuleRegistry(AllergenRegistry allergens) {
        this(loadBundled(), allergens);
    }

    public RegulatoryRuleRegistry(List<RegulatoryAllergenRuleSet> ruleSets, AllergenRegistry allergens) {
        this.ruleSets = List.copyOf(ruleSets);
        this.allergens = Objects.requireNonNull(allergens);
        if (ruleSets.isEmpty()) throw new IllegalArgumentException("규정 설정이 비었습니다.");
        if (ruleSets.stream().map(RegulatoryAllergenRuleSet::id).distinct().count() != ruleSets.size()) {
            throw new IllegalArgumentException("중복 rule set ID");
        }
        for (int i = 0; i < ruleSets.size(); i++) {
            var rule = ruleSets.get(i);
            for (String child : rule.regulatoryParents().keySet()) {
                Set<String> visited = new HashSet<>();
                for (String current = child; current != null;
                        current = rule.regulatoryParents().containsKey(current)
                                ? rule.regulatoryParents().get(current) : allergens.parentOf(current).orElse(null)) {
                    if (!visited.add(current)) throw new IllegalArgumentException("Regulatory hierarchy 순환");
                }
                var existing = allergens.parentOf(child);
                if (existing.isPresent() && !existing.get().equals(rule.regulatoryParents().get(child))) {
                    throw new IllegalArgumentException("Registry와 충돌하는 regulatory parent: " + child);
                }
                if (scope(rule, child).isEmpty()) {
                    throw new IllegalArgumentException("Regulatory hierarchy가 scope에 도달하지 않음: " + child);
                }
            }
            for (int j = i + 1; j < ruleSets.size(); j++) {
                var other = ruleSets.get(j);
                if (rule.lifecycle() == EFFECTIVE && other.lifecycle() == EFFECTIVE
                        && rule.jurisdiction().equals(other.jurisdiction())
                        && (rule.effectiveTo() == null || !other.effectiveFrom().isAfter(rule.effectiveTo()))
                        && (other.effectiveTo() == null || !rule.effectiveFrom().isAfter(other.effectiveTo()))) {
                    throw new IllegalArgumentException("RULESET_OVERLAP: " + rule.id() + " / " + other.id());
                }
            }
        }
    }

    private static List<RegulatoryAllergenRuleSet> loadBundled() {
        try (InputStream input = new ClassPathResource("regulations/kr-allergen-labeling.yaml").getInputStream()) {
            return read(input);
        } catch (Exception error) {
            throw new IllegalStateException("KR 규정 설정을 불러오지 못했습니다.", error);
        }
    }

    static List<RegulatoryAllergenRuleSet> read(InputStream input) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object yaml = new Yaml(new SafeConstructor(options)).load(input);
        return new ObjectMapper().registerModule(new JavaTimeModule()).convertValue(yaml, Config.class).ruleSets();
    }

    public List<RegulatoryAllergenRuleSet> ruleSets() { return ruleSets; }

    public Optional<RegulatoryAllergenRuleSet> selectEffective(String jurisdiction, LocalDate date) {
        Objects.requireNonNull(date);
        return ruleSets.stream().filter(r -> r.lifecycle() == EFFECTIVE && r.jurisdiction().equals(jurisdiction)
                && !date.isBefore(r.effectiveFrom()) && (r.effectiveTo() == null || !date.isAfter(r.effectiveTo()))).findFirst();
    }

    /** Parser parent를 재사용하고 해당 법규 버전에 명시한 scope parent만 보충한다. */
    public Optional<RegulatoryAllergenRuleSet.Rule> scope(RegulatoryAllergenRuleSet rule, String allergen) {
        Set<String> visited = new HashSet<>();
        for (String current = allergen; current != null;
                current = rule.regulatoryParents().containsKey(current)
                        ? rule.regulatoryParents().get(current) : allergens.parentOf(current).orElse(null)) {
            if (!visited.add(current)) throw new IllegalArgumentException("Regulatory hierarchy 순환");
            String id = current;
            var found = rule.rules().stream().filter(r -> r.allergen().equals(id)).findFirst();
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    public boolean proposedOnly(String jurisdiction, LocalDate date, String allergen) {
        return ruleSets.stream().filter(r -> r.lifecycle() == PROPOSED && r.jurisdiction().equals(jurisdiction)
                && !date.isBefore(r.source().publishedAt())).anyMatch(r -> scope(r, allergen).isPresent());
    }
}
