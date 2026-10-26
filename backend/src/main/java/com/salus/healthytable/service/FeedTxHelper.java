package com.salus.healthytable.service;

import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.repository.FeedPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 피드 공유의 트랜잭션 단위입니다. {@link FeedService#share}가 트랜잭션 밖에서 호출합니다.
 * 같은 요청 ID의 동시 공유로 UNIQUE 위반이 나면, 호출자가 예외를 잡고 새 트랜잭션 조회로 먼저 만든 공유본을 찾습니다.
 */
@Component
@RequiredArgsConstructor
public class FeedTxHelper {

    private final FeedPostRepository feedPostRepository;

    /** flush해서 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. */
    @Transactional
    public FeedPost insert(FeedPost post) {
        return feedPostRepository.saveAndFlush(post);
    }

    @Transactional(readOnly = true)
    public Optional<FeedPost> findByRequest(Long userId, String clientRequestId) {
        return feedPostRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
    }
}
