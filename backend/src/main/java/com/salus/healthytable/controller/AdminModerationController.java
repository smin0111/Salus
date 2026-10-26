package com.salus.healthytable.controller;

import com.salus.healthytable.dto.ModerationDto;
import com.salus.healthytable.service.ModerationService;
import com.salus.healthytable.service.adminauth.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관리자 신고 검토 API입니다. 관리자 보안 설정(SecurityConfig의 /api/admin/** 규칙)이 적용됩니다:
 * GET은 ADMIN·ADMIN_VIEWER, 조치(POST)는 ADMIN만. 사용자 JWT로는 통과할 수 없고, 피드가 꺼져 있어도 쓸 수 있습니다.
 */
@RestController
@RequestMapping("/api/admin/moderation")
@RequiredArgsConstructor
public class AdminModerationController {

    private final ModerationService moderationService;

    @GetMapping("/queue")
    public List<ModerationDto.QueueItem> queue() {
        return moderationService.queue();
    }

    @GetMapping("/targets/{type}/{id}")
    public ModerationDto.TargetDetail target(@PathVariable String type, @PathVariable Long id) {
        return moderationService.target(type, id);
    }

    @PostMapping("/targets/{type}/{id}/actions")
    public ModerationDto.ActionResult act(@AuthenticationPrincipal AdminPrincipal principal,
                                          @PathVariable String type, @PathVariable Long id,
                                          @RequestBody ModerationDto.ActionRequest request) {
        return moderationService.act(principal.adminId(), type, id, request);
    }
}
