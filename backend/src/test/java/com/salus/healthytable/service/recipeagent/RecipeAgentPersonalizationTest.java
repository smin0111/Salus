package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.service.RecipeNormalizer;
import com.salus.healthytable.service.RecipeWorkSessionService;
import com.salus.healthytable.service.ChatSafetyContextService.SafetyContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recipe Agent 개인화 흐름 테스트입니다.
 * 요청 분석, 사용자 맥락 로드 상태, 알레르기/당뇨/약물/식단 제한 정책, 후속 요청, 세션 만료를 확인합니다.
 */
class RecipeAgentPersonalizationTest {
    // 실제 알레르겐 사전으로 만든 Matcher를 테스트 전체에서 공유합니다.
    private static com.salus.healthytable.service.allergen.AllergenMatcher sharedAllergenMatcher() {
        com.salus.healthytable.service.allergen.AllergenDictionary dictionary =
                new com.salus.healthytable.service.allergen.AllergenDictionary();
        dictionary.load();
        return new com.salus.healthytable.service.allergen.AllergenMatcher(dictionary);
    }


    private final Clock clock = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    // "들깨 빼 주세요" 같은 자연스러운 요청에서 제외 재료를 정확히 추출해야 합니다.
    @Test
    void naturalKoreanRemoveRequestProducesCanonicalExplicitExclusion() {
        DefaultRecipeRequestPlanner planner = new DefaultRecipeRequestPlanner(new RecipeNormalizer());

        assertThat(planner.extractExplicitExclusions("양파는 빼줘")).containsExactly("양파");
        assertThat(planner.extractExplicitExclusions("땅콩을 빼 주세요")).containsExactly("땅콩");
    }

    // 띄어 쓴 요리 이름을 크리에이터 지정 요청으로 잘못 분류하면 안 됩니다.
    @Test
    void spacedDishNameIsNotMisclassifiedAsCreatorSpecificRequest() {
        DefaultRecipeRequestPlanner planner = new DefaultRecipeRequestPlanner(new RecipeNormalizer());

        RecipeResearchPlan dishPlan = planner.plan("바나나 브륄레 레시피 알려줘.", UserRecipeContext.empty(1L));
        RecipeResearchPlan creatorPlan = planner.plan("fixture-creator 참치김밥 레시피 알려줘.", UserRecipeContext.empty(1L));

        assertThat(dishPlan.dishName()).isEqualTo("바나나 브륄레");
        assertThat(dishPlan.creatorName()).isEmpty();
        assertThat(dishPlan.mode()).isEqualTo(RecipeRequestMode.CREATE);
        assertThat(creatorPlan.dishName()).isEqualTo("참치김밥");
        assertThat(creatorPlan.creatorName()).isEqualTo("fixture-creator");
        assertThat(creatorPlan.mode()).isEqualTo(RecipeRequestMode.CREATOR_SPECIFIC);
    }

    // 로그인 사용자는 항상 건강 프로필을 로드해야 합니다.
    @Test
    void loggedInContextAlwaysLoadsHealthProfile() {
        HealthProfileRepository healthProfiles = mock(HealthProfileRepository.class);
        HealthProfile profile = new HealthProfile();
        profile.setUserId(1L);
        profile.setAllergies(List.of("깻잎"));
        profile.setChronicConditions(List.of("당뇨"));
        profile.setMedications(List.of("와파린"));
        profile.setGoals(List.of("당류 줄이기"));

        when(healthProfiles.findByUserId(1L)).thenReturn(Optional.of(profile));

        RepositoryUserRecipeContextLoader loader = new RepositoryUserRecipeContextLoader(healthProfiles);
        UserRecipeContext context = loader.load(1L);

        assertThat(context.allergies()).containsExactly("깻잎");
        assertThat(context.chronicConditions()).containsExactly("당뇨");
        assertThat(context.medications()).containsExactly("와파린");
        assertThat(context.healthGoals()).containsExactly("당류 줄이기");
        verify(healthProfiles).findByUserId(1L);
    }

    // 게스트 맥락은 비어 있고 DB를 조회하지 않아야 합니다.
    @Test
    void guestContextIsEmptyAndDoesNotQueryRepositories() {
        HealthProfileRepository healthProfiles = mock(HealthProfileRepository.class);

        RepositoryUserRecipeContextLoader loader = new RepositoryUserRecipeContextLoader(healthProfiles);
        UserRecipeContext context = loader.load(null);

        assertThat(context.userId()).isNull();
        assertThat(context.allergies()).isEmpty();
        verify(healthProfiles, never()).findByUserId(any());
    }

    // 로드 상태를 "등록 정보 없음/로드됨/로드 실패"로 구분해야 합니다. 프로필을 못 읽으면 알레르기를 모르므로 실패입니다.
    @Test
    void contextLoaderDistinguishesNotRegisteredLoadedAndFailed() {
        HealthProfileRepository healthProfiles = mock(HealthProfileRepository.class);
        RepositoryUserRecipeContextLoader loader = new RepositoryUserRecipeContextLoader(healthProfiles);

        when(healthProfiles.findByUserId(1L)).thenReturn(Optional.empty());
        assertThat(loader.loadWithStatus(1L).status()).isEqualTo(UserRecipeContextLoadStatus.NOT_REGISTERED);

        HealthProfile profile = new HealthProfile();
        profile.setAllergies(List.of("우유"));
        when(healthProfiles.findByUserId(2L)).thenReturn(Optional.of(profile));
        assertThat(loader.loadWithStatus(2L).status()).isEqualTo(UserRecipeContextLoadStatus.LOADED);

        when(healthProfiles.findByUserId(3L)).thenThrow(new RuntimeException("profile unavailable"));
        UserRecipeContextLoadResult failed = loader.loadWithStatus(3L);
        assertThat(failed.status()).isEqualTo(UserRecipeContextLoadStatus.LOAD_FAILED);
        assertThat(failed.profileStatus()).isEqualTo(ContextSectionLoadStatus.LOAD_FAILED);
    }

