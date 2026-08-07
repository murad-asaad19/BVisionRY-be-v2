package com.bvisionry.aiconfig.service;

import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The login backoff against a REAL Redis — i.e. the path that actually runs in
 * production, and the only one that executes the Lua in
 * {@code RateLimitService.RECORD_LOGIN_FAILURE}.
 *
 * <p>{@link RateLimitServiceTest} covers the same rules on the in-memory fallback, but
 * that fallback exists only for "Redis is down" and for unit tests. The distributed
 * path is a hand-written script: nothing else in the suite compiles it, so a typo or a
 * Lua arithmetic slip would ship green and only show up as a rate limiter that silently
 * stops limiting. These tests are the compiler.
 */
@EnabledIfDockerAvailable
class RateLimitServiceRedisIntegrationTest {

    private static final String VICTIM = "victim@example.com";

    @SuppressWarnings("resource") // closed in tearDown
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RateLimitService rateLimitService;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        // Same 5-free / 900s-memory configuration RateLimitServiceTest uses.
        rateLimitService = new RateLimitService(5, 10, 10, 10, 5, 50, 7, 30, 10, 5, 3, 5, 4, 5, 900);
        // The template is @Autowired(required = false) precisely so the limiter still
        // constructs without Redis; injecting it here is what selects the Redis path.
        ReflectionTestUtils.setField(rateLimitService, "redisTemplate", redis);
    }

    /** The schedule, unchanged, as the script computes it. */
    @Test
    void theFreeBudgetArmsNothingAndTheFailureThatEndsItArmsTheFirstRung() {
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimitService.recordLoginFailure(VICTIM)).isZero();
        }
        rateLimitService.checkLoginBackoff(VICTIM); // still free

        assertThat(rateLimitService.recordLoginFailure(VICTIM)).isEqualTo(5);
        assertThatThrownBy(() -> rateLimitService.checkLoginBackoff(VICTIM))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("Too many failed sign-in attempts. Try again later.");
    }

    /**
     * THE BURST BOUND, distributed. Across instances there is no shared lock — the
     * atomicity has to come from the script itself, or N concurrent failures each INCR
     * and the ladder jumps to {@code backoff(N)} in one round trip.
     */
    @Test
    void aConcurrentBurstAdvancesTheLadderByAtMostOneRung() throws Exception {
        for (int i = 0; i < 5; i++) {
            rateLimitService.recordLoginFailure(VICTIM);
        }

        int burst = 32;
        ExecutorService pool = Executors.newFixedThreadPool(burst);
        CountDownLatch ready = new CountDownLatch(burst);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < burst; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return rateLimitService.recordLoginFailure(VICTIM);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> armed = new ArrayList<>();
            for (Future<Integer> result : results) {
                int delay = result.get(30, TimeUnit.SECONDS);
                if (delay > 0) {
                    armed.add(delay);
                }
            }
            assertThat(armed)
                    .as("exactly one of %d simultaneous failures may buy a rung, and only the next one",
                            burst)
                    .containsExactly(5);
        } finally {
            pool.shutdownNow();
        }

        // The counter must have moved by exactly one, not by the burst size.
        assertThat(redis.opsForValue().get("rl:login-fail:" + VICTIM)).isEqualTo("6");
        assertThat(redis.getExpire("rl:login-block:" + VICTIM)).isBetween(1L, 5L);
    }

    /**
     * The delay is derived from the counter inside the script, so the clamp that keeps
     * the exponent from overflowing has to live there too. Drive the counter far past
     * the cap and assert it pins rather than wrapping negative (a negative TTL is an
     * error to Redis; a wrapped one would be a free pass).
     */
    @Test
    void theCounterAndTheDelayBothPinAtTheCapInsteadOfOverflowing() {
        redis.opsForValue().set("rl:login-fail:" + VICTIM, String.valueOf(Long.MAX_VALUE - 1));

        int delay = rateLimitService.recordLoginFailure(VICTIM);

        assertThat(delay).isEqualTo(900);
        assertThat(Long.parseLong(redis.opsForValue().get("rl:login-fail:" + VICTIM)))
                .isEqualTo(5 + 20 + 1); // loginFreeAttempts + LOGIN_BACKOFF_MAX_STEPS + 1
        assertThat(redis.getExpire("rl:login-block:" + VICTIM)).isBetween(1L, 900L);
    }

    /** Both keys go, or a "cleared" account is still refused by a surviving block. */
    @Test
    void clearingWipesBothTheCounterAndTheLiveRefusal() {
        for (int i = 0; i < 6; i++) {
            rateLimitService.recordLoginFailure(VICTIM);
        }
        assertThat(redis.hasKey("rl:login-block:" + VICTIM)).isTrue();

        rateLimitService.clearLoginFailures(VICTIM);

        assertThat(redis.hasKey("rl:login-fail:" + VICTIM)).isFalse();
        assertThat(redis.hasKey("rl:login-block:" + VICTIM)).isFalse();
        rateLimitService.checkLoginBackoff(VICTIM);
    }

    @Test
    void oneThrottledAccountLeavesEveryOtherAccountSignable() {
        for (int i = 0; i < 20; i++) {
            rateLimitService.recordLoginFailure(VICTIM);
        }

        rateLimitService.checkLoginBackoff("bystander@example.com");
    }
}
