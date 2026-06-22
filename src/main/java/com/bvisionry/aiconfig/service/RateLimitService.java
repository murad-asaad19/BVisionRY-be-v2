package com.bvisionry.aiconfig.service;

import com.bvisionry.common.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory sliding window rate limiter for dev/V1.
 * For production, replace with Redis-backed implementation (e.g., Bucket4j + Redis).
 */
@Service
public class RateLimitService {

    private final int tryItOutRequestsPerMinute;
    private final int evaluationRequestsPerMinute;
    private final int authRequestsPerMinute;
    private final int surveySubmitRequestsPerMinute;
    private final int publicAssessmentRequestsPerMinute;
    private final int businessCardRequestsPerMinute;
    private final int refreshRequestsPerMinute;
    private final int acceptRequestsPerHour;
    private final int contactRequestsPerMinute;
    private final int leadMagnetRequestsPerMinute;

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
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> businessCardWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> refreshWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> acceptWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> contactWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> leadMagnetWindows =
            new ConcurrentHashMap<>();

    public RateLimitService(
            @Value("${bvisionry.rate-limit.try-it-out.requests-per-minute:10}") int tryItOutRequestsPerMinute,
            @Value("${bvisionry.rate-limit.evaluation.requests-per-minute:30}") int evaluationRequestsPerMinute,
            @Value("${bvisionry.rate-limit.auth.requests-per-minute:10}") int authRequestsPerMinute,
            @Value("${bvisionry.rate-limit.survey-submit.requests-per-minute:10}") int surveySubmitRequestsPerMinute,
            @Value("${bvisionry.rate-limit.public-assessment.requests-per-minute:5}") int publicAssessmentRequestsPerMinute,
            @Value("${bvisionry.rate-limit.business-card.requests-per-minute:60}") int businessCardRequestsPerMinute,
            @Value("${bvisionry.rate-limit.refresh.requests-per-minute:30}") int refreshRequestsPerMinute,
            @Value("${bvisionry.rate-limit.accept.requests-per-hour:10}") int acceptRequestsPerHour,
            @Value("${bvisionry.rate-limit.contact.requests-per-minute:100}") int contactRequestsPerMinute,
            @Value("${bvisionry.rate-limit.lead-magnet.requests-per-minute:20}") int leadMagnetRequestsPerMinute) {
        this.tryItOutRequestsPerMinute = tryItOutRequestsPerMinute;
        this.evaluationRequestsPerMinute = evaluationRequestsPerMinute;
        this.authRequestsPerMinute = authRequestsPerMinute;
        this.surveySubmitRequestsPerMinute = surveySubmitRequestsPerMinute;
        this.publicAssessmentRequestsPerMinute = publicAssessmentRequestsPerMinute;
        this.businessCardRequestsPerMinute = businessCardRequestsPerMinute;
        this.refreshRequestsPerMinute = refreshRequestsPerMinute;
        this.acceptRequestsPerHour = acceptRequestsPerHour;
        this.contactRequestsPerMinute = contactRequestsPerMinute;
        this.leadMagnetRequestsPerMinute = leadMagnetRequestsPerMinute;
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
     * Checks the public-assessment rate limit (anonymous session create +
     * submit). Applied per IP and per token+IP, like the survey limit.
     */
    public void checkPublicAssessmentLimit(String key) {
        checkLimit(publicAssessmentWindows, key, publicAssessmentRequestsPerMinute, 60, "public-assessment");
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
     * Rate limit for the public marketing "Contact Us" form. Its own per-IP bucket
     * and ceiling (default 5/min) so contact traffic is isolated from other anonymous
     * limiters and can be tuned independently against inbox-flooding bots.
     */
    public void checkContactLimit(String key) {
        checkLimit(contactWindows, key, contactRequestsPerMinute, 60, "contact");
    }

    /**
     * Rate limit for the public lead-magnet capture ("the science behind the 11
     * pillars"). Its own per-IP bucket and ceiling (default 5/min) so it is
     * isolated from the AI "try it out" limiter and can be tuned independently
     * against a bot flooding the lead table or the PDF mailer.
     */
    public void checkLeadMagnetLimit(String key) {
        checkLimit(leadMagnetWindows, key, leadMagnetRequestsPerMinute, 60, "lead-magnet");
    }

    private void checkLimit(ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> windows,
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
            String windowDesc = windowSeconds == 60 ? "minute"
                    : windowSeconds == 3600 ? "hour"
                    : (windowSeconds + " seconds");
            throw new RateLimitExceededException(
                    "Rate limit exceeded for " + limitType + ". Maximum " + maxRequests
                            + " requests per " + windowDesc + ".");
        }

        timestamps.addLast(now);
    }

    @Scheduled(fixedDelay = 60_000)
    void evictStaleWindows() {
        // Per-minute windows: drop entries older than 60s.
        Instant minuteCutoff = Instant.now().minusSeconds(60);
        evictOlderThan(List.of(tryItOutWindows, evaluationWindows, authWindows,
                surveySubmitWindows, publicAssessmentWindows, businessCardWindows,
                refreshWindows, contactWindows, leadMagnetWindows), minuteCutoff);

        // Per-hour windows: drop entries older than 3600s.
        Instant hourCutoff = Instant.now().minusSeconds(3600);
        evictOlderThan(List.of(acceptWindows), hourCutoff);
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
