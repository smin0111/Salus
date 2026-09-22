package com.salus.healthytable.service.allergen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 정규화된 프로필 term의 exact identity resolution만 수행한다.
 * Spring bean이 아니며 6c ShadowObserver 내부에서만 runtime 호출한다. Matcher, parent, Evidence를 소비하지 않는다.
 */
public final class ProfileAllergenResolver {
    private final AllergenRegistry registry;

    public ProfileAllergenResolver(AllergenRegistry registry) { this.registry = Objects.requireNonNull(registry); }

    public ProfileResolution resolve(Collection<NormalizedProfileAllergenTerm> inputs) {
        List<ResolvedProfileAllergen> resolved = new ArrayList<>();
        List<UnresolvedProfileAllergen> unresolved = new ArrayList<>();
        // null 입력/원소는 계약 위반이다. unknown을 빈 Complete로 바꾸지 않는다.
        for (NormalizedProfileAllergenTerm input : List.copyOf(inputs)) {
            List<AllergenAlias> exact = registry.findExactAliases(input.value());
            List<AllergenAlias> eligible = exact.stream()
                    .filter(alias -> alias.relation() == AllergenAlias.Relation.DIRECT_NAME)
                    .sorted(Comparator.comparing(AllergenAlias::text).thenComparing(AllergenAlias::allergen))
                    .toList();
            Set<String> ids = ids(eligible);
            if (exact.isEmpty()) {
                unresolved.add(new UnresolvedProfileAllergen(input, ProfileUnresolvedReason.REGISTRY_MISS, Set.of()));
            } else if (eligible.isEmpty()) {
                unresolved.add(new UnresolvedProfileAllergen(input, ProfileUnresolvedReason.NO_PROFILE_ELIGIBLE_ALIAS, ids(exact)));
            } else if (ids.size() > 1) {
                unresolved.add(new UnresolvedProfileAllergen(input, ProfileUnresolvedReason.AMBIGUOUS, ids));
            } else {
                resolved.add(new ResolvedProfileAllergen(input, ids.iterator().next(), ProfileResolutionType.EXACT_ALIAS, eligible));
            }
        }
        return unresolved.isEmpty() ? new CompleteProfileResolution(resolved)
                : new PartialProfileResolution(resolved, unresolved);
    }

    private static Set<String> ids(List<AllergenAlias> aliases) {
        Set<String> ids = new TreeSet<>();
        aliases.forEach(alias -> ids.add(alias.allergen()));
        return ids;
    }
}
