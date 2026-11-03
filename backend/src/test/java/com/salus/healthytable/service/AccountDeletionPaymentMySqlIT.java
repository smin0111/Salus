package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회원 탈퇴와 결제 주문이 실제 MySQL에서 엇갈리지 않는지 확인합니다.
 * - 최근 미완료 주문이 있으면 탈퇴 트랜잭션이 회원 행을 잠근 뒤 보고 막습니다(아무것도 지우지 않음).
 * - 탈퇴가 회원 행을 잠근 동안 들어온 새 주문은 외래 키 확인에서 기다리다 탈퇴 뒤 실패해, 회원 없는 주문이 생기지 않습니다.
 *
 * 실행 예: SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=AccountDeletionPaymentMySqlIT test   (Skipped 0 확인)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserAccountService.class, TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class AccountDeletionPaymentMySqlIT {

    private static final String NAME = "탈퇴결제IT";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @MockBean
    private RecipeWorkSessionService recipeWorkSessionService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long userId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("INSERT INTO users (name, grade, role) VALUES (?, 'BASIC', 'USER')", NAME);
        userId = jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, NAME);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_orders WHERE merchant_uid LIKE 'it_del_%'");
        jdbc.update("DELETE FROM users WHERE name = ?", NAME);
    }

    @Test
    void recentPendingOrderBlocksDeletionAndKeepsData() {
        insertOrder("it_del_recent", LocalDateTime.now().minusMinutes(5));

        assertThatThrownBy(() -> userAccountService.deleteAccount(userId))
                .isInstanceOf(UserAccountService.PaymentInProgressException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(1);
    }

    // 30분이 지난 미완료 주문(결제창을 닫고 떠난 주문)은 막지 않습니다. 주문은 회원 연결만 끊겨 남습니다.
    @Test
    void oldPendingOrderDoesNotBlockDeletion() {
        insertOrder("it_del_old", LocalDateTime.now().minusMinutes(40));

        userAccountService.deleteAccount(userId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM payment_orders WHERE merchant_uid = 'it_del_old'", Long.class)).isNull();
    }

    // 탈퇴가 회원 행을 잠근 상태에서 들어온 주문 저장은 기다렸다가 탈퇴 뒤 외래 키 오류로 실패합니다.
    @Test
    void orderInsertedWhileDeletionHoldsLockFailsAfterDeletion() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> deletion = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            userAccountService.deleteAccount(userId);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Throwable> order = CompletableFuture.supplyAsync(() -> {
            try {
                insertOrder("it_del_race", LocalDateTime.now());
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        Thread.sleep(500);
        assertThat(order.isDone()).as("주문 저장이 탈퇴 잠금을 기다려야 합니다").isFalse();
        release.countDown();
        deletion.get(20, TimeUnit.SECONDS);

        assertThat(order.get(20, TimeUnit.SECONDS)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_orders WHERE merchant_uid = 'it_del_race'", Integer.class))
                .isZero();
    }

    private void insertOrder(String merchantUid, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO payment_orders (merchant_uid, user_id, product_code, amount, plus_days, status, created_at) "
                + "VALUES (?, ?, 'PLUS_30', 9900, 30, 'PENDING', ?)", merchantUid, userId, createdAt);
    }
}
