package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.domain.ConsentType;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL에서 가입 동의 기록을 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V27 적용과 엔티티 매핑(ddl-auto=validate), 같은 버전을 두 번 보내도
 * 한 행만 남는지(유니크 키), 회원을 지우면 동의 기록도 함께 지워지는지(FK CASCADE).
 *
 * 실행 예: SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... mvn -Dtest=ConsentMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConsentService.class, TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class ConsentMySqlIT {

    private static final String NAME = "동의 테스트";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private ConsentService consentService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("INSERT INTO users (name, grade, role) VALUES (?, 'BASIC', 'USER')", NAME);
        userId = jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, NAME);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE name = ?", NAME);
    }

    // 처음 동의하는 회원이 동시에 두 번 보내도(둘 다 행이 없다고 보는 경우) 둘 다 성공하고 네 행만 남습니다.
    @Test
    void concurrentFirstAgreeBothSucceed() throws Exception {
        ConsentService.AgreeRequest request = new ConsentService.AgreeRequest(
                ConsentService.CURRENT_VERSIONS.get(ConsentType.TERMS),
                ConsentService.CURRENT_VERSIONS.get(ConsentType.PRIVACY),
                ConsentService.CURRENT_VERSIONS.get(ConsentType.HEALTH_INFO),
                true);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<ConsentService.ConsentStatus>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return consentService.agree(userId, request);
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<ConsentService.ConsentStatus> result : results) {
                assertThat(result.get().required()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents WHERE user_id = ?", Integer.class, userId)).isEqualTo(4);
    }

    // 기존 회원은 동의가 필요하고, 동의하면 네 행이 생기며 다시 보내도 늘지 않습니다. 회원을 지우면 함께 지워집니다.
    @Test
    void agreeIsIdempotentAndCascadesOnUserDeletion() {
        assertThat(consentService.status(userId).required()).isTrue();
        ConsentService.AgreeRequest request = new ConsentService.AgreeRequest(
                ConsentService.CURRENT_VERSIONS.get(ConsentType.TERMS),
                ConsentService.CURRENT_VERSIONS.get(ConsentType.PRIVACY),
                ConsentService.CURRENT_VERSIONS.get(ConsentType.HEALTH_INFO),
                true);

        assertThat(consentService.agree(userId, request).required()).isFalse();
        consentService.agree(userId, request);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents WHERE user_id = ?", Integer.class, userId)).isEqualTo(4);
        consentService.requireConsent(userId);

        // 동시에 두 번 보내도 둘 다 성공하고 행은 늘지 않습니다(INSERT ... ON DUPLICATE KEY).
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<ConsentService.ConsentStatus>> results = pool.invokeAll(java.util.List.of(
                    () -> consentService.agree(userId, request), () -> consentService.agree(userId, request)));
            for (java.util.concurrent.Future<ConsentService.ConsentStatus> result : results) {
                assertThat(result.get().required()).isFalse();
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents WHERE user_id = ?", Integer.class, userId)).isEqualTo(4);

        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents WHERE user_id = ?", Integer.class, userId)).isZero();
    }
}
