package com.salus.healthytable.repository;

import com.salus.healthytable.domain.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    Optional<UserBlock> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    long countByBlockerId(Long blockerId);

    List<UserBlock> findByBlockerIdOrderByIdDesc(Long blockerId);

    /** ids 가운데 blocker가 차단한 사용자 ID(댓글 작성자·답글 대상 이름 처리에서 한 번에 조회). */
    @Query("SELECT b.blockedId FROM UserBlock b WHERE b.blockerId = :blockerId AND b.blockedId IN :ids")
    List<Long> findBlockedAmong(@Param("blockerId") Long blockerId, @Param("ids") Collection<Long> ids);

    @Modifying
    @Query("DELETE FROM UserBlock b WHERE b.id = :id AND b.blockerId = :blockerId")
    int deleteOwn(@Param("id") Long id, @Param("blockerId") Long blockerId);

    /**
     * 차단한 사람의 users 행을 잠급니다(SELECT ... FOR UPDATE). 같은 사람의 동시 차단 요청이 최대 인원 검사·저장을
     * 차례로 하게 합니다. 트랜잭션 안에서만 부릅니다.
     */
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockBlocker(@Param("userId") Long userId);
}
