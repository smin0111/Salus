package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL에서 닉네임 중복 판정을 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V21 적용과 엔티티 매핑(ddl-auto=validate), nickname_normalized의 비교 규칙이
 * utf8mb4_bin인지, 대소문자·전각·한글 자모 조합이 정규화 뒤 같은 닉네임으로 막히는지, 동시에 같은 닉네임을 신청하면
 * DB UNIQUE가 한 명만 통과시키는지.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=NicknameMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NicknameService.class, TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class NicknameMySqlIT {

    private static final String PREFIX = "닉네임 테스트 ";

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
    private NicknameService nicknameService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanUp();
        for (int i = 0; i < 6; i++) {
            jdbc.update("INSERT INTO users (name, grade, role) VALUES (?, 'BASIC', 'USER')", PREFIX + i);
            users.add(jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, PREFIX + i));
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE name LIKE ?", PREFIX + "%");
        users.clear();
    }

    // V21이 적용되고, 중복 판정 컬럼은 utf8mb4_bin 비교 규칙과 UNIQUE를 가집니다.
    @Test
    void migrationV21UsesBinaryCollationAndUniqueKey() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '21' AND success = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COLLATION_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'nickname_normalized'
                """, String.class)).isEqualTo("utf8mb4_bin");
        assertThat(jdbc.queryForObject("""
                SELECT NON_UNIQUE FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'uk_users_nickname_normalized'
                """, Integer.class)).isZero();
    }

    // 대소문자·전각·한글 자모 조합이 달라도 정규화 뒤 같은 닉네임이면 다른 사람은 쓸 수 없습니다(409).
    @Test
    void normalizedDuplicatesAreRejected() {
        nicknameService.set(users.get(0), "ChefKim");
        nicknameService.set(users.get(1), "가나다");

        for (String duplicate : List.of("chefkim", "CHEFKIM", "ＣｈｅｆＫｉｍ")) {
            assertThatThrownBy(() -> nicknameService.set(users.get(2), duplicate))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        }
        // "가나다"를 자모로 입력
        assertThatThrownBy(() -> nicknameService.set(users.get(2), "가나다"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, users.get(0))).isEqualTo("ChefKim");
        assertThat(jdbc.queryForObject("SELECT nickname_normalized FROM users WHERE id = ?", String.class, users.get(0))).isEqualTo("chefkim");
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, users.get(2))).isNull();
        // 내 닉네임은 대소문자만 바꿔 다시 정할 수 있습니다.
        assertThat(nicknameService.set(users.get(0), "CHEFKIM").getNickname()).isEqualTo("CHEFKIM");
    }

    // 6명이 동시에 같은 닉네임을 신청하면 한 명만 통과하고 나머지는 409입니다(미리 확인을 지나쳐도 DB UNIQUE가 막음).
    @Test
    void concurrentRequestsForSameNicknameAllowOnlyOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(users.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Long userId : users) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    nicknameService.set(userId, "동시닉네임");
                    return true;
                } catch (ResponseStatusException e) {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            succeeded += future.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE nickname_normalized = '동시닉네임'", Integer.class))
                .isEqualTo(1);
    }

    // 닉네임이 없는 기존 회원(NULL)은 여러 명이어도 UNIQUE에 걸리지 않습니다.
    @Test
    void usersWithoutNicknameCoexist() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE name LIKE ? AND nickname_normalized IS NULL", Integer.class, PREFIX + "%"))
                .isEqualTo(users.size());
    }
}
