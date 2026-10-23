package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 채팅에서 사용자에게 보낸 레시피 카드입니다(chat_recipe_cards 테이블).
 *
 * <p>cardJson은 서버가 응답으로 보낸 카드 객체를 그대로 직렬화한 값이고, 앱에는 publicId를 cardId로 줍니다.
 * sourcesJson은 카드의 출처 정보(역할·종류)와, AI 생성 때 검색되어 제공된 자료(RETRIEVED, 실제 인용 아님)를 담습니다.
 */
@Entity
@Table(name = "chat_recipe_cards")
@Getter
@Setter
@NoArgsConstructor
public class ChatRecipeCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 36)
    private String publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "chat_session_id", nullable = false)
    private Long chatSessionId;

    @Column(name = "chat_message_id", nullable = false, unique = true)
    private Long chatMessageId;

    // 대화 요청으로 고친 카드면 고치기 전 카드입니다(같은 사용자·대화의 카드만, 서버가 확인해 넣음).
    @Column(name = "parent_card_id")
    private Long parentCardId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    private RecipeCardProvenance provenance;

    @Column(name = "card_json", nullable = false, columnDefinition = "JSON")
    private String cardJson;

    @Column(name = "sources_json", nullable = false, columnDefinition = "JSON")
    private String sourcesJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
