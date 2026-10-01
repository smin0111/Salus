package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.repository.RecipeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.salus.healthytable.service.ApprovedRecipeCatalogDefinition.*;

/**
 * 사람이 검수한 "승인 레시피 카탈로그"를 관리하고 사용자 요청에 맞게 보여 주는 서비스입니다.
 *
 * - 원본: resources/recipes/approved-recipes.json (Git에서 리뷰하는 문서)
 * - 서버 시작 시(ApplicationRunner.run) 원본을 읽어 엄격하게 검증하고, recipes 테이블 등에 동기화합니다.
 * - 채팅 메시지에 승인 레시피 이름/별칭이 있으면 LLM 생성 대신 이 원본으로 답합니다.
 * - 인분 수(1~6)와 맵기 조정은 항상 원본에서 다시 계산합니다(이전 답변의 반올림 값을 재사용하지 않음).
 * 카탈로그 검증에 실패하면 예외를 던져 애플리케이션 시작을 막습니다(잘못된 레시피가 승인 상태로 노출되지 않게).
 */
@Slf4j
@Service
public class ApprovedRecipeService implements ApplicationRunner {

    // 카탈로그 파일 위치와 검증 기준값(레시피 개수, 인분 조정 범위, 설명되지 않은 조리 시간 허용치)
    private static final String CATALOG_PATH = "recipes/approved-recipes.json";
    private static final int REQUIRED_RECIPE_COUNT = 10;
    private static final int MIN_SERVINGS = 1;
    private static final int MAX_SERVINGS = 6;
    private static final int MAX_UNACCOUNTED_MINUTES = 10;
    // "3인분", "2인용"에서 숫자를 찾는 정규식
    private static final Pattern SERVINGS_PATTERN = Pattern.compile("(\\d{1,2})\\s*(?:인분|인용)");
    // "약 5~7분" 같은 단계 시간 문자열에서 분 값을 읽는 정규식(범위면 큰 값을 사용)
    private static final Pattern DURATION_MINUTES_PATTERN = Pattern.compile(
            "(?:약\\s*)?(\\d+)(?:\\s*[~～-]\\s*(\\d+))?\\s*분");
    // 사용자용 조리 설명에 섞이면 안 되는 내부 작성 규칙 문구
    private static final Pattern LEAKED_AUTHORING_RULE_PATTERN = Pattern.compile(
            "재료\\s*목록|표시된\\s*경우|선택한\\s*맵기\\s*단계");

    private final RecipeRepository recipeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // false면 카탈로그를 메모리에만 읽고 DB 동기화는 하지 않습니다.
    @Value("${recipe.catalog.sync-enabled:true}")
    private boolean syncEnabled;

    // 메모리 캐시. volatile은 여러 요청 스레드가 항상 최신 참조를 보도록 보장합니다.
    // recipesByKey: key → 레시피 정의, aliases: 정규화된 이름/별칭 → key (긴 이름 우선 정렬)
    private volatile ApprovedRecipeCatalogDefinition catalog;
    private volatile Map<String, RecipeDefinition> recipesByKey = Map.of();
    private volatile List<AliasEntry> aliases = List.of();

    public ApprovedRecipeService(
            RecipeRepository recipeRepository,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        this.recipeRepository = recipeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 애플리케이션 시작 직후 한 번 실행됩니다(ApplicationRunner).
     * 카탈로그를 읽고 검증한 뒤, 설정이 켜져 있으면 DB와 동기화합니다.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ApprovedRecipeCatalogDefinition loaded = loadAndValidateCatalog();
        cache(loaded);
        if (!syncEnabled) {
            log.info("Approved recipe catalog loaded without DB sync: {} recipes", loaded.recipes().size());
            return;
        }

        // 삭제하지 않는다. 현 카탈로그에서 제거된 과거 승인 행만 미검증 상태로 되돌린다.
        String placeholders = String.join(",", loaded.recipes().stream().map(recipe -> "?").toList());
        Object[] catalogKeys = loaded.recipes().stream().map(RecipeDefinition::key).toArray();
        jdbcTemplate.update("UPDATE recipes SET approval_status = 'UNVERIFIED' "
                + "WHERE approval_status = 'APPROVED' "
                + "AND (catalog_key IS NULL OR catalog_key NOT IN (" + placeholders + "))", catalogKeys);
        for (RecipeDefinition definition : loaded.recipes()) {
            synchronizeRecipe(loaded, definition);
        }
        log.info("Approved recipe catalog synchronized: {} approved recipes", loaded.recipes().size());
    }

