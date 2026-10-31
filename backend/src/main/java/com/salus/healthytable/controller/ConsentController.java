package com.salus.healthytable.controller;

import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.ConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 가입 동의 API(/api/users/me/consents)입니다. 로그인한 사용자만 쓰고(/api/users/** 인증 규칙),
 * 사용자는 언제나 인증 정보에서 꺼냅니다.
 */
@RestController
@RequestMapping("/api/users/me/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    /** 현재 문서 버전과 동의가 필요한지 알려 줍니다. */
    @GetMapping
    public ConsentService.ConsentStatus status() {
        return consentService.status(authenticatedUserProvider.requireUserId());
    }

    /** 현재 버전에 동의합니다. 오래된 버전·만 14세 미만은 400입니다. */
    @PostMapping
    public ConsentService.ConsentStatus agree(@RequestBody(required = false) ConsentService.AgreeRequest request) {
        return consentService.agree(authenticatedUserProvider.requireUserId(), request);
    }
}
