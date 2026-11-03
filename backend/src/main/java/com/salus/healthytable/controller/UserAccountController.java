package com.salus.healthytable.controller;

import com.salus.healthytable.dto.AccountDeletionRequestDTO;
import com.salus.healthytable.dto.UserDataSummaryDTO;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.RecentIdentityConfirmation;
import com.salus.healthytable.service.AccountDeletionGuard;
import com.salus.healthytable.service.KakaoUnlinkClient;
import com.salus.healthytable.service.MonthlyAnalysisGuard;
import com.salus.healthytable.service.PaymentService;
import com.salus.healthytable.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 내 계정 관리 API(/api/users/me)입니다.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserAccountController {

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final UserAccountService userAccountService;
    private final AccountDeletionGuard accountDeletionGuard;
    private final RecentIdentityConfirmation recentIdentityConfirmation;
    private final PaymentService paymentService;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final MonthlyAnalysisGuard monthlyAnalysisGuard;

    /**
     * 내가 서비스에 남긴 데이터 종류별 건수를 조회합니다.
     */
    @GetMapping("/data-summary")
    public ResponseEntity<UserDataSummaryDTO> getDataSummary() {
        Long userId = authenticatedUserProvider.requireUserId();
        return ResponseEntity.ok(userAccountService.summarizeUserData(userId));
    }

    /**
     * 회원 탈퇴: 본인 확인(최근 로그인·비밀번호·소셜 로그인 중 하나) 뒤 계정과 개인 데이터를 삭제합니다.
     * 확인이 없으면 403 REAUTH_REQUIRED와 쓸 수 있는 확인 수단(methods)을, 틀리면 403 REAUTH_FAILED를 줍니다.
     * 401이 아닌 이유: 앱은 401을 로그인 만료로 보고 토큰 갱신 후 세션을 지우므로, 본인 확인 요청이 로그아웃이 됩니다.
     * 결제 확인 중인 주문이 있으면 409 PAYMENT_IN_PROGRESS로 막습니다(결제만 되고 PLUS를 받을 회원이 없어지는 것 방지).
     * 삭제가 커밋된 뒤 월간 총평 사본을 지우고 카카오 연결 끊기를 시도합니다(실패해도 탈퇴는 그대로).
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteMyAccount(
            @RequestBody(required = false) AccountDeletionRequestDTO request, HttpServletRequest httpRequest) {
        Long userId = authenticatedUserProvider.requireUserId();
        String path = httpRequest.getRequestURI();
        AccountDeletionGuard.Result proof = accountDeletionGuard.check(
                userId, recentIdentityConfirmation.isRecent(httpRequest), request);
        if (proof == AccountDeletionGuard.Result.REAUTH_REQUIRED) {
            Map<String, Object> body = error(HttpStatus.FORBIDDEN, "REAUTH_REQUIRED",
                    "보안을 위해 본인 확인 후 탈퇴할 수 있어요.", path);
            body.put("methods", accountDeletionGuard.availableMethods(userId));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        if (proof == AccountDeletionGuard.Result.INVALID) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(HttpStatus.FORBIDDEN, "REAUTH_FAILED",
                    "본인 확인에 실패했어요. 비밀번호를 여러 번 틀렸다면 15분 뒤 다시 시도해 주세요.", path));
        }
        // 먼저 포트원에 다시 조회해 끝난 결제를 마무리하고 확인합니다. 삭제 트랜잭션 안에서도 회원 행을 잠근 뒤 한 번 더 봅니다.
        if (paymentService.hasPaymentInProgress(userId)) {
            return paymentInProgress(path);
        }
        List<String> kakaoUserIds = accountDeletionGuard.kakaoUserIds(userId);
        try {
            userAccountService.deleteAccount(userId);
        } catch (UserAccountService.PaymentInProgressException e) {
            return paymentInProgress(path);
        }
        // 식단 기록으로 만든 총평 사본(서버 메모리)도 지웁니다.
        monthlyAnalysisGuard.forgetUser(userId);
        kakaoUserIds.forEach(kakaoUnlinkClient::unlink);
        return ResponseEntity.ok(Map.of("message", "계정과 개인 데이터가 삭제되었습니다."));
    }

    private ResponseEntity<Map<String, Object>> paymentInProgress(String path) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(HttpStatus.CONFLICT, "PAYMENT_IN_PROGRESS",
                "확인 중인 결제가 있어요. 결제가 끝나거나 취소된 뒤(최대 30분) 다시 탈퇴해 주세요.", path));
    }

    private Map<String, Object> error(HttpStatus status, String code, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        body.put("path", path);
        return body;
    }
}
