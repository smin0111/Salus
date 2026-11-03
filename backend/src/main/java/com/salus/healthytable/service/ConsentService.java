package com.salus.healthytable.service;

import com.salus.healthytable.domain.ConsentType;
import com.salus.healthytable.repository.UserConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 가입 동의(약관·처리방침·건강정보·만 14세 이상)를 기록하고, 현재 문서 버전에 모두 동의했는지 확인합니다.
 * 건강정보는 서비스의 핵심이라 필수 동의입니다(2026-10-29 결정). 동의하지 않은 로그인 사용자는
 * 건강 프로필·검진 저장과 대화를 쓸 수 없습니다(403 CONSENT_REQUIRED).
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    // 문서를 고치면 버전을 올립니다. 올리면 모든 사용자가 다음 로그인 때 다시 동의합니다.
    static final Map<ConsentType, String> CURRENT_VERSIONS = Map.of(
            ConsentType.TERMS, "2026-10-31",
            ConsentType.PRIVACY, "2026-10-31",
            ConsentType.HEALTH_INFO, "2026-10-29",
            ConsentType.AGE_OVER_14, "1");

    static final String CONSENT_REQUIRED_MESSAGE = "서비스 이용약관·개인정보 처리방침·건강정보 수집 동의가 필요해요. 동의 화면에서 확인해 주세요.";

    private final UserConsentRepository userConsentRepository;
    private final Clock clock;

    /** 동의 요청 본문입니다. 화면에 보여 준 문서 버전을 그대로 돌려보내고, 만 14세 이상이면 true입니다. */
    public record AgreeRequest(String termsVersion, String privacyVersion, String healthInfoVersion, Boolean ageOver14) {
    }

    /** 동의 상태입니다. required가 true면 동의 화면을 보여 줍니다. */
    public record ConsentStatus(boolean required, Map<ConsentType, String> versions) {
    }

    @Transactional(readOnly = true)
    public ConsentStatus status(Long userId) {
        return new ConsentStatus(!hasAllCurrent(userId), orderedVersions());
    }

    /**
     * 현재 버전에 동의합니다. 화면이 오래된 버전을 보여 줬거나 하나라도 빠지면 400으로 거절합니다.
     * 같은 버전을 다시 보내도 한 번만 기록합니다.
     */
    @Transactional
    public ConsentStatus agree(Long userId, AgreeRequest request) {
        if (request == null
                || !Objects.equals(request.termsVersion(), CURRENT_VERSIONS.get(ConsentType.TERMS))
                || !Objects.equals(request.privacyVersion(), CURRENT_VERSIONS.get(ConsentType.PRIVACY))
                || !Objects.equals(request.healthInfoVersion(), CURRENT_VERSIONS.get(ConsentType.HEALTH_INFO))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "약관이 바뀌었어요. 화면을 새로 고친 뒤 다시 동의해 주세요.");
        }
        if (!Boolean.TRUE.equals(request.ageOver14())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만 14세 미만은 가입할 수 없어요.");
        }
        // 같은 회원의 동시 요청은 회원 행 잠금으로 차례로 처리합니다. 회원이 없으면(탈퇴 직후 등) 기록하지 않습니다.
        if (userConsentRepository.lockUser(userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 이미 있는 행은 그대로 두고 없는 행만 넣습니다.
        for (Map.Entry<ConsentType, String> entry : orderedVersions().entrySet()) {
            userConsentRepository.insertIfAbsent(userId, entry.getKey().name(), entry.getValue(), now);
        }
        return status(userId);
    }

    /** 현재 버전에 모두 동의했는지 알려 줍니다(막지 않고 다르게 처리할 때 씁니다). */
    @Transactional(readOnly = true)
    public boolean hasConsent(Long userId) {
        return userId != null && hasAllCurrent(userId);
    }

    /** 현재 버전에 모두 동의하지 않았으면 403으로 막습니다. */
    @Transactional(readOnly = true)
    public void requireConsent(Long userId) {
        if (userId != null && !hasAllCurrent(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, CONSENT_REQUIRED_MESSAGE);
        }
    }

    private boolean hasAllCurrent(Long userId) {
        return CURRENT_VERSIONS.entrySet().stream().allMatch(entry ->
                userConsentRepository.existsByUserIdAndConsentTypeAndVersion(userId, entry.getKey(), entry.getValue()));
    }

    private static Map<ConsentType, String> orderedVersions() {
        Map<ConsentType, String> ordered = new LinkedHashMap<>();
        for (ConsentType type : ConsentType.values()) {
            ordered.put(type, CURRENT_VERSIONS.get(type));
        }
        return ordered;
    }
}
