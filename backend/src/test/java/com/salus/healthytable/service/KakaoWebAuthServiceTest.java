package com.salus.healthytable.service;

import com.salus.healthytable.domain.ProviderWebAuthRequest;
import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.repository.ProviderWebAuthRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KakaoWebAuthService} 테스트입니다. state 대조·일회용 처리·회원 일치·취소·만료를 확인합니다.
 */
class KakaoWebAuthServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 12, 0);

    private final ProviderWebAuthRequestRepository repository = mock(ProviderWebAuthRequestRepository.class);
    private final OAuthService oAuthService = mock(OAuthService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
    private KakaoWebAuthService service;

    @BeforeEach
    void setUp() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            tokenRequestBody.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"access_token\":\"kakao-access\"}").build());
        });
        service = new KakaoWebAuthService(repository, oAuthService, builder, mock(PlatformTransactionManager.class), clock);
        ReflectionTestUtils.setField(service, "restApiKey", "rest-key");
        ReflectionTestUtils.setField(service, "callbackUri", "https://api.salus.test/api/auth/kakao/callback");
        ReflectionTestUtils.setField(service, "appRedirectUri", "salus://kakao-web-auth");
    }

    // 시작하면 해시만 저장하고, 카카오 주소에는 state·콜백 주소·REST 키가 담겨야 합니다.
    @Test
    void startStoresHashesAndBuildsAuthorizeUrl() {
        KakaoWebAuthService.Started started = service.start(10L);

        ArgumentCaptor<ProviderWebAuthRequest> captor = ArgumentCaptor.forClass(ProviderWebAuthRequest.class);
        verify(repository).save(captor.capture());
        ProviderWebAuthRequest saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(10L);
        assertThat(saved.getRequestTokenHash()).isEqualTo(SocialLoginTxHelper.sha256(started.requestToken()));
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(started.authorizeUrl()).startsWith("https://kauth.kakao.com/oauth/authorize")
                .contains("client_id=rest-key").contains("response_type=code").contains("state=")
                .contains("redirect_uri=https://api.salus.test/api/auth/kakao/callback");
        // 앱만 갖는 requestToken은 카카오 주소에 실리지 않아야 합니다.
        assertThat(started.authorizeUrl()).doesNotContain(started.requestToken());
    }

    // 설정이 비어 있으면 시작하지 않아야 합니다(fail closed).
    @Test
    void startFailsClosedWithoutConfiguration() {
        ReflectionTestUtils.setField(service, "restApiKey", "");
        assertThatThrownBy(() -> service.start(10L)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    // 콜백: state가 맞으면 서버가 코드를 교환하고 카카오 계정을 확인해 저장한 뒤 성공으로 앱에 돌려보내야 합니다.
    @Test
    void callbackExchangesCodeAndStoresConfirmedAccount() {
        ProviderWebAuthRequest request = pending("state-1");
        when(repository.findById(1L)).thenReturn(Optional.of(request));
        when(oAuthService.verifyKakao("kakao-access"))
                .thenReturn(new VerifiedSocialIdentity(SocialProvider.KAKAO, "k1", null, false, "카카오"));

        String location = service.handleCallback("auth-code", "state-1", null);

        assertThat(location).isEqualTo("salus://kakao-web-auth?result=success");
        assertThat(location).doesNotContain("auth-code").doesNotContain("kakao-access");
        assertThat(request.getCompletedAt()).isEqualTo(NOW);
        assertThat(request.getProviderUserId()).isEqualTo("k1");
    }

    // 같은 state로 두 번째 콜백이 오면 처리하지 않아야 합니다(재사용 방지).
    @Test
    void secondCallbackWithSameStateIsRejected() {
        ProviderWebAuthRequest request = pending("state-1");
        request.setCallbackStartedAt(NOW.minusSeconds(5));

        assertThat(service.handleCallback("auth-code", "state-1", null)).endsWith("result=error");
        verify(oAuthService, never()).verifyKakao(anyString());
    }

    // 만료됐거나 모르는 state면 처리하지 않아야 합니다.
    @Test
    void expiredOrUnknownStateIsRejected() {
        ProviderWebAuthRequest request = pending("state-1");
        request.setExpiresAt(NOW);
        when(repository.findForUpdateByStateHash(SocialLoginTxHelper.sha256("unknown"))).thenReturn(Optional.empty());

        assertThat(service.handleCallback("code", "state-1", null)).endsWith("result=error");
        assertThat(service.handleCallback("code", "unknown", null)).endsWith("result=error");
        assertThat(service.handleCallback("code", null, null)).endsWith("result=error");
        verify(oAuthService, never()).verifyKakao(anyString());
    }

    // 사용자가 카카오 창에서 취소하면 실패로 기록하고 취소로 돌려보내야 합니다.
    @Test
    void userCancellationIsRecorded() {
        ProviderWebAuthRequest request = pending("state-1");
        when(repository.findById(1L)).thenReturn(Optional.of(request));

        assertThat(service.handleCallback(null, "state-1", "access_denied")).endsWith("result=cancelled");
        assertThat(request.getFailureReason()).isEqualTo("provider_error");
    }

    // 완료: 시작한 회원이 성공한 요청을 한 번만 가져가고, 확인 시각은 서버가 카카오 계정을 확인한 시각이어야 합니다.
    @Test
    void completeReturnsConfirmedIdentityOnceForSameUser() {
        ProviderWebAuthRequest request = completed();
        when(repository.findForUpdateByRequestTokenHash(SocialLoginTxHelper.sha256("token"))).thenReturn(Optional.of(request));

        VerifiedSocialIdentity identity = service.complete(10L, "token");

        assertThat(identity.provider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(identity.providerUserId()).isEqualTo("k1");
        assertThat(identity.identityConfirmedAt()).isEqualTo(Instant.parse("2026-10-07T02:59:00Z"));
        assertThat(request.getConsumedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.complete(10L, "token")).isInstanceOf(OAuthVerificationException.class);
    }

    // 다른 회원, 아직 완료되지 않음, 실패, 만료된 요청은 가져갈 수 없어야 합니다.
    @Test
    void completeRejectsOtherUserIncompleteFailedOrExpired() {
        ProviderWebAuthRequest request = completed();
        when(repository.findForUpdateByRequestTokenHash(SocialLoginTxHelper.sha256("token"))).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> service.complete(99L, "token")).isInstanceOf(OAuthVerificationException.class);

        request.setCompletedAt(null);
        assertThatThrownBy(() -> service.complete(10L, "token")).isInstanceOf(OAuthVerificationException.class);

        request.setCompletedAt(NOW.minusMinutes(1));
        request.setFailureReason("verification_failed");
        assertThatThrownBy(() -> service.complete(10L, "token")).isInstanceOf(OAuthVerificationException.class);

        request.setFailureReason(null);
        request.setExpiresAt(NOW);
        assertThatThrownBy(() -> service.complete(10L, "token")).isInstanceOf(OAuthVerificationException.class);
        assertThat(request.getConsumedAt()).isNull();
    }

    // 웹 앱 로그인 주소에는 웹 앱이 준 redirectUri·state가 담기고, 값이 비면 만들지 않아야 합니다.
    @Test
    void loginAuthorizeUrlUsesWebRedirectAndState() {
        assertThat(service.loginAuthorizeUrl("http://localhost:8081", "web-state"))
                .startsWith("https://kauth.kakao.com/oauth/authorize")
                .contains("client_id=rest-key").contains("response_type=code")
                .contains("redirect_uri=http://localhost:8081").contains("state=web-state");
        assertThatThrownBy(() -> service.loginAuthorizeUrl("", "web-state")).isInstanceOf(OAuthVerificationException.class);
        assertThatThrownBy(() -> service.loginAuthorizeUrl("http://localhost:8081", " ")).isInstanceOf(OAuthVerificationException.class);
        ReflectionTestUtils.setField(service, "restApiKey", "");
        assertThatThrownBy(() -> service.loginAuthorizeUrl("http://localhost:8081", "web-state")).isInstanceOf(IllegalStateException.class);
    }

    // 웹 앱이 받은 인가 코드는 서버가 교환해 카카오 계정을 확인하고, 확인 시각을 붙여야 합니다.
    @Test
    void verifyLoginCodeExchangesAndConfirms() {
        when(oAuthService.verifyKakao("kakao-access"))
                .thenReturn(new VerifiedSocialIdentity(SocialProvider.KAKAO, "k1", null, false, "카카오"));

        VerifiedSocialIdentity identity = service.verifyLoginCode("auth-code", "http://localhost:8081");

        assertThat(identity.providerUserId()).isEqualTo("k1");
        assertThat(identity.identityConfirmedAt()).isEqualTo(Instant.parse("2026-10-07T03:00:00Z"));
        assertThat(tokenRequestBody.get()).isEqualTo("https://kauth.kakao.com/oauth/token");
        assertThatThrownBy(() -> service.verifyLoginCode(" ", "http://localhost:8081")).isInstanceOf(OAuthVerificationException.class);
        assertThatThrownBy(() -> service.verifyLoginCode("auth-code", null)).isInstanceOf(OAuthVerificationException.class);
    }

    private ProviderWebAuthRequest pending(String state) {
        ProviderWebAuthRequest request = new ProviderWebAuthRequest();
        request.setId(1L);
        request.setProvider(SocialProvider.KAKAO);
        request.setUserId(10L);
        request.setStateHash(SocialLoginTxHelper.sha256(state));
        request.setRequestTokenHash("token-hash");
        request.setCreatedAt(NOW.minusMinutes(1));
        request.setExpiresAt(NOW.plusMinutes(9));
        when(repository.findForUpdateByStateHash(SocialLoginTxHelper.sha256(state))).thenReturn(Optional.of(request));
        return request;
    }

    private ProviderWebAuthRequest completed() {
        ProviderWebAuthRequest request = new ProviderWebAuthRequest();
        request.setId(1L);
        request.setProvider(SocialProvider.KAKAO);
        request.setUserId(10L);
        request.setCreatedAt(NOW.minusMinutes(2));
        request.setExpiresAt(NOW.plusMinutes(8));
        request.setCallbackStartedAt(NOW.minusMinutes(1));
        request.setCompletedAt(NOW.minusMinutes(1));
        request.setProviderUserId("k1");
        return request;
    }
}
