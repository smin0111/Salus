package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 내 레시피입니다(user_recipes 테이블). 나만 볼 수 있습니다.
 *
 * <p>원본(originalSnapshot)·출처(sourcesJson)·만든 방식은 처음 저장할 때 정해지고 바뀌지 않습니다.
 * 이 클래스는 그 필드들의 setter를 두지 않고, 사용자 수정은 {@link #applyEdit}로 지금 버전·제목·수정 메모만 바꿉니다.
 * (@Column(updatable = false)는 실수로 UPDATE에 섞이지 않게 하는 보조 장치일 뿐 DB 수준의 잠금은 아닙니다.)
 */
@Entity
@Table(name = "user_recipes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRecipe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "origin_type", nullable = false, length = 20, updatable = false)
    private UserRecipeOrigin originType;

    // 원래 채팅 카드입니다. 대화를 지우면 DB가 NULL로 바꿉니다(내 레시피는 남음).
    @Column(name = "source_card_id", updatable = false)
    private Long sourceCardId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 40, updatable = false)
    private RecipeCardProvenance provenance;

    // 채팅 카드면 서버가 보낸 카드 JSON 그대로, 직접 작성이면 처음 작성한 내용(MyRecipeContent)입니다.
    @Column(name = "original_snapshot", nullable = false, columnDefinition = "JSON", updatable = false)
    private String originalSnapshot;

    @Column(name = "sources_json", nullable = false, columnDefinition = "JSON", updatable = false)
    private String sourcesJson;

    // 목록용 제목입니다(200자까지). 전체 제목은 currentRecipe에 자르지 않고 있습니다.
    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "current_recipe", nullable = false, columnDefinition = "JSON")
    private String currentRecipe;

    @Column(name = "modification_note", length = 500)
    private String modificationNote;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static UserRecipe fromChatCard(Long userId, Long sourceCardId, RecipeCardProvenance provenance,
                                          String cardJson, String sourcesJson, String title, String currentRecipe,
                                          LocalDateTime now) {
        UserRecipe recipe = new UserRecipe();
        recipe.userId = userId;
        recipe.originType = UserRecipeOrigin.CHAT_CARD;
        recipe.sourceCardId = sourceCardId;
        recipe.provenance = provenance;
        recipe.originalSnapshot = cardJson;
        recipe.sourcesJson = sourcesJson;
        recipe.title = title;
        recipe.currentRecipe = currentRecipe;
        recipe.createdAt = now;
        recipe.updatedAt = now;
        return recipe;
    }

    public static UserRecipe manual(Long userId, String title, String contentJson, String modificationNote,
                                    LocalDateTime now) {
        UserRecipe recipe = new UserRecipe();
        recipe.userId = userId;
        recipe.originType = UserRecipeOrigin.MANUAL;
        recipe.originalSnapshot = contentJson;
        recipe.sourcesJson = "[]";
        recipe.title = title;
        recipe.currentRecipe = contentJson;
        recipe.modificationNote = modificationNote;
        recipe.createdAt = now;
        recipe.updatedAt = now;
        return recipe;
    }

    /** 사용자가 바꿀 수 있는 것은 지금 버전(제목 포함)과 수정 메모뿐입니다. */
    public void applyEdit(String title, String currentRecipe, String modificationNote, LocalDateTime now) {
        this.title = title;
        this.currentRecipe = currentRecipe;
        this.modificationNote = modificationNote;
        this.updatedAt = now;
    }
}
