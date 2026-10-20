package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM이 생성한 레시피와 그 검증 결과를 남기는 감사(audit) 기록 엔티티입니다(generated_recipes 테이블).
 *
 * 생성에 성공한 레시피뿐 아니라 검증에 실패한 시도도 저장해,
 * "어떤 검색 근거로, 몇 번째 시도에서, 왜 실패/성공했는지"를 나중에 분석할 수 있게 합니다.
 */
@Entity
@Table(name = "generated_recipes")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GeneratedRecipe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String description;

    @Convert(converter = JsonStringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> ingredients;

    @Convert(converter = JsonStringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> steps;

    // calories는 기존 호환용 값이고, 1인분 기준 열량은 caloriesPerServing에 저장합니다.
    private Integer calories;
    @Column(name = "calories_per_serving")
    private Integer caloriesPerServing;
    private Integer difficulty;

    @Column(name = "cooking_time")
    private Integer cookingTime;
    private Integer servings;

    // 레시피 생성에 사용한 검색어와 검색 결과 본문(LLM에 근거로 넘긴 자료)
    @Column(name = "search_query")
    private String searchQuery;

    @Column(name = "search_context", columnDefinition = "LONGTEXT")
    private String searchContext;

    // LLM이 돌려준 원본 응답
    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    private String source;

    // 검증 결과: 신뢰도 점수, 금지 재료(알레르기 등) 포함 여부, 최종 통과 여부와 그 이유
    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "has_forbidden_ingredients")
    private Boolean hasForbiddenIngredients;

    private Boolean valid;

    @Column(name = "validation_reason", columnDefinition = "TEXT")
    private String validationReason;

    @Column(name = "validation_details", columnDefinition = "LONGTEXT")
    private String validationDetails;

    // 어떤 버전의 검증 규칙으로 판정했는지 기록합니다. 규칙이 바뀌어도 과거 기록을 올바르게 해석할 수 있습니다.
    @Column(name = "validator_version")
    private String validatorVersion;

    // 몇 번째 생성 시도인지(1부터 시작, 복구(repair) 재시도 시 증가)
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber = 1;

    // 파이프라인의 어느 단계에서 기록되었는지, 실패했다면 어떤 실패 코드들이 나왔는지
    @Column(name = "generation_stage")
    private String generationStage;

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "failure_codes", columnDefinition = "JSON")
    private List<String> failureCodes;

    // 생성에 걸린 시간(밀리초)
    @Column(name = "generation_ms")
    private Long generationMs;

    // 검증 실패 후 복구(repair) 시도를 사용했는지 여부
    @Column(name = "repair_used", nullable = false)
    private Boolean repairUsed = false;

    @Column(name = "final_status")
    private String finalStatus;

    // insertable/updatable=false: 이 값은 애플리케이션이 아니라 DB 기본값(CURRENT_TIMESTAMP)이 채웁니다.
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
