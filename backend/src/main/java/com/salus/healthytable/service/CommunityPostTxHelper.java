package com.salus.healthytable.service;

import com.salus.healthytable.domain.CommunityPost;
import com.salus.healthytable.repository.CommunityPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 게시글 등록의 트랜잭션 단위입니다. {@link CommunityPostService#createPost}가 트랜잭션 밖에서 호출합니다.
 *
 * 같은 요청 ID로 동시에 두 번 저장하면 한쪽은 UNIQUE 위반으로 롤백됩니다. 롤백된 트랜잭션 안에서는 다시 조회할 수 없으므로,
 * 호출자가 예외를 잡고 이 클래스의 조회 메서드(새 트랜잭션)로 먼저 저장된 게시글을 찾습니다.
 */
@Component
@RequiredArgsConstructor
public class CommunityPostTxHelper {

    private final CommunityPostRepository postRepository;

    /** flush해서 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다(호출자가 잡아 기존 게시글을 조회). */
    @Transactional
    public CommunityPost insert(CommunityPost post) {
        return postRepository.saveAndFlush(post);
    }

    @Transactional(readOnly = true)
    public Optional<CommunityPost> findByRequest(Long userId, String clientRequestId) {
        return postRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
    }
}
