package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.FridgeItem;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import com.salus.healthytable.repository.FridgeItemRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.service.RecipeNormalizer;
import com.salus.healthytable.service.RecipeWorkSessionService;
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

class RecipeAgentPersonalizationTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void naturalKoreanRemoveRequestProducesCanonicalExplicitExclusion() {
        DefaultRecipeRequestPlanner planner = new DefaultRecipeRequestPlanner(new RecipeNormalizer());

        assertThat(planner.extractExplicitExclusions("양파는 빼줘")).containsExactly("양파");
        assertThat(planner.extractExplicitExclusions("땅콩을 빼 주세요")).containsExactly("땅콩");
    }

    @Test
    void spacedDishNameIsNotMisclassifiedAsCreatorSpecificRequest() {
        DefaultRecipeRequestPlanner planner = new DefaultRecipeRequestPlanner(new RecipeNormalizer());

        RecipeResearchPlan dishPlan = planner.plan("바나나 브륄레 레시피 알려줘.", false, UserRecipeContext.empty(1L));
        RecipeResearchPlan creatorPlan = planner.plan("fixture-creator 참치김밥 레시피 알려줘.", false, UserRecipeContext.empty(1L));

        assertThat(dishPlan.dishName()).isEqualTo("바나나 브륄레");
        assertThat(dishPlan.creatorName()).isEmpty();
        assertThat(dishPlan.mode()).isEqualTo(RecipeRequestMode.CREATE);
        assertThat(creatorPlan.dishName()).isEqualTo("참치김밥");
        assertThat(creatorPlan.creatorName()).isEqualTo("fixture-creator");
        assertThat(creatorPlan.mode()).isEqualTo(RecipeRequestMode.CREATOR_SPECIFIC);
    }

    @Test
    void loggedInContextAlwaysLoadsHealthProfileAndFridge() {
        HealthProfileRepository healthProfiles = mock(HealthProfileRepository.class);
        FridgeItemRepository fridgeItems = mock(FridgeItemRepository.class);
        HealthProfile profile = new HealthProfile();
        profile.setUserId(1L);
        profile.setAllergies(List.of("깻잎"));
        profile.setChronicConditions(List.of("당뇨"));
        profile.setMedications(List.of("와파린"));
        profile.setGoals(List.of("당류 줄이기"));
        FridgeItem cucumber = fridge("오이", "1개", LocalDate.of(2026, 7, 18));

        when(healthProfiles.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(fridgeItems.findByUserIdOrderByExpiryDate(1L)).thenReturn(List.of(cucumber));

        RepositoryUserRecipeContextLoader loader = new RepositoryUserRecipeContextLoader(healthProfiles, fridgeItems);
        UserRecipeContext context = loader.load(1L);

        assertThat(context.allergies()).containsExactly("깻잎");
        assertThat(context.chronicConditions()).containsExactly("당뇨");
        assertThat(context.medications()).containsExactly("와파린");
        assertThat(context.healthGoals()).containsExactly("당류 줄이기");
        assertThat(context.fridgeIngredients()).extracting(FridgeIngredientContext::name).containsExactly("오이");
        verify(healthProfiles).findByUserId(1L);
        verify(fridgeItems).findByUserIdOrderByExpiryDate(1L);
    }

    @Test
    void guestContextIsEmptyAndDoesNotQueryRepositories() {
        HealthProfileRepository healthProfiles = mock(HealthProfileRepository.class);
        FridgeItemRepository fridgeItems = mock(FridgeItemRepository.class);

        RepositoryUserRecipeContextLoader loader = new RepositoryUserRecipeContextLoader(healthProfiles, fridgeItems);
        UserRecipeContext context = loader.load(null);

        assertThat(context.userId()).isNull();
        assertThat(context.allergies()).isEmpty();
        assertThat(context.fridgeIngredients()).isEmpty();
        verify(healthProfiles, never()).findByUserId(any());
        verify(fridgeItems, never()).findByUserIdOrderByExpiryDate(any());
    }

    @Test
    void contextLoaderDistinguishesNotRegisteredPartialAndFailed() {
        HealthProfileRepository healthProfiles = mock(HealthProfileRepository.class);
        FridgeItemRepository fridgeItems = mock(FridgeItemRepository.class);
        RepositoryUserRecipeContextLoader loader = new RepositoryUserRecipeContextLoader(healthProfiles, fridgeItems);

        when(healthProfiles.findByUserId(1L)).thenReturn(Optional.empty());
        when(fridgeItems.findByUserIdOrderByExpiryDate(1L)).thenReturn(List.of());
        assertThat(loader.loadWithStatus(1L).status()).isEqualTo(UserRecipeContextLoadStatus.NOT_REGISTERED);

        when(healthProfiles.findByUserId(2L)).thenThrow(new RuntimeException("profile unavailable"));
        when(fridgeItems.findByUserIdOrderByExpiryDate(2L))
                .thenReturn(List.of(fridge("두부", "1모", LocalDate.of(2026, 7, 18))));
        assertThat(loader.loadWithStatus(2L).status()).isEqualTo(UserRecipeContextLoadStatus.PARTIALLY_LOADED);

        when(healthProfiles.findByUserId(3L)).thenThrow(new RuntimeException("profile unavailable"));
        when(fridgeItems.findByUserIdOrderByExpiryDate(3L)).thenThrow(new RuntimeException("fridge unavailable"));
        assertThat(loader.loadWithStatus(3L).status()).isEqualTo(UserRecipeContextLoadStatus.LOAD_FAILED);
    }

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

    @Test
    void diabetesBananaBruleeRecommendsAlternativeButDoesNotBlockEveryBananaRecipe() {
        UserRecipeContext diabetes = new UserRecipeContext(
                1L,
                List.of(),
                List.of("당뇨"),
                List.of(),
                List.of(),
                List.of("당류 줄이기"),
                List.of(),
                List.of());

        RecipePersonalizationDecision brulee = engine(unknownMedication()).evaluate(bananaBrulee(), diabetes);
        RecipePersonalizationDecision smoothie = engine(unknownMedication()).evaluate(lowSugarBananaSmoothie(), diabetes);

        assertThat(brulee.decisionType()).isEqualTo(RecipeDecisionType.RECOMMEND_ALTERNATIVE);
        assertThat(smoothie.decisionType()).isEqualTo(RecipeDecisionType.ALLOW_WITH_NOTICE);
        assertThat(smoothie.conflicts()).noneMatch(conflict -> conflict.severity() == ConflictSeverity.BLOCKING);
    }

    @Test
    void lowSugarModificationPossibleRecipeUsesModifyDecision() {
        UserRecipeContext diabetesWithAllulose = new UserRecipeContext(
                1L,
                List.of(),
                List.of("당뇨"),
                List.of(),
                List.of(),
                List.of("당류 줄이기"),
                List.of(new FridgeIngredientContext("알룰로스", 1.0, "병", LocalDate.of(2026, 8, 1))),
                List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(jeyukWithSugar(), diabetesWithAllulose);
        RecipeCandidate personalized = new RecipeModificationService().apply(jeyukWithSugar(), decision);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.MODIFY);
        assertThat(decision.modifications()).anyMatch(modification -> "SUBSTITUTE_OR_REDUCE".equals(modification.action()));
        assertThat(String.join(" ", personalized.ingredients())).contains("알룰로스").doesNotContain("설탕 1큰술");
    }

    @Test
    void automaticallyInferredCoreListDoesNotMakeEveryAddedSugarRecipeAnAlternativeOnlyDish() {
        UserRecipeContext diabetesWithAllulose = new UserRecipeContext(
                1L, List.of(), List.of("당뇨"), List.of(), List.of(), List.of("당류 줄이기"),
                List.of(new FridgeIngredientContext("알룰로스", 1.0, "병", LocalDate.of(2026, 8, 1))), List.of());
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

    @Test
    void unknownMedicationInteractionCreatesNoticeButNoFakeConflict() {
        UserRecipeContext context = new UserRecipeContext(1L, List.of(), List.of(), List.of(), List.of("혈압약"), List.of(), List.of(), List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(tunaKimbap(), context);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.ALLOW_WITH_NOTICE);
        assertThat(decision.conflicts()).noneMatch(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION);
        assertThat(decision.userNotices())
                .contains("확인된 공식 음식 상호작용 근거를 찾지 못했습니다.")
                .contains("상호작용이 없다는 의미는 아닙니다.")
                .contains("약 복용 방식은 의사 또는 약사에게 확인하세요.");
    }

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
        UserRecipeContext context = new UserRecipeContext(1L, List.of(), List.of(), List.of(), List.of("상호작용 확인 약"), List.of(), List.of(), List.of());

        RecipePersonalizationDecision decision = engine(conflictPort).evaluate(grapefruitDrink(), context);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.BLOCK);
        assertThat(decision.conflicts()).anyMatch(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION
                && conflict.severity() == ConflictSeverity.BLOCKING);
    }

    @Test
    void fridgePolicyUsesMatchingIngredientsAndAddsMissingCorePurchasesOnly() {
        UserRecipeContext context = new UserRecipeContext(
                1L,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new FridgeIngredientContext("오이", 1.0, "개", LocalDate.of(2026, 7, 17)),
                        new FridgeIngredientContext("계란", 2.0, "개", LocalDate.of(2026, 7, 20)),
                        new FridgeIngredientContext("초콜릿", 1.0, "개", LocalDate.of(2026, 7, 20))),
                List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(tunaKimbap(), context);

        assertThat(decision.fridgeItemsUsed()).contains("오이", "계란").doesNotContain("초콜릿");
        assertThat(decision.additionalPurchaseItems()).contains("참치", "김밥김", "밥");
        assertThat(decision.userNotices()).anyMatch(notice -> notice.contains("유통기한"));
    }

    @Test
    void allergyPolicyHasPriorityOverFridgeAdaptation() {
        UserRecipeContext context = new UserRecipeContext(
                1L,
                List.of("깻잎"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new FridgeIngredientContext("깻잎", 5.0, "장", LocalDate.of(2026, 7, 17))),
                List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(tunaKimbap(), context);
        RecipeCandidate personalized = new RecipeModificationService().apply(tunaKimbap(), decision);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.MODIFY);
        assertThat(decision.fridgeItemsUsed()).doesNotContain("깻잎");
        assertThat(String.join(" ", personalized.ingredients())).doesNotContain("깻잎");
    }

    @Test
    void healthGoalWithoutRegisteredConditionStillAppliesLowSodiumPolicy() {
        UserRecipeContext context = new UserRecipeContext(
                1L, List.of(), List.of(), List.of(), List.of(), List.of("저염"), List.of(), List.of());
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

    @Test
    void blockingMedicationIngredientIsNotReportedAsFridgeItemUsed() {
        MedicationFoodInteractionPort conflictPort = (medications, ingredients) -> new MedicationInteractionResult(
                InteractionStatus.CONFIRMED_CONFLICT,
                List.of(new RecipeConflict(
                        RecipeConflictType.MEDICATION_INTERACTION,
                        "자몽",
                        "fixture medication",
                        "공식 복약정보에서 자몽 회피 조건이 확인되었습니다.",
                        ConflictSeverity.BLOCKING,
                        "fixture:official-medication-evidence")),
                List.of("자몽을 포함한 레시피 제공을 제한합니다."));
        UserRecipeContext context = new UserRecipeContext(
                1L,
                List.of(),
                List.of(),
                List.of(),
                List.of("fixture medication"),
                List.of(),
                List.of(new FridgeIngredientContext("자몽", 1.0, "개", LocalDate.of(2026, 7, 20))),
                List.of());

        RecipePersonalizationDecision decision = engine(conflictPort).evaluate(grapefruitDrink(), context);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.BLOCK);
        assertThat(decision.fridgeItemsUsed()).doesNotContain("자몽");
    }

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

    @Test
    void dietaryRestrictionBlocksOriginalWhenNoVerifiedAlternativeExists() {
        UserRecipeContext vegan = new UserRecipeContext(
                1L, List.of(), List.of(), List.of("비건"), List.of(), List.of(), List.of(), List.of());

        RecipePersonalizationDecision decision = engine(unknownMedication()).evaluate(tunaKimbap(), vegan);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.BLOCK);
        assertThat(decision.conflicts()).anyMatch(conflict -> conflict.type() == RecipeConflictType.DIETARY_RESTRICTION);
        assertThat(decision.modifications()).anyMatch(modification -> "동물성 재료".equals(modification.ingredient()));
    }

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
        assertThat(response.getReply()).contains("[개인화 판단]");
        assertThat(response.getReply()).contains("제공 제한");
    }

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
    void successfulCurrentContextLoadReplacesDeletedProfileAndFridgeValuesFromSnapshot() {
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
        assertThat(saved.contextSnapshot().fridgeIngredients()).isEmpty();
        assertThat(saved.contextSnapshot().explicitlyExcludedIngredients()).containsExactly("양파");
    }

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
        assertThat(response.getReply()).contains("사용자 건강정보와 냉장고 정보를 불러오지 못해");
    }

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
        assertThat((List<?>) context.get("fridgeIngredients")).isEmpty();
        assertThat((List<?>) context.get("explicitlyExcludedIngredients")).isEmpty();
    }

    @Test
    void recipeAgentAndModelSettingsKeepSafeDefaultsAndEnvironmentOverrides() throws Exception {
        String properties = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.properties"));

        assertThat(properties).contains(
                "ollama.chat-model=${OLLAMA_CHAT_MODEL:${OLLAMA_MODEL:gemma2}}",
                "ollama.recipe-model=${OLLAMA_RECIPE_MODEL:${OLLAMA_MODEL:gemma2}}",
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


    private RecipePersonalizationPolicyEngine engine(MedicationFoodInteractionPort interactionPort) {
        return new RecipePersonalizationPolicyEngine(List.of(
                new AllergyPolicy(),
                new MedicationInteractionPolicy(interactionPort),
                new ChronicConditionPolicy(),
                new DietaryRestrictionPolicy(),
                new ExplicitExclusionPolicy(),
                new FridgeAdaptationPolicy(clock)));
    }

    private MedicationFoodInteractionPort unknownMedication() {
        return new UnknownMedicationFoodInteractionAdapter();
    }

    private UserRecipeContext contextWithAllergy(String allergy) {
        return new UserRecipeContext(1L, List.of(allergy), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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

    private FridgeItem fridge(String name, String quantity, LocalDate expirationDate) {
        FridgeItem item = new FridgeItem();
        item.setName(name);
        item.setQuantity(quantity);
        item.setExpiryDate(expirationDate);
        return item;
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