    /**
     * 메시지에 승인 레시피의 제목이나 별칭이 포함되어 있으면 그 레시피를 반환합니다.
     * 긴 별칭부터 검사해 "된장찌개"와 "차돌된장찌개" 같은 경우 더 구체적인 이름이 먼저 매칭되게 합니다.
     */
    public Optional<Recipe> findApprovedMatch(String requestText) {
        ensureCatalogLoaded();
        String normalizedRequest = normalizeLookup(requestText);
        if (normalizedRequest.isBlank()) {
            return Optional.empty();
        }
        return aliases.stream()
                .filter(alias -> normalizedRequest.contains(alias.normalizedAlias()))
                .findFirst()
                .flatMap(alias -> recipeRepository.findByCatalogKey(alias.catalogKey()))
                .filter(recipe -> recipe.getApprovalStatus() == RecipeApprovalStatus.APPROVED);
    }

    // 메시지에서 인분 수("3인분")와 맵기 요청("덜 맵게", "불닭 수준")을 읽습니다. 둘 다 없으면 빈 Optional입니다.
    public Optional<AdjustmentRequest> parseAdjustment(String message) {
        String value = message == null ? "" : message;
        Matcher servingsMatcher = SERVINGS_PATTERN.matcher(value);
        Integer servings = servingsMatcher.find() ? Integer.parseInt(servingsMatcher.group(1)) : null;

        String compact = normalizeLookup(value);
        SpiceLevel spiceLevel = null;
        if (containsAny(compact, "불닭", "극매운", "아주맵", "매우맵")) {
            spiceLevel = SpiceLevel.EXTREME;
        } else if (containsAny(compact, "안맵", "맵지않", "덜맵", "순한맛", "순하게")) {
            spiceLevel = SpiceLevel.MILD;
        } else if (containsAny(compact, "더맵", "맵게", "매운맛")) {
            spiceLevel = SpiceLevel.SPICY;
        } else if (containsAny(compact, "기본맵기", "보통맵기", "기본맛")) {
            spiceLevel = SpiceLevel.NORMAL;
        }
        if (servings == null && spiceLevel == null) {
            return Optional.empty();
        }
        return Optional.of(new AdjustmentRequest(servings, spiceLevel));
    }

    /**
     * 승인 레시피를 요청한 인분과 맵기로 렌더링합니다.
     *
     * @param expectedVersion 사용자가 이전에 본 레시피 버전. 그사이 원본이 바뀌었으면 섞이지 않도록 다시 요청하게 합니다.
     * @throws IllegalArgumentException 지원하지 않는 인분/맵기 요청
     * @throws IllegalStateException    원본 문서를 찾을 수 없거나 버전이 바뀐 경우
     */
    public RenderedRecipe renderApprovedRecipe(
            Long recipeId,
            Integer expectedVersion,
            int servings,
            SpiceLevel requestedSpiceLevel) {
        ensureCatalogLoaded();
        if (servings < MIN_SERVINGS || servings > MAX_SERVINGS) {
            throw new IllegalArgumentException("승인 레시피의 인분 조정은 1인분부터 6인분까지만 지원합니다.");
        }

        Recipe approved = recipeRepository.findByIdAndApprovalStatus(recipeId, RecipeApprovalStatus.APPROVED)
                .orElseThrow(() -> new IllegalArgumentException("승인된 레시피를 찾을 수 없습니다."));
        RecipeDefinition definition = recipesByKey.get(approved.getCatalogKey());
        if (definition == null) {
            throw new IllegalStateException("승인 레시피 원본 문서를 찾을 수 없습니다: " + approved.getCatalogKey());
        }
        if (expectedVersion != null && expectedVersion != definition.version()) {
            throw new IllegalStateException("레시피 원본 버전이 변경되었습니다. 레시피를 다시 요청해 주세요.");
        }

        SpiceLevel spiceLevel = requestedSpiceLevel == null ? SpiceLevel.NORMAL : requestedSpiceLevel;
        SpiceProfileDefinition spiceProfile = definition.spiceProfiles().get(spiceLevel.name());
        if (spiceProfile == null) {
            throw new IllegalArgumentException(definition.title() + "에는 검증된 '" + spiceLevel.label
                    + "' 변형이 없습니다. 기본맛으로 요청해 주세요.");
        }

        // 배율 = 요청 인분 / 기준 인분. BigDecimal로 계산해 소수 오차를 줄이고, 재료 양은 소수 첫째 자리에서 반올림합니다.
        // 맵기 프로필에서 양이 0이 된 재료는 목록에서 뺍니다.
        BigDecimal scale = BigDecimal.valueOf(servings)
                .divide(BigDecimal.valueOf(definition.baseServings()), 6, RoundingMode.HALF_UP);
        List<String> renderedIngredients = new ArrayList<>();
        for (IngredientDefinition ingredient : definition.ingredients()) {
            BigDecimal baseAmount = spiceProfile.overrides().getOrDefault(ingredient.key(), ingredient.amount());
            BigDecimal adjusted = baseAmount.multiply(scale).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros();
            if (adjusted.signum() <= 0) {
                continue;
            }
            String preparation = isBlank(ingredient.preparation()) ? "" : " (" + ingredient.preparation() + ")";
            renderedIngredients.add(ingredient.name() + " " + formatAmount(adjusted) + ingredient.unit() + preparation);
        }

        List<String> renderedSteps = definition.steps().stream()
                .sorted(Comparator.comparingInt(StepDefinition::number))
                .map(step -> instructionFor(step, spiceLevel)
                        + " [불: " + step.heat()
                        + " / 시간: " + step.duration()
                        + " / 완료 기준: " + step.doneness() + "]")
                .toList();

        Recipe transformed = new Recipe();
        transformed.setId(approved.getId());
        transformed.setCatalogKey(approved.getCatalogKey());
        transformed.setApprovalStatus(RecipeApprovalStatus.APPROVED);
        transformed.setCatalogVersion(definition.version());
        transformed.setBaseServings(servings);
        transformed.setVerifiedBy(approved.getVerifiedBy());
        transformed.setVerifiedAt(approved.getVerifiedAt());
        transformed.setSourceHash(approved.getSourceHash());
        transformed.setTitle(definition.title());
        transformed.setDescription(definition.description());
        transformed.setIngredients(renderedIngredients);
        transformed.setSteps(renderedSteps);
        transformed.setCalories(definition.caloriesPerServing());
        transformed.setCaloriesPerServing(definition.caloriesPerServing());
        transformed.setDifficulty(definition.difficulty());
        transformed.setCookingTime(definition.cookingTime());
        transformed.setAverageRating(approved.getAverageRating());
        transformed.setImageUrl(approved.getImageUrl());
        transformed.setCreatedAt(approved.getCreatedAt());

        String reply = buildReply(definition, transformed, servings, spiceLevel, spiceProfile);
        return new RenderedRecipe(transformed, reply, servings, spiceLevel, definition.version(), definition.sources());
    }

