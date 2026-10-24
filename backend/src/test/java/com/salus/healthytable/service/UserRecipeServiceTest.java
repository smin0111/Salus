package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.ChatRecipeCard;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.domain.UserRecipe;
import com.salus.healthytable.domain.UserRecipeOrigin;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.MyRecipeDto;
import com.salus.healthytable.repository.ChatRecipeCardRepository;
import com.salus.healthytable.repository.UserRecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserRecipeService}·{@link UserRecipeTxHelper} 단위 테스트입니다. 실제 트랜잭션·유니크 제약은 UserRecipeMySqlIT에서 확인합니다.
 */
class UserRecipeServiceTest {

    // 고정 시각 2026-10-24 10:00:00.123(서울). 저장 시각은 초 아래를 버립니다.
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 10, 0);

    private final ChatRecipeCardRepository cardRepository = mock(ChatRecipeCardRepository.class);
    private final UserRecipeRepository recipeRepository = mock(UserRecipeRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T01:00:00.123Z"), ZoneId.of("Asia/Seoul"));
    private final UserRecipeService service = new UserRecipeService(
            new UserRecipeTxHelper(cardRepository, recipeRepository), recipeRepository, objectMapper, clock);

    @BeforeEach
    void saveReturnsEntityWithId() {
        when(recipeRepository.saveAndFlush(any(UserRecipe.class))).thenAnswer(invocation -> {
            UserRecipe recipe = invocation.getArgument(0);
            if (recipe.getId() == null) {
                ReflectionTestUtils.setField(recipe, "id", 50L);
                ReflectionTestUtils.setField(recipe, "version", 0);
            }
            return recipe;
        });
    }

    // 카드 저장: 서버가 보관한 카드를 복사합니다. 원본은 카드 JSON 그대로, 출처는 카드 출처 그대로(RETRIEVED는 응답에서 빠짐).
    @Test
    void saveFromCardCopiesServerCardAsOriginalAndCurrent() throws Exception {
        ChatRecipeCard card = card(7L, "card-1", 1L);
        when(cardRepository.findByPublicIdAndUserId("card-1", 1L)).thenReturn(Optional.of(card));
        when(recipeRepository.findByUserIdAndSourceCardId(1L, 7L)).thenReturn(Optional.empty());

        UserRecipeService.SaveResult result = service.saveFromCard(1L, new MyRecipeDto.FromCardRequest(" card-1 "));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<UserRecipe> saved = ArgumentCaptor.forClass(UserRecipe.class);
        verify(recipeRepository).saveAndFlush(saved.capture());
        UserRecipe recipe = saved.getValue();
        assertThat(recipe.getOriginType()).isEqualTo(UserRecipeOrigin.CHAT_CARD);
        assertThat(recipe.getSourceCardId()).isEqualTo(7L);
        assertThat(recipe.getProvenance()).isEqualTo(RecipeCardProvenance.WEB_EXTRACTED);
        assertThat(recipe.getOriginalSnapshot()).isEqualTo(card.getCardJson());
        assertThat(recipe.getSourcesJson()).isEqualTo(card.getSourcesJson());
        assertThat(recipe.getCreatedAt()).isEqualTo(NOW);

        MyRecipeDto.Detail detail = result.detail();
        assertThat(detail.current().title()).isEqualTo("김치찌개");
        assertThat(detail.current().ingredients()).containsExactly("김치 300g", "돼지고기 150g");
        assertThat(detail.current().caloriesPerServing()).isEqualTo(360);
        assertThat(detail.original()).isEqualTo(detail.current());
        assertThat(detail.sources()).extracting(MyRecipeDto.Source::role).containsExactly("PRIMARY");
        assertThat(detail.version()).isZero();
    }

    // 남의 카드나 없는 카드는 404이고, 빈 cardId는 400입니다.
    @Test
    void saveFromCardRejectsOthersCardAndBlankId() {
        when(cardRepository.findByPublicIdAndUserId("card-of-2", 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveFromCard(1L, new MyRecipeDto.FromCardRequest("card-of-2")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.saveFromCard(1L, new MyRecipeDto.FromCardRequest(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recipeRepository, never()).saveAndFlush(any());
    }

    // 이미 저장한 카드는 새로 만들지 않고 기존 레시피를 돌려줍니다(200).
    @Test
    void savingSameCardAgainReturnsExisting() throws Exception {
        ChatRecipeCard card = card(7L, "card-1", 1L);
        UserRecipe existing = savedFromCard(card);
        when(cardRepository.findByPublicIdAndUserId("card-1", 1L)).thenReturn(Optional.of(card));
        when(recipeRepository.findByUserIdAndSourceCardId(1L, 7L)).thenReturn(Optional.of(existing));

        UserRecipeService.SaveResult result = service.saveFromCard(1L, new MyRecipeDto.FromCardRequest("card-1"));

        assertThat(result.created()).isFalse();
        assertThat(result.detail().id()).isEqualTo(existing.getId());
        verify(recipeRepository, never()).saveAndFlush(any());
    }

    // 동시에 두 번 눌러 유니크 위반이 나면, 새 조회로 기존 레시피를 돌려줍니다. 찾지 못하면 원래 예외를 올려 보냅니다.
    @Test
    void uniqueRaceFallsBackToExistingOrRethrows() throws Exception {
        ChatRecipeCard card = card(7L, "card-1", 1L);
        UserRecipe existing = savedFromCard(card);
        DataIntegrityViolationException race = new DataIntegrityViolationException("uk_user_recipes_user_card");
        when(cardRepository.findByPublicIdAndUserId("card-1", 1L)).thenReturn(Optional.of(card));
        when(recipeRepository.findByUserIdAndSourceCardId(1L, 7L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(recipeRepository.saveAndFlush(any(UserRecipe.class))).thenThrow(race);

        UserRecipeService.SaveResult result = service.saveFromCard(1L, new MyRecipeDto.FromCardRequest("card-1"));
        assertThat(result.created()).isFalse();
        assertThat(result.detail().id()).isEqualTo(existing.getId());

        assertThatThrownBy(() -> service.saveFromCard(1L, new MyRecipeDto.FromCardRequest("card-1"))).isSameAs(race);
    }

    // 서버 카드는 자르지 않고 그대로 저장·응답합니다(재료 줄 끝의 분량이나 알레르기 성분 이름이 잘려 사라지면 안 됨).
    @Test
    void cardContentIsSavedWithoutTruncation() throws Exception {
        String longIngredient = "땅콩버터 듬뿍(" + "고소한 ".repeat(30) + ") 1큰술";
        List<String> steps = IntStream.rangeClosed(1, 31).mapToObj(i -> i + "단계").toList();
        ChatDto.RecipeCard big = cardContent();
        big.setTitle("가".repeat(80));
        big.setIngredients(List.of(longIngredient, "김치 300g"));
        big.setSteps(steps);
        big.setServings(99);
        ChatRecipeCard card = card(7L, "card-1", 1L);
        card.setCardJson(objectMapper.writeValueAsString(big));
        when(cardRepository.findByPublicIdAndUserId("card-1", 1L)).thenReturn(Optional.of(card));
        when(recipeRepository.findByUserIdAndSourceCardId(1L, 7L)).thenReturn(Optional.empty());

        MyRecipeDto.Detail detail = service.saveFromCard(1L, new MyRecipeDto.FromCardRequest("card-1")).detail();

        for (MyRecipeDto.Content content : List.of(detail.current(), detail.original())) {
            assertThat(content.title()).hasSize(80);
            assertThat(content.ingredients()).containsExactly(longIngredient, "김치 300g");
            assertThat(content.steps()).containsExactlyElementsOf(steps);
            assertThat(content.servings()).isEqualTo(99);
        }
    }

    // 수정 제한은 원본보다 엄격하지 않습니다: 31단계 카드는 31단계까지, 원본의 범위 밖 값(99인분)은 그대로 두면 통과합니다.
    // 원본을 넘어서는 입력(32단계, 98인분)은 400입니다.
    @Test
    void editLimitsAreNeverStricterThanOriginal() throws Exception {
        List<String> steps = IntStream.rangeClosed(1, 31).mapToObj(i -> i + "단계").toList();
        ChatDto.RecipeCard big = cardContent();
        big.setSteps(steps);
        big.setServings(99);
        ChatRecipeCard card = card(7L, "card-1", 1L);
        card.setCardJson(objectMapper.writeValueAsString(big));
        UserRecipe recipe = UserRecipe.fromChatCard(1L, 7L, card.getProvenance(), card.getCardJson(), card.getSourcesJson(),
                "김치찌개", objectMapper.writeValueAsString(new MyRecipeDto.Content("김치찌개", null, List.of("김치 300g"),
                        steps, 99, null, null, null)), NOW);
        ReflectionTestUtils.setField(recipe, "id", 50L);
        ReflectionTestUtils.setField(recipe, "version", 0);
        when(recipeRepository.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(recipe));

        MyRecipeDto.Detail kept = service.update(1L, 50L, new MyRecipeDto.UpdateRequest("김치찌개", null,
                List.of("김치 300g", "간장 1큰술"), steps, 99, null, null, null, "간을 더했어요", 0));
        assertThat(kept.current().steps()).hasSize(31);

        List<String> tooMany = IntStream.rangeClosed(1, 32).mapToObj(i -> i + "단계").toList();
        assertThatThrownBy(() -> service.update(1L, 50L, new MyRecipeDto.UpdateRequest("김치찌개", null,
                List.of("김치 300g"), tooMany, 99, null, null, null, null, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1L, 50L, new MyRecipeDto.UpdateRequest("김치찌개", null,
                List.of("김치 300g"), steps, 98, null, null, null, null, 1))).isInstanceOf(IllegalArgumentException.class);
    }

    // 직접 작성: 처음 내용이 원본이 되고, 범위를 벗어난 입력은 400입니다.
    @Test
    void createManualUsesFirstContentAsOriginalAndValidates() {
        MyRecipeDto.Detail detail = service.create(1L, new MyRecipeDto.CreateRequest(
                " 할머니 된장찌개 ", null, List.of("된장 2큰술", " ", "두부 반 모"), List.of("끓인다."),
                2, 20, 2, 300, "우리 집 비법"));

        assertThat(detail.originType()).isEqualTo(UserRecipeOrigin.MANUAL);
        assertThat(detail.current().title()).isEqualTo("할머니 된장찌개");
        assertThat(detail.current().ingredients()).containsExactly("된장 2큰술", "두부 반 모");
        assertThat(detail.original()).isEqualTo(detail.current());
        assertThat(detail.sources()).isEmpty();
        assertThat(detail.modificationNote()).isEqualTo("우리 집 비법");

        List<MyRecipeDto.CreateRequest> invalid = List.of(
                new MyRecipeDto.CreateRequest(" ", null, List.of("a"), List.of("b"), null, null, null, null, null),
                new MyRecipeDto.CreateRequest("a".repeat(61), null, List.of("a"), List.of("b"), null, null, null, null, null),
                new MyRecipeDto.CreateRequest("찌개", null, List.of(), List.of("b"), null, null, null, null, null),
                new MyRecipeDto.CreateRequest("찌개", null, IntStream.range(0, 51).mapToObj(i -> "a").toList(), List.of("b"), null, null, null, null, null),
                new MyRecipeDto.CreateRequest("찌개", null, List.of("a"), List.of(), null, null, null, null, null),
                new MyRecipeDto.CreateRequest("찌개", null, List.of("a"), List.of("b"), 21, null, null, null, null),
                new MyRecipeDto.CreateRequest("찌개", null, List.of("a"), List.of("b"), null, null, 6, null, null),
                new MyRecipeDto.CreateRequest("찌개", null, List.of("a"), List.of("b"), null, null, null, null, "n".repeat(501)));
        for (MyRecipeDto.CreateRequest request : invalid) {
            assertThatThrownBy(() -> service.create(1L, request)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // 수정: 버전이 맞으면 지금 버전·메모만 바뀌고 원본·출처·만든 방식은 그대로입니다.
    @Test
    void updateChangesOnlyCurrentAndNoteWhenVersionMatches() throws Exception {
        UserRecipe recipe = savedFromCard(card(7L, "card-1", 1L));
        String originalBefore = recipe.getOriginalSnapshot();
        String sourcesBefore = recipe.getSourcesJson();
        when(recipeRepository.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(recipe));

        MyRecipeDto.Detail detail = service.update(1L, 50L, new MyRecipeDto.UpdateRequest(
                "김치찌개(간 세게)", null, List.of("김치 300g", "돼지고기 150g", "간장 1큰술"), List.of("김치를 볶는다."),
                2, 30, 2, 360, "싱거워서 간장을 더했어요", 0));

        assertThat(detail.current().ingredients()).contains("간장 1큰술");
        assertThat(detail.modificationNote()).isEqualTo("싱거워서 간장을 더했어요");
        assertThat(detail.original().ingredients()).doesNotContain("간장 1큰술");
        assertThat(recipe.getOriginalSnapshot()).isEqualTo(originalBefore);
        assertThat(recipe.getSourcesJson()).isEqualTo(sourcesBefore);
        assertThat(recipe.getOriginType()).isEqualTo(UserRecipeOrigin.CHAT_CARD);
        assertThat(recipe.getTitle()).isEqualTo("김치찌개(간 세게)");
    }

    // 수정: 오래된 버전은 바로 409(저장하지 않음), 저장 중 @Version 충돌도 409, 버전이 없으면 400입니다.
    @Test
    void updateRejectsStaleVersionConcurrentEditAndMissingVersion() throws Exception {
        UserRecipe recipe = savedFromCard(card(7L, "card-1", 1L));
        ReflectionTestUtils.setField(recipe, "version", 4);
        when(recipeRepository.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(recipe));

        assertThatThrownBy(() -> service.update(1L, 50L, updateWithVersion(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(recipeRepository, never()).saveAndFlush(any());

        when(recipeRepository.saveAndFlush(any(UserRecipe.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserRecipe.class, 50L));
        assertThatThrownBy(() -> service.update(1L, 50L, updateWithVersion(4)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.update(1L, 50L, updateWithVersion(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 남의 레시피는 조회·수정·삭제 모두 404입니다.
    @Test
    void otherUsersRecipeIsNotFound() {
        when(recipeRepository.findByIdAndUserId(50L, 2L)).thenReturn(Optional.empty());

        for (Runnable call : List.<Runnable>of(
                () -> service.get(2L, 50L),
                () -> service.update(2L, 50L, updateWithVersion(0)),
                () -> service.delete(2L, 50L))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verify(recipeRepository, never()).delete(any());
    }

    // 목록: 한 개 더 읽어 다음 페이지가 있으면 커서를 주고, 커서로 다음 페이지를 읽습니다. 잘못된 커서는 400입니다.
    @Test
    void listUsesCursorPagination() throws Exception {
        UserRecipe a = manual(3L, NOW.plusMinutes(3));
        UserRecipe b = manual(2L, NOW.plusMinutes(2));
        UserRecipe c = manual(1L, NOW.plusMinutes(1));
        when(recipeRepository.findByUserIdOrderByUpdatedAtDescIdDesc(eq(1L), any(Pageable.class))).thenReturn(List.of(a, b, c));

        MyRecipeDto.Page first = service.list(1L, null, 2);
        assertThat(first.items()).extracting(MyRecipeDto.Summary::id).containsExactly(3L, 2L);
        assertThat(first.nextCursor()).isNotBlank();

        when(recipeRepository.findPageAfter(eq(1L), eq(NOW.plusMinutes(2)), eq(2L), any(Pageable.class))).thenReturn(List.of(c));
        MyRecipeDto.Page second = service.list(1L, first.nextCursor(), 2);
        assertThat(second.items()).extracting(MyRecipeDto.Summary::id).containsExactly(1L);
        assertThat(second.nextCursor()).isNull();

        assertThatThrownBy(() -> service.list(1L, "not-a-cursor!", 2)).isInstanceOf(IllegalArgumentException.class);
    }

    private static MyRecipeDto.UpdateRequest updateWithVersion(Integer version) {
        return new MyRecipeDto.UpdateRequest("김치찌개", null, List.of("김치 300g"), List.of("끓인다."),
                null, null, null, null, null, version);
    }

    private UserRecipe savedFromCard(ChatRecipeCard card) throws Exception {
        MyRecipeDto.Content content = new MyRecipeDto.Content("김치찌개", "얼큰한 찌개", List.of("김치 300g", "돼지고기 150g"),
                List.of("김치를 볶는다."), 2, 30, 2, 360);
        UserRecipe recipe = UserRecipe.fromChatCard(1L, card.getId(), card.getProvenance(), card.getCardJson(),
                card.getSourcesJson(), "김치찌개", objectMapper.writeValueAsString(content), NOW);
        ReflectionTestUtils.setField(recipe, "id", 50L);
        ReflectionTestUtils.setField(recipe, "version", 0);
        return recipe;
    }

    private UserRecipe manual(long id, LocalDateTime updatedAt) throws Exception {
        MyRecipeDto.Content content = new MyRecipeDto.Content("레시피 " + id, null, List.of("a"), List.of("b"),
                null, null, null, null);
        UserRecipe recipe = UserRecipe.manual(1L, "레시피 " + id, objectMapper.writeValueAsString(content), null, updatedAt);
        ReflectionTestUtils.setField(recipe, "id", id);
        ReflectionTestUtils.setField(recipe, "version", 0);
        return recipe;
    }

    private ChatRecipeCard card(Long id, String publicId, Long userId) throws Exception {
        ChatRecipeCard card = new ChatRecipeCard();
        card.setId(id);
        card.setPublicId(publicId);
        card.setUserId(userId);
        card.setProvenance(RecipeCardProvenance.WEB_EXTRACTED);
        card.setCardJson(objectMapper.writeValueAsString(cardContent()));
        card.setSourcesJson("""
                [{"role":"PRIMARY","sourceType":"WEB","url":"https://blog.example.com/p","title":"원본 블로그"},
                 {"role":"RETRIEVED","sourceType":"WEB","url":"https://search.example.com/r","title":"검색 결과","rank":1}]
                """);
        return card;
    }

    private static ChatDto.RecipeCard cardContent() {
        ChatDto.RecipeCard card = new ChatDto.RecipeCard(null, "김치찌개", "얼큰한 찌개", List.of("김치 300g", "돼지고기 150g"),
                List.of("김치를 볶는다."), 2, 360, 360, 2, 30, null, List.of("돼지고기 알레르기 확인"));
        card.setSources(List.of(new ChatDto.SourceLink("원본 블로그", "https://blog.example.com/p", false)));
        return card;
    }
}
