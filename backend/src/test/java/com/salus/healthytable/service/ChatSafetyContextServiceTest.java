package com.salus.healthytable.service;

import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.service.allergen.AllergenAlias;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenRegistry;
import com.salus.healthytable.service.allergen.ProfileResolutionShadowObserver;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChatSafetyContextService} 테스트입니다. 건강 정보 조회 실패가 "조건 없음"으로 바뀌지 않는지(fail closed) 확인합니다.
 */
class ChatSafetyContextServiceTest {
    private static AllergenRegistry allergenRegistry() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenRegistry(dictionary);
    }

    private ChatSafetyContextService service(HealthProfileRepository profiles, AllergenRegistry registry) {
        return new ChatSafetyContextService(profiles, mock(HealthCheckupRepository.class),
                mock(HealthCheckupAnalysisService.class), allergenMatcher(), registry, mock(ProfileResolutionShadowObserver.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void structuredProfilesPreserveKnownAndUnknownSingleCharacterTermsThroughMatching(boolean stored) {
        HealthProfileRepository profiles = mock(HealthProfileRepository.class);
        AllergenRegistry registry = mock(AllergenRegistry.class);
        ChatSafetyContextService service = service(profiles, registry);
        List<String> terms = Arrays.asList(" 굴 ", "김", "밀", "게", "잣", "콩", "우유", "대두", "복숭아", "굴", null, " ", "!!!");
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("레시피 알려줘");
        if (stored) {
            HealthProfile profile = new HealthProfile();
            profile.setAllergies(terms);
            when(profiles.findByUserId(1L)).thenReturn(Optional.of(profile));
        } else {
            request.setHealthProfile(new ChatDto.HealthProfileContext(terms, List.of(), List.of(), List.of(), List.of()));
        }

        var context = service.build(stored ? Optional.of(1L) : Optional.empty(), request);

        assertThat(context.allergies()).containsExactly("굴", "김", "밀", "게", "잣", "콩", "우유", "대두", "복숭아");
        org.mockito.Mockito.verifyNoInteractions(registry);
        Recipe recipe = new Recipe();
        recipe.setIngredients(List.of("굴 100g", "김 2장"));
        assertThat(service.findAllergyConflicts(context, "요리", recipe, "굴 빼고"))
                .contains("굴", "김");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void structuredProfilesDoNotApplyNaturalLanguageLengthOrStopWordHeuristics(boolean stored) {
        HealthProfileRepository profiles = mock(HealthProfileRepository.class);
        ChatSafetyContextService service = service(profiles, allergenRegistry());
        List<String> terms = List.of("가".repeat(20) + "김", "건강정보", "우유/대두·복숭아", "키위 알레르기 있어요");
        ChatDto.Request request = new ChatDto.Request();
        if (stored) {
            HealthProfile profile = new HealthProfile();
            profile.setAllergies(terms);
            when(profiles.findByUserId(1L)).thenReturn(Optional.of(profile));
        } else {
            request.setHealthProfile(new ChatDto.HealthProfileContext(terms, List.of(), List.of(), List.of(), List.of()));
        }
        assertThat(service.build(stored ? Optional.of(1L) : Optional.empty(), request).allergies())
                .containsExactly("가".repeat(20) + "김", "건강정보", "우유", "대두", "복숭아", "키위");
    }

    @ParameterizedTest
    @ValueSource(strings = {"굴", "밀", "게", "잣", "우유", "대두", "복숭아", "키위"})
    void explicitChatAllergyMentionsRetainKnownSingleCharactersAndExistingLongerTerms(String term) {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage(term + " 알레르기 있어요");
        assertThat(service(mock(HealthProfileRepository.class), allergenRegistry())
                .build(Optional.empty(), request).allergies()).containsExactly(term);
    }

    @ParameterizedTest
    @ValueSource(strings = {"김 알레르기 있어요", "콩 알레르기 있어요", "오늘 굴 먹었는데", "건강정보 알레르기"})
    void chatCandidatesDoNotPromoteUnknownCharactersLexicalHintsOrOrdinaryFoodMentions(String message) {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage(message);
        assertThat(service(mock(HealthProfileRepository.class), allergenRegistry())
                .build(Optional.empty(), request).allergies()).isEmpty();
    }

    @Test
    void historyUsesExtractedCandidatePolicyAndIgnoresModelMessages() {
        ChatDto.Request request = new ChatDto.Request();
        request.setHistory(List.of(new ChatDto.Message("user", "굴 알레르기 있어요"),
                new ChatDto.Message("user", "김 알레르기 있어요"),
                new ChatDto.Message("model", "게 알레르기 있어요")));
        assertThat(service(mock(HealthProfileRepository.class), allergenRegistry())
                .build(Optional.empty(), request).allergies()).containsExactly("굴");
    }

    @Test
    void ambiguousDirectNamesAreRejectedButDuplicateIdentityIsNotAmbiguity() {
        AllergenRegistry registry = mock(AllergenRegistry.class);
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), registry);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김 알레르기 있어요");
        AllergenAlias first = new AllergenAlias("김", AllergenAlias.Relation.DIRECT_NAME, "TEST_A");
        when(registry.findExactAliases("김")).thenReturn(List.of(first,
                new AllergenAlias("김", AllergenAlias.Relation.DIRECT_NAME, "TEST_B")));
        assertThat(service.build(Optional.empty(), request).allergies()).isEmpty();
        when(registry.findExactAliases("김")).thenReturn(List.of(first, first));
        assertThat(service.build(Optional.empty(), request).allergies()).containsExactly("김");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DERIVED_FROM", "LEXICAL_HINT"})
    void singleCharacterCandidateRequiresDirectIdentityRatherThanOtherExactRelations(String relation) {
        AllergenRegistry registry = mock(AllergenRegistry.class);
        when(registry.findExactAliases("김")).thenReturn(List.of(
                new AllergenAlias("김", AllergenAlias.Relation.valueOf(relation), "TEST_A")));
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김 알레르기 있어요");
        assertThat(service(mock(HealthProfileRepository.class), registry)
                .build(Optional.empty(), request).allergies()).isEmpty();
    }

    // 실제 알레르겐 사전 파일을 읽어 Matcher를 만듭니다.
    private static ChatSafetyContextService.SafetyContext allergyContext(String... allergies) {
        return new ChatSafetyContextService.SafetyContext(
                List.of(allergies), List.of(), List.of(), List.of(), List.of(), true);
    }

    // 일반 대화 답변에 등록 알레르겐이 나오면 차단하지 않고 확인 문구를 덧붙입니다.
    @Test
    void generalChatReplyMentioningRegisteredAllergenGetsCautionNote() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));
        String reply = "해물파전 대신 새우전을 추천드려요.";

        String result = service.appendAllergyCautionIfMentioned(allergyContext("새우"), reply);

        assertThat(result).startsWith(reply).contains("※ 등록하신 알레르기(새우)");
    }

    // 파생 재료(버터)도 같은 매처로 잡습니다. 알레르겐 단어가 직접 나오지 않아도 확인 문구가 붙어야 합니다.
    @Test
    void generalChatReplyWithDerivedIngredientGetsCautionNote() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        String result = service.appendAllergyCautionIfMentioned(allergyContext("우유"), "버터에 구운 감자를 추천해요.");

        assertThat(result).contains("※ 등록하신 알레르기(우유)");
    }

    // 알레르겐과 무관한 답변, 한 글자 알레르겐의 부분 일치(밀 vs 밀크)는 그대로 둡니다.
    @Test
    void generalChatReplyWithoutAllergenStaysUnchanged() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        assertThat(service.appendAllergyCautionIfMentioned(allergyContext("새우"), "김치전을 추천해요."))
                .isEqualTo("김치전을 추천해요.");
        assertThat(service.appendAllergyCautionIfMentioned(allergyContext("밀"), "밀크티를 곁들여 보세요."))
                .isEqualTo("밀크티를 곁들여 보세요.");
    }

    // 등록 알레르기가 없으면 답변을 건드리지 않습니다.
    @Test
    void generalChatReplyWithoutRegisteredAllergyStaysUnchanged() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        assertThat(service.appendAllergyCautionIfMentioned(allergyContext(), "새우전을 추천해요."))
                .isEqualTo("새우전을 추천해요.");
    }

    private static com.salus.healthytable.service.allergen.AllergenMatcher allergenMatcher() {
        com.salus.healthytable.service.allergen.AllergenDictionary dictionary =
                new com.salus.healthytable.service.allergen.AllergenDictionary();
        dictionary.load();
        return new com.salus.healthytable.service.allergen.AllergenMatcher(dictionary);
    }


    // 건강 프로필 조회가 실패하면 healthContextAvailable=false여야 하고, 메시지에서 읽은 알레르기는 유지해야 합니다.
    @Test
    void healthProfileReadFailureIsNotTreatedAsAvailablePersonalizationContext() {
        HealthProfileRepository healthProfileRepository = mock(HealthProfileRepository.class);
        ChatSafetyContextService service = new ChatSafetyContextService(
                healthProfileRepository,
                mock(HealthCheckupRepository.class),
                mock(HealthCheckupAnalysisService.class),
                allergenMatcher(), allergenRegistry(), mock(ProfileResolutionShadowObserver.class));
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("두부 레시피 알려줘");
        request.setHealthProfile(new ChatDto.HealthProfileContext(
                List.of("땅콩"), List.of(), List.of(), List.of(), List.of()));
        when(healthProfileRepository.findByUserId(1L))
                .thenThrow(new IllegalStateException("database unavailable"));

        ChatSafetyContextService.SafetyContext context = service.build(Optional.of(1L), request);

        assertThat(context.healthContextAvailable()).isFalse();
        assertThat(context.allergies()).containsExactly("땅콩");
    }

    // 건강검진 조회가 실패하면 호출자에게 false로 알려야 합니다.
    @Test
    void healthCheckupReadFailureIsReportedToTheOrchestrator() {
        HealthCheckupRepository healthCheckupRepository = mock(HealthCheckupRepository.class);
        ChatSafetyContextService service = new ChatSafetyContextService(
                mock(HealthProfileRepository.class),
                healthCheckupRepository,
                mock(HealthCheckupAnalysisService.class),
                allergenMatcher(), allergenRegistry(), mock(ProfileResolutionShadowObserver.class));
        when(healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(1L))
                .thenThrow(new IllegalStateException("database unavailable"));

        boolean available = service.appendLatestCheckupContext(new StringBuilder(), 1L);

        assertThat(available).isFalse();
    }
}