    // 메시지에서 인분/맵기를 읽어 렌더링합니다. 요청이 없으면 기준 인분과 기본맛을 사용합니다.
    public RenderedRecipe renderApprovedRecipe(Recipe recipe, String requestMessage) {
        AdjustmentRequest adjustment = parseAdjustment(requestMessage).orElse(new AdjustmentRequest(null, null));
        int servings = adjustment.servings() == null ? recipe.getBaseServings() : adjustment.servings();
        SpiceLevel spice = adjustment.spiceLevel() == null ? SpiceLevel.NORMAL : adjustment.spiceLevel();
        return renderApprovedRecipe(recipe.getId(), recipe.getCatalogVersion(), servings, spice);
    }

    public int approvedCatalogSize() {
        ensureCatalogLoaded();
        return recipesByKey.size();
    }

    /**
     * 카탈로그 레시피 하나를 DB에 반영합니다.
     * 원본 JSON의 SHA-256 해시가 바뀌었거나 버전이 달라졌을 때만 검수 시각(verifiedAt)을 새로 기록합니다.
     * 출처(recipe_sources)는 지우고 다시 넣고, 원본 문서(approved_recipe_documents)는 upsert합니다.
     */
    private void synchronizeRecipe(ApprovedRecipeCatalogDefinition loaded, RecipeDefinition definition) {
        String documentJson = writeJson(definition);
        String hash = sha256(documentJson);
        Recipe recipe = recipeRepository.findByCatalogKey(definition.key())
                .or(() -> recipeRepository.findFirstByTitle(definition.title()))
                .orElseGet(Recipe::new);

        boolean newlyVerified = recipe.getApprovalStatus() != RecipeApprovalStatus.APPROVED
                || recipe.getCatalogVersion() == null
                || recipe.getCatalogVersion() != definition.version()
                || !hash.equals(recipe.getSourceHash());
        recipe.setCatalogKey(definition.key());
        recipe.setApprovalStatus(RecipeApprovalStatus.APPROVED);
        recipe.setCatalogVersion(definition.version());
        recipe.setBaseServings(definition.baseServings());
        recipe.setVerifiedBy(loaded.verifiedBy());
        if (newlyVerified || recipe.getVerifiedAt() == null) {
            recipe.setVerifiedAt(LocalDateTime.now(clock));
        }
        recipe.setSourceHash(hash);
        recipe.setTitle(definition.title());
        recipe.setDescription(definition.description());
        recipe.setIngredients(renderBaseIngredients(definition));
        recipe.setSteps(renderBaseSteps(definition));
        recipe.setCalories(definition.caloriesPerServing());
        recipe.setCaloriesPerServing(definition.caloriesPerServing());
        recipe.setDifficulty(definition.difficulty());
        recipe.setCookingTime(definition.cookingTime());
        if (recipe.getAverageRating() == null) {
            recipe.setAverageRating(0.0);
        }
        if (recipe.getCreatedAt() == null) {
            recipe.setCreatedAt(LocalDateTime.now(clock));
        }
        Recipe saved = recipeRepository.saveAndFlush(recipe);

        jdbcTemplate.update("DELETE FROM recipe_sources WHERE recipe_id = ?", saved.getId());
        for (SourceDefinition source : definition.sources()) {
            jdbcTemplate.update("""
                    INSERT INTO recipe_sources (recipe_id, source_type, source_name, source_url, retrieved_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, saved.getId(), source.type(), source.name(), source.url(), LocalDate.parse(source.retrievedAt()));
        }
        jdbcTemplate.update("""
                INSERT INTO approved_recipe_documents
                    (recipe_id, catalog_key, catalog_version, document_json, source_hash, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    recipe_id = VALUES(recipe_id),
                    catalog_version = VALUES(catalog_version),
                    document_json = VALUES(document_json),
                    source_hash = VALUES(source_hash),
                    updated_at = VALUES(updated_at)
                """, saved.getId(), definition.key(), definition.version(), documentJson, hash, LocalDateTime.now(clock));
    }

    // DB에 저장할 기본(기준 인분, 기본맛) 재료 문자열 목록을 만듭니다.
    private List<String> renderBaseIngredients(RecipeDefinition definition) {
        SpiceProfileDefinition normal = definition.spiceProfiles().get("NORMAL");
        List<String> result = new ArrayList<>();
        for (IngredientDefinition ingredient : definition.ingredients()) {
            BigDecimal amount = normal.overrides().getOrDefault(ingredient.key(), ingredient.amount());
            if (amount.signum() <= 0) {
                continue;
            }
            String preparation = isBlank(ingredient.preparation()) ? "" : " (" + ingredient.preparation() + ")";
            result.add(ingredient.name() + " " + formatAmount(amount) + ingredient.unit() + preparation);
        }
        return result;
    }

    // 조리 단계를 "설명 [불: … / 시간: … / 완료 기준: …]" 형태의 문자열로 만듭니다.
    private List<String> renderBaseSteps(RecipeDefinition definition) {
        return definition.steps().stream()
                .sorted(Comparator.comparingInt(StepDefinition::number))
                .map(step -> instructionFor(step, SpiceLevel.NORMAL)
                        + " [불: " + step.heat()
                        + " / 시간: " + step.duration()
                        + " / 완료 기준: " + step.doneness() + "]")
                .toList();
    }

    // 승인 레시피 채팅 답변 텍스트를 만듭니다(검증 정보, 재료, 조리 순서, 검증 출처 포함).
    private String buildReply(
            RecipeDefinition definition,
            Recipe recipe,
            int servings,
            SpiceLevel spiceLevel,
            SpiceProfileDefinition spiceProfile) {
        StringBuilder reply = new StringBuilder();
        reply.append(definition.title()).append(" ").append(servings).append("인분 레시피입니다.\n\n");
        reply.append(definition.description()).append("\n\n");
        reply.append("[검증 정보]\n");
        reply.append("- 카탈로그 버전: v").append(definition.version()).append("\n");
        reply.append("- 맵기: ").append(spiceProfile.label()).append("\n");
        reply.append("- 조리 시간: 약 ").append(definition.cookingTime()).append("분\n");
        reply.append("- 열량: 1인분당 약 ").append(definition.caloriesPerServing()).append("kcal\n\n");
        reply.append("[재료 - ").append(servings).append("인분]\n");
        recipe.getIngredients().forEach(ingredient -> reply.append("- ").append(ingredient).append("\n"));
        reply.append("\n[조리 순서]\n");
        for (int i = 0; i < recipe.getSteps().size(); i++) {
            reply.append(i + 1).append(". ").append(recipe.getSteps().get(i)).append("\n");
        }
        reply.append("\n[검증 출처]\n");
        definition.sources().forEach(source -> reply.append("- ").append(source.name())
                .append(": ").append(source.url()).append("\n"));
        reply.append("\n인분과 맵기 변경은 이 승인 원본에서 다시 계산하며 이전 답변의 반올림값을 재사용하지 않습니다.");
        if (spiceLevel == SpiceLevel.EXTREME) {
            reply.append(" 매우 매운 음식은 개인별 자극 차이가 크므로 처음에는 소량을 맛본 뒤 드세요.");
        }
        return reply.toString().trim();
    }

    // 아직 카탈로그를 읽지 않았다면(예: 테스트에서 run을 거치지 않음) 한 번만 읽어 캐시합니다.
    private synchronized void ensureCatalogLoaded() {
        if (catalog == null) {
            cache(loadAndValidateCatalog());
        }
    }

    // 클래스패스의 JSON 파일을 읽어 객체로 바꾸고 검증합니다. 실패하면 IllegalStateException입니다.
    private ApprovedRecipeCatalogDefinition loadAndValidateCatalog() {
        try {
            ApprovedRecipeCatalogDefinition loaded = objectMapper.readValue(
                    new ClassPathResource(CATALOG_PATH).getInputStream(),
                    ApprovedRecipeCatalogDefinition.class);
            validateCatalog(loaded);
            return loaded;
        } catch (Exception error) {
            throw new IllegalStateException("승인 레시피 카탈로그를 읽거나 검증하지 못했습니다.", error);
        }
    }

    // 검증된 카탈로그를 key/별칭 조회용 자료구조로 만들어 한 번에 교체합니다.
    private void cache(ApprovedRecipeCatalogDefinition loaded) {
        Map<String, RecipeDefinition> byKey = new LinkedHashMap<>();
        List<AliasEntry> aliasEntries = new ArrayList<>();
        for (RecipeDefinition definition : loaded.recipes()) {
            byKey.put(definition.key(), definition);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            names.add(definition.title());
            names.addAll(definition.aliases());
            names.stream()
                    .map(this::normalizeLookup)
                    .filter(name -> !name.isBlank())
                    .forEach(name -> aliasEntries.add(new AliasEntry(name, definition.key())));
        }
        aliasEntries.sort(Comparator.comparingInt((AliasEntry entry) -> entry.normalizedAlias().length()).reversed());
        this.catalog = loaded;
        this.recipesByKey = Map.copyOf(byKey);
        this.aliases = List.copyOf(aliasEntries);
    }

    /**
     * 카탈로그 전체를 검증합니다. 문제가 하나라도 있으면 IllegalArgumentException을 던집니다.
     * 전체 규칙: 버전/검수자 필수, 레시피 정확히 10개, key/제목/별칭 중복 금지
     */
    void validateCatalog(ApprovedRecipeCatalogDefinition loaded) {
        if (loaded == null || loaded.catalogVersion() < 1 || isBlank(loaded.verifiedBy())) {
            throw new IllegalArgumentException("카탈로그 버전과 검증자를 입력해야 합니다.");
        }
        if (loaded.recipes() == null || loaded.recipes().size() != REQUIRED_RECIPE_COUNT) {
            throw new IllegalArgumentException("초기 승인 카탈로그는 정확히 10개여야 합니다.");
        }

        Set<String> keys = new HashSet<>();
        Set<String> titles = new HashSet<>();
        Set<String> allAliases = new HashSet<>();
        for (RecipeDefinition recipe : loaded.recipes()) {
            validateRecipe(recipe, keys, titles, allAliases);
        }
    }

    /**
     * 레시피 하나를 검증합니다.
     * - 기준 인분 2, 재료 5개 이상, 조리 단계 4개 이상, 단계 번호 1부터 연속
     * - 모든 재료가 어떤 단계에서든 사용되어야 하고, 단계는 존재하는 재료만 참조
     * - 단계별 최대 시간 합계가 총 조리 시간을 넘지 않고, 설명되지 않은 시간이 10분 이하
     * - NORMAL 맵기 프로필 필수, 출처 2개 이상, 식품안전 기준 충족
     */
    private void validateRecipe(
            RecipeDefinition recipe,
            Set<String> keys,
            Set<String> titles,
            Set<String> allAliases) {
        if (recipe == null || isBlank(recipe.key()) || isBlank(recipe.title()) || isBlank(recipe.description())) {
            throw new IllegalArgumentException("레시피 key, title, description은 필수입니다.");
        }
        if (!keys.add(recipe.key()) || !titles.add(normalizeLookup(recipe.title()))) {
            throw new IllegalArgumentException("레시피 key 또는 title이 중복되었습니다: " + recipe.title());
        }
        if (recipe.version() < 1 || recipe.baseServings() != 2) {
            throw new IllegalArgumentException(recipe.title() + "의 버전은 1 이상, 기준 인분은 2여야 합니다.");
        }
        if (recipe.cookingTime() < 1 || recipe.difficulty() < 1 || recipe.caloriesPerServing() < 1) {
            throw new IllegalArgumentException(recipe.title() + "의 시간·난이도·열량이 올바르지 않습니다.");
        }
        Set<String> recipeAliases = new LinkedHashSet<>();
        recipeAliases.add(normalizeLookup(recipe.title()));
        for (String alias : recipe.aliases()) {
            String normalized = normalizeLookup(alias);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("레시피 별칭이 비어 있습니다: " + alias);
            }
            recipeAliases.add(normalized);
        }
        for (String alias : recipeAliases) {
            if (!allAliases.add(alias)) {
                throw new IllegalArgumentException("다른 레시피와 제목/별칭이 중복되었습니다: " + alias);
            }
        }

        if (recipe.ingredients() == null || recipe.ingredients().size() < 5) {
            throw new IllegalArgumentException(recipe.title() + "의 재료는 5개 이상이어야 합니다.");
        }
        Set<String> ingredientKeys = new LinkedHashSet<>();
        Map<String, IngredientDefinition> ingredientsByKey = new LinkedHashMap<>();
        for (IngredientDefinition ingredient : recipe.ingredients()) {
            if (isBlank(ingredient.key()) || isBlank(ingredient.name()) || isBlank(ingredient.unit())
                    || ingredient.amount() == null || ingredient.amount().signum() < 0
                    || !ingredientKeys.add(ingredient.key())) {
                throw new IllegalArgumentException(recipe.title() + "의 재료 정의가 올바르지 않습니다.");
            }
            ingredientsByKey.put(ingredient.key(), ingredient);
        }

        if (recipe.steps() == null || recipe.steps().size() < 4) {
            throw new IllegalArgumentException(recipe.title() + "의 조리 단계는 4개 이상이어야 합니다.");
        }
        Set<String> referencedIngredients = new HashSet<>();
        int maximumStepMinutes = 0;
        for (int i = 0; i < recipe.steps().size(); i++) {
            StepDefinition step = recipe.steps().get(i);
            if (step.number() != i + 1 || isBlank(step.instruction()) || isBlank(step.heat())
                    || isBlank(step.duration()) || isBlank(step.doneness())
                    || step.ingredientKeys() == null || step.ingredientKeys().isEmpty()) {
                throw new IllegalArgumentException(recipe.title() + "의 " + (i + 1) + "단계가 불완전합니다.");
            }
            validateUserFacingStepText(recipe, step.number(), step.instruction(), step.doneness());
            if (step.instructionOverrides() != null) {
                for (Map.Entry<String, String> override : step.instructionOverrides().entrySet()) {
                    SpiceLevel.valueOf(override.getKey());
                    validateUserFacingStepText(recipe, step.number(), override.getValue(), step.doneness());
                }
            }
            maximumStepMinutes += maximumDurationMinutes(recipe, step);
            for (String ingredientKey : step.ingredientKeys()) {
                if (!ingredientKeys.contains(ingredientKey)) {
                    throw new IllegalArgumentException(recipe.title() + " 단계가 없는 재료를 참조합니다: " + ingredientKey);
                }
                referencedIngredients.add(ingredientKey);
            }
        }
        if (!referencedIngredients.containsAll(ingredientKeys)) {
            Set<String> missing = new LinkedHashSet<>(ingredientKeys);
            missing.removeAll(referencedIngredients);
            throw new IllegalArgumentException(recipe.title() + " 조리 단계에서 사용하지 않은 재료: " + missing);
        }
        if (maximumStepMinutes > recipe.cookingTime()) {
            throw new IllegalArgumentException(recipe.title() + "의 총 시간보다 단계별 최대 시간 합계가 큽니다: "
                    + recipe.cookingTime() + "분 / " + maximumStepMinutes + "분");
        }
        if (recipe.cookingTime() - maximumStepMinutes > MAX_UNACCOUNTED_MINUTES) {
            throw new IllegalArgumentException(recipe.title() + "의 총 시간에 설명되지 않은 시간이 너무 많습니다: "
                    + recipe.cookingTime() + "분 / 단계 합계 " + maximumStepMinutes + "분");
        }

        if (recipe.spiceProfiles() == null || !recipe.spiceProfiles().containsKey("NORMAL")) {
            throw new IllegalArgumentException(recipe.title() + "에 NORMAL 맵기 프로필이 없습니다.");
        }
        for (Map.Entry<String, SpiceProfileDefinition> entry : recipe.spiceProfiles().entrySet()) {
            SpiceLevel.valueOf(entry.getKey());
            SpiceProfileDefinition profile = entry.getValue();
            if (profile == null || isBlank(profile.label()) || profile.overrides() == null) {
                throw new IllegalArgumentException(recipe.title() + "의 맵기 프로필이 불완전합니다.");
            }
            profile.overrides().forEach((key, amount) -> {
                if (!ingredientKeys.contains(key) || amount == null || amount.signum() < 0) {
                    throw new IllegalArgumentException(recipe.title() + "의 맵기 재료 조정이 올바르지 않습니다: " + key);
                }
            });
        }
        for (StepDefinition step : recipe.steps()) {
            if (step.instructionOverrides() == null) {
                continue;
            }
            for (String profileName : step.instructionOverrides().keySet()) {
                if (!recipe.spiceProfiles().containsKey(profileName)) {
                    throw new IllegalArgumentException(recipe.title() + " 단계가 없는 맵기 프로필을 참조합니다: " + profileName);
                }
            }
        }

        validateRenderedSpiceProfiles(recipe, ingredientsByKey);

        validateSources(recipe);
        validateFoodSafety(recipe, ingredientsByKey);
    }

    // 출처는 2개 이상, 서로 다른 도메인이어야 하고 https URL, 이름/종류, 미래가 아닌 확인 날짜가 필요합니다.
    private void validateSources(RecipeDefinition recipe) {
        if (recipe.sources() == null || recipe.sources().size() < 2) {
            throw new IllegalArgumentException(recipe.title() + "은 서로 다른 출처가 2개 이상 필요합니다.");
        }
        Set<String> domains = new HashSet<>();
        Set<String> sourceUrls = new HashSet<>();
        for (SourceDefinition source : recipe.sources()) {
            try {
                URI uri = URI.create(source.url());
                LocalDate retrievedAt = LocalDate.parse(source.retrievedAt());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || isBlank(uri.getHost())
                        || isBlank(source.name()) || isBlank(source.type())
                        || retrievedAt.isAfter(LocalDate.now(clock))
                        || !sourceUrls.add(uri.normalize().toString())) {
                    throw new IllegalArgumentException();
                }
                domains.add(uri.getHost().toLowerCase(Locale.ROOT));
            } catch (Exception error) {
                throw new IllegalArgumentException(recipe.title() + "의 출처가 올바르지 않습니다: " + source.url(), error);
            }
        }
        if (domains.size() < 2) {
            throw new IllegalArgumentException(recipe.title() + "은 서로 다른 도메인의 출처가 2개 이상 필요합니다.");
        }
    }

    /**
     * 맵기 프로필별로 실제 렌더링했을 때 모순이 없는지 검사합니다.
     * 어떤 프로필에서는 빠지는(양 0) 재료가 있다면, 그 재료가 들어가는 프로필의 조리 설명에는 재료가 언급되어야 하고
     * 빠지는 프로필의 조리 설명에는 언급되면 안 됩니다.
     */
    private void validateRenderedSpiceProfiles(
            RecipeDefinition recipe,
            Map<String, IngredientDefinition> ingredientsByKey) {
        Set<String> conditionalKeys = new LinkedHashSet<>();
        for (IngredientDefinition ingredient : recipe.ingredients()) {
            boolean hasZeroAmount = false;
            boolean hasPositiveAmount = false;
            for (SpiceProfileDefinition profile : recipe.spiceProfiles().values()) {
                BigDecimal amount = profile.overrides().getOrDefault(ingredient.key(), ingredient.amount());
                hasZeroAmount |= amount.signum() == 0;
                hasPositiveAmount |= amount.signum() > 0;
            }
            if (!hasPositiveAmount) {
                throw new IllegalArgumentException(recipe.title() + "의 모든 맵기 프로필에서 제외되는 재료가 있습니다: "
                        + ingredient.name());
            }
            if (hasZeroAmount) {
                conditionalKeys.add(ingredient.key());
            }
        }

        for (Map.Entry<String, SpiceProfileDefinition> entry : recipe.spiceProfiles().entrySet()) {
            SpiceLevel level = SpiceLevel.valueOf(entry.getKey());
            SpiceProfileDefinition profile = entry.getValue();
            String renderedInstructions = normalizeLookup(recipe.steps().stream()
                    .map(step -> instructionFor(step, level))
                    .reduce("", (left, right) -> left + " " + right));
            for (String key : conditionalKeys) {
                IngredientDefinition ingredient = ingredientsByKey.get(key);
                BigDecimal amount = profile.overrides().getOrDefault(key, ingredient.amount());
                boolean instructionMentionsIngredient = renderedInstructions.contains(normalizeLookup(ingredient.name()));
                if (amount.signum() > 0 && !instructionMentionsIngredient) {
                    throw new IllegalArgumentException(recipe.title() + " " + level.label
                            + " 단계에서 활성 재료를 사용하지 않습니다: " + ingredient.name());
                }
                if (amount.signum() == 0 && instructionMentionsIngredient) {
                    throw new IllegalArgumentException(recipe.title() + " " + level.label
                            + " 단계가 제외된 재료를 언급합니다: " + ingredient.name());
                }
            }
        }
    }

    // 사용자에게 보이는 조리 설명에 내부 작성 규칙이나 내부 안전 온도(℃)가 노출되지 않았는지 검사합니다.
    private void validateUserFacingStepText(
            RecipeDefinition recipe,
            int stepNumber,
            String instruction,
            String doneness) {
        if (isBlank(instruction) || LEAKED_AUTHORING_RULE_PATTERN.matcher(instruction).find()) {
            throw new IllegalArgumentException(recipe.title() + "의 " + stepNumber
                    + "단계에 사용자에게 노출하면 안 되는 작성 규칙이 있습니다.");
        }
        if (doneness.contains("°C") || doneness.contains("℃")) {
            throw new IllegalArgumentException(recipe.title() + "의 " + stepNumber
                    + "단계 완료 기준에 내부 안전 온도가 노출되어 있습니다.");
        }
    }

    // 단계 시간 문자열에서 최대 분 값을 읽습니다. 분으로 해석할 수 없으면 카탈로그 오류입니다.
    private int maximumDurationMinutes(RecipeDefinition recipe, StepDefinition step) {
        Matcher matcher = DURATION_MINUTES_PATTERN.matcher(step.duration());
        if (!matcher.find()) {
            throw new IllegalArgumentException(recipe.title() + "의 " + step.number()
                    + "단계 시간을 분 단위로 해석할 수 없습니다: " + step.duration());
        }
        return matcher.group(2) == null
                ? Integer.parseInt(matcher.group(1))
                : Integer.parseInt(matcher.group(2));
    }

    /**
     * 식품안전 내부 기준을 검사합니다.
     * 닭고기 74℃, 달걀 71℃, 돼지고기/소고기 63℃·3분 이상 기준이 재료별로 기록되어 있어야 합니다.
     */
    private void validateFoodSafety(
            RecipeDefinition recipe,
            Map<String, IngredientDefinition> ingredientsByKey) {
        Map<String, SafetyCheckDefinition> checksByIngredient = new LinkedHashMap<>();
        List<SafetyCheckDefinition> safetyChecks = recipe.safetyChecks() == null ? List.of() : recipe.safetyChecks();
        for (SafetyCheckDefinition check : safetyChecks) {
            if (check == null || !ingredientsByKey.containsKey(check.ingredientKey())
                    || check.minimumInternalTemperatureC() < 1 || check.minimumHoldMinutes() < 0
                    || checksByIngredient.putIfAbsent(check.ingredientKey(), check) != null) {
                throw new IllegalArgumentException(recipe.title() + "의 내부 식품안전 검증값이 올바르지 않습니다.");
            }
        }

        for (IngredientDefinition ingredient : recipe.ingredients()) {
            SafetyCheckDefinition check = checksByIngredient.get(ingredient.key());
            String name = ingredient.name();
            if (name.contains("닭") && (check == null || check.minimumInternalTemperatureC() < 74)) {
                throw new IllegalArgumentException(recipe.title() + "에 닭고기 내부 안전 기준 74°C가 없습니다.");
            }
            if (name.contains("달걀") && (check == null || check.minimumInternalTemperatureC() < 71)) {
                throw new IllegalArgumentException(recipe.title() + "에 달걀 요리 내부 안전 기준 71°C가 없습니다.");
            }
            if ((name.contains("돼지고기") || name.contains("소고기"))
                    && (check == null || check.minimumInternalTemperatureC() < 63
                    || check.minimumHoldMinutes() < 3)) {
                throw new IllegalArgumentException(recipe.title() + "에 육류 내부 안전 기준 63°C·3분이 없습니다.");
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("레시피 원본 JSON 직렬화에 실패했습니다.", error);
        }
    }

    // 문자열의 SHA-256 해시를 16진수 문자열로 만듭니다(원본 변경 감지용).
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("레시피 원본 해시 생성에 실패했습니다.", error);
        }
    }

    // 맵기 단계별로 다르게 쓴 조리 설명이 있으면 그것을, 없으면 기본 설명을 사용합니다.
    private String instructionFor(StepDefinition step, SpiceLevel spiceLevel) {
        if (step.instructionOverrides() == null) {
            return step.instruction();
        }
        return step.instructionOverrides().getOrDefault(spiceLevel.name(), step.instruction());
    }

    // 이름 비교용 정규화: 소문자, 흔한 오타(찌게→찌개) 교정, 한글/영문/숫자 외 문자 제거
    private String normalizeLookup(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("찌게", "찌개")
                .replaceAll("[^가-힣a-z0-9]", "");
    }

    private String formatAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // 맵기 단계와 화면 표시 이름
    public enum SpiceLevel {
        MILD("순한맛"),
        NORMAL("기본맛"),
        SPICY("매운맛"),
        EXTREME("불닭 수준을 목표로 한 매우 매운맛");

        private final String label;

        SpiceLevel(String label) {
            this.label = label;
        }
    }

    // 사용자 조정 요청(인분 수, 맵기). 요청하지 않은 값은 null입니다.
    public record AdjustmentRequest(Integer servings, SpiceLevel spiceLevel) {
    }

    // 렌더링 결과: 화면용 레시피, 답변 텍스트, 적용한 인분/맵기, 원본 버전, 출처
    public record RenderedRecipe(
            Recipe recipe,
            String reply,
            int servings,
            SpiceLevel spiceLevel,
            int version,
            List<SourceDefinition> sources) {
    }

    // 정규화된 별칭과 레시피 key 한 쌍
    private record AliasEntry(String normalizedAlias, String catalogKey) {
    }
}
