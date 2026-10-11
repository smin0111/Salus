package com.salus.healthytable.service;

import com.salus.healthytable.domain.ProviderWebAuthRequest;
import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.repository.ProviderWebAuthRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * 계정 설정의 재확인·로그인 수단 추가에 쓰는 카카오 웹 로그인(서버 콜백 방식)입니다.
 *
 * 카카오 웹 로그인은 Redirect URI에 앱 주소를 쓸 수 없어 카카오 → 서버 콜백 → 앱 순서로 돌아옵니다.
 * 1) start: 로그인한 회원에게 묶인 요청을 만들고, 카카오 로그인 주소(state 포함)와 앱만 갖는 requestToken을 돌려줌
 * 2) handleCallback: state가 1)에서 만든 것인지 서버가 대조 → 일회용 인가 코드를 서버가 직접 교환 → 카카오 계정 확인 → 결과 저장
 * 3) complete: 같은 회원이 requestToken으로 결과를 한 번만 가져감 → 확인된 카카오 계정(확인 시각 포함)
 *
 * 인가 코드는 일회용이고 서버가 지금 교환하므로 네이버와 같은 기준으로 "이번 요청에서 계정을 확인"한 것으로 인정합니다.
 * 주소에 실리는 state만으로는 완료할 수 없고(requestToken 필요), 콜백 중 카카오와 통신하는 동안 DB 트랜잭션을 잡지 않습니다.
 * 설정(REST API 키, 콜백 주소)이 비어 있으면 동작하지 않습니다(fail closed).
 */
@Slf4j
@Service
public class KakaoWebAuthService {

    static final Duration VALIDITY = Duration.ofMinutes(10);
    private static final String AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";
    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final int RANDOM_BYTES = 32;

    private final ProviderWebAuthRequestRepository repository;
    private final OAuthService oAuthService;
    private final WebClient.Builder webClientBuilder;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${kakao.web-auth.rest-api-key:}")
    private String restApiKey;

    @Value("${kakao.web-auth.client-secret:}")
    private String clientSecret;

    // 카카오 콘솔에 등록한 Redirect URI와 정확히 같아야 합니다. 예: https://<서버>/api/auth/kakao/callback
    @Value("${kakao.web-auth.callback-uri:}")
    private String callbackUri;

    // 처리 후 브라우저를 돌려보낼 앱 주소. 결과 상태만 담고 코드·토큰은 담지 않습니다.
    @Value("${kakao.web-auth.app-redirect-uri:salus://kakao-web-auth}")
    private String appRedirectUri;

