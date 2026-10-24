package com.salus.healthytable.controller;

import com.salus.healthytable.dto.UserResponseDTO;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.NicknameService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 닉네임 API(/api/users/me/nickname)입니다. 로그인한 사용자만 쓰고(/api/users/** 인증 규칙),
 * 사용자는 언제나 인증 정보에서 꺼냅니다.
 */
@RestController
@RequestMapping("/api/users/me/nickname")
@RequiredArgsConstructor
public class NicknameController {

    private final NicknameService nicknameService;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    /** 닉네임 요청 본문입니다. */
    public record NicknameRequest(String nickname) {
    }

    /** 사용 가능한지 미리 확인합니다(저장하지 않음). 최종 판정은 저장할 때 다시 합니다. */
    @GetMapping("/check")
    public NicknameService.CheckResult check(@RequestParam(required = false) String nickname) {
        return nicknameService.check(authenticatedUserProvider.requireUserId(), nickname);
    }

    /** 닉네임을 정합니다. 형식·금지어 위반은 400, 다른 사람이 쓰는 닉네임은 409입니다. */
    @PutMapping
    public UserResponseDTO set(@RequestBody NicknameRequest request) {
        return nicknameService.set(authenticatedUserProvider.requireUserId(), request == null ? null : request.nickname());
    }
}