    // 들깨 알레르기면 참치김밥에서 들깨(핵심 재료 아님)를 제거하고 MODIFY로 판정해야 합니다.
    @Test
    void perillaAllergyRemovesPerillaFromTunaKimbapAndDecisionIsModify() {
        RecipeCandidate original = tunaKimbap();
        UserRecipeContext context = contextWithAllergy("깻잎")
                .withExplicitlyExcludedIngredients(List.of());
        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(original, context);

        RecipeCandidate personalized = new RecipeModificationService().apply(original, decision);
        RecipeValidationResult validation = new RecipeValidationPipeline().validate(personalized, context);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.MODIFY);
        assertThat(decision.modifications()).extracting(RecipeModification::ingredient).contains("깻잎");
        assertThat(String.join(" ", personalized.ingredients())).doesNotContain("깻잎");
        assertThat(String.join(" ", personalized.steps())).doesNotContain("깻잎");
        assertThat(validation.valid()).isTrue();
    }

    // 땅콩이 핵심 재료인 레시피는 신뢰할 대체안이 없으면 차단해야 합니다.
    @Test
    void peanutAllergyBlocksCorePeanutSauceWithoutTrustedAlternative() {
        RecipeCandidate recipe = new RecipeCandidate(
                "땅콩소스 비빔면",
                "고소한 땅콩소스가 핵심인 비빔면",
                List.of("땅콩버터 2큰술", "면 1인분", "오이 1/3개"),
                List.of("땅콩버터로 소스를 만듭니다.", "면과 소스를 비빕니다."),
                null,
                2,
                15,
                List.of("땅콩버터", "면"),
                List.of("오이"),
                List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(recipe, contextWithAllergy("땅콩"));

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.BLOCK);
        assertThat(decision.conflicts()).anyMatch(conflict -> conflict.type() == RecipeConflictType.ALLERGY
                && conflict.severity() == ConflictSeverity.BLOCKING);
    }

    // 당뇨 사용자에게 설탕이 핵심인 바나나 브륄레는 대체 메뉴를 추천하되, 모든 바나나 레시피를 막지는 않아야 합니다.
    @Test
    void diabetesBananaBruleeRecommendsAlternativeButDoesNotBlockEveryBananaRecipe() {
        UserRecipeContext diabetes = new UserRecipeContext(
                1L,
                List.of(),
                List.of("당뇨"),
                List.of(),
                List.of(),
                List.of("당류 줄이기"),
                List.of());

        RecipePersonalizationDecision brulee = engine(unknownMedication()).evaluate(bananaBrulee(), diabetes);
        RecipePersonalizationDecision smoothie = engine(unknownMedication()).evaluate(lowSugarBananaSmoothie(), diabetes);

        assertThat(brulee.decisionType()).isEqualTo(RecipeDecisionType.RECOMMEND_ALTERNATIVE);
        assertThat(smoothie.decisionType()).isEqualTo(RecipeDecisionType.ALLOW_WITH_NOTICE);
        assertThat(smoothie.conflicts()).noneMatch(conflict -> conflict.severity() == ConflictSeverity.BLOCKING);
    }

    // 당류를 줄여 수정할 수 있는 레시피는 MODIFY로 판정해야 합니다.
    @Test
    void lowSugarModificationPossibleRecipeUsesModifyDecision() {
        UserRecipeContext diabetesWithAllulose = new UserRecipeContext(
                1L,
                List.of(),
                List.of("당뇨"),
                List.of(),
                List.of(),
                List.of("당류 줄이기"),
                List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(jeyukWithSugar(), diabetesWithAllulose);
        RecipeCandidate personalized = new RecipeModificationService().apply(jeyukWithSugar(), decision);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.MODIFY);
        assertThat(decision.modifications()).anyMatch(modification -> "SUBSTITUTE_OR_REDUCE".equals(modification.action()));
        assertThat(String.join(" ", personalized.ingredients())).contains("알룰로스").doesNotContain("설탕 1큰술");
    }

    // 자동 추정한 핵심 재료 때문에 설탕이 조금 들어간 모든 레시피가 대체 메뉴 전용이 되면 안 됩니다.
    @Test
    void automaticallyInferredCoreListDoesNotMakeEveryAddedSugarRecipeAnAlternativeOnlyDish() {
        UserRecipeContext diabetesWithAllulose = new UserRecipeContext(
                1L, List.of(), List.of("당뇨"), List.of(), List.of(), List.of("당류 줄이기"), List.of());
        RecipeCandidate operationalShape = new RecipeCandidate(
                "제육볶음",
                "양념 볶음",
                List.of("돼지고기 200g", "고추장 1큰술", "설탕 1큰술", "양파 1/2개"),
                List.of("양념을 만듭니다.", "돼지고기와 양파를 볶습니다."),
                520, 2, 20,
                List.of("돼지고기", "고추장", "설탕", "양파"),
                List.of(), List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(operationalShape, diabetesWithAllulose);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.MODIFY);
        assertThat(decision.modifications()).anyMatch(modification -> "설탕".equals(modification.ingredient())
                && "SUBSTITUTE_OR_REDUCE".equals(modification.action()));
    }

    // 약물 상호작용을 모르면 안내만 추가하고 가짜 충돌을 만들지 않아야 합니다.
    @Test
    void unknownMedicationInteractionCreatesNoticeButNoFakeConflict() {
        UserRecipeContext context = new UserRecipeContext(1L, List.of(), List.of(), List.of(), List.of("혈압약"), List.of(), List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(tunaKimbap(), context);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.ALLOW_WITH_NOTICE);
        assertThat(decision.conflicts()).noneMatch(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION);
        assertThat(decision.userNotices())
                .contains("확인된 공식 음식 상호작용 근거를 찾지 못했습니다.")
                .contains("상호작용이 없다는 의미는 아닙니다.")
                .contains("약 복용 방식은 의사 또는 약사에게 확인하세요.");
    }

    // 공식 근거로 확인된 약물 충돌은 최종 추천을 제한해야 합니다.
    @Test
    void trustedMedicationConflictLimitsFinalRecommendation() {
        MedicationFoodInteractionPort conflictPort = (medications, ingredients) -> new MedicationInteractionResult(
                InteractionStatus.CONFLICT,
                List.of(new RecipeConflict(
                        RecipeConflictType.MEDICATION_INTERACTION,
                        "자몽",
                        "상호작용 확인 약",
                        "신뢰 가능한 근거에서 자몽 상호작용이 확인되었습니다.",
                        ConflictSeverity.BLOCKING,
                        "trusted-test-source")),
                List.of("복용약과 자몽 상호작용이 확인되어 제공을 제한합니다."));
        UserRecipeContext context = new UserRecipeContext(1L, List.of(), List.of(), List.of(), List.of("상호작용 확인 약"), List.of(), List.of());

        RecipePersonalizationDecision decision = engine(conflictPort).evaluate(grapefruitDrink(), context);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.BLOCK);
        assertThat(decision.conflicts()).anyMatch(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION
                && conflict.severity() == ConflictSeverity.BLOCKING);
    }

    // 깻잎 알레르기면 참치김밥 재료에서 깻잎을 빼고 MODIFY로 판정해야 합니다.
    @Test
    void allergyPolicyRemovesPerillaFromTunaKimbapIngredients() {
        UserRecipeContext context = new UserRecipeContext(
                1L,
                List.of("깻잎"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(tunaKimbap(), context);
        RecipeCandidate personalized = new RecipeModificationService().apply(tunaKimbap(), decision);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.MODIFY);
        assertThat(String.join(" ", personalized.ingredients())).doesNotContain("깻잎");
    }

    // 질환 등록 없이 "저염" 건강 목표만 있어도 저염 정책을 적용해야 합니다.
    @Test
    void healthGoalWithoutRegisteredConditionStillAppliesLowSodiumPolicy() {
        UserRecipeContext context = new UserRecipeContext(
                1L, List.of(), List.of(), List.of(), List.of(), List.of("저염"), List.of());
        RecipeCandidate recipe = new RecipeCandidate(
                "된장찌개",
                "된장으로 맛을 낸 국물 요리",
                List.of("된장 1큰술", "두부 1/2모", "소금 약간"),
                List.of("물을 끓여 된장을 풀고 두부를 익힙니다."),
                180, 1, 20,
                List.of("된장", "두부"),
                List.of("소금"),
                List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(recipe, context);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.MODIFY);
        assertThat(decision.conflicts()).anyMatch(conflict -> "염분 양념".equals(conflict.ingredient()));
        assertThat(decision.modifications()).anyMatch(modification -> "REDUCE".equals(modification.action()));
    }

    // 조리 순서가 없는 레시피는 최종 검증에서 실패해야 합니다.
    @Test
    void recipeWithoutInstructionsFailsValidation() {
        RecipeCandidate incomplete = new RecipeCandidate(
                "재료만 있는 레시피",
                "조리 단계가 누락됨",
                List.of("두부 1모"),
                List.of(),
                null, null, null,
                List.of("두부"),
                List.of(),
                List.of());

        RecipeValidationResult validation = new RecipeValidationPipeline()
                .validate(incomplete, UserRecipeContext.empty(1L));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reasons()).anyMatch(reason -> reason.contains("조리 순서"));
    }

    // 식단 제한과 충돌하고 검증된 대체안이 없으면 원본 레시피를 차단해야 합니다.
    @Test
    void dietaryRestrictionBlocksOriginalWhenNoVerifiedAlternativeExists() {
        UserRecipeContext vegan = new UserRecipeContext(
                1L, List.of(), List.of(), List.of("비건"), List.of(), List.of(), List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(tunaKimbap(), vegan);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.BLOCK);
        assertThat(decision.conflicts()).anyMatch(conflict -> conflict.type() == RecipeConflictType.DIETARY_RESTRICTION);
        assertThat(decision.modifications()).anyMatch(modification -> "동물성 재료".equals(modification.ingredient()));
    }

    // 답변은 원본과 개인화 레시피를 구분하고 변경 이유를 포함해야 합니다.
    @Test
    void composerKeepsOriginalAndPersonalizedRecipeSeparateAndIncludesChangeReason() {
        UserRecipeContext context = contextWithAllergy("깻잎");
        RecipeCandidate original = tunaKimbap();
        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(original, context);
        RecipeCandidate personalized = new RecipeModificationService().apply(original, decision);
        PersonalizedRecipeResult result = new PersonalizedRecipeResult(original, personalized, decision, List.of());
        String reply = new RecipeResponseComposer().compose(result, new RecipeValidationPipeline().validate(personalized, context));

        assertThat(result.originalRecipe().ingredients()).anyMatch(ingredient -> ingredient.contains("깻잎"));
        assertThat(result.personalizedRecipe().ingredients()).noneMatch(ingredient -> ingredient.contains("깻잎"));
        assertThat(reply)
                .contains("[원본 레시피 기준]")
                .contains("[개인화 판단]")
                .contains("등록된 알레르기 때문에 제외")
                .contains("수정 후 추천");
    }

    // 출처 검색이 실패해도 레시피를 지어내지 않고 정책 판단 결과만으로 응답해야 합니다.
    @Test
    void orchestratorFallsBackToPolicyResponseWhenSourceDiscoveryFails() {
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> contextWithAllergy("깻잎"),
                (plan, context) -> {
                    throw new RuntimeException("LLM timeout");
                },
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("참치김밥 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 10L, request).block();

        assertThat(response).isNotNull();
        // 출처를 못 찾으면 지어낸 레시피 대신 찾지 못했다고 사람이 읽는 문장으로 답합니다(카드 없음).
        assertThat(response.getReply()).contains("믿을 만한 출처에서 찾지 못했어요").doesNotContain("[원본 레시피 기준]");
        assertThat(response.getRecipe()).isNull();
    }

    @Test
    void chatSafetyContextBlocksDerivedMilkIngredientsAndKeepsStoredAllergies() {
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> contextWithAllergy("땅콩"),
                (plan, context) -> {
                    assertThat(context.allergies()).contains("우유");
                    if (context.userId() != null) {
                        assertThat(context.allergies()).contains("땅콩");
                    }
                    return List.of(milkCookieSource());
                },
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("두쫀쿠 레시피 알려줘");
        SafetyContext safety = new SafetyContext(
                List.of("우유"), List.of(), List.of(), List.of(), List.of(), true);

        for (Long userId : java.util.Arrays.asList(null, 1L)) {
            ChatDto.Response response = orchestrator.handle(userId, null, request, safety).block();

            assertThat(response).isNotNull();
            assertThat(response.getRecipe()).isNull();
            // 차단 답변은 사람이 읽는 문장으로 바뀌었지만(2026-10-12), 차단 사실과 알레르기 재료는 그대로 알려야 합니다.
            assertThat(response.getReply()).contains("우유", "제공할 수 없어요");
        }
    }

    // 처음 보는 요리 찾기 하루 횟수를 다 쓰면 출처 실패로 처리하지 않고 안내 문구로 답합니다.
    @Test
    void newDishSearchLimitAnswersWithNotice() {
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> { throw new NewDishSearchQuota.LimitExceededException(5); },
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("두쫀쿠 레시피 알려줘");
        request.setQuotaSubject("user:1");
        SafetyContext safety = new SafetyContext(List.of(), List.of(), List.of(), List.of(), List.of(), true);

        ChatDto.Response response = orchestrator.handle(1L, 10L, request, safety).block();

        assertThat(response.getRecipe()).isNull();
        assertThat(response.getReply()).isEqualTo(NewDishSearchQuota.limitReachedReply(5));
    }

    // 출처를 찾는 중에 사용자가 멈추면 "출처 실패"로 바꿔 다음 단계로 가지 않고 그대로 멈춥니다(빈 결과).
    @Test
    void cancellationDuringSourceDiscoveryStopsTheRequest() {
        RecipeSourceDiscoveryPort cancelledSearch = (plan, context) -> {
            throw new com.salus.healthytable.service.ChatCancellation.ChatCancelledException();
        };
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty, cancelledSearch, mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("두쫀쿠 레시피 알려줘");
        SafetyContext safety = new SafetyContext(List.of(), List.of(), List.of(), List.of(), List.of(), true);

        assertThat(orchestrator.handle(1L, 10L, request, safety).blockOptional()).isEmpty();
    }

    @Test
    void chatSafetyContextAllowsNoAllergyButBlocksUnavailableHealthContext() {
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> List.of(milkCookieSource()),
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("두쫀쿠 레시피 알려줘");

        for (boolean available : List.of(true, false)) {
            SafetyContext safety = new SafetyContext(
                    List.of(), List.of(), List.of(), List.of(), List.of(), available);
            ChatDto.Response response = orchestrator.handle(null, null, request, safety).block();
            assertThat(response).isNotNull();
            if (available) {
                assertThat(response.getRecipe()).isNotNull();
            } else {
                assertThat(response.getRecipe()).isNull();
                assertThat(response.getReply()).contains("불러오지 못해");
            }
        }
    }

    private RecipeSourceDocument milkCookieSource() {
        return new RecipeSourceDocument(
                "fixture:milk-cookie", RecipeSourceType.GENERAL_WEB, "두쫀쿠", "fixture",
                "https://example.com/recipe",
                "title: 두쫀쿠\ningredients:\n- 버터 20g\n- 탈지분유 10g\nsteps:\n- 버터를 녹입니다.\n- 탈지분유를 섞습니다.",
                java.time.LocalDateTime.of(2026, 7, 1, 0, 0), 0.95);
    }

    // 구조화 출처를 못 찾으면 호출자가 더 낮은 근거 등급으로 내려갈 수 있게 빈 값을 줘야 합니다.
    // 여기서 거절 응답을 만들어 버리면 마크업이 없는 요리의 커버리지가 통째로 사라집니다.
    @Test
    void handleIfSourceFoundReturnsEmptyWhenNoReliableSource() {
        RecipeWorkSessionService workSessions = mock(RecipeWorkSessionService.class);
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> contextWithAllergy("깻잎"),
                (plan, context) -> List.of(),
                workSessions);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("마라샹궈 레시피 알려줘");

        assertThat(orchestrator.handleIfSourceFound(1L, 10L, request).block()).isEmpty();
        // 출처가 없으면 세션도 저장하지 않아야 합니다. 저장하면 다음 요청이 빈 상태를 재사용합니다.
        verify(workSessions, never()).saveAgentSession(any(), any(), any(), any());
    }

    // 출처를 찾으면 평소대로 응답해야 합니다.
    @Test
    void handleIfSourceFoundReturnsResponseWhenSourceExists() {
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> contextWithAllergy("깻잎"),
                (plan, context) -> List.of(sourceDocument()),
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘");

        assertThat(orchestrator.handleIfSourceFound(1L, 10L, request).block()).isPresent();
    }

    // 출처 검색이 꺼져 있으면 빈 값을 줘야 합니다. 켜지 않은 채로 Agent가 응답을 독점하면 안 됩니다.
    @Test
    void handleIfSourceFoundReturnsEmptyWhenDiscoveryDisabled() {
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> contextWithAllergy("깻잎"),
                (plan, context) -> List.of(sourceDocument()),
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", false);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘");

        assertThat(orchestrator.handleIfSourceFound(1L, 10L, request).block()).isEmpty();
    }

    // 후속 요청은 답변 텍스트를 다시 파싱하지 않고 저장된 구조화 Agent 세션을 사용해야 합니다.
    @Test
    void followUpUsesStructuredAgentSessionInsteadOfParsingReplyText() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        RecipeWorkSessionDTO stored = RecipeWorkSessionDTO.builder()
                .userId(1L)
                .chatSessionId(10L)
                .lastRecommendation("이 문자열은 파싱하면 안 됩니다")
                .agentSession(agentSessionMap(tunaKimbap()))
                .build();
        when(workSessionService.find(1L, 10L)).thenReturn(Optional.of(stored));
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> UserRecipeContext.empty(userId).withExplicitlyExcludedIngredients(List.of("깻잎")),
                (plan, context) -> {
                    throw new AssertionError("source discovery should not be used for structured follow-up");
                },
                workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("그럼 깻잎 말고 상추 넣어줘");
        ChatDto.Response response = orchestrator.handle(1L, 10L, request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("깻잎").contains("수정 후 추천");
        verify(workSessionService).find(1L, 10L);
    }

    // 2026-10-14 실사용 대화: 육회 → "나 배 알러지 있어서 배는 빼줘" → "배 알러지 있다고".
    // 한 글자 재료와 대화 속 알레르기 말을 후속 요청으로 반영하고, 뺄 재료를 못 읽으면 같은 레시피를 다시 보여 주지 않습니다.
    @Test
    void yukhoeFollowUpsRemovePearAndAskWhenNothingToRemove() {
        RecipeCandidate yukhoe = new RecipeCandidate("육회", "",
                List.of("소고기 우둔살 300g", "배 1/2개", "달걀노른자 2개", "진간장 2큰술"),
                List.of("우둔살을 얇게 채 썬다.", "채썬 배 1/2개를 가늘게 채 썰어 차가운 고기에 양념장을 넣고 버무린다.",
                        "접시에 배를 먼저 두르고 육회를 담는다."),
                null, 1, 20, List.of("소고기"), List.of(), List.of());
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        Map<String, Object> session = agentSessionMap(yukhoe);
        session.put("originalRecipe", session.get("personalizedRecipe"));
        when(workSessionService.find(1L, 40L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(40L).lastRecommendation("육회").agentSession(session).build()));
        RecipeAgentOrchestrator orchestrator = orchestrator(UserRecipeContext::empty,
                (plan, context) -> {
                    throw new AssertionError("후속 요청은 새로 검색하지 않습니다");
                }, workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        for (String message : List.of("나 배 알러지 있어서 배는 빼줘", "배 알러지 있다고")) {
            ChatDto.Request request = new ChatDto.Request();
            request.setMessage(message);
            ChatDto.Response response = orchestrator.handle(1L, 40L, request).block();
            assertThat(response.getRecipe()).as(message).isNotNull();
            assertThat(response.getRecipe().getIngredients()).as(message).noneMatch(line -> line.startsWith("배 "));
            assertThat(String.join(" ", response.getRecipe().getSteps())).as(message)
                    .doesNotContain("배 1/2개").doesNotContain("배를").doesNotContain("채썬 가늘게")
                    .contains("차가운 고기에 양념장을 넣고 버무린다.");
        }

        ChatDto.Request vague = new ChatDto.Request();
        vague.setMessage("그거 좀 빼줘");
        ChatDto.Response clarification = orchestrator.handle(1L, 40L, vague).block();
        assertThat(clarification.getRecipe()).isNull();
        assertThat(clarification.getReply()).contains("어떤 재료를 빼야 할지 알아듣지 못했어요");
    }

    // 같은 대화에서 그 요리로 이미 보여 준 출처는 "다른 레시피" 요청 때 모두 뺍니다. 중간에 다른 요리를 물었어도 같습니다
    // (2026-10-14 배추찜 → 그래놀라 → "다른 삼겹살 배추찜 레시피"에 처음과 같은 출처를 다시 답함).
    @Test
    void anotherRecipeRequestExcludesSourcesShownEarlierInConversation() {
        RecipeSourceDocument first = new RecipeSourceDocument("web:a", RecipeSourceType.GENERAL_WEB, "삼겹살 배추찜", "a",
                "https://cookpick.kr/baechujjim", "title: 삼겹살 배추찜\ningredients:\n- 배추 1통\n- 대패 삼겹살 300g\nsteps:\n- 배추와 삼겹살을 쌓는다.\n- 15분 찐다.",
                null, 0.8);
        RecipeSourceDocument second = new RecipeSourceDocument("web:b", RecipeSourceType.GENERAL_WEB, "삼겹살 배추찜", "b",
                "https://www.10000recipe.com/recipe/2", "title: 삼겹살 배추찜\ningredients:\n- 알배추 1/2통\n- 삼겹살 200g\nsteps:\n- 배추 사이에 삼겹살을 끼운다.\n- 20분 찐다.",
                null, 0.8);
        RecipeCandidate granola = new RecipeCandidate("오트밀 그래놀라", "", List.of("오트밀 150g", "올리브오일 50g"),
                List.of("섞는다.", "굽는다."), null, 1, 30, List.of("오트밀"), List.of(), List.of());
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        Map<String, Object> session = agentSessionMap(granola);
        session.put("sourceEvidence", List.of());
        session.put("shownSourcesByDish", Map.of("삼겹살배추찜", List.of("https://cookpick.kr/baechujjim")));
        when(workSessionService.find(1L, 50L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(50L).lastRecommendation("그래놀라").agentSession(session).build()));
        List<RecipeResearchPlan> plans = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(UserRecipeContext::empty,
                (plan, context) -> {
                    plans.add(plan);
                    return plan.excludedSourceUrls().contains("https://cookpick.kr/baechujjim") ? List.of(second) : List.of(first);
                }, workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("다른 삼겹살 배추찜 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 50L, request).block();

        assertThat(plans.get(0).excludedSourceUrls()).contains("https://cookpick.kr/baechujjim");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getSources()).extracting(ChatDto.SourceLink::getUrl)
                .contains("https://www.10000recipe.com/recipe/2").doesNotContain("https://cookpick.kr/baechujjim");
        assertThat(response.getRecipe().getNotices()).contains("이전과 다른 출처의 레시피를 찾았어요.");
    }

    // 후속 요청은 원본 레시피를 복원하고, 안전 판단은 현재 DB의 최신 건강 정보로 해야 합니다.
    @Test
    void followUpRestoresOriginalRecipeAndUsesCurrentAuthoritativeSafetyContext() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        Map<String, Object> session = agentSessionMap(tunaKimbap());
        session.put("originalRecipe", session.get("personalizedRecipe"));
        session.put("contextSnapshot", Map.of(
                "allergies", List.of("깻잎"),
                "chronicConditions", List.of(),
                "dietaryRestrictions", List.of(),
                "medications", List.of(),
                "healthGoals", List.of(),
                "fridgeIngredients", List.of(),
                "explicitlyExcludedIngredients", List.of()));
        RecipeWorkSessionDTO stored = RecipeWorkSessionDTO.builder()
                .userId(1L)
                .chatSessionId(11L)
                .lastRecommendation("이 문자열은 안전 컨텍스트 복원에 사용하면 안 됩니다")
                .agentSession(session)
                .build();
        when(workSessionService.find(1L, 11L)).thenReturn(Optional.of(stored));
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> contextWithAllergy("깻잎"),
                (plan, context) -> {
                    throw new AssertionError("structured follow-up must not rediscover source");
                },
                workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("좀 더 자세히 설명해줘");
        ChatDto.Response response = orchestrator.handle(1L, 11L, request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("깻잎 알레르기").contains("수정 후 추천");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(String.join(" ", response.getRecipe().getIngredients())).doesNotContain("깻잎");
    }

    @Test
    void newDishRequestDoesNotReusePreviousSessionRecipe() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        RecipeWorkSessionDTO stored = RecipeWorkSessionDTO.builder()
                .userId(1L)
                .chatSessionId(12L)
                .lastRecommendation("이전 두쫀쿠 추천")
                .agentSession(agentSessionMap(new RecipeCandidate(
                        "두바이 쫀득 쿠키", "두바이 쫀득 쿠키 설명", List.of("마시멜로우", "카다이프면"), List.of("마시멜로우를 녹입니다.", "성형합니다."),
                        null, null, null, List.of("마시멜로우"), List.of(), List.of())))
                .build();
        when(workSessionService.find(1L, 12L)).thenReturn(Optional.of(stored));

        RecipeCandidate croiffleRecipe = new RecipeCandidate(
                "크룽지", "크룽지 설명", List.of("크루아상 생지 2개", "버터 10g", "시럽 1큰술"), List.of("생지를 누릅니다.", "바삭하게 굽습니다."),
                null, null, null, List.of("크루아상 생지"), List.of(), List.of());
        RecipeSourceDocument croiffleSource = new RecipeSourceDocument(
                "fixture:croiffle", RecipeSourceType.INTERNAL_DB, "크룽지", "chef",
                "https://salus.internal/recipes/croiffle",
                "title: 크룽지\ningredients:\n- 크루아상 생지 2개\n- 버터 10g\nsteps:\n- 생지를 누릅니다.\n- 바삭하게 굽습니다.",
                null, 1.0);

        java.util.concurrent.atomic.AtomicBoolean discoveredNewDish = new java.util.concurrent.atomic.AtomicBoolean(false);
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> UserRecipeContext.empty(userId),
                (plan, context) -> {
                    discoveredNewDish.set(true);
                    return List.of(croiffleSource);
                },
                workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("크룽지 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 12L, request).block();

        assertThat(response).isNotNull();
        assertThat(discoveredNewDish.get()).isTrue();
        assertThat(response.getReply()).contains("크룽지").doesNotContain("두바이 쫀득 쿠키");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getTitle()).contains("크룽지");
    }

    // 현재 로드에 성공하면 이전 스냅샷의 삭제된 프로필 값이 최신 값으로 대체되어야 합니다.
    // 냉장고 기능을 없애기 전에 저장된 세션(fridgeIngredients 키가 남아 있음)도 그대로 읽혀야 합니다.
    @Test
    void successfulCurrentContextLoadReplacesDeletedProfileValuesFromSnapshot() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        Map<String, Object> session = agentSessionMap(tunaKimbap());
        session.put("originalRecipe", session.get("personalizedRecipe"));
        session.put("contextSnapshot", Map.of(
                "allergies", List.of("깻잎"),
                "chronicConditions", List.of(),
                "dietaryRestrictions", List.of(),
                "medications", List.of("삭제된 테스트약"),
                "healthGoals", List.of(),
                "fridgeIngredients", List.of(Map.of("name", "삭제된 두부", "quantity", 1)),
                "explicitlyExcludedIngredients", List.of("양파")));
        RecipeWorkSessionDTO stored = RecipeWorkSessionDTO.builder()
                .userId(1L)
                .chatSessionId(13L)
                .lastRecommendation("legacy reply must not be parsed")
                .agentSession(session)
                .build();
        when(workSessionService.find(1L, 13L)).thenReturn(Optional.of(stored));
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> {
                    throw new AssertionError("structured follow-up must not rediscover source");
                },
                workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("좀 더 자세히 설명해줘");
        orchestrator.handle(1L, 13L, request).block();

        ArgumentCaptor<Object> stateCaptor = ArgumentCaptor.forClass(Object.class);
        verify(workSessionService).saveAgentSession(eq(1L), eq(13L), anyString(), stateCaptor.capture());
        RecipeAgentSession saved = (RecipeAgentSession) stateCaptor.getValue();
        assertThat(saved.contextSnapshot().allergies()).isEmpty();
        assertThat(saved.contextSnapshot().medications()).isEmpty();
        assertThat(saved.contextSnapshot().explicitlyExcludedIngredients()).containsExactly("양파");
    }

    // 사용자 맥락 로드에 실패하면 개인화 레시피를 노출하지 않아야 합니다(fail closed).
    @Test
    void contextLoadFailureBlocksPersonalizedRecipeExposure() {
        RecipeAgentOrchestrator orchestrator = orchestrator(
                userId -> {
                    throw new RuntimeException("context unavailable");
                },
                (plan, context) -> List.of(new RecipeSourceDocument(
                        "fixture:source",
                        RecipeSourceType.INTERNAL_DB,
                        "참치김밥",
                        "fixture",
                        "",
                        "title: 참치김밥\ningredients:\n- 밥 1공기\n- 참치 1캔\nsteps:\n- 재료를 넣고 김밥을 맙니다.",
                        java.time.LocalDateTime.of(2026, 7, 1, 0, 0),
                        0.95)),
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("참치김밥 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 12L, request).block();

        assertThat(response).isNotNull();
        assertThat(response.getRecipe()).isNull();
        assertThat(response.getReply()).contains("사용자 건강정보를 불러오지 못해");
    }

    // 세션 TTL이 지나면 오래된 건강 정보 스냅샷을 다음 요청에 쓰지 않아야 합니다.
    @Test
    void ttlExpiryDropsStaleHealthSnapshotBeforeNextOperationalRequest() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        RecipeWorkSessionService workSessions = new RecipeWorkSessionService(
                mock(StringRedisTemplate.class), new ObjectMapper().findAndRegisterModules(), mutableClock);
        Map<String, Object> stale = agentSessionMap(tunaKimbap());
        stale.put("contextSnapshot", Map.of(
                "allergies", List.of("깻잎"),
                "medications", List.of("만료될 테스트약"),
                "fridgeIngredients", List.of(Map.of("name", "두부")),
                "explicitlyExcludedIngredients", List.of("양파")));
        workSessions.saveAgentSession(1L, 77L, "stale", stale);
        mutableClock.advance(Duration.ofHours(6));

        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> List.of(new RecipeSourceDocument(
                        "fixture:ttl", RecipeSourceType.INTERNAL_DB, "참치김밥", "", "",
                        "title: 참치김밥\ningredients:\n- 밥 1공기\n- 참치 1캔\nsteps:\n- 재료를 넣고 김밥을 맙니다.",
                        java.time.LocalDateTime.of(2026, 7, 21, 0, 0), 0.95)),
                workSessions);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("참치김밥 레시피 알려줘");

        ChatDto.Response response = orchestrator.handle(1L, 77L, request).block();

        assertThat(response).isNotNull();
        RecipeWorkSessionDTO current = workSessions.find(1L, 77L).orElseThrow();
        Map<?, ?> context = (Map<?, ?>) current.getAgentSession().get("contextSnapshot");
        assertThat((List<?>) context.get("allergies")).isEmpty();
        assertThat((List<?>) context.get("medications")).isEmpty();
        assertThat(context.containsKey("fridgeIngredients")).isFalse();
        assertThat((List<?>) context.get("explicitlyExcludedIngredients")).isEmpty();
    }

    // Recipe Agent 기능 플래그는 기본적으로 꺼져 있고, 모델 설정은 환경 변수로 덮어쓸 수 있어야 합니다.
    @Test
    void recipeAgentAndModelSettingsKeepSafeDefaultsAndEnvironmentOverrides() throws Exception {
        String properties = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.properties"));

        assertThat(properties).contains(
                "ollama.chat-model=${OLLAMA_CHAT_MODEL:${OLLAMA_MODEL:qwen3:8b}}",
                "ollama.recipe-model=${OLLAMA_RECIPE_MODEL:${OLLAMA_MODEL:qwen3:8b}}",
                "ollama.recipe-timeout-seconds=${OLLAMA_RECIPE_TIMEOUT_SECONDS:${OLLAMA_TIMEOUT_SECONDS:180}}",
                "recipe.agent.enabled=${RECIPE_AGENT_ENABLED:false}",
                "recipe.agent.source-discovery-enabled=${RECIPE_AGENT_SOURCE_DISCOVERY_ENABLED:false}",
                "recipe.agent.web-source-enabled=${RECIPE_AGENT_WEB_SOURCE_ENABLED:false}",
                "recipe.agent.youtube-source-enabled=${RECIPE_AGENT_YOUTUBE_SOURCE_ENABLED:false}",
                "recipe.agent.youtube-api-key=${YOUTUBE_API_KEY:}",
                "recipe.agent.youtube-max-search-results=${RECIPE_AGENT_YOUTUBE_MAX_RESULTS:5}",
                "recipe.agent.youtube-max-search-requests=${RECIPE_AGENT_YOUTUBE_MAX_SEARCH_REQUESTS:2}",
                "recipe.agent.youtube-transcript-enabled=${RECIPE_AGENT_YOUTUBE_TRANSCRIPT_ENABLED:false}",
                "recipe.agent.personalization-enabled=${RECIPE_AGENT_PERSONALIZATION_ENABLED:true}");
    }


    // 아래 private 메서드/클래스들은 정책 엔진, 오케스트레이터, 테스트용 레시피와 가짜 Clock을 만드는 도우미입니다.
    private RecipePersonalizationPolicyEngine engine(MedicationFoodInteractionPort interactionPort) {
        return new RecipePersonalizationPolicyEngine(List.of(
                new AllergyPolicy(sharedAllergenMatcher()),
                new MedicationInteractionPolicy(interactionPort),
                new ChronicConditionPolicy(),
                new DietaryRestrictionPolicy(),
                new ExplicitExclusionPolicy()));
    }

    private MedicationFoodInteractionPort unknownMedication() {
        return new UnknownMedicationFoodInteractionAdapter();
    }

    private UserRecipeContext contextWithAllergy(String allergy) {
        return new UserRecipeContext(1L, List.of(allergy), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private RecipeSourceDocument sourceDocument() {
        return new RecipeSourceDocument(
                "fixture:source",
                RecipeSourceType.INTERNAL_DB,
                "김치찌개",
                "fixture",
                "",
                "title: 김치찌개\ningredients:\n- 김치 200g\n- 두부 1모\nsteps:\n- 김치를 볶고 물을 부어 끓입니다.",
                java.time.LocalDateTime.of(2026, 7, 1, 0, 0),
                0.95);
    }

    // 뺀 재료가 핵심이라 막히면, 그 재료 없이 만드는 같은 요리를 한 번 더 찾아 검증을 거쳐 답합니다
    // (2026-10-09 실전 평가: "김치찌개 레시피 알려줘, 돼지고기는 빼줘"가 차단만 되고 대안이 없었음).
    @Test
    void excludedCoreIngredientLeadsToAlternativeRecipeWithoutIt() {
        RecipeSourceDocument porkStew = new RecipeSourceDocument(
                "fixture:pork-kimchi", RecipeSourceType.INTERNAL_DB, "김치찌개", "chef", "https://salus.internal/recipes/pork-kimchi",
                "title: 김치찌개\ningredients:\n- 돼지고기 200g\n- 김치 300g\n- 두부 1/2모\nsteps:\n- 돼지고기와 김치를 볶습니다.\n- 물을 붓고 끓입니다.",
                null, 1.0);
        RecipeSourceDocument tunaStew = new RecipeSourceDocument(
                "fixture:tuna-kimchi", RecipeSourceType.INTERNAL_DB, "참치김치찌개", "chef", "https://salus.internal/recipes/tuna-kimchi",
                "title: 참치김치찌개\ningredients:\n- 참치 1캔\n- 김치 300g\n- 두부 1/2모\nsteps:\n- 김치를 볶습니다.\n- 물과 참치를 넣고 끓입니다.",
                null, 1.0);
        List<RecipeResearchPlan> plans = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> {
                    plans.add(plan);
                    return List.of(plan.avoidIngredients().isEmpty() ? porkStew : tunaStew);
                },
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘, 돼지고기는 빼줘");
        ChatDto.Response response = orchestrator.handle(null, null, request).block();

        assertThat(plans).hasSize(2);
        assertThat(plans.get(1).avoidIngredients()).containsExactly("돼지고기");
        assertThat(plans.get(1).searchQueries()).contains("돼지고기 없는 김치찌개 레시피");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getTitle()).isEqualTo("참치김치찌개");
        assertThat(String.join(" ", response.getRecipe().getIngredients())).doesNotContain("돼지고기");
        assertThat(response.getReply()).contains("요청하신 대로 돼지고기 없이 만드는 김치찌개 레시피를 찾았어요");
    }

    // 차단 답변은 내부 판정 형식 대신 사람이 읽는 문장으로 쓰되, 알레르기 이유와 제외 재료 이름은 빠뜨리지 않습니다.
    // 2026-10-12 "오징어 안 들어간 김치전"에 "[원본 레시피 기준] … 안전상 제공 차단"이 그대로 노출됨.
    @Test
    void blockedReplyIsReadableAndKeepsAllergyAndExclusionReasons() {
        RecipeCandidate recipe = new RecipeCandidate("오징어김치전", "", List.of("오징어 1마리", "김치 1컵", "부침가루 1컵"),
                List.of("반죽을 만든다.", "팬에 부친다."), null, 1, 20, List.of("오징어", "김치"), List.of(), List.of());
        RecipePersonalizationDecision decision = new RecipePersonalizationDecision(
                RecipeDecisionType.BLOCK,
                List.of(
                        new RecipeConflict(RecipeConflictType.USER_EXCLUSION, "오징어", "사용자 제외", "사용자 제외 요청", ConflictSeverity.HIGH, null),
                        new RecipeConflict(RecipeConflictType.ALLERGY, "밀", "밀 알레르기", "밀 알레르기와 충돌하는 부침가루가 들어 있습니다.", ConflictSeverity.BLOCKING, null)),
                List.of(), List.of());
        PersonalizedRecipeResult result = new PersonalizedRecipeResult(recipe, recipe, decision, List.of());

        String reply = new RecipeResponseComposer().compose(result, new RecipeValidationResult(false,
                List.of("사용자 제외 재료가 최종 레시피에 남아 있습니다: 오징어")));

        assertThat(reply).startsWith("'오징어김치전' 레시피는 이번에 제공할 수 없어요.")
                .contains("오징어가 이 요리의 핵심 재료라 빼면 다른 요리가 돼요.")
                .contains("밀 알레르기와 충돌하는 부침가루가 들어 있습니다.")
                .doesNotContain("[원본 레시피 기준]").doesNotContain("[개인화된 최종 레시피]").doesNotContain("안전상 제공 차단")
                .doesNotContain("남아 있습니다: 오징어");
        assertThat(AgentText.withSubjectParticle("명란")).isEqualTo("명란이");
        assertThat(AgentText.withSubjectParticle("오징어")).isEqualTo("오징어가");
    }

    // 차단 답변에도 차단 이유가 아닌 건강 주의(만성질환 등)와 개인차 안내는 남기고, 핵심 아닌 제외 재료는 쓰지 않습니다.
    @Test
    void blockedReplyKeepsHealthCautionsButNotMinorExclusions() {
        RecipeCandidate recipe = new RecipeCandidate("땅콩조림", "", List.of("땅콩 200g", "간장 3큰술", "대파 1대"),
                List.of("땅콩을 삶는다.", "간장에 조린다."), null, 1, 30, List.of("땅콩"), List.of(), List.of());
        RecipePersonalizationDecision decision = new RecipePersonalizationDecision(
                RecipeDecisionType.BLOCK,
                List.of(
                        new RecipeConflict(RecipeConflictType.ALLERGY, "땅콩", "땅콩 알레르기", "땅콩 알레르기와 충돌하는 핵심 재료입니다.", ConflictSeverity.BLOCKING, null),
                        new RecipeConflict(RecipeConflictType.CHRONIC_CONDITION, "염분 양념", "혈압/저염 목표", "염분이 높은 양념은 양 조절이 필요합니다.", ConflictSeverity.CAUTION, null),
                        new RecipeConflict(RecipeConflictType.USER_EXCLUSION, "대파", "사용자 제외 요청", "사용자가 명시적으로 제외를 요청했습니다.", ConflictSeverity.CAUTION, null)),
                List.of(), List.of());
        PersonalizedRecipeResult result = new PersonalizedRecipeResult(recipe, recipe, decision, List.of());

        String reply = new RecipeResponseComposer().compose(result, new RecipeValidationResult(true, List.of()));

        assertThat(reply).contains("제공할 수 없어요")
                .contains("- 땅콩 알레르기와 충돌하는 핵심 재료입니다.")
                .contains("- 참고: 염분이 높은 양념은 양 조절이 필요합니다.")
                .contains("섭취 가능 여부와 적정량은 개인 상태에 따라 다를 수 있습니다.")
                .doesNotContain("명시적으로 제외");
    }

    // 대안 검색에서 출처 자체를 못 찾으면 그렇게 알립니다.
    @Test
    void alternativeSearchWithoutAnySourceSaysSourceWasNotFound() {
        RecipeSourceDocument porkStew = new RecipeSourceDocument(
                "fixture:pork-kimchi", RecipeSourceType.INTERNAL_DB, "김치찌개", "chef", "https://salus.internal/recipes/pork-kimchi",
                "title: 김치찌개\ningredients:\n- 돼지고기 200g\n- 김치 300g\nsteps:\n- 돼지고기와 김치를 볶습니다.\n- 물을 붓고 끓입니다.",
                null, 1.0);
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> plan.avoidIngredients().isEmpty() ? List.of(porkStew) : List.of(),
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘, 돼지고기는 빼줘");
        ChatDto.Response response = orchestrator.handle(null, null, request).block();

        assertThat(response.getReply()).contains("돼지고기 없이 만드는 김치찌개 레시피도 찾아봤지만 믿을 만한 출처를 찾지 못했어요")
                .doesNotContain("찾은 레시피도");
    }

    // "돼지고기는 빼줘"인데 출처 재료가 "삼겹살"로만 적혀 있으면 이름 비교로는 못 잡습니다. 사전의 파생 재료까지 봐서
    // 돼지고기 없는 레시피를 다시 찾습니다. 2026-10-13 실전 평가: 삼겹살 김치찌개가 "그대로 추천"으로 나감.
    @Test
    void excludedIngredientWrittenAsDerivedCutLeadsToAlternative() {
        RecipeSourceDocument samgyeopsalStew = new RecipeSourceDocument(
                "fixture:samgyeopsal-kimchi", RecipeSourceType.INTERNAL_DB, "김치찌개", "chef", "https://salus.internal/recipes/samgyeopsal-kimchi",
                "title: 김치찌개\ningredients:\n- 김치 400g\n- 삼겹살 200g (또는 목살)\n- 물 1L\nsteps:\n- 삼겹살과 김치를 볶습니다.\n- 물을 붓고 끓입니다.",
                null, 1.0);
        RecipeSourceDocument tunaStew = new RecipeSourceDocument(
                "fixture:tuna-kimchi", RecipeSourceType.INTERNAL_DB, "참치김치찌개", "chef", "https://salus.internal/recipes/tuna-kimchi",
                "title: 참치김치찌개\ningredients:\n- 참치 1캔\n- 김치 300g\n- 두부 1/2모\nsteps:\n- 김치를 볶습니다.\n- 물과 참치를 넣고 끓입니다.",
                null, 1.0);
        List<RecipeResearchPlan> plans = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> {
                    plans.add(plan);
                    return List.of(plan.avoidIngredients().isEmpty() ? samgyeopsalStew : tunaStew);
                },
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘, 돼지고기는 빼줘");
        ChatDto.Response response = orchestrator.handle(null, null, request).block();

        assertThat(plans).hasSize(2);
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getTitle()).isEqualTo("참치김치찌개");
        String all = String.join(" ", response.getRecipe().getIngredients()) + " " + String.join(" ", response.getRecipe().getSteps());
        assertThat(all).doesNotContain("삼겹살").doesNotContain("목살").doesNotContain("돼지고기");
    }

    // 대안도 없으면 삼겹살이 든 레시피를 내보내지 않고, 이유를 사람이 읽는 문장으로 알립니다.
    @Test
    void excludedIngredientWrittenAsDerivedCutIsNotServedWithoutAlternative() {
        RecipeSourceDocument samgyeopsalStew = new RecipeSourceDocument(
                "fixture:samgyeopsal-kimchi", RecipeSourceType.INTERNAL_DB, "김치찌개", "chef", "https://salus.internal/recipes/samgyeopsal-kimchi",
                "title: 김치찌개\ningredients:\n- 김치 400g\n- 삼겹살 200g (또는 목살)\n- 물 1L\nsteps:\n- 삼겹살과 김치를 볶습니다.\n- 물을 붓고 끓입니다.",
                null, 1.0);
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> plan.avoidIngredients().isEmpty() ? List.of(samgyeopsalStew) : List.of(),
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘, 돼지고기는 빼줘");
        ChatDto.Response response = orchestrator.handle(null, null, request).block();

        assertThat(response.getRecipe()).isNull();
        assertThat(response.getReply()).contains("제공할 수 없어요")
                .contains("뺀 재료(돼지고기)가 다른 이름의 재료로 들어 있어")
                .doesNotContain("[개인화된 최종 레시피]");
    }

    // 사전의 파생 재료로 제외 재료를 찾되, 다른 고기를 돼지고기로 보지 않습니다(오탐 방지).
    @Test
    void avoidFilterUsesDictionaryDerivedTermsWithoutFalsePositives() {
        assertThat(CompositeRecipeSourceDiscoveryAdapter.containsAvoided("삼겹살 200g (또는 목살)", List.of("돼지고기"))).isTrue();
        assertThat(CompositeRecipeSourceDiscoveryAdapter.containsAvoided("돼지 고기 200g", List.of("돼지고기"))).isTrue();
        assertThat(CompositeRecipeSourceDiscoveryAdapter.containsAvoided("소고기 양지 200g, 닭가슴살 1쪽", List.of("돼지고기"))).isFalse();
        assertThat(CompositeRecipeSourceDiscoveryAdapter.containsAvoided("우유 200ml", List.of("밀"))).isFalse();
        assertThat(CompositeRecipeSourceDiscoveryAdapter.containsAvoided("오이 1개", List.of("명란"))).isFalse();

        RecipeValidationResult validation = new RecipeValidationPipeline().validate(
                new RecipeCandidate("김치찌개", "", List.of("김치 400g", "목살 200g"), List.of("목살과 김치를 볶는다.", "물을 붓고 끓인다."),
                        null, 1, 20, List.of("김치"), List.of(), List.of()),
                new UserRecipeContext(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of("돼지고기")));
        assertThat(validation.valid()).isFalse();
        assertThat(validation.reasons()).contains("사용자 제외 재료가 최종 레시피에 남아 있습니다: 돼지고기");
    }

    // 재료를 뺄 때 그 재료만 다루는 앞 절과 재료로 만든 낱말("오이채")도 함께 지워, 뜻이 없는 조각을 남기지 않습니다.
    // 2026-10-14 비빔국수 "오이는 빼줘": "채 썰고 계란을 …", "그릇에 담고채, 계란지단 …"이 남음.
    @Test
    void removingIngredientDropsItsClauseAndDerivedWords() {
        RecipeCandidate noodles = new RecipeCandidate("비빔국수", "", List.of("소면 200g", "오이 1/2개", "계란 2개"),
                List.of("오이는 채 썰고 계란을 지져서 채 썰어 고명을 준비한다.",
                        "오이는 씨를 제거하고 반달 모양으로 썰고, 새우는 껍질을 벗긴다.",
                        "그릇에 담고 오이채, 계란지단, 김가루 고명을 올린다.",
                        "소면을 삶아 찬물에 헹군다."),
                null, 1, 15, List.of("소면"), List.of(), List.of());
        RecipePersonalizationDecision decision = new RecipePersonalizationDecision(RecipeDecisionType.MODIFY, List.of(),
                List.of(new RecipeModification("오이", "REMOVE", null, "사용자 제외 요청")), List.of());

        RecipeCandidate personalized = new RecipeModificationService().apply(noodles, decision);

        assertThat(personalized.steps()).containsExactly(
                "계란을 지져서 채 썰어 고명을 준비한다.",
                "새우는 껍질을 벗긴다.",
                "그릇에 담고 계란지단, 김가루 고명을 올린다.",
                "소면을 삶아 찬물에 헹군다.");
        assertThat(personalized.ingredients()).noneMatch(line -> line.contains("오이"));

        // 그 재료만 목적어인 첫 절은 지우고, 다른 재료와 나열된 경우는 동사를 남깁니다(2026-10-14 함박스테이크, 계란 알레르기).
        RecipeCandidate steak = new RecipeCandidate("함박스테이크", "", List.of("다진 고기 600g", "빵가루 2컵", "계란 2개"),
                List.of("다진 고기에 빵가루, 계란을 넣고 치댄다.",
                        "볼에 빵가루와 계란을 넣고 섞은 뒤 고기를 넣는다.",
                        "볼에 다진 마늘(1스푼), 계란(2개), 소금(1/2스푼)을 넣는다.",
                        "계란을 반숙으로 튀겨 준비하고, 아스파라거스와 방울토마토를 곁들인다."),
                null, 1, 30, List.of("다진 고기"), List.of(), List.of());
        RecipeCandidate noEgg = new RecipeModificationService().apply(steak, new RecipePersonalizationDecision(
                RecipeDecisionType.MODIFY, List.of(), List.of(new RecipeModification("계란", "REMOVE", null, "알레르기")),
                List.of()));
        // 분량 뒤 조사와 주격 조사도 함께 지웁니다(2026-10-14 제육덮밥 "양파는 빼줘").
        RecipeCandidate jeyuk = new RecipeCandidate("제육덮밥", "", List.of("앞다리살 300g", "양파 1/2개", "대파 1대"),
                List.of("파기름 위에 양파 1/2개를 슬라이스하여 넣고 물을 붓는다.", "양파가 어느 정도 익으면 대파를 넣는다."),
                null, 1, 20, List.of("앞다리살"), List.of(), List.of());
        RecipeCandidate noOnion = new RecipeModificationService().apply(jeyuk, new RecipePersonalizationDecision(
                RecipeDecisionType.MODIFY, List.of(), List.of(new RecipeModification("양파", "REMOVE", null, "사용자 제외 요청")),
                List.of()));
        // 그 재료만 다루던 절과 조건은 절째 지워, 무엇을 써는지 모르는 문장을 남기지 않습니다.
        assertThat(noOnion.steps()).containsExactly("물을 붓는다.", "대파를 넣는다.");

        // 한 문장 전체가 뺀 재료만 다루면 단계째 지웁니다(2026-10-14 닭볶음탕 "감자는 빼줘").
        RecipeCandidate stew = new RecipeCandidate("닭볶음탕", "", List.of("닭 1마리", "감자 2개", "양파 1개"),
                List.of("감자는 먹기 좋은 크기로 잘라주세요.", "양파는 3등분으로 썰어 준비합니다.", "닭과 양념을 넣고 끓인다."),
                null, 1, 40, List.of("닭"), List.of(), List.of());
        RecipeCandidate noPotato = new RecipeModificationService().apply(stew, new RecipePersonalizationDecision(
                RecipeDecisionType.MODIFY, List.of(), List.of(new RecipeModification("감자", "REMOVE", null, "사용자 제외 요청")),
                List.of()));
        assertThat(noPotato.steps()).containsExactly("양파는 3등분으로 썰어 준비합니다.", "닭과 양념을 넣고 끓인다.");

        assertThat(noEgg.steps()).containsExactly(
                "다진 고기에 빵가루를 넣고 치댄다.",
                "볼에 빵가루를 넣고 섞은 뒤 고기를 넣는다.",
                "볼에 다진 마늘(1스푼), 소금(1/2스푼)을 넣는다.",
                "아스파라거스와 방울토마토를 곁들인다.");
    }

    // "팽이버섯은 빼줘"인데 출처에 "팽이"로만 적혀 있어도 찾고, 한 줄에 나열한 다른 버섯은 남깁니다(2026-10-14 버섯전골).
    @Test
    void mushroomExclusionMatchesShortNameAndKeepsOtherListedItems() {
        RecipeCandidate hotpot = new RecipeCandidate("버섯전골", "",
                List.of("다양한버섯 (느타리/표고/팽이/새송이)", "소불고기 200g", "대파 1대"),
                List.of("버섯을 먹기 좋게 손질한다.", "육수에 버섯과 고기를 올려 끓인다."),
                null, 1, 30, List.of("버섯"), List.of(), List.of());
        UserRecipeContext context = new UserRecipeContext(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("팽이버섯"));

        PolicyEvaluation evaluation = new ExplicitExclusionPolicy().evaluate(hotpot, context);
        RecipeCandidate personalized = new RecipeModificationService().apply(hotpot, new RecipePersonalizationDecision(
                RecipeDecisionType.MODIFY, evaluation.conflicts(), evaluation.modifications(), List.of()));

        assertThat(evaluation.conflicts()).isNotEmpty();
        assertThat(personalized.ingredients()).contains("다양한버섯 (느타리/표고/새송이)");
        assertThat(String.join(" ", personalized.ingredients())).doesNotContain("팽이");
        assertThat(new RecipeValidationPipeline().validate(personalized, context).valid()).isTrue();
        assertThat(new RecipeValidationPipeline().validate(hotpot, context).valid()).isFalse();
        assertThat(AgentText.exclusionForms("양송이버섯")).containsExactly("양송이버섯", "양송이");
        assertThat(AgentText.exclusionForms("버섯")).containsExactly("버섯");
    }

    // 한 글자 재료("배")를 빼면 배와 배즙은 빼고 배추·배달은 건드리지 않습니다(2026-10-14 육회 "배 알러지 있어서 배는 빼줘").
    @Test
    void singleSyllableExclusionRemovesPearButNotNapaCabbage() {
        RecipeCandidate yukhoe = new RecipeCandidate("육회", "",
                List.of("소고기 우둔살 300g", "배 1/2개", "배즙 2큰술", "배추 2장", "달걀노른자 2개"),
                List.of("배 1/2개를 가늘게 채 썰고, 배추는 깨끗이 씻는다.", "배를 먼저 두르고 육회를 담는다.", "배달 용기에 담지 않는다."),
                null, 1, 20, List.of("소고기"), List.of(), List.of());
        UserRecipeContext context = new UserRecipeContext(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("배"));

        PolicyEvaluation evaluation = new ExplicitExclusionPolicy().evaluate(yukhoe, context);
        RecipeCandidate personalized = new RecipeModificationService().apply(yukhoe, new RecipePersonalizationDecision(
                RecipeDecisionType.MODIFY, evaluation.conflicts(), evaluation.modifications(), List.of()));

        assertThat(evaluation.conflicts()).singleElement().satisfies(conflict ->
                assertThat(conflict.severity()).isEqualTo(ConflictSeverity.CAUTION));
        assertThat(personalized.ingredients()).containsExactly("소고기 우둔살 300g", "배추 2장", "달걀노른자 2개");
        assertThat(String.join(" ", personalized.steps())).contains("배추는 깨끗이 씻는다").contains("배달 용기")
                .doesNotContain("배 1/2개").doesNotContain("배를");
        assertThat(new RecipeValidationPipeline().validate(personalized, context).valid()).isTrue();
        assertThat(new RecipeValidationPipeline().validate(yukhoe, context).valid()).isFalse();
        assertThat(AgentText.mentions("배추김치", "배")).isFalse();
        assertThat(AgentText.mentions("두배로 늘린다", "배")).isFalse();
        // "에·에서·랑"이 붙어도 그 재료입니다(2026-10-14 깍두기 "깍둑썬 무에"가 남음).
        assertThat(AgentText.mentions("깍둑썬 무에 소금을 뿌린다", "무")).isTrue();
        assertThat(AgentText.mentions("무랑 쪽파를 버무린다", "무")).isTrue();
        assertThat(AgentText.mentions("무조건 20분 절인다", "무")).isFalse();
    }

    // 대안에도 뺀 재료가 핵심이면 처음 결과를 그대로 답합니다. 대안 검색은 한 번뿐입니다.
    @Test
    void alternativeSearchHappensOnceAndKeepsFirstResultWhenNothingBetterIsFound() {
        RecipeSourceDocument porkStew = new RecipeSourceDocument(
                "fixture:pork-kimchi", RecipeSourceType.INTERNAL_DB, "김치찌개", "chef", "https://salus.internal/recipes/pork-kimchi",
                "title: 김치찌개\ningredients:\n- 돼지고기 200g\n- 김치 300g\nsteps:\n- 돼지고기와 김치를 볶습니다.\n- 물을 붓고 끓입니다.",
                null, 1.0);
        List<RecipeResearchPlan> plans = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> {
                    plans.add(plan);
                    return List.of(porkStew);
                },
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘, 돼지고기는 빼줘");
        ChatDto.Response response = orchestrator.handle(null, null, request).block();

        assertThat(plans).hasSize(2);
        assertThat(response.getReply()).doesNotContain("없이 만드는 김치찌개 레시피를 찾았어요");
        // 대안을 찾았지만 쓸 수 없었다는 것도 알려 줍니다(2026-10-12 "오징어 안 들어간 김치전"이 차단으로만 끝남).
        // 출처를 찾긴 했으므로 "출처를 찾지 못했어요"라고 하지 않습니다.
        assertThat(response.getReply()).contains("돼지고기 없이 만드는 김치찌개 레시피도 찾아봤지만, 찾은 레시피도 뺀 재료가 핵심이거나")
                .doesNotContain("믿을 만한 출처를 찾지 못했어요");
        if (response.getRecipe() != null) {
            assertThat(String.join(" ", response.getRecipe().getIngredients())).doesNotContain("돼지고기");
        }
    }

    // 두 요리를 함께 물으면 첫 요리만 찾고, 나머지는 이어서 물어보도록 카드 안내로 알려 줍니다.
    // 웹 출처는 "검토 전" 출처로 링크와 함께 카드에 담습니다.
    @Test
    void multiDishRequestAnswersFirstDishWithNoticeAndWebSourceLink() {
        RecipeSourceDocument kimbap = new RecipeSourceDocument(
                "web:kimbap", RecipeSourceType.GENERAL_WEB, "참치김밥 만들기", "blogger", "https://www.10000recipe.com/recipe/1",
                "title: 참치김밥\ningredients:\n- 밥 1공기\n- 참치 1캔\n- 김 2장\nsteps:\n- 밥을 펴고 참치를 올립니다.\n- 단단히 맙니다.",
                null, 0.7);
        List<RecipeResearchPlan> plans = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> {
                    plans.add(plan);
                    return List.of(kimbap);
                },
                mock(RecipeWorkSessionService.class));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("참치김밥 레시피랑 유부초밥 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(null, null, request).block();

        assertThat(plans.get(0).dishName()).isEqualTo("참치김밥");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getNotices()).singleElement().asString()
                .contains("'참치김밥'부터").contains("'유부초밥'은(는) 이어서 물어봐 주세요");
        assertThat(response.getRecipe().getSources()).singleElement().satisfies(source -> {
            assertThat(source.getUrl()).isEqualTo("https://www.10000recipe.com/recipe/1");
            assertThat(source.isReviewed()).isFalse();
        });
    }

    private RecipeSourceDocument kimchiPancakeSource() {
        return new RecipeSourceDocument(
                "web:kimchijeon", RecipeSourceType.GENERAL_WEB, "김치전", "blogger", "https://www.10000recipe.com/recipe/2",
                "title: 김치전\ningredients:\n- 김치 1/3포기\n- 부침가루 1.5컵\n- 물 100ml\nsteps:\n- 부침가루와 물을 섞어 반죽을 만든다.\n- 김치를 넣고 섞는다.\n- 팬에 얇게 펴서 굽는다.",
                null, 0.7);
    }

    // 레시피를 찾지 못한 답(재료 없음)은 직전 레시피로 이어 쓰지 않습니다.
    // 2026-10-12 실사용: "그냥 김치전" 실패 뒤 "김치전 레시피 알려줘"가 같은 실패를 세 번 되풀이함.
    @Test
    void failedPreviousAnswerIsNotReusedAsFollowUp() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        Map<String, Object> failed = agentSessionMap(RecipeCandidate.empty("그냥 김치전"));
        when(workSessionService.find(1L, 30L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(30L).lastRecommendation("못 찾음").agentSession(failed).build()));
        List<RecipeResearchPlan> plans = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(UserRecipeContext::empty,
                (plan, context) -> {
                    plans.add(plan);
                    return List.of(kimchiPancakeSource());
                }, workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치전 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 30L, request).block();

        assertThat(plans).extracting(RecipeResearchPlan::dishName).containsExactly("김치전");
        assertThat(response.getRecipe()).isNotNull();
    }

    // 이전 대화에서 뺀 재료(오징어)는 다른 요리 요청에 끌고 가지 않습니다.
    @Test
    void previousExclusionsDoNotCarryOverToANewDish() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        Map<String, Object> session = agentSessionMap(tunaKimbap());
        session.put("contextSnapshot", Map.of(
                "allergies", List.of(), "chronicConditions", List.of(), "dietaryRestrictions", List.of(),
                "medications", List.of(), "healthGoals", List.of(), "fridgeIngredients", List.of(),
                "explicitlyExcludedIngredients", List.of("오징어")));
        when(workSessionService.find(1L, 31L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(31L).lastRecommendation("참치김밥").agentSession(session).build()));
        List<UserRecipeContext> contexts = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(UserRecipeContext::empty,
                (plan, context) -> {
                    contexts.add(context);
                    return List.of(kimchiPancakeSource());
                }, workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치전 레시피 알려줘");
        orchestrator.handle(1L, 31L, request).block();

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).explicitlyExcludedIngredients()).doesNotContain("오징어");
    }

    // "초보라서 자세히"에는 초보 팁을 단계와 따로 붙이고, 원문에 없는 설명이라고 안내합니다.
    // 팁을 만들 수 없으면 지어내지 않고 원문 링크에서 보도록 안내합니다.
    @Test
    void detailFollowUpAddsBeginnerTipsOrPointsToTheOriginal() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        Map<String, Object> session = agentSessionMap(tunaKimbap());
        session.put("sourceEvidence", List.of(Map.of("sourceId", "web:k", "sourceType", "GENERAL_WEB", "title", "참치김밥",
                "url", "https://www.10000recipe.com/recipe/3", "content", "title: 참치김밥", "sourceReliability", 0.7)));
        when(workSessionService.find(1L, 32L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(32L).lastRecommendation("참치김밥").agentSession(session).build()));
        RecipeAgentOrchestrator orchestrator = orchestrator(UserRecipeContext::empty,
                (plan, context) -> {
                    throw new AssertionError("detail follow-up must not search again");
                }, workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("나 초보라서 좀더 자세히 알려줘");

        ChatDto.Response withoutGuide = orchestrator.handle(1L, 32L, request).block();
        assertThat(withoutGuide.getRecipe().getNotices()).contains("더 자세한 설명과 사진은 아래 '참고한 출처'의 원문에서 확인해 주세요.");

        RecipeBeginnerGuide guide = mock(RecipeBeginnerGuide.class);
        when(guide.explain(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(List.of("김이 찢어지지 않게 밥을 얇게 펴요.", "")));
        ReflectionTestUtils.setField(orchestrator, "beginnerGuide", guide);

        ChatDto.Response withGuide = orchestrator.handle(1L, 32L, request).block();
        assertThat(withGuide.getRecipe().getStepTips()).containsExactly("김이 찢어지지 않게 밥을 얇게 펴요.", "");
        assertThat(withGuide.getRecipe().getNotices()).anyMatch(notice -> notice.contains("원문에 없는 내용"));
        assertThat(withGuide.getReply()).contains("[초보 팁").contains("김이 찢어지지 않게");
    }

    // 2026-10-12 실사용: 명란계란말이 다음 "명란이 안 들어간 계란말이 레시피도 알려줘"에 명란 든 레시피를 그대로 줌.
    // 후속 요청이어도 새로 뺀 재료가 핵심이면 그 재료 없는 같은 요리를 다시 찾고, 명란 든 레시피는 내보내지 않습니다.
    @Test
    void newlyExcludedCoreIngredientInFollowUpFindsRecipeWithoutIt() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        RecipeCandidate myeongran = new RecipeCandidate("명란계란말이", "", List.of("달걀 4개", "저염 명란젓 3개", "맛술 1스푼"),
                List.of("달걀을 풀어 맛술을 섞는다.", "달걀물 위에 명란젓을 올려 돌돌 만다."), null, null, null,
                List.of("달걀", "명란젓"), List.of(), List.of());
        when(workSessionService.find(1L, 40L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(40L).lastRecommendation("명란계란말이").agentSession(agentSessionMap(myeongran)).build()));
        RecipeSourceDocument plain = new RecipeSourceDocument(
                "fixture:gyeranmari", RecipeSourceType.INTERNAL_DB, "계란말이", "chef", "https://salus.internal/recipes/gyeranmari",
                "title: 계란말이\ningredients:\n- 달걀 4개\n- 대파 1/4대\n- 소금 약간\nsteps:\n- 달걀에 대파와 소금을 넣고 푼다.\n- 팬에 부어 돌돌 만다.",
                null, 1.0);
        List<RecipeResearchPlan> plans = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(UserRecipeContext::empty,
                (plan, context) -> {
                    plans.add(plan);
                    return plan.avoidIngredients().contains("명란") ? List.of(plain) : List.of();
                }, workSessionService);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("명란이 안 들어간 계란말이 레시피도 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 40L, request).block();

        assertThat(plans).isNotEmpty();
        assertThat(plans.get(plans.size() - 1).avoidIngredients()).contains("명란");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getTitle()).isEqualTo("계란말이");
        assertThat(String.join(" ", response.getRecipe().getIngredients()) + String.join(" ", response.getRecipe().getSteps()))
                .doesNotContain("명란");
    }

    // 알레르기 재료를 단계에서 지울 때 뒤의 분량도 함께 지웁니다(2026-10-12 "오이 1개, 깻잎 한 묶음" → "오이 1개 한 묶음").
    @Test
    void removingIngredientFromStepAlsoRemovesItsQuantity() {
        RecipeCandidate golbaengi = new RecipeCandidate("골뱅이소면", "", List.of("골뱅이 1캔", "오이 1개", "깻잎 1묶음", "대파 1/2대"),
                List.of("골뱅이를 자르고 오이 1개, 깻잎 한 묶음, 대파 반 대를 먹기 좋게 썬다.", "양념에 버무린다."),
                null, null, null, List.of("골뱅이"), List.of("깻잎"), List.of());
        RecipePersonalizationDecision decision = new RecipePersonalizationDecision(RecipeDecisionType.MODIFY,
                List.of(), List.of(new RecipeModification("깻잎", "REMOVE", null, "알레르기")), List.of());

        RecipeCandidate modified = new RecipeModificationService().apply(golbaengi, decision);

        assertThat(modified.steps().get(0)).doesNotContain("깻잎").doesNotContain("한 묶음").contains("오이 1개").contains("대파 반 대");
        assertThat(AgentText.withObjectParticle("깻잎")).isEqualTo("깻잎을");
        assertThat(AgentText.withObjectParticle("오이")).isEqualTo("오이를");
    }

    private RecipeWorkSessionService previousDujjonkuSession() {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        Map<String, Object> session = agentSessionMap(new RecipeCandidate(
                "두바이 쫀득 쿠키", "", List.of("마시멜로우 150g", "카다이프면 100g"), List.of("마시멜로우를 녹입니다.", "성형합니다."),
                null, null, null, List.of("마시멜로우"), List.of(), List.of()));
        session.put("sourceEvidence", List.of(Map.of(
                "sourceId", "web:a", "sourceType", "GENERAL_WEB", "title", "두쫀쿠 A",
                "url", "https://blog.naver.com/a/1", "content", "title: 두바이 쫀득 쿠키", "sourceReliability", 0.6)));
        when(workSessionService.find(1L, 20L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(20L).lastRecommendation("이전 두쫀쿠").agentSession(session).build()));
        return workSessionService;
    }

    // "다른 두쫀쿠 레시피 알려줘"는 이미 보여 준 출처를 빼고 다시 찾습니다(2026-10-09 평가: 같은 출처로 답함).
    // 모바일 주소(m.blog.naver.com)로 저장된 같은 글도 이미 보여 준 출처로 봅니다.
    @Test
    void anotherRecipeRequestExcludesPreviouslyShownSource() {
        RecipeSourceDocument otherSource = new RecipeSourceDocument(
                "fixture:b", RecipeSourceType.INTERNAL_DB, "두쫀쿠 B", "chef", "https://salus.internal/recipes/dujjonku-b",
                "title: 두쫀쿠\ningredients:\n- 마시멜로우 200g\n- 카다이프면 80g\n- 피스타치오 스프레드 100g\nsteps:\n- 카다이프면을 볶습니다.\n- 마시멜로우로 감쌉니다.",
                null, 1.0);
        List<RecipeResearchPlan> plans = new java.util.ArrayList<>();
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> {
                    plans.add(plan);
                    return List.of(otherSource);
                },
                previousDujjonkuSession());
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("다른 두쫀쿠 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 20L, request).block();

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).dishName()).isEqualTo("두쫀쿠");
        assertThat(plans.get(0).excludedSourceUrls()).containsExactly("https://blog.naver.com/a/1");
        assertThat(response.getReply()).contains("이전과 다른 출처의 레시피를 찾았어요").contains("dujjonku-b");
        // 카드에는 안내 문구를 "건강 주의"가 아니라 안내로, 참고한 출처를 함께 담습니다(내부 주소는 링크로 내보내지 않음).
        assertThat(response.getRecipe().getNotices()).containsExactly("이전과 다른 출처의 레시피를 찾았어요.");
        assertThat(response.getRecipe().getSafetyNotes()).doesNotContain("이전과 다른 출처의 레시피를 찾았어요.");
        assertThat(response.getRecipe().getSources()).singleElement().satisfies(source -> {
            assertThat(source.getTitle()).isEqualTo("두쫀쿠 B");
            assertThat(source.getUrl()).isNull();
            assertThat(source.isReviewed()).isTrue();
        });
        assertThat(CompositeRecipeSourceDiscoveryAdapter.isExcludedSource(
                "https://m.blog.naver.com/a/1/", List.of("https://blog.naver.com/a/1"))).isTrue();
    }

    // 다른 출처가 없으면 지어내지 않고 이전 레시피를 다시 보여 주며 그렇게 안내합니다.
    @Test
    void anotherRecipeRequestFallsBackToPreviousRecipeWhenNoOtherSource() {
        RecipeAgentOrchestrator orchestrator = orchestrator(
                UserRecipeContext::empty,
                (plan, context) -> List.of(),
                previousDujjonkuSession());
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("다른 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 20L, request).block();

        assertThat(response.getReply()).contains("다른 출처의 레시피를 찾지 못해 이전 레시피를 다시 보여 드려요");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getTitle()).isEqualTo("두바이 쫀득 쿠키");
    }

    private RecipeAgentOrchestrator orchestrator(
            UserRecipeContextLoader loader,
            RecipeSourceDiscoveryPort sourcePort,
            RecipeWorkSessionService workSessionService) {
        return new RecipeAgentOrchestrator(
                loader,
                new DefaultRecipeRequestPlanner(new RecipeNormalizer()),
                sourcePort,
                new RecipeEvidenceExtractor(),
                new RecipeCandidateBuilder(),
                engine(unknownMedication()),
                new RecipeModificationService(),
                new RecipeValidationPipeline(),
                new RecipeResponseComposer(),
                workSessionService,
                new ObjectMapper());
    }

    private RecipeCandidate tunaKimbap() {
        return new RecipeCandidate(
                "참치김밥",
                "참치와 채소를 넣은 김밥",
                List.of("밥 1공기", "김밥김 2장", "참치 1캔", "오이 1/2개", "계란 2개", "단무지 2줄", "깻잎 4장"),
                List.of("밥을 김 위에 펴고 참치와 오이, 계란, 단무지, 깻잎을 올립니다.", "단단히 말아 한입 크기로 썹니다."),
                450,
                1,
                20,
                List.of("밥", "김밥김", "참치"),
                List.of("깻잎", "오이", "계란", "단무지"),
                List.of());
    }

    private RecipeCandidate bananaBrulee() {
        return new RecipeCandidate(
                "바나나 브륄레",
                "설탕을 캐러멜화하는 디저트",
                List.of("바나나 1개", "설탕 2큰술", "버터 약간"),
                List.of("바나나 위에 설탕을 뿌립니다.", "토치로 설탕을 캐러멜화합니다."),
                240,
                2,
                8,
                List.of("바나나", "설탕"),
                List.of("버터"),
                List.of("HIGH_ADDED_SUGAR", "CARAMELIZED_SUGAR", "CORE_SUGAR"));
    }

    private RecipeCandidate lowSugarBananaSmoothie() {
        return new RecipeCandidate(
                "바나나 스무디",
                "추가 설탕 없는 음료",
                List.of("바나나 1/2개", "우유 150ml", "얼음 100g"),
                List.of("바나나, 우유, 얼음을 차갑게 갈아줍니다."),
                160,
                1,
                5,
                List.of("바나나", "우유", "얼음"),
                List.of(),
                List.of());
    }

    private RecipeCandidate jeyukWithSugar() {
        return new RecipeCandidate(
                "제육볶음",
                "양념에 단맛을 더한 볶음",
                List.of("돼지고기 200g", "고추장 1큰술", "설탕 1큰술", "양파 1/2개"),
                List.of("고추장과 설탕으로 양념을 만듭니다.", "돼지고기와 양파를 볶습니다."),
                520,
                2,
                20,
                List.of("돼지고기", "고추장"),
                List.of("설탕", "양파"),
                List.of());
    }

    private RecipeCandidate grapefruitDrink() {
        return new RecipeCandidate(
                "자몽 음료",
                "자몽을 사용한 음료",
                List.of("자몽 1개", "탄산수 150ml"),
                List.of("자몽즙과 탄산수를 섞습니다."),
                80,
                1,
                5,
                List.of("자몽"),
                List.of("탄산수"),
                List.of());
    }

    private Map<String, Object> agentSessionMap(RecipeCandidate personalizedRecipe) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("title", personalizedRecipe.title());
        recipe.put("description", personalizedRecipe.description());
        recipe.put("ingredients", personalizedRecipe.ingredients());
        recipe.put("steps", personalizedRecipe.steps());
        recipe.put("calories", personalizedRecipe.calories());
        recipe.put("difficulty", personalizedRecipe.difficulty());
        recipe.put("cookingTime", personalizedRecipe.cookingTime());
        recipe.put("coreIngredients", personalizedRecipe.coreIngredients());
        recipe.put("optionalIngredients", personalizedRecipe.optionalIngredients());
        recipe.put("healthRiskTags", personalizedRecipe.healthRiskTags());
        root.put("personalizedRecipe", recipe);
        return root;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
