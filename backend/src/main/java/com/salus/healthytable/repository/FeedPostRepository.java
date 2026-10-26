package com.salus.healthytable.repository;

import com.salus.healthytable.domain.FeedPost;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeedPostRepository extends JpaRepository<FeedPost, Long> {

    // 최신순 첫 페이지와 다음 페이지(커서 = 앞 페이지 마지막 ID). ID는 저장 순서대로 커지므로 최신순과 같습니다.
    List<FeedPost> findAllByOrderByIdDesc(Pageable pageable);

    List<FeedPost> findByIdLessThanOrderByIdDesc(Long id, Pageable pageable);

    // 공유 재전송 판별: 같은 사용자의 같은 요청 ID로 이미 만든 공유본
    Optional<FeedPost> findByUserIdAndClientRequestId(Long userId, String clientRequestId);
}
