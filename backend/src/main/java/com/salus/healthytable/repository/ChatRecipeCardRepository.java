package com.salus.healthytable.repository;

import com.salus.healthytable.domain.ChatRecipeCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * {@link ChatRecipeCard} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface ChatRecipeCardRepository extends JpaRepository<ChatRecipeCard, Long> {
    // 대화 복원: 메시지 목록에 붙일 카드를 한 번에 조회합니다.
    List<ChatRecipeCard> findByChatMessageIdIn(Collection<Long> chatMessageIds);

}
