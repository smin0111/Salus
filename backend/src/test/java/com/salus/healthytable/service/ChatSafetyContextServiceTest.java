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

    // 등록된 알레르기는 끝 글자를 조사로 보고 떼지 않습니다. "오이"가 "오", "포도"가 "포"가 되면
    // 한 글자 낱말 규칙 때문에 오이무침·포도 주스 충돌을 놓쳤습니다(2026-11-01 발견, bcaf136부터 있던 버그).
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registeredAllergiesEndingLikeParticlesAreKeptWholeAndStillConflict(boolean stored) {
        HealthProfileRepository profiles = mock(HealthProfileRepository.class);
        ChatSafetyContextService service = service(profiles, allergenRegistry());
        List<String> terms = List.of("오이", "포도", "송이", "고추냉이");
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

        assertThat(context.allergies()).containsExactly("오이", "포도", "송이", "고추냉이");
        assertThat(service.findAllergyConflicts(context, "오이무침", null, null)).containsExactly("오이");
        assertThat(service.findAllergyConflicts(context, "포도 주스", null, null)).containsExactly("포도");
        assertThat(service.findAllergyConflicts(context, "송이버섯 볶음", null, null)).containsExactly("송이");
    }

    // 메시지에서 읽은 알레르기는 조사를 뗀 형태와 떼지 않은 형태를 모두 후보로 둡니다("오이 알레르기" → 오이, "새우를" → 새우).
    @Test
    void extractedAllergiesKeepWholeNameAndParticleStrippedName() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), allergenRegistry());

        ChatDto.Request cucumber = new ChatDto.Request();
        cucumber.setMessage("오이 알레르기 있어요");
        ChatSafetyContextService.SafetyContext context = service.build(Optional.empty(), cucumber);
        assertThat(context.allergies()).contains("오이").doesNotContain("오");
        assertThat(service.findAllergyConflicts(context, "오이무침", null, null)).isNotEmpty();

        ChatDto.Request shrimp = new ChatDto.Request();
        shrimp.setMessage("새우를 못 먹어요");
        assertThat(service.findAllergyConflicts(service.build(Optional.empty(), shrimp), "새우볶음밥", null, null)).isNotEmpty();
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

    // 서버가 message를 고쳐 써도(메뉴 번호 고르기, 멈춘 질문 합치기) 사용자가 보낸 원문의 알레르기는 빠지면 안 됩니다
    // (2026-10-17 리뷰: "우유 알레르기 있어. 1번으로 해줘"가 "계란말이 레시피 알려줘"로 바뀌며 우유가 사라짐).
    @Test
    void allergyMentionsAreReadFromSentTextsEvenWhenMessageWasRewritten() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), allergenRegistry());

        ChatDto.Request menuPick = new ChatDto.Request();
        menuPick.setMessage("계란말이 레시피 알려줘");
        menuPick.setSentUserTexts(List.of("우유 알레르기 있어. 1번으로 해줘"));
        assertThat(service.build(Optional.empty(), menuPick).allergies()).contains("우유");

        ChatDto.Request merged = new ChatDto.Request();
        merged.setMessage("2인분 파스타 레시피 알려줘");
        merged.setInterruptedMessages(List.of("우유 알레르기 있어. 파스타 레시피 알려줘"));
        merged.setOriginalMessage("2인분으로");
        assertThat(service.build(Optional.empty(), merged).allergies()).contains("우유");
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

    // "알레르기" 뒤의 문장 나머지를 알레르기 이름으로 잡지 않습니다. 목록 형태("알레르기: 땅콩, 우유")는 그대로 읽습니다.
    @Test
    void allergyMentionDoesNotSwallowTheRestOfTheSentence() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), allergenRegistry());

        ChatDto.Request question = new ChatDto.Request();
        question.setMessage("나 새우 알레르기인데 새우깡 먹어도 될까?");
        assertThat(service.build(Optional.empty(), question).allergies()).containsExactly("새우");

        // 목록 형태는 이 변경과 무관하게 지금처럼 읽고, 두 알레르겐 모두 충돌로 잡혀야 합니다.
        ChatDto.Request list = new ChatDto.Request();
        list.setMessage("알레르기: 땅콩, 우유");
        ChatSafetyContextService.SafetyContext listed = service.build(Optional.empty(), list);
        assertThat(service.findAllergyConflicts(listed, "땅콩버터", null, null)).isNotEmpty();
        assertThat(service.findAllergyConflicts(listed, "우유 푸딩", null, null)).isNotEmpty();

        ChatDto.Request recipe = new ChatDto.Request();
        recipe.setMessage("땅콩 알레르기 있는데 쿠키 레시피 알려줘");
        assertThat(service.build(Optional.empty(), recipe).allergies()).containsExactly("땅콩");
    }

    // 절단은 낱말 단위입니다. "돼지고기"의 "돼"에서 잘라 알레르기를 잃으면 안 됩니다(Codex 검토 P1).
    @Test
    void allergyExtractionKeepsPorkWhetherDeclaredBeforeOrAfter() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), allergenRegistry());

        for (String message : List.of("돼지고기 알레르기 있어요", "알레르기: 돼지고기", "알레르기는 돼지고기", "나 돼지고기 알레르기인데 뭐 먹지",
                "알레르기: 먹태, 돼지고기")) {
            ChatDto.Request request = new ChatDto.Request();
            request.setMessage(message);
            ChatSafetyContextService.SafetyContext context = service.build(Optional.empty(), request);
            // 목록("A, B")은 원래부터 한 덩어리로 저장되므로, 저장 형태가 아니라 충돌 판정으로 확인합니다.
            assertThat(service.findAllergyConflicts(context, "돼지고기 김치찌개", null, null)).as(message).isNotEmpty();
        }
    }

    // 목적어와 부정 사이에 꾸밈말이 끼면 꾸밈말이 아니라 목적어가 피하는 음식입니다(2026-11-01).
    @Test
    void avoidedFoodExtractionUsesObjectWhenModifierSitsBeforeNegation() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), allergenRegistry());

        for (String message : List.of("우유를 절대 안 먹어요", "저는 땅콩은 전혀 못 먹어요", "새우를 아예 못 먹어")) {
            ChatDto.Request request = new ChatDto.Request();
            request.setMessage(message);
            ChatSafetyContextService.SafetyContext context = service.build(Optional.empty(), request);
            assertThat(context.allergies()).as(message).noneMatch(term -> term.equals("절대") || term.equals("전혀") || term.equals("아예"));
        }
        ChatDto.Request milk = new ChatDto.Request();
        milk.setMessage("우유를 절대 안 먹어요");
        assertThat(service.findAllergyConflicts(service.build(Optional.empty(), milk), "우유 푸딩", null, null)).isNotEmpty();
        ChatDto.Request peanut = new ChatDto.Request();
        peanut.setMessage("저는 땅콩은 전혀 못 먹어요");
        assertThat(service.findAllergyConflicts(service.build(Optional.empty(), peanut), "땅콩 쿠키", null, null)).isNotEmpty();
    }

    // "안 먹을 때", "안 먹으면"은 상황을 묻는 말이라 알레르기로 읽지 않습니다. "못 먹"과 평서문은 그대로 읽습니다(2026-11-01).
    @Test
    void avoidedFoodExtractionIgnoresSituationalNotEating() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), allergenRegistry());

        for (String message : List.of("나물을 바로 안 먹을 때 어떻게 보관해?", "밥을 바로 안 먹으면 냉동해도 돼?",
                "국을 바로 안 먹을 경우 냉장해?")) {
            ChatDto.Request request = new ChatDto.Request();
            request.setMessage(message);
            assertThat(service.build(Optional.empty(), request).allergies()).as(message).isEmpty();
        }
        for (String[] pair : List.of(new String[]{"땅콩 못 먹을 때 대신 뭐 넣어?", "땅콩"},
                new String[]{"고기 안 먹어요", "고기"}, new String[]{"새우를 못 먹어요", "새우"},
                new String[]{"우유 먹으면 안 돼요", "우유"}, new String[]{"땅콩은 안 먹을래요", "땅콩"},
                new String[]{"우유는 안 먹으니까 다른 재료로 추천해 줘", "우유"}, new String[]{"새우는 안 먹을 거예요", "새우"})) {
            ChatDto.Request request = new ChatDto.Request();
            request.setMessage(pair[0]);
            assertThat(service.build(Optional.empty(), request).allergies()).as(pair[0]).contains(pair[1]);
        }
    }

    // "먹"으로 시작하는 음식 이름도 서술어로 보고 지우면 안 됩니다(Codex 검토 2회차 P1).
    @Test
    void allergyExtractionKeepsFoodNamesThatLookLikePredicates() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), allergenRegistry());

        for (String message : List.of("알레르기: 먹태", "먹태 알레르기 있어요", "알레르기는 먹태")) {
            ChatDto.Request request = new ChatDto.Request();
            request.setMessage(message);
            ChatSafetyContextService.SafetyContext context = service.build(Optional.empty(), request);
            assertThat(service.findAllergyConflicts(context, "먹태구이", null, null)).as(message).isNotEmpty();
        }
    }

    // 쉼표 목록은 항목별로 저장합니다. 한 덩어리("먹태 땅콩")로 저장하면 사전에 없는 먹태를 놓쳤습니다(2026-11-01).
    @Test
    void commaListedAllergiesAreStoredPerItem() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), allergenRegistry());

        for (String message : List.of("알레르기: 먹태, 땅콩", "먹태, 땅콩 알레르기 있어요", "알레르기는 먹태，땅콩이에요", "나 알레르기 있어. 먹태, 땅콩")) {
            ChatDto.Request request = new ChatDto.Request();
            request.setMessage(message);
            ChatSafetyContextService.SafetyContext context = service.build(Optional.empty(), request);
            assertThat(service.findAllergyConflicts(context, "먹태구이", null, null)).as(message).isNotEmpty();
            assertThat(service.findAllergyConflicts(context, "땅콩조림", null, null)).as(message).isNotEmpty();
            assertThat(context.allergies()).as(message).noneMatch(term -> term.contains("먹태 땅콩"));
        }
    }

    // 나열한 음식은 모두 검사합니다. 알레르겐이 처음·가운데에 있어도 충돌 안내가 나와야 합니다(Codex 검토 P2).
    @Test
    void eatingQuestionChecksEveryListedFood() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        assertThat(service.buildEatingPermissionReply(allergyContext("땅콩"), "땅콩, 우유 먹어도 돼?").orElseThrow())
                .contains("확인된 알레르기(땅콩)").contains("드시지 않는 것을 권해요");
        assertThat(service.buildEatingPermissionReply(allergyContext("땅콩"), "우유, 땅콩, 계란 먹어도 돼?").orElseThrow())
                .contains("드시지 않는 것을 권해요");
        assertThat(service.buildEatingPermissionReply(allergyContext("땅콩"), "우유랑 땅콩 먹어도 돼?").orElseThrow())
                .contains("드시지 않는 것을 권해요");
    }

    // "X 먹어도 돼?"는 LLM 대신 규칙으로 답하고, 어떤 경우에도 괜찮다·안전하다고 말하지 않습니다.
    @Test
    void eatingQuestionAboutRegisteredAllergenAdvisesNotToEat() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        String reply = service.buildEatingPermissionReply(allergyContext("땅콩"), "땅콩버터 먹어도 돼?").orElseThrow();

        assertThat(reply).startsWith("‘땅콩버터’는 확인된 알레르기(땅콩)와 관련된 음식이에요.")
                .contains("드시지 않는 것을 권해요").doesNotContain("괜찮아요");
        // 파생 재료도 같은 매처로 잡습니다(우유 → 버터).
        assertThat(service.buildEatingPermissionReply(allergyContext("우유"), "버터 먹어도 될까요?").orElseThrow())
                .contains("확인된 알레르기(우유)");
    }

    // 이름 끝이 조사처럼 보이는 음식("오이")도 충돌을 놓치지 않습니다(Codex 검토 3회차 P2).
    @Test
    void eatingQuestionKeepsFoodNamesEndingLikeParticles() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        for (String message : List.of("오이 먹어도 돼?", "오이는 먹어도 돼?", "오이를 먹어도 될까요?")) {
            assertThat(service.buildEatingPermissionReply(allergyContext("오이"), message).orElseThrow()).as(message)
                    .startsWith("‘오이’는 확인된 알레르기(오이)와 관련된 음식이에요.");
        }
    }

    @Test
    void eatingQuestionWithoutConflictStillDoesNotCallItSafe() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        // 앞의 사정 설명("땅콩 알레르기 있는데")이 아니라 묻는 음식(우유)으로 판정합니다.
        String reply = service.buildEatingPermissionReply(allergyContext("땅콩"), "땅콩 알레르기 있는데 우유 먹어도 돼?").orElseThrow();
        assertThat(reply).startsWith("Salus는 먹어도 되는지 판단하지 않아요.")
                .contains("‘우유’ 이름에서").contains("안전하다는 뜻은 아니에요").doesNotContain("괜찮아요");
        // 한 글자 알레르겐은 낱말 전체가 같아야 합니다(밀 vs 밀크티).
        assertThat(service.buildEatingPermissionReply(allergyContext("밀"), "밀크티 먹어도 돼?").orElseThrow())
                .contains("안전하다는 뜻은 아니에요");
    }

    @Test
    void eatingQuestionWithoutAllergyInformationSaysThereIsNoBasis() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        assertThat(service.buildEatingPermissionReply(allergyContext(), "땅콩 먹어도 돼?").orElseThrow())
                .contains("등록된 알레르기 정보가 없어 확인할 기준도 없어요").doesNotContain("괜찮아요");
        // 판단 근거가 없는 상태(null)도 '알레르기 없음'으로 보지 않습니다.
        assertThat(service.buildEatingPermissionReply(null, "땅콩 먹어도 돼?").orElseThrow())
                .contains("확인할 기준도 없어요");
    }

    @Test
    void eatingQuestionWithConditionsOrMedicationsRecommendsProfessionals() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));
        ChatSafetyContextService.SafetyContext context = new ChatSafetyContextService.SafetyContext(
                List.of(), List.of("고혈압"), List.of(), List.of("와파린"), List.of(), true);

        assertThat(service.buildEatingPermissionReply(context, "자몽 먹어도 돼?").orElseThrow())
                .contains("의사나 약사와 상담해 주세요");
    }

    @Test
    void nonEatingQuestionIsNotHandled() {
        ChatSafetyContextService service = service(mock(HealthProfileRepository.class), mock(AllergenRegistry.class));

        assertThat(service.buildEatingPermissionReply(allergyContext("땅콩"), "땅콩 좋아해")).isEmpty();
        assertThat(service.buildEatingPermissionReply(allergyContext("땅콩"), "땅콩은 뭐야?")).isEmpty();
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
