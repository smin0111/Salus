package com.salus.healthytable.repository;

import com.salus.healthytable.domain.FeedPost;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FeedPostRepository extends JpaRepository<FeedPost, Long> {

    // 최신순 첫 페이지와 다음 페이지(커서 = 앞 페이지 마지막 ID). ID는 저장 순서대로 커지므로 최신순과 같습니다.
    // 관리자가 숨긴 공유본은 작성자 본인에게만 보입니다(조회 조건에서 걸러 빈 페이지가 생기지 않음).
    // 보는 사람이 차단한 사람의 공유본도 조회 조건에서 뺍니다(차단한 사람 화면에만 적용).
    @Query("""
            SELECT p FROM FeedPost p WHERE (p.hidden = false OR p.userId = :viewerId)
              AND NOT EXISTS (SELECT b.id FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.userId)
            ORDER BY p.id DESC
            """)
    List<FeedPost> findVisibleLatest(@Param("viewerId") Long viewerId, Pageable pageable);

    @Query("""
            SELECT p FROM FeedPost p WHERE (p.hidden = false OR p.userId = :viewerId) AND p.id < :before
              AND NOT EXISTS (SELECT b.id FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.userId)
            ORDER BY p.id DESC
            """)
    List<FeedPost> findVisibleLatestBefore(@Param("viewerId") Long viewerId, @Param("before") Long before, Pageable pageable);

    /** 관리자 조치용: 공유본 행을 잠가 읽습니다(SELECT ... FOR UPDATE). 트랜잭션 안에서만 부릅니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM FeedPost p WHERE p.id = :id")
    Optional<FeedPost> findByIdForUpdate(@Param("id") Long id);

    // 공유 재전송 판별: 같은 사용자의 같은 요청 ID로 이미 만든 공유본
    Optional<FeedPost> findByUserIdAndClientRequestId(Long userId, String clientRequestId);
}
