package com.salus.healthytable.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import com.salus.healthytable.util.PublicUserName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 피드(내 레시피 공유)입니다. 로그인한 사용자만 쓰고, feed.enabled가 꺼져 있으면 상태 조회 외의 모든 피드 API가 404입니다
 * (신고·차단·관리자 검토가 준비된 뒤 켭니다. 기존 커뮤니티와는 별개).
 *
 * <p>공유: 앱은 내 레시피 ID만 보내고, 서버가 소유권을 확인한 뒤 그 순간의 지금 버전을 복사해 공유본을 만듭니다.
 * 공개 범위는 출처로 정합니다. 직접 작성·살루스 승인/DB 기반 레시피는 전체 공개, 웹에서 추출했거나 웹 검색을 근거로
 * AI가 구성했거나 출처 계보를 확인할 수 없는 변형(VARIANT·출처 없음)은 조리 단계를 공개하지 않습니다(재료·원본 링크·메모만).
 *
 * <p>알레르기: 보는 사람의 등록 알레르기를 볼 때마다 공통 로직(ChatSafetyContextService로 불러오고 AllergenMatcher로 판정)으로
 * 계산하고 저장하지 않습니다. 확인할 수 없으면 "확인 불가"이며 "안전"이라는 상태는 없습니다.
 */
@Slf4j
@Service
public class FeedService {