    public KakaoWebAuthService(ProviderWebAuthRequestRepository repository, OAuthService oAuthService,
            WebClient.Builder webClientBuilder, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.oAuthService = oAuthService;
        this.webClientBuilder = webClientBuilder;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public record Started(String authorizeUrl, String requestToken, String appRedirectUri) {
    }

    public Started start(Long userId) {
        requireConfigured();
        String state = randomToken();
        String requestToken = randomToken();
        LocalDateTime now = LocalDateTime.now(clock);

        ProviderWebAuthRequest request = new ProviderWebAuthRequest();
        request.setProvider(SocialProvider.KAKAO);
        request.setUserId(userId);
        request.setStateHash(SocialLoginTxHelper.sha256(state));
        request.setRequestTokenHash(SocialLoginTxHelper.sha256(requestToken));
        request.setCreatedAt(now);
        request.setExpiresAt(now.plus(VALIDITY));
        tx.executeWithoutResult(status -> repository.save(request));

        String authorizeUrl = UriComponentsBuilder.fromHttpUrl(AUTHORIZE_URL)
                .queryParam("client_id", restApiKey)
                .queryParam("redirect_uri", callbackUri)
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .encode()
                .toUriString();
        return new Started(authorizeUrl, requestToken, appRedirectUri);
    }

    /**
     * 카카오 콜백을 처리하고, 브라우저를 돌려보낼 앱 주소(결과 상태만 포함)를 돌려줍니다.
     */
    public String handleCallback(String code, String state, String error) {
        if (state == null || state.isBlank()) {
            return appRedirect("error");
        }
        // 1) 같은 state의 두 번째 콜백을 막기 위해 먼저 "처리 시작"으로 표시합니다(짧은 트랜잭션).
        Long requestId = tx.execute(status -> repository.findForUpdateByStateHash(SocialLoginTxHelper.sha256(state.trim()))
                .filter(request -> request.getCallbackStartedAt() == null)
                .filter(request -> LocalDateTime.now(clock).isBefore(request.getExpiresAt()))
                .map(request -> {
                    request.setCallbackStartedAt(LocalDateTime.now(clock));
                    return request.getId();
                })
                .orElse(null));
        if (requestId == null) {
            return appRedirect("error");
        }
        if (error != null || code == null || code.isBlank()) {
            // 사용자가 카카오 로그인 창에서 취소했거나 카카오가 오류를 돌려준 경우
            markFailed(requestId, error != null ? "provider_error" : "code_missing");
            return appRedirect("cancelled");
        }

        // 2) 트랜잭션 밖에서 카카오와 통신합니다(코드 교환 → 앱 소속 확인 → 사용자 정보).
        VerifiedSocialIdentity identity;
        try {
            identity = oAuthService.verifyKakao(exchangeCode(code.trim(), callbackUri));
        } catch (RuntimeException e) {
            log.info("Kakao web auth failed. reason={}", e instanceof OAuthVerificationException oauth
                    ? oauth.getReason() : "exception_" + e.getClass().getSimpleName());
            markFailed(requestId, "verification_failed");
            return appRedirect("error");
        }

        // 3) 결과 저장(짧은 트랜잭션)
        tx.executeWithoutResult(status -> repository.findById(requestId).ifPresent(request -> {
            request.setCompletedAt(LocalDateTime.now(clock));
            request.setProviderUserId(identity.providerUserId());
            request.setProviderEmail(identity.email());
            request.setProviderEmailVerified(identity.emailVerified());
        }));
        return appRedirect("success");
    }

    /**
     * 웹 앱(브라우저)의 카카오 로그인용 카카오 로그인 주소를 만듭니다. 웹에는 카카오 네이티브 SDK가 없어 인가 코드 방식을 씁니다.
     * redirectUri는 카카오 콘솔에 등록된 주소여야 하며(카카오가 거절), state는 웹 앱이 만들고 돌아온 응답과 직접 대조합니다.
     */
    public String loginAuthorizeUrl(String redirectUri, String state) {
        requireConfigured();
        return UriComponentsBuilder.fromHttpUrl(AUTHORIZE_URL)
                .queryParam("client_id", restApiKey)
                .queryParam("redirect_uri", requireText(redirectUri, "redirect_uri_missing"))
                .queryParam("response_type", "code")
                .queryParam("state", requireText(state, "state_missing"))
                .encode()
                .toUriString();
    }

    /**
     * 웹 앱이 받은 카카오 인가 코드를 서버가 교환해 계정을 확인합니다.
     * 인가 코드는 일회용·수 분짜리이고 서버가 지금 교환하므로 네이버와 같은 기준으로 계정 확인으로 인정합니다.
     */
    public VerifiedSocialIdentity verifyLoginCode(String code, String redirectUri) {
        requireConfigured();
        String accessToken = exchangeCode(requireText(code, "code_missing"), requireText(redirectUri, "redirect_uri_missing"));
        return oAuthService.verifyKakao(accessToken).withIdentityConfirmedAt(clock.instant());
    }

    /**
     * 같은 회원이 requestToken으로 결과를 한 번만 가져갑니다. 확인 시각은 서버가 카카오 계정을 확인한 시각입니다.
     */
    public VerifiedSocialIdentity complete(Long userId, String requestToken) {
        if (userId == null || requestToken == null || requestToken.isBlank()) {
            throw new OAuthVerificationException("web_auth_request_missing");
        }
        return tx.execute(status -> {
            ProviderWebAuthRequest request = repository
                    .findForUpdateByRequestTokenHash(SocialLoginTxHelper.sha256(requestToken.trim()))
                    .orElseThrow(() -> new OAuthVerificationException("web_auth_request_invalid"));
            LocalDateTime now = LocalDateTime.now(clock);
            if (!userId.equals(request.getUserId())
                    || request.getCompletedAt() == null
                    || request.getFailureReason() != null
                    || request.getConsumedAt() != null
                    || !now.isBefore(request.getExpiresAt())) {
                throw new OAuthVerificationException("web_auth_request_invalid");
            }
            request.setConsumedAt(now);
            return new VerifiedSocialIdentity(
                    SocialProvider.KAKAO,
                    request.getProviderUserId(),
                    request.getProviderEmail(),
                    request.isProviderEmailVerified(),
                    "Kakao User",
                    request.getCompletedAt().atZone(clock.getZone()).toInstant());
        });
    }

    @SuppressWarnings("unchecked")
    private String exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", restApiKey);
        // 카카오는 인가 요청 때와 같은 redirect_uri인지 확인합니다.
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.add("client_secret", clientSecret);
        }
        Map<String, Object> response = webClientBuilder.build()
                .post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        Object accessToken = response != null ? response.get("access_token") : null;
        if (accessToken == null || String.valueOf(accessToken).isBlank()) {
            throw new OAuthVerificationException("access_token_missing");
        }
        return String.valueOf(accessToken);
    }

    private void markFailed(Long requestId, String reason) {
        tx.executeWithoutResult(status -> repository.findById(requestId)
                .ifPresent(request -> request.setFailureReason(reason)));
    }

    private String appRedirect(String result) {
        return UriComponentsBuilder.fromUriString(appRedirectUri).queryParam("result", result).build().toUriString();
    }

    private void requireConfigured() {
        if (restApiKey == null || restApiKey.isBlank() || callbackUri == null || callbackUri.isBlank()) {
            throw new IllegalStateException("카카오 웹 로그인 설정(KAKAO_REST_API_KEY, KAKAO_WEB_AUTH_CALLBACK_URI)이 비어 있습니다.");
        }
    }

    private String requireText(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw new OAuthVerificationException(reason);
        }
        return value.trim();
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
