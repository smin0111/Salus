package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.Callable;

/**
 * 저장된 근거가 없는 "처음 보는 요리"를 웹에서 새로 찾는 횟수를 하루 단위로 제한합니다.
 *
 * <p>웹 검색·페이지 확인·조리 순서 요약에 30~60초씩 서버(LLM)를 쓰므로 무료 사용자는 하루 횟수를 정합니다.
 * 이미 찾아 둔 요리(메모리 캐시·DB 근거)를 다시 묻는 것은 세지 않습니다. PLUS 회원은 제한하지 않습니다.
 * 로그인 사용자는 사용자 ID, 게스트는 IP 기준으로 세며(ChatRateLimitService와 같은 키), 날짜는 한국 시간으로 바뀝니다.
 *
 * <p>안전 기능이 아니라 비용 제한이므로 Redis를 읽지 못하면 막지 않고 허용합니다(경고 로그만 남김).
 */
@Slf4j
@Component
class NewDishSearchQuota {

    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Seoul");
    private static final String KEY_PREFIX = "salus:new-dish-search:";
    // 채팅 요청을 처리하는 작업 스레드에 묶어 두는 "누가 물었는지" 키입니다("user:1", "guest:1.2.3.4").
    private static final ThreadLocal<String> CURRENT_SUBJECT = new ThreadLocal<>();
    // 한 질문에서 대안 레시피를 다시 찾는 등 검색을 여러 번 해도 한 번만 셉니다.
    private static final ThreadLocal<Boolean> CONSUMED = new ThreadLocal<>();

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final int memberDailyLimit;
    private final int guestDailyLimit;

    @Autowired
    NewDishSearchQuota(ObjectProvider<StringRedisTemplate> redis,
                       UserRepository userRepository,
                       @Value("${recipe.agent.new-dish-search.member-daily-limit:5}") int memberDailyLimit,
                       @Value("${recipe.agent.new-dish-search.guest-daily-limit:3}") int guestDailyLimit) {
        this(redis.getIfAvailable(), userRepository, memberDailyLimit, guestDailyLimit);
    }

    NewDishSearchQuota(StringRedisTemplate redis, UserRepository userRepository, int memberDailyLimit, int guestDailyLimit) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.memberDailyLimit = Math.max(0, memberDailyLimit);
        this.guestDailyLimit = Math.max(0, guestDailyLimit);
    }

    /** 작업 스레드에 요청자 키를 묶은 채로 실행합니다. 키가 없으면(평가·내부 호출) 제한하지 않습니다. */
    static <T> T bind(String subject, Callable<T> work) throws Exception {
        String previous = CURRENT_SUBJECT.get();
        Boolean previousConsumed = CONSUMED.get();
        CURRENT_SUBJECT.set(subject);
        CONSUMED.remove();
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT_SUBJECT.remove();
            } else {
                CURRENT_SUBJECT.set(previous);
            }
            if (previousConsumed == null) {
                CONSUMED.remove();
            } else {
                CONSUMED.set(previousConsumed);
            }
        }
    }

    /**
     * 현재 요청자의 오늘 횟수를 하나 씁니다. 다 썼으면 {@link LimitExceededException}을 던지고 웹 검색을 하지 않습니다.
     */
    void consumeForCurrentRequest() {
        String subject = CURRENT_SUBJECT.get();
        if (subject == null || subject.isBlank() || Boolean.TRUE.equals(CONSUMED.get()) || isPlusMember(subject)) {
            return;
        }
        int limit = subject.startsWith("user:") ? memberDailyLimit : guestDailyLimit;
        if (redis == null) {
            log.warn("[NewDishSearchQuota] Redis unavailable; allowing search.");
            return;
        }
        String key = KEY_PREFIX + LocalDate.now(QUOTA_ZONE) + ":" + subject;
        Long used;
        try {
            used = redis.opsForValue().increment(key);
            redis.expire(key, Duration.ofDays(2));
        } catch (RuntimeException e) {
            log.warn("[NewDishSearchQuota] Redis unavailable; allowing search. category={}", e.getClass().getSimpleName());
            return;
        }
        if (used != null && used > limit) {
            try {
                redis.opsForValue().decrement(key);
            } catch (RuntimeException ignored) {
                // 되돌리지 못해도 이미 넘은 상태라 결과는 같습니다.
            }
            log.info("[NewDishSearchQuota] Daily limit reached. subjectType={}, limit={}",
                    subject.startsWith("user:") ? "member" : "guest", limit);
            throw new LimitExceededException(limit);
        }
        CONSUMED.set(Boolean.TRUE);
    }

    // PLUS 회원은 세지 않습니다. 회원 정보를 읽지 못하면 무료 회원처럼 셉니다.
    private boolean isPlusMember(String subject) {
        if (!subject.startsWith("user:") || userRepository == null) {
            return false;
        }
        try {
            long userId = Long.parseLong(subject.substring("user:".length()));
            return userRepository.findById(userId).map(user -> user.getGrade() == UserGrade.PLUS).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String limitReachedReply(int limit) {
        return "오늘 새 요리 찾기(하루 " + limit + "회)를 모두 사용했어요. 이미 찾아 둔 요리와 저장된 레시피는 계속 물어볼 수 있고, "
                + "새 요리 찾기는 내일 다시 할 수 있어요.";
    }

    /** 오늘 새 요리 찾기 횟수를 다 쓴 경우입니다. */
    static final class LimitExceededException extends RuntimeException {
        private final int limit;

        LimitExceededException(int limit) {
            super("new dish search daily limit reached");
            this.limit = limit;
        }

        int limit() {
            return limit;
        }
    }
}
