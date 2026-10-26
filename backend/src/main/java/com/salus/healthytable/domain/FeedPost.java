package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 피드 공유본입니다(feed_posts 테이블). 내 레시피를 공유하는 순간의 내용을 복사한 것이라 이후 수정하지 않습니다.
 *
 * <p>snapshotJson은 공유 당시 전체 내용(조리 단계 포함)이고 그대로 응답하지 않습니다. 공개 응답은 서비스가 stepsPublic 등
 * 출처 규칙에 따라 허용한 필드만 구성합니다. 보는 사람의 알레르기·판정 결과는 여기에 저장하지 않습니다.
 * sourceUserRecipeId는 원래 내 레시피를 가리키며, 그 레시피를 지우면 NULL이 되고 공유본은 남습니다.
 */
@Entity
@Table(name = "feed_posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedPost {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "source_user_recipe_id", updatable = false)
    private Long sourceUserRecipeId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "origin_type", nullable = false, length = 20, updatable = false)
    private UserRecipeOrigin originType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 40, updatable = false)
    private RecipeCardProvenance provenance;

    @Column(name = "steps_public", nullable = false, updatable = false)
    private boolean stepsPublic;

    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "JSON", updatable = false)
    private String snapshotJson;

    @Column(name = "sources_json", nullable = false, columnDefinition = "JSON", updatable = false)
    private String sourcesJson;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "client_request_id", length = 36, updatable = false)
    private String clientRequestId;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 관리자 숨김(V25). 작성자 삭제(행 삭제)와 별개이며 관리자 조치로만 바뀝니다.
    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    public static FeedPost share(Long userId, Long sourceUserRecipeId, UserRecipeOrigin originType,
                                 RecipeCardProvenance provenance, boolean stepsPublic, String title, String snapshotJson,
                                 String sourcesJson, String note, String clientRequestId, String requestHash,
                                 LocalDateTime now) {
        FeedPost post = new FeedPost();
        post.userId = userId;
        post.sourceUserRecipeId = sourceUserRecipeId;
        post.originType = originType;
        post.provenance = provenance;
        post.stepsPublic = stepsPublic;
        post.title = title;
        post.snapshotJson = snapshotJson;
        post.sourcesJson = sourcesJson;
        post.note = note;
        post.clientRequestId = clientRequestId;
        post.requestHash = requestHash;
        post.createdAt = now;
        return post;
    }

    /** 관리자 숨김. 이미 숨겼으면 그대로입니다. */
    public void hide(LocalDateTime now) {
        if (!hidden) {
            hidden = true;
            hiddenAt = now;
        }
    }

    /** 관리자 숨김 해제. */
    public void unhide() {
        hidden = false;
        hiddenAt = null;
    }
}
