package com.bvisionry.aiconfig.service;

import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.security.LoginBackoffPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
public class RateLimitService implements LoginBackoffPort {

    /** Atomic INCR + first-write EXPIRE; returns the new counter value for the window. */
    private static final RedisScript<Long> INCREMENT_WINDOW = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    /**
     * Login backoff: count one failure and derive its refusal in ONE atomic step.
     *
     * <p>KEYS: {@code 1} = decaying failure counter, {@code 2} = the refusal ("block")
     * key {@link #checkLoginBackoff} reads. ARGV: {@code 1} = counter TTL,
     * {@code 2} = free attempts, {@code 3} = base delay, {@code 4} = max delay,
     * {@code 5} = max steps. Returns the seconds this failure armed, {@code 0} if it
     * armed nothing.
     *
     * <p><b>Why one script and not INCR-then-SET.</b> The controller checks the block
     * key, authenticates, and only then records — so N concurrent bad logins all pass
     * the check before any block exists. If each then merely INCR'd, one burst would
     * walk the counter to N and arm {@code backoff(N)}: a single round trip could pin
     * a victim at the 900s cap instead of serving the 5s/10s/20s… ladder one rung at a
     * time. Deriving the block FROM the counter here, and refusing to count while a
     * refusal is already live, bounds any burst to exactly one rung — the losers of
     * the race read the block the winner just created.
     *
     * <p>The counter's TTL is pushed forward on every hit, counted or not, so "quiet
     * for 15 minutes" forgets while "hammered for an hour" does not.
     */
    private static final RedisScript<Long> RECORD_LOGIN_FAILURE = RedisScript.of(
            "if redis.call('EXISTS', KEYS[2]) == 1 then "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) return 0 end "
                    + "local free = tonumber(ARGV[2]) "
                    + "local steps = tonumber(ARGV[5]) "
                    + "local c = redis.call('INCR', KEYS[1]) "
                    + "if c > free + steps + 1 then "
                    + "c = free + steps + 1 redis.call('SET', KEYS[1], c) end "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "local step = c - free "
                    + "if step <= 0 then return 0 end "
                    + "local delay = math.min(tonumber(ARGV[3]) * 2 ^ math.min(step - 1, steps), "
                    + "tonumber(ARGV[4])) "
                    + "redis.call('SET', KEYS[2], '1', 'EX', delay) "
                    + "return delay",
            Long.class);

    /**
     * Optional — present only when a Redis connection is configured. Field-injected
     * (not via the constructor) so the limiter still constructs cleanly in unit tests
     * and simply uses the in-memory path when Redis is absent.
     */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * The service's only source of "now". Swapped by tests, never in production — and
     * NOT a constructor parameter, so every existing construction site (and the frozen
     * ArchUnit descriptors) stay as they are.
     *
     * <p>It exists because the thing worth proving about this backoff is that a refusal
     * LIFTS. Without a movable clock the only way to observe that is to sleep for the
     * armed delay — 5 seconds at the first rung, 15 minutes at the cap — so in practice
     * nobody asserts it, and "the backoff is deliberately not a lock" becomes a comment
     * no test can contradict.
     */
    private Clock clock = Clock.systemUTC();

    /** Test seam — see {@link #clock}. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

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
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> exerciseSubmitWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> exercisePdfWindows =
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
     * Checks the public EXERCISE submit rate limit. The same shape of anonymous
     * form post as a survey submit, so it is sized by the same property — but on
     * its own bucket, so a flood against one public form never throttles the
     * other. Give it its own property the day the two need different ceilings.
     */
    public void checkExerciseSubmitLimit(String key) {
        checkLimit(exerciseSubmitWindows, key, surveySubmitRequestsPerMinute, 60, "exercise-submit");
    }

    /**
     * Checks the public EXERCISE PDF render limit. Its own bucket, deliberately
     * NOT the submit one: a workshop room shares a single NAT IP, so a few
     * people downloading their copy would otherwise burn the budget and 429
     * the next person's SUBMIT — losing an answer to save a printout.
     */
    public void checkExercisePdfLimit(String key) {
        checkLimit(exercisePdfWindows, key, surveySubmitRequestsPerMinute, 60, "exercise-pdf");
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
     * <p>Called only AFTER the credential check has rejected the attempt: the backoff
     * throttles wrong guesses and must never refuse a proven-correct password, or an
     * attacker who knows the address could keep a block armed and lock the owner out
     * — the hard lockout this section's design notes reject.
     *
     * @param emailKey the submitted email, lowercased and trimmed, existing or not
     */
    @Override
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
        if (state != null && state.blockedUntil().isAfter(Instant.now(clock))) {
            throw new RateLimitExceededException(LOGIN_THROTTLED_MESSAGE);
        }
    }

    /**
     * Count one rejected password login and (re)arm the backoff for that email.
     *
     * <p>Counting and arming are ONE atomic step per key — see
     * {@link #RECORD_LOGIN_FAILURE} for why, and for the identical bound the
     * in-memory fallback below reproduces with {@link ConcurrentHashMap#compute}.
     *
     * @return the seconds this failure armed a refusal for, or {@code 0} when it armed
     *         nothing — either still inside the free budget, or a refusal was already
     *         live and this attempt (which raced past {@link #checkLoginBackoff}) must
     *         not advance the ladder a second time
     */
    @Override
    public int recordLoginFailure(String emailKey) {
        StringRedisTemplate redis = this.redisTemplate;
        if (redis != null) {
            try {
                Long delay = redis.execute(RECORD_LOGIN_FAILURE,
                        List.of(loginFailKey(emailKey), loginBlockKey(emailKey)),
                        String.valueOf(loginFailureTtlSeconds),
                        String.valueOf(loginFreeAttempts),
                        String.valueOf(LOGIN_BACKOFF_BASE_SECONDS),
                        String.valueOf(LOGIN_BACKOFF_MAX_SECONDS),
                        String.valueOf(LOGIN_BACKOFF_MAX_STEPS));
                if (delay != null) {
                    return delay.intValue();
                }
            } catch (RuntimeException e) {
                log.warn("Redis rate-limit backend unavailable ({}) — falling back to per-instance login backoff",
                        e.getMessage());
            }
        }
        Instant now = Instant.now(clock);
        int[] armed = {0};
        loginBackoffs.compute(emailKey, (k, prev) -> {
            if (prev != null && prev.blockedUntil().isAfter(now)) {
                // Same bound as the script: a refusal is already live, so this attempt
                // is not counted. Only the TTL moves, keeping the memory alive under a
                // sustained attack without handing the attacker another rung.
                return new LoginBackoff(prev.failures(),
                        now.plusSeconds(loginFailureTtlSeconds), prev.blockedUntil());
            }
            int failures = prev == null || !prev.countExpiresAt().isAfter(now)
                    ? 1
                    : clampFailures(prev.failures() + 1L);
            armed[0] = loginBackoffSeconds(failures);
            return new LoginBackoff(failures, now.plusSeconds(loginFailureTtlSeconds),
                    armed[0] > 0 ? now.plusSeconds(armed[0]) : Instant.EPOCH);
        });
        return armed[0];
    }

    /**
     * Wipe an email's failure history once control of the account has been proven
     * some other way — a successful sign-in (only reachable when the account was NOT
     * throttled) or a completed password reset, whose emailed single-use token proves
     * mailbox ownership. The reset case is the escape hatch that keeps the backoff
     * from becoming the lockout it exists to avoid: without it a guessed-at victim
     * would still be refused after choosing a new password.
     */
    @Override
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
        Instant now = Instant.now(clock);
        Instant windowStart = now.minusSeconds(windowSeconds);

        // Evict-check-record atomically under the key's map bin (compute), so two
        // concurrent requests can't both pass a size() check at the limit and
        // overshoot it — the check-then-act race the previous computeIfAbsent
        // formulation had.
        boolean[] allowed = new boolean[1];
        windows.compute(key, (k, timestamps) -> {
            if (timestamps == null) {
                timestamps = new ConcurrentLinkedDeque<>();
            }
            // Remove expired entries outside the window
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() < maxRequests) {
                timestamps.addLast(now);
                allowed[0] = true;
            }
            return timestamps;
        });
        if (!allowed[0]) {
            throw rateLimitExceeded(limitType, maxRequests, windowSeconds);
        }
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
        Instant minuteCutoff = Instant.now(clock).minusSeconds(60);
        evictOlderThan(List.of(tryItOutWindows, evaluationWindows, authWindows,
                surveySubmitWindows, publicAssessmentWindows, publicAssessmentSaveWindows,
                businessCardWindows, refreshWindows, contactWindows, leadMagnetWindows,
                errorReportWindows), minuteCutoff);

        // Per-hour windows: drop entries older than 3600s.
        Instant hourCutoff = Instant.now(clock).minusSeconds(3600);
        evictOlderThan(List.of(acceptWindows, passwordResetWindows), hourCutoff);

        // Login backoff: an entry is dead only once BOTH its counter and its active
        // refusal have expired — a short configured TTL can leave a live block behind
        // a dead counter, and evicting that would hand back a free attempt. Same
        // reasoning as above: this is this JVM's own memory, so every replica runs it.
        Instant now = Instant.now(clock);
        loginBackoffs.entrySet().removeIf(e -> !e.getValue().countExpiresAt().isAfter(now)
                && !e.getValue().blockedUntil().isAfter(now));
    }

    private static void evictOlderThan(
            List<ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>>> allWindows,
            Instant cutoff) {
        for (var windows : allWindows) {
            // computeIfPresent, NOT forEach + remove: checkLimitInMemory does its
            // evict-check-record under the key's bin lock (compute), and that
            // atomicity only holds if EVERY mutator takes the same lock. A
            // lock-free sweep could drain a deque, be descheduled while a
            // concurrent compute recorded a fresh timestamp into that same
            // instance, then drop the whole mapping with a value-matching
            // remove(key, deque) — losing the just-recorded request and handing
            // back a free one at the limit. Returning null here evicts the entry
            // under the lock, so the two can no longer interleave.
            for (String key : windows.keySet()) {
                windows.computeIfPresent(key, (k, deque) -> {
                    while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                        deque.pollFirst();
                    }
                    return deque.isEmpty() ? null : deque;
                });
            }
        }
    }
}
