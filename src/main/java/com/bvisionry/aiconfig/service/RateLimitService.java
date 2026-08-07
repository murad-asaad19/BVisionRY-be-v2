package com.bvisionry.aiconfig.service;

import com.bvisionry.common.exception.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-key rate limiter.
 *
 * <p><b>Distributed by default (F4/F8/F15/F21/F27/F37).</b> When a Redis connection
 * is available the counter lives in Redis, so the limit is enforced across ALL
 * backend instances and survives a redeploy. The increment + first-write expiry is
 * a single atomic Lua script, so there is no INCR/EXPIRE race that could leak a
 * never-expiring key.
 *
 * <p><b>Graceful degradation.</b> If Redis is unreachable, each check falls back to
 * the in-process sliding window below — the platform degrades to PER-INSTANCE
 * limiting (still bounded) rather than failing open (no limit) or failing closed
 * (locking everyone out). The in-memory path is also the sole path in unit tests,
 * which construct this service directly with no Redis wired.
 */
@Service
@Slf4j
public class RateLimitService {

    /** Atomic INCR + first-write EXPIRE; returns the new counter value for the window. */
    private static final RedisScript<Long> INCREMENT_WINDOW = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    /**
     * Atomic INCR + EXPIRE-on-EVERY-write: the TTL is pushed forward by each hit, so
     * the counter decays only after a full idle period rather than at a fixed window
     * boundary. Used by the login backoff, where "quiet for 15 minutes" must forget.
     */
    private static final RedisScript<Long> INCREMENT_DECAYING = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "return c",
            Long.class);

    /**
     * Optional — present only when a Redis connection is configured. Field-injected
     * (not via the constructor) so the limiter still constructs cleanly in unit tests
     * and simply uses the in-memory path when Redis is absent.
     */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final int tryItOutRequestsPerMinute;
    private final int evaluationRequestsPerMinute;
    private final int authRequestsPerMinute;
    private final int surveySubmitRequestsPerMinute;
    private final int publicAssessmentRequestsPerMinute;
    private final int publicAssessmentSaveRequestsPerMinute;
    private final int businessCardRequestsPerMinute;
    private final int refreshRequestsPerMinute;
    private final int acceptRequestsPerHour;
    private final int passwordResetRequestsPerHour;
    private final int contactRequestsPerMinute;
    private final int leadMagnetRequestsPerMinute;
    private final int errorReportRequestsPerMinute;
    private final int loginFreeAttempts;
    private final int loginFailureTtlSeconds;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> tryItOutWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> evaluationWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> authWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> surveySubmitWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> publicAssessmentWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> publicAssessmentSaveWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> businessCardWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> refreshWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> acceptWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> passwordResetWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> contactWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> leadMagnetWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> errorReportWindows =
            new ConcurrentHashMap<>();

    public RateLimitService(
            @Value("${bvisionry.rate-limit.try-it-out.requests-per-minute:10}") int tryItOutRequestsPerMinute,
            @Value("${bvisionry.rate-limit.evaluation.requests-per-minute:30}") int evaluationRequestsPerMinute,
            @Value("${bvisionry.rate-limit.auth.requests-per-minute:10}") int authRequestsPerMinute,
            @Value("${bvisionry.rate-limit.survey-submit.requests-per-minute:10}") int surveySubmitRequestsPerMinute,
            @Value("${bvisionry.rate-limit.public-assessment.requests-per-minute:5}") int publicAssessmentRequestsPerMinute,
            @Value("${bvisionry.rate-limit.public-assessment-save.requests-per-minute:60}") int publicAssessmentSaveRequestsPerMinute,
            @Value("${bvisionry.rate-limit.business-card.requests-per-minute:60}") int businessCardRequestsPerMinute,
            @Value("${bvisionry.rate-limit.refresh.requests-per-minute:30}") int refreshRequestsPerMinute,
            @Value("${bvisionry.rate-limit.accept.requests-per-hour:10}") int acceptRequestsPerHour,
            @Value("${bvisionry.rate-limit.password-reset.requests-per-hour:5}") int passwordResetRequestsPerHour,
            @Value("${bvisionry.rate-limit.contact.requests-per-minute:100}") int contactRequestsPerMinute,
            @Value("${bvisionry.rate-limit.lead-magnet.requests-per-minute:20}") int leadMagnetRequestsPerMinute,
            @Value("${bvisionry.rate-limit.error-report.requests-per-minute:30}") int errorReportRequestsPerMinute,
            @Value("${bvisionry.rate-limit.login.free-attempts:5}") int loginFreeAttempts,
            @Value("${bvisionry.rate-limit.login.failure-ttl-seconds:900}") int loginFailureTtlSeconds) {
        this.tryItOutRequestsPerMinute = tryItOutRequestsPerMinute;
        this.evaluationRequestsPerMinute = evaluationRequestsPerMinute;
        this.authRequestsPerMinute = authRequestsPerMinute;
        this.surveySubmitRequestsPerMinute = surveySubmitRequestsPerMinute;
        this.publicAssessmentRequestsPerMinute = publicAssessmentRequestsPerMinute;
        this.publicAssessmentSaveRequestsPerMinute = publicAssessmentSaveRequestsPerMinute;
        this.businessCardRequestsPerMinute = businessCardRequestsPerMinute;
        this.refreshRequestsPerMinute = refreshRequestsPerMinute;
        this.acceptRequestsPerHour = acceptRequestsPerHour;
        this.passwordResetRequestsPerHour = passwordResetRequestsPerHour;
        this.contactRequestsPerMinute = contactRequestsPerMinute;
        this.leadMagnetRequestsPerMinute = leadMagnetRequestsPerMinute;
        this.errorReportRequestsPerMinute = errorReportRequestsPerMinute;
        this.loginFreeAttempts = loginFreeAttempts;
        this.loginFailureTtlSeconds = loginFailureTtlSeconds;
    }

    /**
     * Checks the "try it out" rate limit for a given key (user ID or IP).
     * Throws RateLimitExceededException if limit exceeded.
     */
    public void checkTryItOutLimit(String key) {
        checkLimit(tryItOutWindows, key, tryItOutRequestsPerMinute, 60, "try-it-out");
    }

    /**
     * Checks the evaluation rate limit for a given key (org ID or user ID).
     * Throws RateLimitExceededException if limit exceeded.
     */
    public void checkEvaluationLimit(String key) {
        checkLimit(evaluationWindows, key, evaluationRequestsPerMinute, 60, "evaluation");
    }

    /**
     * Checks the auth rate limit for a given key (IP address or email).
     * Throws RateLimitExceededException if limit exceeded.
     */
    public void checkAuthLimit(String key) {
        checkLimit(authWindows, key, authRequestsPerMinute, 60, "authentication");
    }

    /**
     * Checks the public survey submission rate limit. Applied per IP and per token+IP.
     */
    public void checkSurveySubmitLimit(String key) {
        checkLimit(surveySubmitWindows, key, surveySubmitRequestsPerMinute, 60, "survey-submit");
    }

    /**
     * Checks the public-assessment rate limit (anonymous session create + submit +
     * retake). Applied per IP and per token+IP, like the survey limit.
     */
    public void checkPublicAssessmentLimit(String key) {
        checkLimit(publicAssessmentWindows, key, publicAssessmentRequestsPerMinute, 60, "public-assessment");
    }

    /**
     * Checks the public-assessment ANSWER-SAVE rate limit — its own, generous bucket
     * so frequent legitimate autosaves are not throttled as if they were submits,
     * while still bounding a request flood. Answer saves upsert by
     * (submission, question), so they cannot grow storage; this only caps request rate.
     */
    public void checkPublicAssessmentSaveLimit(String key) {
        checkLimit(publicAssessmentSaveWindows, key, publicAssessmentSaveRequestsPerMinute, 60, "public-assessment-save");
    }

    /**
     * Rate limit for the public, unauthenticated business-card lookup. Its own
     * per-IP bucket with a generous default (60/min) because a card may be
     * scanned repeatedly at an event, but still bounded so the PII endpoint can't
     * be enumerated or scraped unthrottled.
     */
    public void checkBusinessCardLimit(String key) {
        checkLimit(businessCardWindows, key, businessCardRequestsPerMinute, 60, "business-card");
    }

    /**
     * Rate limit for token-refresh and similar high-frequency auth-adjacent endpoints.
     * Higher per-minute ceiling than {@link #checkAuthLimit} because legitimate clients
     * silently refresh on app focus / tab restore.
     */
    public void checkRefreshLimit(String key) {
        checkLimit(refreshWindows, key, refreshRequestsPerMinute, 60, "refresh");
    }

    /**
     * Rate limit for invitation/join-link acceptance. Hourly window because legitimate
     * users hit these once per onboarding; an attacker brute-forcing tokens needs to be
     * shut down hard.
     */
    public void checkAcceptLimit(String key) {
        checkLimit(acceptWindows, key, acceptRequestsPerHour, 3600, "accept");
    }

    /**
     * Rate limit for "forgot password" email requests. Hourly window, checked
     * per IP and per target email: the endpoint sends mail and must not reveal
     * account existence, so both inbox-bombing a victim and scanning from one
     * host need the same hard ceiling.
     */
    public void checkPasswordResetLimit(String key) {
        checkLimit(passwordResetWindows, key, passwordResetRequestsPerHour, 3600, "password-reset");
    }

    /**
     * Rate limit for the public marketing "Contact Us" form. Its own per-IP bucket
     * and ceiling so contact traffic is isolated from other anonymous limiters and
     * can be tuned independently against inbox-flooding bots.
     */
    public void checkContactLimit(String key) {
        checkLimit(contactWindows, key, contactRequestsPerMinute, 60, "contact");
    }

    /**
     * Rate limit for the public web-tier error-report ingest
     * ({@code POST /api/v1/error-events}). Its own per-IP bucket so a browser stuck
     * in a render-crash loop — or a bot pointed at the endpoint — cannot flood the
     * error store and bury the real regression signal it exists to carry. The
     * ceiling is deliberately higher than contact's: a genuinely broken page can
     * legitimately report a handful of errors per navigation.
     */
    public void checkErrorReportLimit(String key) {
        checkLimit(errorReportWindows, key, errorReportRequestsPerMinute, 60, "error-report");
    }

    /**
     * Rate limit for the public lead-magnet capture ("the science behind the 11
     * pillars"). Its own per-IP bucket and ceiling so it is isolated from the AI
     * "try it out" limiter and can be tuned independently against a bot flooding the
     * lead table or the PDF mailer.
     */
    public void checkLeadMagnetLimit(String key) {
        checkLimit(leadMagnetWindows, key, leadMagnetRequestsPerMinute, 60, "lead-magnet");
    }

    // ---------------------------------------------------------------------------
    // Per-account password-login backoff
    // ---------------------------------------------------------------------------
    //
    // Deliberately NOT a lock. No flag on the user, no admin-unlock flow: a hard
    // lockout hands an attacker a denial-of-service against any address they can
    // guess. Failures decay by TTL, so a quiet account always recovers by itself.
    //
    // Keyed on the SUBMITTED email whether or not that account exists, and the
    // refusal is one constant string. If the throttle only bit on real accounts —
    // or said anything different for them — its presence would itself be an
    // account-enumeration oracle, which is the thing the rest of the auth surface
    // (always-204 forgot-password, one "Invalid email or password") pays to avoid.
    //
    // This is the INNER layer. The per-IP `authentication` bucket above still caps
    // how fast any single host may try, independently of this.

    /** Delay after the first throttled failure; doubles per failure from there. */
    private static final int LOGIN_BACKOFF_BASE_SECONDS = 5;
    /** Ceiling on the delay — long enough to kill a brute force, short enough to forgive. */
    private static final int LOGIN_BACKOFF_MAX_SECONDS = 900;
    /**
     * Past this many throttled failures the delay is already pinned at the cap, so
     * counting further buys nothing. Bounds both the shift below and the stored
     * counter, so hammering a throttled account cannot run the exponent into overflow.
     */
    private static final int LOGIN_BACKOFF_MAX_STEPS = 20;
    /**
     * The one and only refusal. Constant on purpose: a message carrying the attempt
     * count or the remaining delay would differ between accounts at different
     * counter states, and byte-identical refusals are the whole point.
     */
    private static final String LOGIN_THROTTLED_MESSAGE =
            "Too many failed sign-in attempts. Try again later.";

    /** In-memory fallback state. {@code blockedUntil} is EPOCH while still inside the free attempts. */
    private record LoginBackoff(int failures, Instant countExpiresAt, Instant blockedUntil) {}

    private final ConcurrentHashMap<String, LoginBackoff> loginBackoffs = new ConcurrentHashMap<>();

    /**
     * Refuse a password login whose account is currently backing off. Read-only —
     * it never counts the attempt; only {@link #recordLoginFailure} does.
     *
     * @param emailKey the submitted email, lowercased and trimmed, existing or not
     */
    public void checkLoginBackoff(String emailKey) {
        StringRedisTemplate redis = this.redisTemplate;
        if (redis != null) {
            try {
                if (Boolean.TRUE.equals(redis.hasKey(loginBlockKey(emailKey)))) {
                    throw new RateLimitExceededException(LOGIN_THROTTLED_MESSAGE);
                }
                return;
            } catch (RateLimitExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Redis rate-limit backend unavailable ({}) — falling back to per-instance login backoff",
                        e.getMessage());
            }
        }
        LoginBackoff state = loginBackoffs.get(emailKey);
        if (state != null && state.blockedUntil().isAfter(Instant.now())) {
            throw new RateLimitExceededException(LOGIN_THROTTLED_MESSAGE);
        }
    }

    /** Count one rejected password login and (re)arm the backoff for that email. */
    public void recordLoginFailure(String emailKey) {
        StringRedisTemplate redis = this.redisTemplate;
        if (redis != null) {
            try {
                Long count = redis.execute(INCREMENT_DECAYING, List.of(loginFailKey(emailKey)),
                        String.valueOf(loginFailureTtlSeconds));
                if (count != null) {
                    int delay = loginBackoffSeconds(clampFailures(count));
                    if (delay > 0) {
                        redis.opsForValue().set(loginBlockKey(emailKey), "1", Duration.ofSeconds(delay));
                    }
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Redis rate-limit backend unavailable ({}) — falling back to per-instance login backoff",
                        e.getMessage());
            }
        }
        Instant now = Instant.now();
        loginBackoffs.compute(emailKey, (k, prev) -> {
            int failures = prev == null || !prev.countExpiresAt().isAfter(now)
                    ? 1
                    : clampFailures(prev.failures() + 1L);
            int delay = loginBackoffSeconds(failures);
            return new LoginBackoff(failures, now.plusSeconds(loginFailureTtlSeconds),
                    delay > 0 ? now.plusSeconds(delay) : Instant.EPOCH);
        });
    }

    /**
     * Wipe an email's failure history after a successful sign-in. Only ever reachable
     * when the account was not throttled, so it cannot be used to clear a live backoff.
     */
    public void clearLoginFailures(String emailKey) {
        loginBackoffs.remove(emailKey);
        StringRedisTemplate redis = this.redisTemplate;
        if (redis == null) {
            return;
        }
        try {
            redis.delete(List.of(loginFailKey(emailKey), loginBlockKey(emailKey)));
        } catch (RuntimeException e) {
            log.warn("Redis rate-limit backend unavailable ({}) — login failure counter not cleared remotely",
                    e.getMessage());
        }
    }

    /**
     * Seconds to refuse for, after {@code failures} rejected attempts: 0 while inside
     * the free budget, then {@code 5s, 10s, 20s …} doubling up to the 15-minute cap.
     */
    int loginBackoffSeconds(int failures) {
        int step = failures - loginFreeAttempts;
        if (step <= 0) {
            return 0;
        }
        int shift = Math.min(step - 1, LOGIN_BACKOFF_MAX_STEPS);
        return Math.min(LOGIN_BACKOFF_BASE_SECONDS << shift, LOGIN_BACKOFF_MAX_SECONDS);
    }

    /** Pin the counter once the delay has saturated — see {@link #LOGIN_BACKOFF_MAX_STEPS}. */
    private int clampFailures(long failures) {
        return (int) Math.min(failures, loginFreeAttempts + LOGIN_BACKOFF_MAX_STEPS + 1L);
    }

    private static String loginFailKey(String emailKey) {
        return "rl:login-fail:" + emailKey;
    }

    private static String loginBlockKey(String emailKey) {
        return "rl:login-block:" + emailKey;
    }

    /**
     * Enforce a limit: try the shared Redis counter first (cross-instance), and only
     * if Redis is unavailable fall back to the per-instance in-memory window.
     */
    private void checkLimit(ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> windows,
                            String key, int maxRequests, int windowSeconds, String limitType) {
        if (checkLimitRedis(key, maxRequests, windowSeconds, limitType)) {
            return;
        }
        checkLimitInMemory(windows, key, maxRequests, windowSeconds, limitType);
    }

    /**
     * @return {@code true} when Redis authoritatively allowed the request (or threw
     *         on exceed); {@code false} when Redis is unavailable, signalling the
     *         caller to fall back to the in-memory window.
     */
    private boolean checkLimitRedis(String key, int maxRequests, int windowSeconds, String limitType) {
        StringRedisTemplate redis = this.redisTemplate;
        if (redis == null) {
            return false;
        }
        try {
            String redisKey = "rl:" + limitType + ":" + key;
            Long count = redis.execute(INCREMENT_WINDOW, List.of(redisKey), String.valueOf(windowSeconds));
            if (count == null) {
                return false; // treat an unexpected null as "backend unavailable"
            }
            if (count > maxRequests) {
                throw rateLimitExceeded(limitType, maxRequests, windowSeconds);
            }
            return true;
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Redis rate-limit backend unavailable ({}) — falling back to per-instance limiting for '{}'",
                    e.getMessage(), limitType);
            return false;
        }
    }

    /** Per-instance sliding window — fallback only (used when Redis is down or in tests). */
    private void checkLimitInMemory(ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> windows,
                                    String key, int maxRequests, int windowSeconds, String limitType) {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(windowSeconds);

        ConcurrentLinkedDeque<Instant> timestamps = windows.computeIfAbsent(key,
                k -> new ConcurrentLinkedDeque<>());

        // Remove expired entries outside the window
        while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxRequests) {
            throw rateLimitExceeded(limitType, maxRequests, windowSeconds);
        }

        timestamps.addLast(now);
    }

    private static RateLimitExceededException rateLimitExceeded(String limitType, int maxRequests, int windowSeconds) {
        String windowDesc = windowSeconds == 60 ? "minute"
                : windowSeconds == 3600 ? "hour"
                : (windowSeconds + " seconds");
        return new RateLimitExceededException(
                "Rate limit exceeded for " + limitType + ". Maximum " + maxRequests
                        + " requests per " + windowDesc + ".");
    }

    // Deliberately NOT under @SchedulerLock. Unlike the reaper/expiry jobs, this evicts
    // this JVM's own in-memory fallback windows (the fields above), never shared DB/Redis
    // state, so there is nothing to double-process across replicas. A distributed lock
    // would be actively harmful: only the lock-holder would clean its memory while every
    // other replica's deques grew unbounded until OOM. Each instance must run this itself.
    @Scheduled(fixedDelay = 60_000)
    void evictStaleWindows() {
        // Per-minute windows: drop entries older than 60s.
        Instant minuteCutoff = Instant.now().minusSeconds(60);
        evictOlderThan(List.of(tryItOutWindows, evaluationWindows, authWindows,
                surveySubmitWindows, publicAssessmentWindows, publicAssessmentSaveWindows,
                businessCardWindows, refreshWindows, contactWindows, leadMagnetWindows,
                errorReportWindows), minuteCutoff);

        // Per-hour windows: drop entries older than 3600s.
        Instant hourCutoff = Instant.now().minusSeconds(3600);
        evictOlderThan(List.of(acceptWindows, passwordResetWindows), hourCutoff);

        // Login backoff: an entry is dead only once BOTH its counter and its active
        // refusal have expired — a short configured TTL can leave a live block behind
        // a dead counter, and evicting that would hand back a free attempt. Same
        // reasoning as above: this is this JVM's own memory, so every replica runs it.
        Instant now = Instant.now();
        loginBackoffs.entrySet().removeIf(e -> !e.getValue().countExpiresAt().isAfter(now)
                && !e.getValue().blockedUntil().isAfter(now));
    }

    private static void evictOlderThan(
            List<ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>>> allWindows,
            Instant cutoff) {
        for (var windows : allWindows) {
            windows.forEach((key, deque) -> {
                while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                    deque.pollFirst();
                }
                if (deque.isEmpty()) {
                    windows.remove(key, deque);
                }
            });
        }
    }
}
