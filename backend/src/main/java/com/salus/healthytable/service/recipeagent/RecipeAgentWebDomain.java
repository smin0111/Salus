package com.salus.healthytable.service.recipeagent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/*
 * Recipe Agent의 웹 레시피 검색/수집 과정에서 쓰는 타입 모음입니다.
 */

// 웹 검색 포트: 검색어 목록으로 최대 maxResults개의 검색 결과를 반환합니다.
interface WebRecipeSearchPort {

    List<WebRecipeSearchResult> search(List<String> queries, int maxResults);
}

// 웹 검색 결과 한 건(제목, URL, 요약, 도메인, 순위)
record WebRecipeSearchResult(
        String title,
        String url,
        String snippet,
        String domain,
        Integer rank
) {
}

// 외부 웹 페이지를 안전하게(내부망 접근 차단, 크기/시간 제한) 가져오는 포트입니다.
interface SafeWebPageFetcher {

    WebPageFetchResult fetch(String url);
}

// 가져온 페이지 정보(최종 URL, HTTP 상태, 콘텐츠 타입, 본문, 가져온 시각, 본문 해시)
record WebPageFetchResult(
        String finalUrl,
        int statusCode,
        String contentType,
        String body,
        LocalDateTime fetchedAt,
        String contentHash
) {
}

// 출처 조사 상태: 검증된 출처 찾음 / 찾았지만 정보 부족 / 신뢰할 출처 없음 / 가져오기 실패 / 추출 실패
enum RecipeResearchStatus {
    VERIFIED_SOURCE_FOUND,
    SOURCE_FOUND_BUT_INCOMPLETE,
    NO_RELIABLE_SOURCE,
    FETCH_FAILED,
    EXTRACTION_FAILED
}

// 웹 페이지(주로 schema.org JSON-LD)에서 추출한 레시피 근거(제목, 작성자, 인분, 시간, 재료, 조리 단계, 영양 정보, 출처 기록)
record ExtractedRecipeEvidence(
        String title,
        String creatorName,
        String description,
        Integer servings,
        Duration prepTime,
        Duration cookTime,
        Duration totalTime,
        LocalDateTime publishedAt,
        List<ExtractedIngredientLine> ingredients,
        List<ExtractedInstructionStep> steps,
        ExtractedNutrition nutrition,
        List<String> suitableForDiets,
        RecipeEvidenceProvenance provenance
) {
    ExtractedRecipeEvidence {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        steps = steps == null ? List.of() : List.copyOf(steps);
        suitableForDiets = suitableForDiets == null ? List.of() : List.copyOf(suitableForDiets);
    }
}

// 재료 한 줄의 원문과 파싱 결과. parseStatus로 파싱이 완전/부분/실패인지 구분합니다.
record ExtractedIngredientLine(
        String originalText,
        String normalizedName,
        Double amount,
        String unit,
        String preparation,
        IngredientParseStatus parseStatus
) {
}

enum IngredientParseStatus {
    FULL,
    PARTIAL,
    UNPARSED
}

// 조리 단계 한 개(순서, 이름, 설명)
record ExtractedInstructionStep(
        Integer position,
        String name,
        String text
) {
}

// 영양 정보(원문 문자열 그대로 보관)
record ExtractedNutrition(
        String calories,
        String carbohydrateContent,
        String proteinContent,
        String fatContent,
        String sodiumContent,
        String sugarContent
) {
}

// 근거의 출처 기록(원본/정규 URL, 도메인, 추출 방식, 가져온 시각, 해시, 추출한 JSON 경로). 나중에 근거를 추적할 수 있게 합니다.
record RecipeEvidenceProvenance(
        String sourceUrl,
        String canonicalUrl,
        String sourceDomain,
        String extractionMethod,
        LocalDateTime fetchedAt,
        String contentHash,
        List<String> extractedJsonPaths
) {
    RecipeEvidenceProvenance {
        extractedJsonPaths = extractedJsonPaths == null ? List.of() : List.copyOf(extractedJsonPaths);
    }
}

// 출처 품질 점수. blockingReasons가 하나라도 있으면 사용할 수 없는 출처입니다.
record RecipeSourceQualityScore(
        double totalScore,
        boolean structuredRecipePresent,
        boolean ingredientsPresent,
        boolean instructionsPresent,
        boolean creatorMatched,
        boolean dishMatched,
        List<String> warnings,
        List<String> blockingReasons
) {
    RecipeSourceQualityScore {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }

    boolean usable() {
        return blockingReasons.isEmpty();
    }
}

// 캐시에 저장하는 출처 근거(만료 시각 포함)
record CachedRecipeEvidence(
        RecipeSourceDocument source,
        RecipeCandidate originalRecipe,
        RecipeSourceQualityScore qualityScore,
        String contentHash,
        LocalDateTime cachedAt,
        LocalDateTime expiresAt
) {
}

// 품질 평가까지 마친 출처 후보(출처, 원본 레시피, 품질 점수, 추출 근거)
record RecipeSourceCandidate(
        RecipeSourceDocument source,
        RecipeCandidate originalRecipe,
        RecipeSourceQualityScore qualityScore,
        ExtractedRecipeEvidence evidence
) {
}
