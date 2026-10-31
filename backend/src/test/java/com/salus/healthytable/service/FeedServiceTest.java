package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.salus.healthytable.domain.FeedLike;
import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserRecipeOrigin;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.dto.MyRecipeDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.FeedLikeRepository;
import com.salus.healthytable.repository.FeedPostRepository;
import com.salus.healthytable.repository.UserBlockRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FeedService} 단위 테스트입니다. 알레르기 판정은 실제 사전·판정기(AllergenDictionary·AllergenMatcher)를 쓰고,
 * 보는 사람의 건강정보 로딩(ChatSafetyContextService)만 Mock으로 바꿉니다. 실제 DB 제약·삭제 규칙은 FeedMySqlIT에서 확인합니다.
 */
class FeedServiceTest {

    private static final String REQUEST_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final AllergenDictionary DICTIONARY = loadedDictionary();

    private final FeedPostRepository feedPostRepository = mock(FeedPostRepository.class);
    private final FeedLikeRepository feedLikeRepository = mock(FeedLikeRepository.class);
    private final FeedCommentRepository feedCommentRepository = mock(FeedCommentRepository.class);
    private final UserBlockRepository userBlockRepository = mock(UserBlockRepository.class);
    private final FeedTxHelper txHelper = mock(FeedTxHelper.class);
    private final UserRecipeService userRecipeService = mock(UserRecipeService.class);
    private final NicknameService nicknameService = mock(NicknameService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ChatSafetyContextService safetyContextService = mock(ChatSafetyContextService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    private FeedService service;

    private static AllergenDictionary loadedDictionary() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return dictionary;
    }

    private FeedService service(boolean enabled) {
        return new FeedService(enabled, feedPostRepository, feedLikeRepository, feedCommentRepository, userBlockRepository, txHelper,
                userRecipeService, nicknameService,
                userRepository, safetyContextService, new AllergenMatcher(DICTIONARY), DICTIONARY, objectMapper, clock);
    }

    @BeforeEach
    void setUp() {
        service = service(true);
        when(txHelper.insert(any(FeedPost.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(txHelper.findByRequest(any(), any())).thenReturn(Optional.empty());
    }

    private static MyRecipeDto.Content content(String title, List<String> ingredients, List<String> steps) {
        return new MyRecipeDto.Content(title, "설명", ingredients, steps, 2, 20, 2, 300);
    }

    private static MyRecipeDto.Detail recipe(Long id, UserRecipeOrigin origin, RecipeCardProvenance provenance,
                                             MyRecipeDto.Content current, List<MyRecipeDto.Source> sources) {
        LocalDateTime at = LocalDateTime.of(2026, 10, 26, 10, 0);
        return new MyRecipeDto.Detail(id, origin, provenance, current, current, sources, "간장 반 큰술 추가", 1, at, at);
    }

    private FeedPost shareAndCapture(MyRecipeDto.Detail detail, String note) {
        when(userRecipeService.get(1L, detail.id())).thenReturn(detail);
        service.share(1L, new FeedDto.ShareRequest(detail.id(), note, REQUEST_ID));
        ArgumentCaptor<FeedPost> captor = ArgumentCaptor.forClass(FeedPost.class);
        verify(txHelper).insert(captor.capture());
        return captor.getValue();
    }

    private void viewerHas(boolean available, List<String> allergies) {
        when(safetyContextService.build(Optional.of(2L), null)).thenReturn(new ChatSafetyContextService.SafetyContext(
                allergies, List.of(), List.of(), List.of(), List.of(), available));
    }

    // 조리 단계 공개: 직접 작성·승인/DB 기반만 공개. 웹 추출·웹 검색 기반 AI·변형·출처 없는 카드는 비공개.
    @Test
    void stepsPublicFollowsProvenanceRules() {
        assertThat(FeedService.stepsPublic(UserRecipeOrigin.MANUAL, null)).isTrue();
        assertThat(FeedService.stepsPublic(UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.APPROVED_CATALOG)).isTrue();
        assertThat(FeedService.stepsPublic(UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.INTERNAL_DB_EXTRACTED)).isTrue();
        assertThat(FeedService.stepsPublic(UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.AI_FROM_INTERNAL_DB)).isTrue();
        assertThat(FeedService.stepsPublic(UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.WEB_EXTRACTED)).isFalse();
        assertThat(FeedService.stepsPublic(UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.AI_FROM_WEB_SEARCH)).isFalse();
        assertThat(FeedService.stepsPublic(UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.VARIANT)).isFalse();
        assertThat(FeedService.stepsPublic(UserRecipeOrigin.CHAT_CARD, null)).isFalse();
    }

    // 공유: 서버가 소유권을 확인한 내 레시피의 지금 버전을 복사하고, 닉네임을 확인하며, 요청 ID와 해시를 남깁니다.
    @Test
    void shareCopiesCurrentRecipeOfOwner() {
        MyRecipeDto.Detail detail = recipe(5L, UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.WEB_EXTRACTED,
                content("김치찌개", List.of("김치", "돼지고기"), List.of("끓인다")),
                List.of(new MyRecipeDto.Source("PRIMARY", "원본 블로그", "https://example.com/r")));

        FeedPost post = shareAndCapture(detail, "  간을 줄였어요  ");

        verify(nicknameService).requireNickname(1L);
        assertThat(post.getUserId()).isEqualTo(1L);
        assertThat(post.getSourceUserRecipeId()).isEqualTo(5L);
        assertThat(post.isStepsPublic()).isFalse();
        assertThat(post.getTitle()).isEqualTo("김치찌개");
        assertThat(post.getSnapshotJson()).contains("끓인다");
        assertThat(post.getSourcesJson()).contains("https://example.com/r");
        assertThat(post.getNote()).isEqualTo("간을 줄였어요");
        assertThat(post.getClientRequestId()).isEqualTo(REQUEST_ID);
        assertThat(post.getRequestHash()).isEqualTo(FeedService.requestHash(5L, "간을 줄였어요"));
    }

    // 남의 레시피(UserRecipeService가 404)나 닉네임 없음(428)이면 저장하지 않습니다.
    @Test
    void shareRejectsOthersRecipeAndMissingNickname() {
        when(userRecipeService.get(1L, 9L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "없음"));
        assertThatThrownBy(() -> service.share(1L, new FeedDto.ShareRequest(9L, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        doThrow(new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "닉네임")).when(nicknameService).requireNickname(3L);
        assertThatThrownBy(() -> service.share(3L, new FeedDto.ShareRequest(5L, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        verify(txHelper, never()).insert(any());
    }

    // 같은 요청 ID 재전송: 같은 내용이면 기존 공유본(created=false), 다른 메모면 409. 동시 공유의 UNIQUE 위반은 재조회.
    @Test
    void shareReplayIsIdempotent() {
        FeedPost existing = FeedPost.share(1L, 5L, UserRecipeOrigin.MANUAL, null, true, "제목", "{}", "[]", "메모",
                REQUEST_ID, FeedService.requestHash(5L, "메모"), LocalDateTime.now(clock));
        when(txHelper.findByRequest(1L, REQUEST_ID)).thenReturn(Optional.of(existing));

        assertThat(service.share(1L, new FeedDto.ShareRequest(5L, "메모", REQUEST_ID)).created()).isFalse();
        assertThatThrownBy(() -> service.share(1L, new FeedDto.ShareRequest(5L, "다른 메모", REQUEST_ID)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(txHelper, never()).insert(any());
        verifyNoInteractions(userRecipeService);

        when(txHelper.findByRequest(1L, REQUEST_ID)).thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        when(userRecipeService.get(1L, 5L)).thenReturn(recipe(5L, UserRecipeOrigin.MANUAL, null,
                content("제목", List.of("두부"), List.of("굽는다")), List.of()));
        when(txHelper.insert(any(FeedPost.class))).thenThrow(new DataIntegrityViolationException("uk_feed_posts_user_request"));
        assertThat(service.share(1L, new FeedDto.ShareRequest(5L, "메모", REQUEST_ID)).created()).isFalse();
    }

    // 입력 검증: 레시피 ID 없음·요청 ID 형식 오류·메모 500자 초과는 400(IllegalArgumentException).
    @Test
    void shareValidatesInput() {
        assertThatThrownBy(() -> service.share(1L, new FeedDto.ShareRequest(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.share(1L, new FeedDto.ShareRequest(5L, null, "not-a-uuid")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.share(1L, new FeedDto.ShareRequest(5L, "가".repeat(501), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 기능이 꺼져 있으면 상태 조회 외 모든 피드 동작이 404이고 아무것도 조회·저장하지 않습니다.
    @Test
    void disabledFeedHidesEverything() {
        FeedService off = service(false);

        assertThat(off.status().enabled()).isFalse();
        for (Runnable call : List.<Runnable>of(
                () -> off.share(1L, new FeedDto.ShareRequest(5L, null, null)),
                () -> off.list(1L, null, null),
                () -> off.get(1L, 7L),
                () -> off.delete(1L, 7L),
                () -> off.like(1L, 7L),
                () -> off.unlike(1L, 7L),
                () -> off.popular(1L))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verifyNoInteractions(feedPostRepository, feedLikeRepository, userRecipeService, nicknameService, safetyContextService);
        verify(txHelper, never()).insert(any());
    }

    private FeedPost stored(boolean stepsPublic, MyRecipeDto.Content content) throws Exception {
        FeedPost post = FeedPost.share(1L, 5L, UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.WEB_EXTRACTED,
                stepsPublic, content.title(), objectMapper.writeValueAsString(content),
                objectMapper.writeValueAsString(List.of(new MyRecipeDto.Source("PRIMARY", "원본", "https://example.com/r"))),
                "메모", null, null, LocalDateTime.now(clock));
        org.springframework.test.util.ReflectionTestUtils.setField(post, "id", 7L);
        when(feedPostRepository.findById(7L)).thenReturn(Optional.of(post));
        User author = new User();
        author.setId(1L);
        author.setName("작성자 실명");
        author.setNickname("김치러버");
        when(userRepository.findById(1L)).thenReturn(Optional.of(author));
        return post;
    }

    // 상세: 조리 단계 비공개 출처면 단계와 원본 설명을 내보내지 않고(저장된 공유본에는 있어도), 작성자는 실명 아닌 닉네임입니다.
    // 응답 JSON 전체에 비공개 문구가 없는지 확인합니다(설명에 조리 내용이 들어 있는 경우 포함).
    @Test
    void detailOmitsPrivateStepsAndDescriptionAndUsesNickname() throws Exception {
        stored(false, new MyRecipeDto.Content("김치찌개", "비밀 설명: 김치를 30분 끓인다", List.of("김치"),
                List.of("비밀 단계: 30분 끓인다"), 2, 30, 2, 450));
        viewerHas(true, List.of());

        FeedDto.Detail detail = service.get(2L, 7L);

        assertThat(detail.steps()).isNull();
        assertThat(detail.description()).isNull();
        assertThat(detail.stepsPublic()).isFalse();
        assertThat(detail.ingredients()).containsExactly("김치");
        assertThat(detail.sources()).extracting(FeedDto.Source::url).containsExactly("https://example.com/r");
        assertThat(detail.authorName()).isEqualTo("김치러버");
        assertThat(detail.mine()).isFalse();
        assertThat(objectMapper.writeValueAsString(detail))
                .doesNotContain("비밀 단계").doesNotContain("비밀 설명").doesNotContain("작성자 실명");
    }

    // 조리 단계를 공개하는 출처면 설명과 단계를 모두 보여 줍니다.
    @Test
    void detailShowsDescriptionAndStepsForOpenSources() throws Exception {
        stored(true, new MyRecipeDto.Content("두부조림", "직접 쓴 설명", List.of("두부"), List.of("굽는다"), 2, 20, 1, 300));
        viewerHas(true, List.of());

        FeedDto.Detail detail = service.get(2L, 7L);

        assertThat(detail.description()).isEqualTo("직접 쓴 설명");
        assertThat(detail.steps()).containsExactly("굽는다");
    }

    // 긴 제목: 목록용 제목은 200자(코드 포인트)로 자르고 이모지를 반으로 가르지 않으며, 상세는 공유본의 전체 제목입니다.
    @Test
    void longTitleIsClippedForListOnly() throws Exception {
        String longTitle = "가".repeat(199) + "😀" + "나".repeat(50);
        String clipped = FeedService.listTitle(longTitle);
        assertThat(clipped.codePointCount(0, clipped.length())).isEqualTo(200);
        assertThat(clipped).endsWith("😀");
        assertThat(FeedService.listTitle("  짧은 제목  ")).isEqualTo("짧은 제목");
        assertThat(FeedService.listTitle(" ")).isEqualTo("제목 없는 레시피");

        MyRecipeDto.Detail detail = recipe(5L, UserRecipeOrigin.MANUAL, null,
                content(longTitle, List.of("두부"), List.of("굽는다")), List.of());
        FeedPost post = shareAndCapture(detail, null);
        assertThat(post.getTitle()).isEqualTo(clipped);
        assertThat(post.getSnapshotJson()).contains(longTitle);
    }

    // 알레르기: 일치하면 CONTAINS(공개하지 않는 조리 단계에만 있어도 판정).
    @Test
    void allergenContainsEvenWhenOnlyInPrivateSteps() throws Exception {
        stored(false, content("볶음밥", List.of("밥", "대파"), List.of("땅콩을 넣고 볶는다")));
        viewerHas(true, List.of("땅콩"));

        FeedDto.AllergenStatus status = service.get(2L, 7L).allergen();

        assertThat(status.state()).isEqualTo("CONTAINS");
        assertThat(status.matched()).containsExactly("땅콩");
        assertThat(service.get(2L, 7L).steps()).isNull();
    }

    // 알레르기: 일치 없음은 NO_MATCH_FOUND이고 문구에 안전 보장이 아님을 밝힙니다. "밀"은 "밀크"와 일치하지 않습니다.
    @Test
    void allergenNoMatchFoundIsNotSafe() throws Exception {
        stored(true, content("라떼", List.of("밀크", "설탕"), List.of("섞는다")));
        viewerHas(true, List.of("밀"));

        FeedDto.AllergenStatus status = service.get(2L, 7L).allergen();

        assertThat(status.state()).isEqualTo("NO_MATCH_FOUND");
        assertThat(status.message()).contains("안전하다는 뜻은 아니니");
        assertThat(status.message()).doesNotContain("안전합니다");
    }

    // 건강정보 수집 동의가 없으면 건강정보를 읽지 않고 "확인 불가"로 둡니다(안전하다고 하지 않음).
    @Test
    void allergenUnknownWithoutConsentAndHealthProfileIsNotRead() throws Exception {
        stored(true, content("샐러드", List.of("양상추"), List.of("섞는다")));
        ConsentService consentService = org.mockito.Mockito.mock(ConsentService.class);
        when(consentService.hasConsent(2L)).thenReturn(false);
        service.setConsentService(consentService);

        FeedDto.AllergenStatus status = service.get(2L, 7L).allergen();

        assertThat(status.state()).isEqualTo("UNKNOWN");
        assertThat(status.message()).contains("동의");
        org.mockito.Mockito.verify(safetyContextService, org.mockito.Mockito.never()).build(Optional.of(2L), null);
    }

    // 알레르기 확인 불가: 건강정보 조회 실패, 재료 없음, 사전에 없는 알레르기. 등록 알레르기가 없으면 NOT_REGISTERED.
    @Test
    void allergenUnknownCases() throws Exception {
        stored(true, content("샐러드", List.of("양상추"), List.of("섞는다")));

        viewerHas(false, List.of());
        assertThat(service.get(2L, 7L).allergen().state()).isEqualTo("UNKNOWN");

        // 사과는 2026-10-29 사전에 추가되어 예시를 사전에 없는 "리치"로 바꿨습니다.
        assertThat(DICTIONARY.isKnown("리치")).as("리치는 아직 사전에 없음(사전 보강 시 이 테스트의 예시를 바꿈)").isFalse();
        viewerHas(true, List.of("리치"));
        FeedDto.AllergenStatus unknownAllergy = service.get(2L, 7L).allergen();
        assertThat(unknownAllergy.state()).isEqualTo("UNKNOWN");
        assertThat(unknownAllergy.unchecked()).containsExactly("리치");

        viewerHas(true, List.of());
        assertThat(service.get(2L, 7L).allergen().state()).isEqualTo("NOT_REGISTERED");

        stored(true, content("빈 재료", List.of(), List.of("만든다")));
        viewerHas(true, List.of("땅콩"));
        assertThat(service.get(2L, 7L).allergen().state()).isEqualTo("UNKNOWN");
    }

    // 삭제: 내 공유본만 지우고, 남의 것은 404입니다.
    @Test
    void deleteOnlyOwnPost() throws Exception {
        FeedPost post = stored(true, content("제목", List.of("두부"), List.of("굽는다")));

        assertThatThrownBy(() -> service.delete(2L, 7L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(feedPostRepository, never()).delete(any());

        service.delete(1L, 7L);
        verify(feedPostRepository).delete(post);
    }

    // 좋아요: 처음이면 저장, 이미 눌렀으면 그대로(같은 결과). 응답은 현재 전체 좋아요 수입니다.
    @Test
    void likeIsIdempotent() throws Exception {
        stored(true, content("두부조림", List.of("두부"), List.of("굽는다")));
        when(feedLikeRepository.findByPostIdAndUserId(7L, 2L)).thenReturn(Optional.empty());
        when(feedLikeRepository.countByPostId(7L)).thenReturn(1L);

        FeedDto.LikeResult first = service.like(2L, 7L);
        assertThat(first.liked()).isTrue();
        assertThat(first.likeCount()).isEqualTo(1L);
        verify(txHelper).insertLike(any(FeedLike.class));
        verify(nicknameService).requireNickname(2L);

        when(feedLikeRepository.findByPostIdAndUserId(7L, 2L))
                .thenReturn(Optional.of(FeedLike.of(7L, 2L, LocalDateTime.now(clock))));
        FeedDto.LikeResult again = service.like(2L, 7L);
        assertThat(again).isEqualTo(first);
        verify(txHelper).insertLike(any(FeedLike.class)); // 두 번째는 저장하지 않음
    }

    // 동시 좋아요로 UNIQUE가 겹치면 다시 확인해 이미 있으면 같은 결과입니다. 그 사이 취소가 끼어 행이 없어졌으면 다시 저장합니다.
    // 공유본이 지워졌으면 404, 계속 겹치면(3회) 위반을 그대로 알립니다.
    @Test
    void concurrentLikeViolationRechecksAndRetries() throws Exception {
        stored(true, content("두부조림", List.of("두부"), List.of("굽는다")));
        org.springframework.dao.DataIntegrityViolationException unique = new org.springframework.dao.DataIntegrityViolationException("uk");
        when(feedPostRepository.existsById(7L)).thenReturn(true);

        // 겹친 뒤 다시 보니 이미 있음 → 같은 결과
        when(txHelper.insertLike(any(FeedLike.class))).thenThrow(unique);
        when(feedLikeRepository.findByPostIdAndUserId(7L, 2L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(FeedLike.of(7L, 2L, LocalDateTime.now(clock))));
        assertThat(service.like(2L, 7L).liked()).isTrue();

        // 겹친 뒤 취소가 끼어 없어짐 → 다시 저장해 성공
        when(feedLikeRepository.findByPostIdAndUserId(7L, 4L)).thenReturn(Optional.empty());
        when(txHelper.insertLike(any(FeedLike.class))).thenThrow(unique).thenReturn(FeedLike.of(7L, 4L, LocalDateTime.now(clock)));
        assertThat(service.like(4L, 7L).liked()).isTrue();

        // 계속 겹치면 3회 뒤 위반을 그대로 알림
        when(feedLikeRepository.findByPostIdAndUserId(7L, 3L)).thenReturn(Optional.empty());
        when(txHelper.insertLike(any(FeedLike.class))).thenThrow(unique);
        assertThatThrownBy(() -> service.like(3L, 7L)).isSameAs(unique);

        // 그 사이 공유본이 지워졌으면 404
        when(feedPostRepository.existsById(7L)).thenReturn(false);
        assertThatThrownBy(() -> service.like(3L, 7L)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // 본인 공유본에는 좋아요 400, 닉네임 없으면 428(저장 안 함). 없는 공유본은 404.
    @Test
    void likeRejectsSelfMissingNicknameAndMissingPost() throws Exception {
        stored(true, content("두부조림", List.of("두부"), List.of("굽는다")));

        assertThatThrownBy(() -> service.like(1L, 7L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(FeedService.SELF_LIKE_MESSAGE);
        doThrow(new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "닉네임")).when(nicknameService).requireNickname(3L);
        assertThatThrownBy(() -> service.like(3L, 7L)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        when(feedPostRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.like(2L, 99L)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(txHelper, never()).insertLike(any());
    }

    // 취소: 누르지 않았어도 같은 결과이고, 닉네임을 요구하지 않습니다(이미 누른 좋아요는 언제든 취소).
    @Test
    void unlikeIsIdempotentAndNeedsNoNickname() throws Exception {
        stored(true, content("두부조림", List.of("두부"), List.of("굽는다")));
        doThrow(new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "닉네임")).when(nicknameService).requireNickname(2L);

        assertThat(service.unlike(2L, 7L)).isEqualTo(new FeedDto.LikeResult(false, 0L));
        assertThat(service.unlike(2L, 7L)).isEqualTo(new FeedDto.LikeResult(false, 0L));
        verify(txHelper, org.mockito.Mockito.times(2)).deleteLike(7L, 2L);
        verify(nicknameService, never()).requireNickname(any());
    }

    // 인기순: 저장소가 정한 순서(최근 7일 좋아요 수 → 최근 좋아요 시각 → ID)를 그대로 지키고, 기간은 7일·개수는 30입니다.
    @Test
    void popularKeepsRepositoryOrderWithSevenDaysAndThirtyLimit() throws Exception {
        FeedPost a = FeedPost.share(1L, 5L, UserRecipeOrigin.MANUAL, null, true, "A", objectMapper.writeValueAsString(
                content("A", List.of("두부"), List.of("굽는다"))), "[]", null, null, null, LocalDateTime.now(clock));
        FeedPost b = FeedPost.share(1L, 6L, UserRecipeOrigin.MANUAL, null, true, "B", objectMapper.writeValueAsString(
                content("B", List.of("두부"), List.of("굽는다"))), "[]", null, null, null, LocalDateTime.now(clock));
        org.springframework.test.util.ReflectionTestUtils.setField(a, "id", 10L);
        org.springframework.test.util.ReflectionTestUtils.setField(b, "id", 20L);
        FeedLikeRepository.PopularRow rowB = popularRow(20L, 3L);
        FeedLikeRepository.PopularRow rowA = popularRow(10L, 1L);
        when(feedLikeRepository.findPopularSince(any(), any(), any())).thenReturn(List.of(rowB, rowA));
        when(feedPostRepository.findAllById(any())).thenReturn(List.of(a, b));
        viewerHas(true, List.of());

        List<FeedDto.Summary> popular = service.popular(2L);

        assertThat(popular).extracting(FeedDto.Summary::id).containsExactly(20L, 10L);
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<org.springframework.data.domain.Pageable> page = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(feedLikeRepository).findPopularSince(org.mockito.ArgumentMatchers.eq(2L), since.capture(), page.capture());
        assertThat(since.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 19, 10, 0));
        assertThat(page.getValue().getPageSize()).isEqualTo(30);
    }

    private static FeedLikeRepository.PopularRow popularRow(Long postId, Long count) {
        return new FeedLikeRepository.PopularRow() {
            public Long getPostId() { return postId; }
            public Long getCount() { return count; }
            public LocalDateTime getLastLikedAt() { return null; }
        };
    }

    // 차단: 내가 차단한 사람의 공유본은 상세·좋아요가 404입니다. 작성자 본인 공유본에는 차단 검사를 하지 않습니다.
    @Test
    void blockedAuthorPostIsNotReadable() throws Exception {
        stored(true, content("두부조림", List.of("두부"), List.of("굽는다")));
        when(userBlockRepository.existsByBlockerIdAndBlockedId(2L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.get(2L, 7L)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.like(2L, 7L)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.unhiddenPost(2L, 7L).getId()).isEqualTo(7L); // 차단 요청의 작성자 확인용은 차단을 보지 않음
        verify(txHelper, never()).insertLike(any());
        verify(userBlockRepository, never()).existsByBlockerIdAndBlockedId(1L, 1L);
        service.readablePost(1L, 7L);
        verify(userBlockRepository, never()).existsByBlockerIdAndBlockedId(1L, 1L);
    }

    // 카운터 잠금 뒤 다시 보니 이미 눌려 있으면(AlreadyApplied, 차감 롤백) 오류 없이 같은 결과입니다.
    @Test
    void likeAlreadyAppliedIsSameResult() throws Exception {
        stored(true, content("두부조림", List.of("두부"), List.of("굽는다")));
        when(feedLikeRepository.findByPostIdAndUserId(7L, 2L)).thenReturn(Optional.empty());
        when(txHelper.insertLike(any(FeedLike.class))).thenThrow(new FeedTxHelper.AlreadyApplied());
        when(feedLikeRepository.countByPostId(7L)).thenReturn(1L);

        assertThat(service.like(2L, 7L)).isEqualTo(new FeedDto.LikeResult(true, 1L));
        verify(txHelper).insertLike(any(FeedLike.class));
    }
}