    static final String DISABLED_MESSAGE = "피드가 아직 열리지 않았어요.";
    static final String NOT_FOUND_MESSAGE = "공유 레시피를 찾을 수 없습니다.";
    static final String REQUEST_REUSED_MESSAGE = "같은 공유 요청으로 다른 내용을 보냈습니다. 화면을 다시 열어 공유해 주세요.";
    private static final int MAX_NOTE = 500;
    static final int TITLE_COLUMN_LENGTH = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    static final int POPULAR_DAYS = 7;
    static final int POPULAR_LIMIT = 30;
    static final int MAX_LIKE_ATTEMPTS = 3;
    static final String HIDDEN_WRITE_MESSAGE = "운영 검토로 숨겨진 레시피라 지금은 새 댓글을 받을 수 없어요.";
    static final String SELF_LIKE_MESSAGE = "내가 공유한 레시피에는 좋아요를 누를 수 없어요.";
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final boolean enabled;
    private final FeedPostRepository feedPostRepository;
    private final FeedLikeRepository feedLikeRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final FeedTxHelper txHelper;
    private final UserRecipeService userRecipeService;
    private final NicknameService nicknameService;
    private final UserRepository userRepository;
    private final ChatSafetyContextService safetyContextService;
    private final AllergenMatcher allergenMatcher;
    private final AllergenDictionary allergenDictionary;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FeedService(@Value("${feed.enabled:false}") boolean enabled,
                       FeedPostRepository feedPostRepository,
                       FeedLikeRepository feedLikeRepository,
                       FeedCommentRepository feedCommentRepository,
                       FeedTxHelper txHelper,
                       UserRecipeService userRecipeService,
                       NicknameService nicknameService,
                       UserRepository userRepository,
                       ChatSafetyContextService safetyContextService,
                       AllergenMatcher allergenMatcher,
                       AllergenDictionary allergenDictionary,
                       ObjectMapper objectMapper,
                       Clock clock) {
        this.enabled = enabled;
        this.feedPostRepository = feedPostRepository;
        this.feedLikeRepository = feedLikeRepository;
        this.feedCommentRepository = feedCommentRepository;
        this.txHelper = txHelper;
        this.userRecipeService = userRecipeService;
        this.nicknameService = nicknameService;
        this.userRepository = userRepository;
        this.safetyContextService = safetyContextService;
        this.allergenMatcher = allergenMatcher;
        this.allergenDictionary = allergenDictionary;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public FeedDto.Status status() {
        return new FeedDto.Status(enabled);
    }

    /**
     * 내 레시피를 피드에 공유합니다. 닉네임이 없으면 428, 남의 레시피면 404입니다.
     * 같은 요청 ID가 다시 오면(공유 화면이 열려 있는 동안의 재전송) 처음 요청 내용과 같을 때 기존 공유본을, 다르면 409입니다.
     */
    public FeedDto.ShareResult share(Long userId, FeedDto.ShareRequest request) {
        requireEnabled();
        if (request == null || request.userRecipeId() == null) {
            throw new IllegalArgumentException("공유할 레시피가 없습니다.");
        }
        String requestId = blankToNull(request.clientRequestId());
        if (requestId != null && !UUID_FORMAT.matcher(requestId).matches()) {
            throw new IllegalArgumentException("요청 ID 형식이 올바르지 않습니다.");
        }
        String note = blankToNull(request.note());
        if (note != null && note.length() > MAX_NOTE) {
            throw new IllegalArgumentException("메모는 " + MAX_NOTE + "자 이하로 입력해 주세요.");
        }
        nicknameService.requireNickname(userId);

        String hash = requestHash(request.userRecipeId(), note);
        if (requestId != null) {
            Optional<FeedPost> existing = txHelper.findByRequest(userId, requestId);
            if (existing.isPresent()) {
                return replayed(existing.get(), hash);
            }
        }

        // 남의 레시피면 여기서 404입니다(소유자 조건으로만 조회).
        MyRecipeDto.Detail recipe = userRecipeService.get(userId, request.userRecipeId());
        FeedPost post = FeedPost.share(userId, recipe.id(), recipe.originType(), recipe.provenance(),
                stepsPublic(recipe.originType(), recipe.provenance()), listTitle(recipe.current().title()),
                write(recipe.current()), write(recipe.sources() == null ? List.of() : recipe.sources()),
                note, requestId, requestId == null ? null : hash, now());
        try {
            return new FeedDto.ShareResult(txHelper.insert(post).getId(), true);
        } catch (DataIntegrityViolationException e) {
            if (requestId == null) {
                throw e;
            }
            FeedPost saved = txHelper.findByRequest(userId, requestId).orElseThrow(() -> e);
            return replayed(saved, hash);
        }
    }

    /**
     * 목록용 제목(feed_posts.title, VARCHAR(200)). 내 레시피는 원래 카드의 긴 제목을 보존하므로 200자를 넘을 수 있어,
     * 목록용으로만 코드 포인트 기준으로 자릅니다. 전체 제목은 공유본(snapshot)에 그대로 있고 상세에서 보여 줍니다.
     */
    static String listTitle(String title) {
        if (title == null || title.isBlank()) {
            return "제목 없는 레시피";
        }
        String trimmed = title.strip();
        return trimmed.codePointCount(0, trimmed.length()) <= TITLE_COLUMN_LENGTH
                ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, TITLE_COLUMN_LENGTH));
    }

    /** 최신순 목록입니다. 커서는 앞 페이지 마지막 공유본 ID입니다. 숨긴 공유본은 작성자 본인 목록에만 숨김 표시와 함께 나옵니다. */
    public FeedDto.Page list(Long viewerId, String cursor, Integer size) {
        requireEnabled();
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<FeedPost> rows = cursor == null || cursor.isBlank()
                ? feedPostRepository.findVisibleLatest(viewerId, page)
                : feedPostRepository.findVisibleLatestBefore(viewerId, parseCursor(cursor), page);
        boolean hasMore = rows.size() > pageSize;
        List<FeedPost> items = hasMore ? rows.subList(0, pageSize) : rows;
        return new FeedDto.Page(summaries(viewerId, items),
                hasMore ? String.valueOf(items.get(items.size() - 1).getId()) : null);
    }

    /**
     * 인기순: 최근 7일간 받은 좋아요 수가 많은 공유본 최대 30개입니다(페이지 없음). 취소한 좋아요는 빠지고, 취소 뒤 다시 누르면
     * 새 반응으로 셉니다. 본인 공유본에는 좋아요를 누를 수 없어 스스로 순위를 올릴 수 없습니다.
     * 동점이면 가장 최근 좋아요 시각, 그다음 공유본 ID 내림차순입니다. 응답의 likeCount는 전체 좋아요 수입니다.
     */
    public List<FeedDto.Summary> popular(Long viewerId) {
        requireEnabled();
        List<FeedLikeRepository.PopularRow> rows = feedLikeRepository.findPopularSince(
                now().minusDays(POPULAR_DAYS), PageRequest.of(0, POPULAR_LIMIT));
        Map<Long, FeedPost> posts = feedPostRepository.findAllById(rows.stream().map(FeedLikeRepository.PopularRow::getPostId).toList())
                .stream().collect(Collectors.toMap(FeedPost::getId, Function.identity()));
        List<FeedPost> ordered = rows.stream().map(row -> posts.get(row.getPostId())).filter(Objects::nonNull).toList();
        return summaries(viewerId, ordered);
    }

    /** 좋아요. 이미 눌렀으면 그대로 두고 같은 결과를 돌려줍니다. 본인 공유본은 400, 닉네임이 없으면 428입니다. */
    public FeedDto.LikeResult like(Long userId, Long postId) {
        requireEnabled();
        FeedPost post = readablePost(userId, postId);
        if (post.getUserId().equals(userId)) {
            throw new IllegalArgumentException(SELF_LIKE_MESSAGE);
        }
        nicknameService.requireNickname(userId);
        // 이미 있으면 그대로(같은 결과). 동시에 들어온 다른 좋아요와 UNIQUE가 겹치면 다시 확인하는데, 그 사이 취소(DELETE)가 끼어
        // 행이 없어졌을 수 있으므로 공유본이 남아 있으면 다시 저장합니다(취소 뒤에 이 좋아요가 처리된 순서). 공유본이 지워졌으면 404입니다.
        for (int attempt = 1; feedLikeRepository.findByPostIdAndUserId(postId, userId).isEmpty(); attempt++) {
            try {
                txHelper.insertLike(FeedLike.of(postId, userId, now()));
                break;
            } catch (DataIntegrityViolationException e) {
                if (!feedPostRepository.existsById(postId)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
                }
                if (attempt >= MAX_LIKE_ATTEMPTS) {
                    throw e;
                }
            }
        }
        return new FeedDto.LikeResult(true, feedLikeRepository.countByPostId(postId));
    }

    /** 좋아요 취소. 누르지 않은 상태여도 같은 결과입니다. 이미 누른 좋아요는 닉네임과 상관없이 취소할 수 있습니다. */
    public FeedDto.LikeResult unlike(Long userId, Long postId) {
        requireEnabled();
        readablePost(userId, postId);
        txHelper.deleteLike(postId, userId);
        return new FeedDto.LikeResult(false, feedLikeRepository.countByPostId(postId));
    }

    // 목록(최신순·인기순) 공통 변환. 작성자·좋아요·댓글 수와 보는 사람의 좋아요 여부를 공유본마다가 아니라 한 번에 조회합니다.
    private List<FeedDto.Summary> summaries(Long viewerId, List<FeedPost> posts) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = posts.stream().map(FeedPost::getId).toList();
        Map<Long, User> authors = authors(posts);
        Map<Long, Long> likeCounts = feedLikeRepository.countByPostIds(ids).stream()
                .collect(Collectors.toMap(FeedLikeRepository.PostCount::getPostId, FeedLikeRepository.PostCount::getCount));
        Set<Long> liked = Set.copyOf(feedLikeRepository.findLikedPostIds(viewerId, ids));
        Map<Long, Long> commentCounts = feedCommentRepository.countActiveByPostIds(ids).stream()
                .collect(Collectors.toMap(FeedCommentRepository.IdCount::getId, FeedCommentRepository.IdCount::getCount));
        ViewerAllergies viewer = viewerAllergies(viewerId);
        return posts.stream()
                .map(post -> new FeedDto.Summary(post.getId(), post.getTitle(),
                        PublicUserName.of(authors.get(post.getUserId())), post.getOriginType(), post.getProvenance(),
                        post.isStepsPublic(), post.getUserId().equals(viewerId), post.getCreatedAt(),
                        allergenStatus(viewer, post), likeCounts.getOrDefault(post.getId(), 0L),
                        liked.contains(post.getId()), commentCounts.getOrDefault(post.getId(), 0L), post.isHidden()))
                .toList();
    }

    /**
     * 신고 시점 복사본·관리자 화면용 공개 내용입니다. 상세 응답과 같은 공개 범위만 담습니다: 조리 단계를 공개하지 않는 출처면
     * 설명·조리 단계를 넣지 않고, 공유본 전체(snapshot_json)나 보는 사람의 건강정보·알레르기 판정은 넣지 않습니다.
     */
    Map<String, Object> publicSnapshot(FeedPost post) {
        MyRecipeDto.Content content = readContent(post);
        boolean open = post.isStepsPublic() && content != null;
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("type", "FEED_POST");
        snapshot.put("postId", post.getId());
        snapshot.put("title", content != null && content.title() != null ? content.title() : post.getTitle());
        snapshot.put("authorName", PublicUserName.of(userRepository.findById(post.getUserId()).orElse(null)));
        snapshot.put("originType", post.getOriginType());
        snapshot.put("provenance", post.getProvenance());
        snapshot.put("stepsPublic", post.isStepsPublic());
        snapshot.put("description", open ? content.description() : null);
        snapshot.put("ingredients", content == null ? List.of() : nonNull(content.ingredients()));
        snapshot.put("steps", open ? nonNull(content.steps()) : null);
        snapshot.put("sources", readSources(post));
        snapshot.put("note", post.getNote());
        snapshot.put("createdAt", String.valueOf(post.getCreatedAt()));
        return snapshot;
    }

    /** 공유본을 찾습니다. 없으면 404입니다. */
    FeedPost findPost(Long postId) {
        return feedPostRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
    }

    /**
     * 보는 사람이 읽을 수 있는 공유본. 관리자가 숨긴 공유본은 작성자 본인만 읽을 수 있고(조회 예외) 다른 사람에게는 404입니다.
     * 댓글 관련 API도 이 검사를 먼저 거칩니다(공유본 → 댓글 순서). 숨긴 공유본에 댓글을 단 사람이라고 볼 권한이 생기지 않습니다.
     */
    FeedPost readablePost(Long viewerId, Long postId) {
        FeedPost post = findPost(postId);
        if (post.isHidden() && !post.getUserId().equals(viewerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        return post;
    }

    /** 새 활동(댓글 작성 등)을 받을 수 있는 공유본. 숨긴 공유본은 작성자 본인에게 409, 다른 사람에게 404입니다. */
    FeedPost writablePost(Long viewerId, Long postId) {
        FeedPost post = readablePost(viewerId, postId);
        if (post.isHidden()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, HIDDEN_WRITE_MESSAGE);
        }
        return post;
    }

    public FeedDto.Detail get(Long viewerId, Long postId) {
        requireEnabled();
        FeedPost post = readablePost(viewerId, postId);
        MyRecipeDto.Content content = readContent(post);
        User author = userRepository.findById(post.getUserId()).orElse(null);
        // 공개 응답은 허용한 필드만 골라 만듭니다. 조리 단계를 공개하지 않는 출처면 원본 설명도 넣지 않습니다
        // (설명에 조리 내용이 들어 있을 수 있음). 그때 공개하는 것은 제목·재료·원본 링크·작성자 메모와 분량·시간 같은 숫자 정보입니다.
        boolean open = post.isStepsPublic() && content != null;
        String fullTitle = content != null && content.title() != null && !content.title().isBlank()
                ? content.title() : post.getTitle();
        return new FeedDto.Detail(post.getId(), fullTitle, PublicUserName.of(author), post.getOriginType(),
                post.getProvenance(),
                open ? content.description() : null,
                content == null ? List.of() : nonNull(content.ingredients()),
                open ? nonNull(content.steps()) : null,
                post.isStepsPublic(),
                content == null ? null : content.servings(),
                content == null ? null : content.cookingTime(),
                content == null ? null : content.difficulty(),
                content == null ? null : content.caloriesPerServing(),
                readSources(post), post.getNote(), post.getUserId().equals(viewerId), post.getCreatedAt(),
                allergenStatus(viewerAllergies(viewerId), post), feedLikeRepository.countByPostId(post.getId()),
                feedLikeRepository.findByPostIdAndUserId(post.getId(), viewerId).isPresent(),
                feedCommentRepository.countActiveByPostIds(List.of(post.getId())).stream()
                        .mapToLong(FeedCommentRepository.IdCount::getCount).sum(),
                post.isHidden());
    }

    /** 내 공유본만 지울 수 있고, 남의 것이면 404입니다. 원래 내 레시피는 그대로입니다. */
    public void delete(Long userId, Long postId) {
        requireEnabled();
        FeedPost post = feedPostRepository.findById(postId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
        feedPostRepository.delete(post);
    }

    /**
     * 조리 단계 공개 여부. 직접 작성과 살루스 승인·DB 기반 레시피만 공개합니다. 웹 추출·웹 검색 기반 AI, 출처 계보를 확인할 수
     * 없는 변형(VARIANT)이나 출처 정보가 없는 카드는 비공개입니다(확신할 수 없으면 덜 공개).
     */
    static boolean stepsPublic(UserRecipeOrigin origin, RecipeCardProvenance provenance) {
        if (origin == UserRecipeOrigin.MANUAL) {
            return true;
        }
        if (provenance == null) {
            return false;
        }
        return switch (provenance) {
            case APPROVED_CATALOG, INTERNAL_DB_EXTRACTED, AI_FROM_INTERNAL_DB -> true;
            case WEB_EXTRACTED, AI_FROM_WEB_SEARCH, VARIANT -> false;
        };
    }

    /** 보는 사람의 등록 알레르기. 불러오지 못하면 available=false입니다(빈 목록으로 바꾸지 않음). */
    record ViewerAllergies(boolean available, List<String> allergies) {
    }

    ViewerAllergies viewerAllergies(Long viewerId) {
        ChatSafetyContextService.SafetyContext context = safetyContextService.build(Optional.of(viewerId), null);
        return new ViewerAllergies(context.healthContextAvailable(), context.allergies());
    }

    /**
     * 공유본 하나에 대한 보는 사람 기준 표시. 일치(CONTAINS)가 가장 먼저이고, 하나라도 판정할 수 없는 알레르기가 있거나
     * 재료 정보·건강정보가 없으면 "확인 불가"입니다. 일치 없음도 안전을 뜻하지 않습니다.
     */
    FeedDto.AllergenStatus allergenStatus(ViewerAllergies viewer, FeedPost post) {
        if (!viewer.available()) {
            return new FeedDto.AllergenStatus("UNKNOWN", List.of(), List.of(),
                    "건강정보를 불러오지 못해 알레르기를 확인할 수 없어요.");
        }
        if (viewer.allergies().isEmpty()) {
            return new FeedDto.AllergenStatus("NOT_REGISTERED", List.of(), List.of(), "등록한 알레르기가 없어요.");
        }
        MyRecipeDto.Content content = readContent(post);
        if (content == null || nonNull(content.ingredients()).isEmpty()) {
            return new FeedDto.AllergenStatus("UNKNOWN", List.of(), List.of(),
                    "재료 정보가 없어 알레르기를 확인할 수 없어요.");
        }
        // 공개하지 않는 조리 단계까지 포함해 판정합니다(표시는 결과만, 단계 내용은 내보내지 않음). 작성자 메모는 레시피 내용이 아니라 제외합니다.
        List<String> texts = new ArrayList<>();
        texts.add(content.title());
        texts.add(content.description());
        texts.addAll(nonNull(content.ingredients()));
        texts.addAll(nonNull(content.steps()));
        texts.removeIf(Objects::isNull);
        List<String> matched = allergenMatcher.findConflicts(viewer.allergies(), texts);
        List<String> unchecked = viewer.allergies().stream()
                .filter(allergy -> !allergenDictionary.isKnown(allergy) && !matched.contains(allergy.trim()))
                .toList();
        if (!matched.isEmpty()) {
            return new FeedDto.AllergenStatus("CONTAINS", matched, unchecked,
                    "등록한 알레르기와 일치하는 성분이 있어요: " + String.join(", ", matched));
        }
        if (!unchecked.isEmpty()) {
            return new FeedDto.AllergenStatus("UNKNOWN", List.of(), unchecked,
                    "판정할 수 없는 알레르기가 있어요: " + String.join(", ", unchecked) + ". 재료를 직접 확인하세요.");
        }
        return new FeedDto.AllergenStatus("NO_MATCH_FOUND", List.of(), List.of(),
                "확인한 재료에서 등록한 알레르기와 일치하는 항목을 찾지 못했어요. 안전하다는 뜻은 아니니 원재료와 교차오염을 직접 확인하세요.");
    }

    void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, DISABLED_MESSAGE);
        }
    }

    private static FeedDto.ShareResult replayed(FeedPost saved, String hash) {
        if (!hash.equals(saved.getRequestHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, REQUEST_REUSED_MESSAGE);
        }
        return new FeedDto.ShareResult(saved.getId(), false);
    }

    /** 공유 요청 내용(내 레시피 ID와 메모)의 SHA-256. 재전송 판별의 비교 기준이며 공유본이 바뀌어도 그대로입니다. */
    static String requestHash(Long userRecipeId, String note) {
        String canonical = userRecipeId + "\n" + Objects.toString(note, "");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("공유 요청 해시를 만들지 못했습니다.", e);
        }
    }

    private Map<Long, User> authors(List<FeedPost> posts) {
        List<Long> ids = posts.stream().map(FeedPost::getUserId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByIdIn(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    // 공유본을 읽지 못하면 null(호출자가 빈 값·확인 불가로 처리). 비공개 내용을 대신 채우지 않습니다.
    private MyRecipeDto.Content readContent(FeedPost post) {
        try {
            return objectMapper.readValue(post.getSnapshotJson(), MyRecipeDto.Content.class);
        } catch (Exception e) {
            log.warn("[Feed] snapshot unreadable. postId={}", post.getId());
            return null;
        }
    }

    private List<FeedDto.Source> readSources(FeedPost post) {
        try {
            return objectMapper.readValue(post.getSourcesJson(), new TypeReference<List<FeedDto.Source>>() { });
        } catch (Exception e) {
            log.warn("[Feed] sources unreadable. postId={}", post.getId());
            return List.of();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("공유본을 저장할 형식으로 바꾸지 못했습니다.", e);
        }
    }

    private static long parseCursor(String cursor) {
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("목록 위치(cursor) 형식이 올바르지 않습니다.");
        }
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static List<String> nonNull(List<String> items) {
        return items == null ? List.of() : items;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
