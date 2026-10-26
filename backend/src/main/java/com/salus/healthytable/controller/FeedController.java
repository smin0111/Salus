package com.salus.healthytable.controller;

import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.FeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 피드 API(/api/feed)입니다. 모두 로그인이 필요하고(SecurityConfig의 기본 규칙), 사용자는 인증 정보에서 꺼냅니다.
 * feed.enabled가 꺼져 있으면 /status 외에는 404입니다. 기존 커뮤니티(/api/community)와는 별개입니다.
 */
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    @GetMapping("/status")
    public FeedDto.Status status() {
        authenticatedUserProvider.requireUserId();
        return feedService.status();
    }

    @GetMapping("/posts")
    public FeedDto.Page list(@RequestParam(required = false) String cursor,
                             @RequestParam(required = false) Integer size) {
        return feedService.list(authenticatedUserProvider.requireUserId(), cursor, size);
    }

    @GetMapping("/posts/{id}")
    public FeedDto.Detail get(@PathVariable Long id) {
        return feedService.get(authenticatedUserProvider.requireUserId(), id);
    }

    /** 내 레시피를 공유합니다. 새로 만들면 201, 같은 요청의 재전송이면 200과 기존 공유본 ID입니다. */
    @PostMapping("/posts")
    public ResponseEntity<FeedDto.ShareResult> share(@RequestBody FeedDto.ShareRequest request) {
        FeedDto.ShareResult result = feedService.share(authenticatedUserProvider.requireUserId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        feedService.delete(authenticatedUserProvider.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
