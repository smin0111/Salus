package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 웹에서 찾은 레시피 근거입니다(web_recipe_sources 테이블).
 *
 * <p>레시피 카탈로그(recipes)가 아니라 근거 자료입니다. 같은 요리를 다시 검색하지 않게 하고(검색 엔진 차단 방지),
 * 사람 검토를 기다리는 대기열 역할을 합니다. 재료·분량, 요약한 조리 단계, 원문 링크만 담고 원문 조리 문장은
 * 담지 않습니다. 사용자별 알레르기 판정은 저장하지 않습니다.
 */
@Entity
@Table(name = "web_recipe_sources")
@Data
@NoArgsConstructor
public class WebRecipeSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 요청 요리·작성자·모드로 만든 조회 키(InMemoryRecipeSourceCache.key와 같은 형식)
    @Column(name = "cache_key", nullable = false, length = 200)
    private String cacheKey;
    @Column(name = "source_url", nullable = false, length = 1000)
    private String sourceUrl;
    @Column(name = "source_domain", nullable = false, length = 200)
    private String sourceDomain;
    @Column(name = "source_title", nullable = false, length = 300)
    private String sourceTitle;
    // SCHEMA_ORG_JSON_LD(구조화 데이터) 또는 LLM_TEXT_EXTRACTION(본문 추출)
    @Column(name = "extraction_method", nullable = false, length = 40)
    private String extractionMethod;
    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "ingredients", nullable = false)
    private List<String> ingredients;
    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "step_summaries", nullable = false)
    private List<String> stepSummaries;
    @Column(name = "content_hash", length = 128)
    private String contentHash;
    @Column(name = "quality_score", nullable = false)
    private Double qualityScore;
    // 사람 검토 상태. 기본은 UNVERIFIED이고, 정식 승인은 approved-recipes.json 카탈로그로 옮기는 것입니다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "review_status", nullable = false, length = 20)
    private RecipeApprovalStatus reviewStatus = RecipeApprovalStatus.UNVERIFIED;
    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
