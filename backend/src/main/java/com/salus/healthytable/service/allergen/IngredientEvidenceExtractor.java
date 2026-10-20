package com.salus.healthytable.service.allergen;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Tree를 Registry에 정확히 조회한다. 다른 source의 Evidence와 합치거나 최종 판정하지 않는다. */
@Component
@RequiredArgsConstructor
public class IngredientEvidenceExtractor {
    private final AllergenRegistry registry;

    public List<AllergenEvidence> extractEvidence(IngredientTree tree) {
        Objects.requireNonNull(tree);
        if (tree.state() != IngredientTree.State.PARSED) return List.of();
        Map<Key, AllergenEvidence> evidence = new LinkedHashMap<>();
        tree.roots().forEach(node -> collect(node, List.of(), tree.rawText(), evidence));
        return List.copyOf(evidence.values());
    }

    private void collect(IngredientNode node, List<String> ancestors, String raw,
            Map<Key, AllergenEvidence> evidence) {
        List<String> path = new ArrayList<>(ancestors);
        path.add(node.normalizedName());
        path = List.copyOf(path);
        for (AllergenAlias alias : registry.findExactAliases(node.semanticName().normalizedName())) {
            AllergenEvidence.EvidenceType type = switch (alias.relation()) {
                case DIRECT_NAME -> AllergenEvidence.EvidenceType.DIRECT_NAME;
                case DERIVED_FROM -> AllergenEvidence.EvidenceType.DERIVED_FROM;
                case LEXICAL_HINT -> AllergenEvidence.EvidenceType.LEXICAL_HINT;
            };
            evidence.putIfAbsent(new Key(path, alias.allergen(), type),
                    new AllergenEvidence(AllergenEvidence.EvidenceSource.INGREDIENT, raw, node.name(),
                            new NormalizedAllergenRef(alias.allergen(), List.of()), type, alias.confidence(), path));
        }
        for (IngredientNode child : node.children()) collect(child, path, raw, evidence);
    }

    private record Key(List<String> path, String allergen, AllergenEvidence.EvidenceType relation) {}
}
