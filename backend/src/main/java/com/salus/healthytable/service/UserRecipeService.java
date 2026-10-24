package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.ChatRecipeCard;
import com.salus.healthytable.domain.UserRecipe;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.MyRecipeDto;
import com.salus.healthytable.repository.UserRecipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 내 레시피(나만 보는 레시피)를 저장·조회·수정·삭제합니다.
 *
 * <p>채팅 카드에서 저장할 때는 앱이 보낸 레시피 내용을 받지 않고, 서버가 보관한 카드(chat_recipe_cards)를 복사합니다.
 * 원본·출처는 처음 저장할 때 정해지고 바뀌지 않으며, 사용자는 지금 버전과 수정 메모만 고칩니다.
 * 이 클래스 자체는 트랜잭션을 열지 않습니다(DB 작업은 {@link UserRecipeTxHelper}가 묶음).
 * 내 레시피는 의료 조언이 아니며, 알레르기 안전 여부를 판정하지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRecipeService {

    static final int MAX_TITLE = 60;
    // 목록용 제목 컬럼 길이입니다. 전체 제목은 레시피 내용(current_recipe)에 그대로 있습니다.
    static final int TITLE_COLUMN_LENGTH = 200;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_INGREDIENTS = 50;
    static final int MAX_INGREDIENT_LENGTH = 100;
    static final int MAX_STEPS = 30;
    static final int MAX_STEP_LENGTH = 500;
    static final int MAX_NOTE = 500;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final UserRecipeTxHelper txHelper;
    private final UserRecipeRepository userRecipeRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 카드 저장 결과입니다. created가 true면 새로 만든 것(201), false면 이미 저장해 둔 것(200)입니다. */
    public record SaveResult(MyRecipeDto.Detail detail, boolean created) {
    }

    /**
     * 내 채팅 카드를 내 레시피로 저장합니다. 같은 카드는 한 번만 저장되고, 다시 누르면 기존 레시피를 돌려줍니다.
     * 동시에 두 번 눌러 유니크 위반이 나면, 그 트랜잭션은 롤백된 상태이므로 새 트랜잭션에서 기존 레시피를 찾습니다.
     */
    public SaveResult saveFromCard(Long userId, MyRecipeDto.FromCardRequest request) {
        String cardId = request == null || request.cardId() == null ? "" : request.cardId().trim();
        if (cardId.isEmpty() || cardId.length() > 36) {
            throw new IllegalArgumentException("저장할 레시피 카드가 없습니다.");
        }
        LocalDateTime now = now();
        try {
            UserRecipeTxHelper.FromCardResult result = txHelper.createFromCard(userId, cardId, card -> toRecipe(userId, card, now));
            return new SaveResult(toDetail(result.recipe()), result.created());
        } catch (DataIntegrityViolationException e) {
            UserRecipe saved = txHelper.findSavedFromCard(userId, cardId).orElseThrow(() -> e);
            return new SaveResult(toDetail(saved), false);
        }
    }

    /** 직접 작성합니다. 처음 작성한 내용이 원본이 됩니다. */
    public MyRecipeDto.Detail create(Long userId, MyRecipeDto.CreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("레시피 내용이 없습니다.");
        }
        MyRecipeDto.Content content = validated(new MyRecipeDto.Content(request.title(), request.description(),
                request.ingredients(), request.steps(), request.servings(), request.cookingTime(), request.difficulty(),
                request.caloriesPerServing()), Limits.DEFAULT, null);
        String contentJson = write(content);
        UserRecipe recipe = UserRecipe.manual(userId, listTitle(content.title()), contentJson,
                note(request.modificationNote()), now());
        return toDetail(txHelper.create(recipe));
    }

    public MyRecipeDto.Detail get(Long userId, Long id) {
        return toDetail(txHelper.get(userId, id));
    }

    /** 최근 수정순 목록입니다. 커서는 (수정 시각, ID)이고 다음 페이지가 없으면 nextCursor가 null입니다. */
    public MyRecipeDto.Page list(Long userId, String cursor, Integer size) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<UserRecipe> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = userRecipeRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId, page);
        } else {
            Cursor after = Cursor.decode(cursor);
            rows = userRecipeRepository.findPageAfter(userId, after.updatedAt(), after.id(), page);
        }
        boolean hasMore = rows.size() > pageSize;
        List<UserRecipe> items = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore ? Cursor.encode(items.get(items.size() - 1)) : null;
        return new MyRecipeDto.Page(items.stream()
                .map(recipe -> new MyRecipeDto.Summary(recipe.getId(), recipe.getTitle(), recipe.getOriginType(),
                        recipe.getProvenance(), recipe.getUpdatedAt()))
                .toList(), next);
    }

    /** 지금 버전과 수정 메모만 바꿉니다. 원본·출처는 요청에 없고 바뀌지 않습니다. */
    public MyRecipeDto.Detail update(Long userId, Long id, MyRecipeDto.UpdateRequest request) {
        if (request == null || request.version() == null) {
            throw new IllegalArgumentException("수정할 레시피의 버전이 필요합니다.");
        }
        // 수정 제한은 원본보다 엄격하지 않게 합니다(31단계 카드는 31단계까지, 원본의 범위 밖 값은 그대로 두면 통과).
        // 버전 비교·저장은 아래 txHelper.update가 다시 조회해서 합니다(여기 조회는 제한 계산용).
        MyRecipeDto.Content original = originalContent(txHelper.get(userId, id));
        MyRecipeDto.Content content = validated(new MyRecipeDto.Content(request.title(), request.description(),
                request.ingredients(), request.steps(), request.servings(), request.cookingTime(), request.difficulty(),
                request.caloriesPerServing()), Limits.notStricterThan(original), original);
        UserRecipe updated = txHelper.update(userId, id, request.version(), listTitle(content.title()), write(content),
                note(request.modificationNote()), now());
        return toDetail(updated);
    }

    public void delete(Long userId, Long id) {
        txHelper.delete(userId, id);
    }

    // 카드 → 새 레시피. 원본은 서버가 보관한 카드 JSON 그대로, 지금 버전은 그 카드의 레시피 내용입니다.
    private UserRecipe toRecipe(Long userId, ChatRecipeCard card, LocalDateTime now) {
        MyRecipeDto.Content content = fromCard(readCard(card.getCardJson()));
        return UserRecipe.fromChatCard(userId, card.getId(), card.getProvenance(), card.getCardJson(),
                card.getSourcesJson(), listTitle(content.title()), write(content), now);
    }

    private ChatDto.RecipeCard readCard(String cardJson) {
        try {
            return objectMapper.readValue(cardJson, ChatDto.RecipeCard.class);
        } catch (Exception e) {
            log.warn("[MyRecipe] card unreadable. type={}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이 레시피는 저장할 수 없어요.");
        }
    }

    // 서버가 만든 카드의 레시피 내용을 그대로 옮깁니다. 자르거나 범위 밖 값을 지우지 않습니다
    // (재료 줄 끝의 분량이나 알레르기 성분 이름이 잘려 사라지면 안 되므로). 수정 제한은 이 원본에 맞춰 완화됩니다.
    private static MyRecipeDto.Content fromCard(ChatDto.RecipeCard card) {
        String title = blankToNull(card.getTitle());
        return new MyRecipeDto.Content(
                title == null ? "이름 없는 레시피" : title,
                card.getDescription(),
                card.getIngredients() == null ? List.of() : card.getIngredients().stream().filter(java.util.Objects::nonNull).toList(),
                card.getSteps() == null ? List.of() : card.getSteps().stream().filter(java.util.Objects::nonNull).toList(),
                card.getServings(),
                card.getCookingTime(),
                card.getDifficulty(),
                card.getCaloriesPerServing() != null ? card.getCaloriesPerServing() : card.getCalories());
    }

    // 사용자가 보낸 내용 검사. 범위를 벗어나면 400입니다. 숫자는 범위 안이거나 원본 값과 같으면 통과합니다.
    private static MyRecipeDto.Content validated(MyRecipeDto.Content content, Limits limits, MyRecipeDto.Content original) {
        String title = blankToNull(content.title());
        require(title != null && title.length() <= limits.title(), "레시피 이름은 1~" + limits.title() + "자로 입력해 주세요.");
        String description = blankToNull(content.description());
        require(description == null || description.length() <= limits.description(),
                "설명은 " + limits.description() + "자 이하로 입력해 주세요.");
        List<String> ingredients = trimmedItems(content.ingredients());
        require(!ingredients.isEmpty() && ingredients.size() <= limits.ingredients(),
                "재료는 1~" + limits.ingredients() + "개로 입력해 주세요.");
        require(ingredients.stream().allMatch(item -> item.length() <= limits.ingredientLength()),
                "재료 한 줄은 " + limits.ingredientLength() + "자 이하로 입력해 주세요.");
        List<String> steps = trimmedItems(content.steps());
        require(!steps.isEmpty() && steps.size() <= limits.steps(), "조리 단계는 1~" + limits.steps() + "개로 입력해 주세요.");
        require(steps.stream().allMatch(item -> item.length() <= limits.stepLength()),
                "조리 단계 한 줄은 " + limits.stepLength() + "자 이하로 입력해 주세요.");
        requireRange(content.servings(), 1, 20, original == null ? null : original.servings(), "인분은 1~20 사이로 입력해 주세요.");
        requireRange(content.cookingTime(), 0, 1440, original == null ? null : original.cookingTime(),
                "조리 시간은 0~1440분 사이로 입력해 주세요.");
        requireRange(content.difficulty(), 1, 5, original == null ? null : original.difficulty(), "난이도는 1~5 사이로 입력해 주세요.");
        requireRange(content.caloriesPerServing(), 0, 5000, original == null ? null : original.caloriesPerServing(),
                "1인분 열량은 0~5000kcal 사이로 입력해 주세요.");
        return new MyRecipeDto.Content(title, description, ingredients, steps, content.servings(), content.cookingTime(),
                content.difficulty(), content.caloriesPerServing());
    }

    /** 입력 제한입니다. 직접 작성은 기본값, 수정은 원본이 이미 넘는 만큼 넓힙니다(원본보다 엄격하지 않게). */
    record Limits(int title, int description, int ingredients, int ingredientLength, int steps, int stepLength) {
        static final Limits DEFAULT = new Limits(MAX_TITLE, MAX_DESCRIPTION, MAX_INGREDIENTS, MAX_INGREDIENT_LENGTH,
                MAX_STEPS, MAX_STEP_LENGTH);

        static Limits notStricterThan(MyRecipeDto.Content original) {
            if (original == null) {
                return DEFAULT;
            }
            return new Limits(
                    Math.max(MAX_TITLE, length(original.title())),
                    Math.max(MAX_DESCRIPTION, length(original.description())),
                    Math.max(MAX_INGREDIENTS, size(original.ingredients())),
                    Math.max(MAX_INGREDIENT_LENGTH, longest(original.ingredients())),
                    Math.max(MAX_STEPS, size(original.steps())),
                    Math.max(MAX_STEP_LENGTH, longest(original.steps())));
        }

        private static int length(String value) {
            return value == null ? 0 : value.trim().length();
        }

        private static int size(List<String> items) {
            return items == null ? 0 : items.size();
        }

        private static int longest(List<String> items) {
            return items == null ? 0 : items.stream().mapToInt(Limits::length).max().orElse(0);
        }
    }

    private static String note(String raw) {
        String note = blankToNull(raw);
        require(note == null || note.length() <= MAX_NOTE, "수정 메모는 500자 이하로 입력해 주세요.");
        return note;
    }

    private MyRecipeDto.Detail toDetail(UserRecipe recipe) {
        return new MyRecipeDto.Detail(
                recipe.getId(),
                recipe.getOriginType(),
                recipe.getProvenance(),
                readContent(recipe.getCurrentRecipe()),
                originalContent(recipe),
                displayedSources(recipe.getSourcesJson()),
                recipe.getModificationNote(),
                recipe.getVersion(),
                recipe.getCreatedAt(),
                recipe.getUpdatedAt());
    }

    // 원본은 채팅 카드면 카드 JSON, 직접 작성이면 처음 내용이라 모양이 다릅니다. 응답은 같은 Content 모양으로 맞춥니다.
    private MyRecipeDto.Content originalContent(UserRecipe recipe) {
        if (recipe.getOriginType() == com.salus.healthytable.domain.UserRecipeOrigin.CHAT_CARD) {
            try {
                return fromCard(objectMapper.readValue(recipe.getOriginalSnapshot(), ChatDto.RecipeCard.class));
            } catch (Exception e) {
                log.warn("[MyRecipe] original unreadable. recipeId={}", recipe.getId());
                return null;
            }
        }
        return readContent(recipe.getOriginalSnapshot());
    }

    private MyRecipeDto.Content readContent(String json) {
        try {
            return objectMapper.readValue(json, MyRecipeDto.Content.class);
        } catch (Exception e) {
            throw new IllegalStateException("저장된 레시피를 읽을 수 없습니다.", e);
        }
    }

    // 카드 출처 중 화면에 보여 줄 것만 돌려줍니다. RETRIEVED(검색만 된 자료)는 인용이 아니므로 빼고, 읽을 수 없으면 빈 목록입니다.
    private List<MyRecipeDto.Source> displayedSources(String sourcesJson) {
        List<MyRecipeDto.Source> sources = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(sourcesJson);
            for (JsonNode node : array) {
                String role = node.path("role").asText(null);
                if (role == null || "RETRIEVED".equals(role)) {
                    continue;
                }
                sources.add(new MyRecipeDto.Source(role, node.path("title").asText(null), node.path("url").asText(null)));
            }
        } catch (Exception e) {
            log.warn("[MyRecipe] sources unreadable. type={}", e.getClass().getSimpleName());
            return List.of();
        }
        return sources;
    }

    private String write(MyRecipeDto.Content content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new IllegalStateException("레시피를 저장할 형식으로 바꾸지 못했습니다.", e);
        }
    }

    // 커서 정렬 기준과 DB 값(초 단위 DATETIME)이 맞도록 초 아래는 버립니다.
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static List<String> trimmedItems(List<String> items) {
        return items == null ? List.of() : items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }

    private static void requireRange(Integer value, int min, int max, Integer originalValue, String message) {
        require(value == null || (value >= min && value <= max) || value.equals(originalValue), message);
    }

    // 목록용 제목(컬럼 길이에 맞춤). 레시피 내용의 제목은 자르지 않습니다.
    private static String listTitle(String title) {
        return clip(title, TITLE_COLUMN_LENGTH);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** 목록 커서: "수정 시각|ID"를 Base64URL로 감쌉니다. 읽을 수 없으면 400입니다. */
    record Cursor(LocalDateTime updatedAt, Long id) {
        static String encode(UserRecipe last) {
            String raw = last.getUpdatedAt() + "|" + last.getId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 2);
                return new Cursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
            } catch (Exception e) {
                throw new IllegalArgumentException("목록 위치(cursor) 형식이 올바르지 않습니다.");
            }
        }
    }
}
