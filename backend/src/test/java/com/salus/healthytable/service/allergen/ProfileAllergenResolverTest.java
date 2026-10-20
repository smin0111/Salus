package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.salus.healthytable.service.allergen.AllergenAlias.Relation.*;
import static com.salus.healthytable.service.allergen.ProfileTermSource.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ProfileAllergenResolverTest {
    static final AllergenDictionary DICTIONARY = dictionary();
    static final AllergenRegistry REGISTRY = new AllergenRegistry(DICTIONARY);
    static final ProfileAllergenResolver RESOLVER = new ProfileAllergenResolver(REGISTRY);

    private static AllergenDictionary dictionary() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return dictionary;
    }

    static NormalizedProfileAllergenTerm term(String value) {
        return new NormalizedProfileAllergenTerm(value, STORED_HEALTH_PROFILE);
    }

    @ParameterizedTest
    @CsvSource({"우유,MILK", "대두,SOY", "밀,WHEAT", "게,CRAB", "잣,PINE_NUT", "굴,OYSTER",
            "전복,ABALONE", "홍합,MUSSEL", "메추리알,QUAIL_EGG", "알류,EGG_GROUP", "난류,EGG_GROUP", "조개류,SHELLFISH"})
    void resolvesOnlyDirectIdentityWithoutParentExpansion(String value, String id) {
        var result = (CompleteProfileResolution) RESOLVER.resolve(List.of(term(value)));
        assertThat(result.resolved()).singleElement().satisfies(resolved -> {
            assertThat(resolved.input()).isEqualTo(term(value));
            assertThat(resolved.allergenId()).isEqualTo(id);
            assertThat(resolved.resolutionType()).isEqualTo(ProfileResolutionType.EXACT_ALIAS);
            assertThat(resolved.matchedAliases()).containsExactly(new AllergenAlias(value, DIRECT_NAME, id));
            assertThat(resolved.matchedAliases().get(0).confidence()).isEqualTo(AllergenEvidence.MatchConfidence.CERTAIN);
        });
    }

    @Test
    void preservesEveryOccurrenceSourceAndInputOrder() {
        var inputs = new ArrayList<NormalizedProfileAllergenTerm>();
        for (ProfileTermSource source : ProfileTermSource.values()) inputs.add(new NormalizedProfileAllergenTerm("우유", source));
        inputs.add(inputs.get(0));
        inputs.add(term("굴"));
        inputs.add(term("메추리알"));
        var result = (CompleteProfileResolution) RESOLVER.resolve(inputs);
        assertThat(result.resolved()).extracting(ResolvedProfileAllergen::input).containsExactlyElementsOf(inputs);
        assertThat(result.resolved()).extracting(ResolvedProfileAllergen::allergenId)
                .containsExactly("MILK", "MILK", "MILK", "MILK", "OYSTER", "QUAIL_EGG");
        assertThat(ProfileTermSource.values()).containsExactly(CHAT_MESSAGE, CHAT_REQUEST_PROFILE, STORED_HEALTH_PROFILE);
        inputs.clear();
        assertThat(result.resolved()).hasSize(6);
        assertThatThrownBy(() -> result.resolved().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.resolved().get(0).matchedAliases().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void partialKeepsResolvedAndUnresolvedWithoutDisablingLegacyFallback() {
        var result = (PartialProfileResolution) RESOLVER.resolve(List.of(term("우유"), term("키위"), term("굴"), term("김")));
        assertThat(result.resolved()).extracting(ResolvedProfileAllergen::allergenId).containsExactly("MILK", "OYSTER");
        assertThat(result.unresolved()).extracting(UnresolvedProfileAllergen::input).containsExactly(term("키위"), term("김"));
        assertThat(result.unresolved()).allSatisfy(unresolved -> {
            assertThat(unresolved.reason()).isEqualTo(ProfileUnresolvedReason.REGISTRY_MISS);
            assertThat(unresolved.candidateAllergenIds()).isEmpty();
        });
        AllergenMatcher matcher = new AllergenMatcher(DICTIONARY);
        assertThat(matcher.conflicts("키위", "키위 2개")).isTrue();
        assertThat(matcher.conflicts("김", "김 2장")).isTrue();
        assertThat(matcher.conflicts("김", "김치 100g")).isFalse();
        assertThatThrownBy(() -> result.unresolved().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"굴소스", "조개육수", "대두레시틴", "우유향", "콩"})
    void exactDerivedAndHintRowsDoNotBecomeProfileIdentity(String value) {
        var result = (PartialProfileResolution) RESOLVER.resolve(List.of(term(value)));
        assertThat(result.resolved()).isEmpty();
        assertThat(result.unresolved()).singleElement().satisfies(unresolved -> {
            assertThat(unresolved.reason()).isEqualTo(ProfileUnresolvedReason.NO_PROFILE_ELIGIBLE_ALIAS);
            assertThat(unresolved.candidateAllergenIds()).isNotEmpty();
        });
    }

    @Test
    void multipleMetadataRowsForOneEligibleIdAreNotAmbiguousAndOrderIsDeterministic() {
        AllergenRegistry registry = mock(AllergenRegistry.class);
        var directA = new AllergenAlias("Milk", DIRECT_NAME, "MILK");
        var directB = new AllergenAlias("milk", DIRECT_NAME, "MILK");
        var derived = new AllergenAlias("milk", DERIVED_FROM, "MILK");
        when(registry.findExactAliases("milk")).thenReturn(List.of(derived, directB, directA, directA),
                List.of(directA, derived, directA, directB));
        var resolver = new ProfileAllergenResolver(registry);
        var first = (CompleteProfileResolution) resolver.resolve(List.of(term("milk")));
        assertThat(first).isEqualTo(resolver.resolve(List.of(term("milk"))));
        assertThat(first.resolved()).singleElement().satisfies(resolved -> {
            assertThat(resolved.allergenId()).isEqualTo("MILK");
            assertThat(resolved.matchedAliases()).containsExactly(directA, directA, directB);
        });
    }

    @Test
    void distinctEligibleIdsAreAmbiguousEvenWhenOtherTermsResolve() {
        AllergenRegistry registry = mock(AllergenRegistry.class);
        when(registry.findExactAliases("우유")).thenReturn(List.of(new AllergenAlias("우유", DIRECT_NAME, "MILK")));
        when(registry.findExactAliases("합성명")).thenReturn(List.of(
                new AllergenAlias("합성명", DIRECT_NAME, "SOY"), new AllergenAlias("합성명", DIRECT_NAME, "MILK")));
        var result = (PartialProfileResolution) new ProfileAllergenResolver(registry)
                .resolve(List.of(term("우유"), term("합성명")));
        assertThat(result.resolved()).extracting(ResolvedProfileAllergen::allergenId).containsExactly("MILK");
        assertThat(result.unresolved()).singleElement().satisfies(unresolved -> {
            assertThat(unresolved.input()).isEqualTo(term("합성명"));
            assertThat(unresolved.reason()).isEqualTo(ProfileUnresolvedReason.AMBIGUOUS);
            assertThat(unresolved.candidateAllergenIds()).containsExactly("MILK", "SOY");
            assertThatThrownBy(() -> unresolved.candidateAllergenIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void noCommonResolvedAccessorRequiresExplicitCompletePartialHandling() {
        assertThat(ProfileResolution.class.isSealed()).isTrue();
        assertThat(ProfileResolution.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(CompleteProfileResolution.class, PartialProfileResolution.class);
        assertThat(ProfileResolution.class.getDeclaredMethods()).isEmpty();
        assertThat(RESOLVER.resolve(List.of())).isEqualTo(new CompleteProfileResolution(List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"저 굴 알레르기 있어요", "우유 먹으면 안 돼요", "땅콩 조심해야 합니다", "밀크", "우유/대두"})
    void doesNotParseRawSentencesSplitTermsOrRetrySubstrings(String value) {
        var input = term(value);
        assertThat(input.value()).isEqualTo(value);
        var result = (PartialProfileResolution) RESOLVER.resolve(List.of(input));
        assertThat(result.unresolved()).containsExactly(new UnresolvedProfileAllergen(input, ProfileUnresolvedReason.REGISTRY_MISS, Set.of()));
    }

    @Test
    void passesInputUnchangedToExactLookupAndDoesNotConsultHierarchy() {
        AllergenRegistry registry = mock(AllergenRegistry.class);
        when(registry.findExactAliases(" 우유 ")).thenReturn(List.of(new AllergenAlias("우유", DIRECT_NAME, "MILK")));
        var input = term(" 우유 ");
        var result = (CompleteProfileResolution) new ProfileAllergenResolver(registry).resolve(List.of(input));
        assertThat(result.resolved().get(0).input().value()).isEqualTo(" 우유 ");
        verify(registry).findExactAliases(" 우유 ");
        verifyNoMoreInteractions(registry);
    }

    @Test
    void rejectsInvalidInputsAndContradictoryResultShapes() {
        assertThatThrownBy(() -> term(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> term(" \n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NormalizedProfileAllergenTerm("우유", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RESOLVER.resolve(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RESOLVER.resolve(Arrays.asList(term("우유"), null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PartialProfileResolution(List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnresolvedProfileAllergen(term("키위"), ProfileUnresolvedReason.REGISTRY_MISS, Set.of("MILK")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnresolvedProfileAllergen(term("우유"), ProfileUnresolvedReason.AMBIGUOUS, Set.of("MILK")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedProfileAllergen(term("우유"), "MILK", ProfileResolutionType.EXACT_ALIAS,
                List.of(new AllergenAlias("우유", DERIVED_FROM, "MILK")))).isInstanceOf(IllegalArgumentException.class);
    }
}
