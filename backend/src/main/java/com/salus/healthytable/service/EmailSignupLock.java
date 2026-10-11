package com.salus.healthytable.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 같은 검증 이메일로 들어온 첫 로그인(회원 생성)을 한 번에 하나씩 처리하기 위한 MySQL 이름 잠금입니다.
 *
 * MySQL GET_LOCK은 트랜잭션이 아니라 DB 연결(세션)에 걸리고, 커밋·롤백으로 풀리지 않습니다. 그래서
 * - 잠금 전용 연결을 따로 빌려 그 연결에서만 획득·해제하고,
 * - 회원 생성 트랜잭션은 그 바깥에서(다른 연결로) 끝까지 커밋한 뒤에 해제합니다.
 * 트랜잭션 안에서 잠그고 finally에서 풀면 커밋 전에 풀려, 다음 요청이 커밋 안 된 회원을 못 보고 또 만들 수 있습니다.
 *
 * 잠금을 얻지 못하면(시간 초과·오류) 가입을 강행하지 않고 예외를 던집니다.
 * 커넥션 풀에 반납하는 것만으로는 MySQL 세션이 끝나지 않아 잠금이 남습니다. 그래서 해제는 finally에서 하고,
 * 해제를 확인하지 못하면 그 연결을 풀에서 폐기(abort)해 세션을 끝냅니다. 그래야 잠금이 다른 요청에 남지 않습니다.
 * 여러 앱 서버가 같은 MySQL 하나를 쓸 때 서버 사이에서도 공유됩니다.
 */
@Component
@RequiredArgsConstructor
public class EmailSignupLock {

    static final int TIMEOUT_SECONDS = 5;
    private static final String PREFIX = "salus-signup-";

    private final DataSource dataSource;

    public <T> T withLock(String email, Supplier<T> action) {
        // 바깥에 트랜잭션이 있으면 action의 커밋이 잠금 해제보다 늦어지므로 허용하지 않습니다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("EmailSignupLock은 트랜잭션 밖에서 호출해야 합니다.");
        }
        String name = lockName(email);
        try (Connection connection = dataSource.getConnection()) {
            if (!acquire(connection, name)) {
                throw new SignupBusyException();
            }
            try {
                return action.get();
            } finally {
                release(connection, name);
            }
        } catch (SQLException e) {
            throw new SignupBusyException(e);
        }
    }

    // 이메일 원문 대신 해시를 씁니다(MySQL 잠금 이름은 64자 제한, 서버 상태 조회에 이메일이 보이지 않게).
    static String lockName(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest).substring(0, 48);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    // GET_LOCK 결과: 1 획득, 0 시간 초과, NULL 오류
    private boolean acquire(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, name);
            statement.setInt(2, TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1 && !result.wasNull();
            }
        }
    }

    // RELEASE_LOCK 결과가 1이 아니면(오류 포함) 연결을 폐기해 MySQL 세션과 함께 잠금을 정리합니다.
    // 원래 작업의 결과·예외는 덮지 않습니다.
    private void release(Connection connection, String name) {
        boolean released = false;
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                released = result.next() && result.getInt(1) == 1;
            }
        } catch (SQLException ignored) {
            released = false;
        }
        if (!released) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException ignored) {
                // 이미 닫힌 연결이면 세션도 끝났습니다.
            }
        }
    }

    /**
     * 잠금을 얻지 못해 가입을 진행하지 않았음을 나타냅니다. 클라이언트에는 잠시 후 다시 시도하라고 안내합니다.
     */
    public static class SignupBusyException extends RuntimeException {
        public SignupBusyException() {
            super("signup_lock_unavailable");
        }

        public SignupBusyException(Throwable cause) {
            super("signup_lock_unavailable", cause);
        }
    }
}
