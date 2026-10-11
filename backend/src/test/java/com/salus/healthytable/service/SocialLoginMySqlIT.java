package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.domain.SocialProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL에서 소셜 로그인·계정 연결·최근 인증 데이터를 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V1~V14 적용과 엔티티 매핑(ddl-auto=validate), GET_LOCK이 커밋 뒤에
 * 풀리는지, 잠금 시간 초과 시 가입을 강행하지 않는지, 행 잠금·unique 제약이 동시 요청을 막는지.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=SocialLoginMySqlIT test
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SocialLoginService.class, SocialLoginTxHelper.class, EmailSignupLock.class, TimeConfig.class,
        AuthNonceService.class, LoginMethodService.class, RefreshTokenService.class, KakaoWebAuthService.class,
        SocialLoginMySqlIT.FakeKakaoHttp.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class SocialLoginMySqlIT {

    private static final String NONCE = "flow-nonce-0123456789abcdef0123456789";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
    }

    @Autowired
    private SocialLoginService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SocialLoginTxHelper txHelper;

    @Autowired
    private EmailSignupLock emailSignupLock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private AuthNonceService authNonceService;

    @Autowired
    private LoginMethodService loginMethodService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private KakaoWebAuthService kakaoWebAuthService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private OAuthService oAuthService;

    /** 카카오 토큰 교환 응답만 흉내 내는 WebClient(실제 카카오 호출 없음). */
    @org.springframework.boot.test.context.TestConfiguration
    static class FakeKakaoHttp {
        @org.springframework.context.annotation.Bean
        org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder() {
            return org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request ->
                    reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                            .create(org.springframework.http.HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"access_token\":\"kakao-access\"}").build()));
        }
    }

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM auth_nonces");
        jdbc.update("DELETE FROM provider_web_auth_requests");
        jdbc.update("DELETE FROM link_requests");
        jdbc.update("DELETE FROM social_accounts");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users");
    }

    // Flyway가 V12까지 모두 성공했고, 엔티티 매핑이 실제 스키마와 맞아야 합니다(맞지 않으면 컨텍스트가 뜨지 않음).
    @Test
    void migrationsApplyAndMappingsValidate() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class);
        assertThat(versions).contains("9", "10", "11", "12", "13", "14");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class))
                .isZero();
    }

    // 같은 검증 이메일로 다른 제공자의 첫 로그인이 동시에 들어와도 회원은 한 명만 생기고, 나머지는 연결 요청이 되어야 합니다.
    @Test
    void concurrentFirstLoginsWithSameEmailCreateExactlyOneUser() throws Exception {
        for (int round = 0; round < 10; round++) {
            String email = "race" + round + "@gmail.com";
            VerifiedSocialIdentity kakao = identity(SocialProvider.KAKAO, "kakao-race-" + round, email);
            VerifiedSocialIdentity google = identity(SocialProvider.GOOGLE, "google-race-" + round, email);

            List<Object> results = runConcurrently(
                    () -> service.login(kakao, null, NONCE),
                    () -> service.login(google, null, NONCE));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email))
                    .as("round %d", round).isEqualTo(1);
            assertThat(results).filteredOn(SocialLoginTxHelper.LoggedIn.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(SocialLoginTxHelper.LinkRequired.class::isInstance).hasSize(1);
        }
        assertThat(isLockFree("race0@gmail.com")).isTrue();
    }

    // 잠금을 쥔 동안 같은 이메일의 다른 요청은 기다려야 하고, 잠금이 풀린 뒤에는 앞 요청이 커밋한 회원을 봐야 합니다.
    @Test
    void waiterBlocksUntilHolderCommitsAndThenSeesCommittedUser() throws Exception {
        String email = "order@gmail.com";
        VerifiedSocialIdentity kakao = identity(SocialProvider.KAKAO, "kakao-order", email);
        VerifiedSocialIdentity google = identity(SocialProvider.GOOGLE, "google-order", email);
        CountDownLatch firstHoldsLock = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 서비스와 같은 순서: 잠금 → 트랜잭션(커밋까지) → 해제. 커밋 뒤 1.5초 더 잠금을 쥡니다.
            Future<Object> first = executor.submit(() -> emailSignupLock.withLock(email, () -> {
                firstHoldsLock.countDown();
                Object decision = txHelper.registerOrRequestLink(kakao, NONCE);
                sleep(1500);
                return decision;
            }));
            assertThat(firstHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            Object second = executor.submit(() -> service.login(google, null, NONCE)).get(20, TimeUnit.SECONDS);
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(first.get(20, TimeUnit.SECONDS)).isInstanceOf(SocialLoginTxHelper.LoggedIn.class);
            assertThat(waitedMillis).as("second request must wait for the lock holder").isGreaterThanOrEqualTo(1000);
            assertThat(second).isInstanceOf(SocialLoginTxHelper.LinkRequired.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
            assertThat(isLockFree(email)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    // 트랜잭션 안에서 잠그면 커밋보다 해제가 먼저 일어나므로, 아예 거부해야 합니다.
    @Test
    void lockInsideTransactionIsRefused() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.execute(status -> emailSignupLock.withLock("tx@gmail.com", () -> "never")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(isLockFree("tx@gmail.com")).isTrue();
    }

    // 잠금을 얻지 못하면(시간 초과) 가입을 강행하지 않아야 하고, 잠금이 커넥션 풀에 남지 않아야 합니다.
    @Test
    void lockTimeoutDoesNotForceSignupAndLeavesNoLock() throws Exception {
        String email = "busy@gmail.com";
        try (Connection holder = dataSource.getConnection()) {
            assertThat(getLock(holder, EmailSignupLock.lockName(email))).isTrue();

            assertThatThrownBy(() -> service.login(identity(SocialProvider.KAKAO, "kakao-busy", email), null, NONCE))
                    .isInstanceOf(EmailSignupLock.SignupBusyException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();

            releaseLock(holder, EmailSignupLock.lockName(email));
        }
        // 잠금이 풀린 뒤에는 정상 가입되어야 합니다.
        assertThat(service.login(identity(SocialProvider.KAKAO, "kakao-busy", email), null, NONCE))
                .isInstanceOf(SocialLoginTxHelper.LoggedIn.class);
        assertThat(isLockFree(email)).isTrue();
    }

    // 전체 흐름: 구글 가입 → 같은 이메일 카카오 → 연결 요청 → 구글 재인증 → 연결 → 이후 카카오로 같은 회원 로그인.
    @Test
    void linkFlowEndToEnd() {
        VerifiedSocialIdentity google = identity(SocialProvider.GOOGLE, "google-e2e", "e2e@gmail.com");
        VerifiedSocialIdentity kakao = identity(SocialProvider.KAKAO, "kakao-e2e", "e2e@gmail.com");
        Long userId = ((SocialLoginTxHelper.LoggedIn) service.login(google, null, NONCE)).user().getId();

        SocialLoginTxHelper.LinkRequired required = (SocialLoginTxHelper.LinkRequired) service.login(kakao, null, NONCE);
        assertThat(required.providers()).containsExactly(SocialProvider.GOOGLE);

        SocialLoginTxHelper.LoggedIn linked =
                (SocialLoginTxHelper.LoggedIn) service.login(google, required.linkToken(), NONCE);
        assertThat(linked.user().getId()).isEqualTo(userId);
        assertThat(linked.linkedProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM link_requests WHERE consumed_at IS NOT NULL", Integer.class))
                .isEqualTo(1);

        // 같은 연결 요청은 다시 쓸 수 없고, 이제 카카오로 바로 같은 회원에 로그인됩니다.
        assertLinkCode(() -> service.login(google, required.linkToken(), NONCE), "LINK_INVALID");
        assertThat(((SocialLoginTxHelper.LoggedIn) service.login(kakao, null, NONCE)).user().getId()).isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }

    // 같은 연결 요청으로 완료가 동시에 들어오면 하나만 성공해야 합니다(행 잠금).
    @Test
    void concurrentCompletionOfSameRequestSucceedsOnce() throws Exception {
        VerifiedSocialIdentity google = identity(SocialProvider.GOOGLE, "google-dup", "dup@gmail.com");
        VerifiedSocialIdentity kakao = identity(SocialProvider.KAKAO, "kakao-dup", "dup@gmail.com");
        service.login(google, null, NONCE);
        String token = ((SocialLoginTxHelper.LinkRequired) service.login(kakao, null, NONCE)).linkToken();

        List<Object> results = runConcurrently(
                () -> service.login(google, token, NONCE),
                () -> service.login(google, token, NONCE));

        assertThat(results).filteredOn(SocialLoginTxHelper.LoggedIn.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof AccountLinkException e && e.getCode().equals("LINK_INVALID"))
                .hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM social_accounts WHERE provider = 'KAKAO' AND provider_user_id = 'kakao-dup'",
                Integer.class)).isEqualTo(1);
    }

    // 서로 다른 연결 요청 두 개가 같은 소셜 계정을 다른 회원에 동시에 연결하려 하면,
    // unique 제약으로 한쪽만 성공하고 다른 회원으로 옮겨지지 않아야 합니다.
    @Test
    void differentRequestsRacingForSameSocialAccountLinkOnlyOnce() throws Exception {
        VerifiedSocialIdentity googleA = identity(SocialProvider.GOOGLE, "google-a", "a@gmail.com");
        VerifiedSocialIdentity googleB = identity(SocialProvider.GOOGLE, "google-b", "b@gmail.com");
        Long userA = ((SocialLoginTxHelper.LoggedIn) service.login(googleA, null, NONCE)).user().getId();
        Long userB = ((SocialLoginTxHelper.LoggedIn) service.login(googleB, null, NONCE)).user().getId();
        String tokenA = insertLinkRequest(userA, "kakao-contested");
        String tokenB = insertLinkRequest(userB, "kakao-contested");

        List<Object> results = runConcurrently(
                () -> service.login(googleA, tokenA, NONCE),
                () -> service.login(googleB, tokenB, NONCE));

        assertThat(results).filteredOn(SocialLoginTxHelper.LoggedIn.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof AccountLinkException e && e.getCode().equals("LINK_ALREADY_LINKED"))
                .hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM social_accounts WHERE provider = 'KAKAO' AND provider_user_id = 'kakao-contested'",
                Integer.class)).isEqualTo(1);
    }

    // 취소한 요청은 서버에서 무효라서, 앱이 들고 있던 ID로 다시 시도해도 연결되지 않아야 합니다.
    @Test
    void cancelledRequestCannotBeUsed() {
        VerifiedSocialIdentity google = identity(SocialProvider.GOOGLE, "google-cancel", "cancel@gmail.com");
        VerifiedSocialIdentity kakao = identity(SocialProvider.KAKAO, "kakao-cancel", "cancel@gmail.com");
        service.login(google, null, NONCE);
        String token = ((SocialLoginTxHelper.LinkRequired) service.login(kakao, null, NONCE)).linkToken();

        service.cancelLink(token, NONCE);

        assertLinkCode(() -> service.login(google, token, NONCE), "LINK_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts", Integer.class)).isEqualTo(1);
    }

    // 회원을 지우면 그 회원을 대상으로 한 연결 요청도 함께 지워져야 합니다(FK ON DELETE CASCADE).
    @Test
    void deletingUserCascadesToLinkRequests() {
        VerifiedSocialIdentity google = identity(SocialProvider.GOOGLE, "google-del", "del@gmail.com");
        Long userId = ((SocialLoginTxHelper.LoggedIn) service.login(google, null, NONCE)).user().getId();
        service.login(identity(SocialProvider.KAKAO, "kakao-del", "del@gmail.com"), null, NONCE);

        jdbc.update("DELETE FROM social_accounts WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM link_requests", Integer.class)).isZero();
    }

    // 서버 nonce는 동시에 두 요청이 와도 한 번만 소비되어야 합니다(행 잠금).
    @Test
    void serverNonceIsConsumedOnceUnderConcurrency() throws Exception {
        String nonce = authNonceService.issue();

        List<Object> results = runConcurrently(
                () -> authNonceService.consume(nonce),
                () -> authNonceService.consume(nonce));

        assertThat(results).containsExactlyInAnyOrder(true, false);
    }

    // 처음 로그인 시각은 refresh token에 저장되고, 교체 후에도 같은 값이어야 합니다(실제 DATETIME 왕복 포함).
    @Test
    void identityConfirmedAtSurvivesRefreshRotationInDatabase() {
        Long userId = ((SocialLoginTxHelper.LoggedIn) service.login(
                identity(SocialProvider.NAVER, "naver-auth", "auth@gmail.com"), null, NONCE)).user().getId();
        java.time.Instant loginAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String raw = refreshTokenService.issue(userId, loginAt);

        RefreshTokenService.RotationResult rotated = refreshTokenService.rotate(raw);

        assertThat(rotated.status()).isEqualTo(RefreshTokenService.Status.ROTATED);
        assertThat(rotated.identityConfirmedAt()).isEqualTo(loginAt);
        assertThat(refreshTokenService.rotate(rotated.refreshToken()).identityConfirmedAt()).isEqualTo(loginAt);
    }

    // 두 회원이 같은 소셜 계정을 동시에 로그인 수단으로 추가하면 한쪽만 성공하고 옮겨지지 않아야 합니다.
    @Test
    void concurrentAddOfSameLoginMethodSucceedsOnce() throws Exception {
        Long userA = ((SocialLoginTxHelper.LoggedIn) service.login(
                identity(SocialProvider.GOOGLE, "google-add-a", "add-a@gmail.com"), null, NONCE)).user().getId();
        Long userB = ((SocialLoginTxHelper.LoggedIn) service.login(
                identity(SocialProvider.GOOGLE, "google-add-b", "add-b@gmail.com"), null, NONCE)).user().getId();
        VerifiedSocialIdentity kakao = new VerifiedSocialIdentity(SocialProvider.KAKAO, "kakao-add", null, false, "카카오");

        List<Object> results = runConcurrently(
                () -> { loginMethodService.add(userA, kakao); return "A"; },
                () -> { loginMethodService.add(userB, kakao); return "B"; });

        assertThat(results).filteredOn(String.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(RuntimeException.class::isInstance).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM social_accounts WHERE provider = 'KAKAO' AND provider_user_id = 'kakao-add'",
                Integer.class)).isEqualTo(1);
    }

    // 카카오 웹 재확인: 같은 state로 콜백이 동시에 두 번 와도 한 번만 처리되고, 결과는 시작한 회원이 한 번만 가져가야 합니다.
    @Test
    void kakaoWebAuthCallbackProcessesSameStateOnce() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(kakaoWebAuthService, "restApiKey", "rest-key");
        org.springframework.test.util.ReflectionTestUtils.setField(kakaoWebAuthService, "callbackUri", "https://api.salus.test/api/auth/kakao/callback");
        org.mockito.Mockito.when(oAuthService.verifyKakao("kakao-access"))
                .thenReturn(new VerifiedSocialIdentity(SocialProvider.KAKAO, "kakao-web-1", null, false, "카카오"));
        Long userId = ((SocialLoginTxHelper.LoggedIn) service.login(
                identity(SocialProvider.NAVER, "naver-web", "web@gmail.com"), null, NONCE)).user().getId();

        KakaoWebAuthService.Started started = kakaoWebAuthService.start(userId);
        String state = (started.authorizeUrl().split("state=")[1]).split("&")[0];

        List<Object> results = runConcurrently(
                () -> kakaoWebAuthService.handleCallback("code", state, null),
                () -> kakaoWebAuthService.handleCallback("code", state, null));

        assertThat(results).filteredOn(r -> String.valueOf(r).endsWith("result=success")).hasSize(1);
        assertThat(results).filteredOn(r -> String.valueOf(r).endsWith("result=error")).hasSize(1);

        assertThatThrownBy(() -> kakaoWebAuthService.complete(userId + 1, started.requestToken()))
                .isInstanceOf(OAuthVerificationException.class);
        VerifiedSocialIdentity confirmed = kakaoWebAuthService.complete(userId, started.requestToken());
        assertThat(confirmed.providerUserId()).isEqualTo("kakao-web-1");
        assertThat(confirmed.identityConfirmedAt()).isNotNull();
        assertThatThrownBy(() -> kakaoWebAuthService.complete(userId, started.requestToken()))
                .isInstanceOf(OAuthVerificationException.class);
    }

    private String insertLinkRequest(Long targetUserId, String pendingKakaoId) {
        String token = "manual-" + targetUserId + "-" + System.nanoTime();
        // 앱은 시각을 자기 시계(Asia/Seoul)로 직접 저장합니다. MySQL NOW()는 서버 시간대(UTC 등)라 쓰면 안 됩니다.
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("INSERT INTO link_requests (token_hash, flow_nonce_hash, purpose, pending_provider, "
                        + "pending_provider_user_id, pending_email, pending_email_verified, target_user_id, created_at, expires_at) "
                        + "VALUES (?, ?, 'LOGIN_LINK', 'KAKAO', ?, NULL, FALSE, ?, ?, ?)",
                SocialLoginTxHelper.sha256(token), SocialLoginTxHelper.sha256(NONCE), pendingKakaoId, targetUserId,
                now, now.plusMinutes(10));
        return token;
    }

    // 두 작업을 최대한 동시에 시작하고, 결과(또는 던진 예외)를 모읍니다.
    @SafeVarargs
    private List<Object> runConcurrently(Callable<Object>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean isLockFree(String email) {
        Integer free = jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class, EmailSignupLock.lockName(email));
        return free != null && free == 1;
    }

    private boolean getLock(Connection connection, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private void releaseLock(Connection connection, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, name);
            statement.executeQuery().close();
        }
    }

    private void assertLinkCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AccountLinkException.class)
                .satisfies(e -> assertThat(((AccountLinkException) e).getCode()).isEqualTo(code));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private VerifiedSocialIdentity identity(SocialProvider provider, String id, String email) {
        return new VerifiedSocialIdentity(provider, id, email, true, "테스트");
    }
}
