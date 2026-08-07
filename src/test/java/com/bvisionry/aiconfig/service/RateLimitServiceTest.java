package com.bvisionry.aiconfig.service;

import com.bvisionry.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class RateLimitServiceTest {

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        // Args: tryItOut, evaluation, auth, surveySubmit, publicAssessment,
        // publicAssessmentSave, businessCard, refresh, accept, passwordReset,
        // contact, leadMagnet, errorReport, loginFreeAttempts, loginFailureTtlSeconds.
        // No StringRedisTemplate is wired here, so all checks use the in-memory path.
        rateLimitService = new RateLimitService(5, 10, 10, 10, 5, 50, 7, 30, 10, 5, 3, 5, 4, 5, 900);
    }

    @Test
    void checkTryItOutLimit_underLimit_succeeds() {
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkTryItOutLimit("user-1");
        }
        // No exception thrown for 5 requests with limit of 5
    }

    @Test
    void checkTryItOutLimit_overLimit_throws() {
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkTryItOutLimit("user-1");
        }

        assertThatThrownBy(() -> rateLimitService.checkTryItOutLimit("user-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Rate limit exceeded");
    }

    @Test
    void checkTryItOutLimit_differentUsers_independentLimits() {
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkTryItOutLimit("user-1");
        }

        // user-2 should still be allowed
        rateLimitService.checkTryItOutLimit("user-2");
    }

    @Test
    void checkEvaluationLimit_underLimit_succeeds() {
        for (int i = 0; i < 10; i++) {
            rateLimitService.checkEvaluationLimit("org-1");
        }
    }

    @Test
    void checkEvaluationLimit_overLimit_throws() {
        for (int i = 0; i < 10; i++) {
            rateLimitService.checkEvaluationLimit("org-1");
        }

        assertThatThrownBy(() -> rateLimitService.checkEvaluationLimit("org-1"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void checkContactLimit_overLimit_throws() {
        for (int i = 0; i < 3; i++) {
            rateLimitService.checkContactLimit("ip-1");
        }

        assertThatThrownBy(() -> rateLimitService.checkContactLimit("ip-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("contact");
    }

    @Test
    void checkBusinessCardLimit_overLimit_throws() {
        for (int i = 0; i < 7; i++) {
            rateLimitService.checkBusinessCardLimit("ip-1");
        }

        assertThatThrownBy(() -> rateLimitService.checkBusinessCardLimit("ip-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("business-card");
    }

    @Test
    void checkBusinessCardLimit_isolatedFromTryItOutBucket() {
        // Exhaust the try-it-out bucket; the business-card bucket must remain unaffected.
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkTryItOutLimit("ip-1");
        }

        rateLimitService.checkBusinessCardLimit("ip-1");
    }

    @Test
    void checkContactLimit_isolatedFromTryItOutBucket() {
        // Exhaust the try-it-out bucket; the contact bucket must remain unaffected.
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkTryItOutLimit("ip-1");
        }

        rateLimitService.checkContactLimit("ip-1");
    }

    @Test
    void checkPublicAssessmentSaveLimit_overLimit_throws() {
        for (int i = 0; i < 50; i++) {
            rateLimitService.checkPublicAssessmentSaveLimit("ip-1");
        }

        assertThatThrownBy(() -> rateLimitService.checkPublicAssessmentSaveLimit("ip-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("public-assessment-save");
    }

    @Test
    void checkPublicAssessmentSaveLimit_isolatedFromSubmitBucket() {
        // Exhaust the tight public-assessment (submit) bucket; the generous autosave
        // bucket must remain usable so legitimate autosaves are never collateral-blocked.
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkPublicAssessmentLimit("ip-1");
        }

        rateLimitService.checkPublicAssessmentSaveLimit("ip-1");
    }

    @Test
    void checkErrorReportLimit_overLimit_throws() {
        for (int i = 0; i < 4; i++) {
            rateLimitService.checkErrorReportLimit("ip:1.2.3.4");
        }

        assertThatThrownBy(() -> rateLimitService.checkErrorReportLimit("ip:1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("error-report");
    }

    /** Its own bucket: a flood of crash reports must not lock anyone out of contact. */
    @Test
    void checkErrorReportLimit_isolatedFromContactBucket() {
        for (int i = 0; i < 4; i++) {
            rateLimitService.checkErrorReportLimit("ip:1.2.3.4");
        }

        rateLimitService.checkContactLimit("ip:1.2.3.4");
    }

    @Test
    void checkAuthLimit_overLimit_throws() {
        for (int i = 0; i < 10; i++) {
            rateLimitService.checkAuthLimit("ip-1");
        }

        assertThatThrownBy(() -> rateLimitService.checkAuthLimit("ip-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("authentication");
    }

    @Test
    void checkAcceptLimit_overLimit_throws() {
        for (int i = 0; i < 10; i++) {
            rateLimitService.checkAcceptLimit("ip-1");
        }

        assertThatThrownBy(() -> rateLimitService.checkAcceptLimit("ip-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("accept");
    }

    @Test
    void checkPasswordResetLimit_overLimit_throws() {
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkPasswordResetLimit("email:target@example.com");
        }

        assertThatThrownBy(() -> rateLimitService.checkPasswordResetLimit("email:target@example.com"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("password-reset");
    }

    // ---- Per-account login backoff --------------------------------------------
    // Configured above with 5 free attempts. The backoff schedule from there is
    // 5s, 10s, 20s … doubling to a 900s cap.

    @Test
    void checkLoginBackoff_withinFreeAttempts_neverThrows() {
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkLoginBackoff("victim@example.com");
            rateLimitService.recordLoginFailure("victim@example.com");
        }

        // The 5th failure has landed and the 6th attempt is still free to try.
        rateLimitService.checkLoginBackoff("victim@example.com");
    }

    @Test
    void checkLoginBackoff_afterFreeAttemptsExhausted_throws() {
        for (int i = 0; i < 6; i++) {
            rateLimitService.recordLoginFailure("victim@example.com");
        }

        assertThatThrownBy(() -> rateLimitService.checkLoginBackoff("victim@example.com"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("Too many failed sign-in attempts. Try again later.");
    }

    @Test
    void loginBackoff_delayEscalatesExponentiallyAndIsCapped() {
        // Inside the free budget nothing is withheld.
        assertThat(rateLimitService.loginBackoffSeconds(1)).isZero();
        assertThat(rateLimitService.loginBackoffSeconds(5)).isZero();

        // Then it doubles per failure.
        assertThat(rateLimitService.loginBackoffSeconds(6)).isEqualTo(5);
        assertThat(rateLimitService.loginBackoffSeconds(7)).isEqualTo(10);
        assertThat(rateLimitService.loginBackoffSeconds(8)).isEqualTo(20);
        assertThat(rateLimitService.loginBackoffSeconds(9)).isEqualTo(40);
        assertThat(rateLimitService.loginBackoffSeconds(12)).isEqualTo(320);
        assertThat(rateLimitService.loginBackoffSeconds(13)).isEqualTo(640);

        // …until the cap, which nothing may exceed however long the pounding lasts.
        assertThat(rateLimitService.loginBackoffSeconds(14)).isEqualTo(900);
        assertThat(rateLimitService.loginBackoffSeconds(100)).isEqualTo(900);
        assertThat(rateLimitService.loginBackoffSeconds(Integer.MAX_VALUE)).isEqualTo(900);
    }

    /**
     * Hammering an already-throttled account must not run the exponent into overflow —
     * a negative or wrapped delay would silently hand the attacker a free pass.
     */
    @Test
    void recordLoginFailure_whileThrottled_staysCappedAndNeverWrapsNegative() {
        for (int i = 0; i < 500; i++) {
            rateLimitService.recordLoginFailure("victim@example.com");
        }

        assertThat(rateLimitService.loginBackoffSeconds(500)).isEqualTo(900);
        assertThatThrownBy(() -> rateLimitService.checkLoginBackoff("victim@example.com"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    /**
     * The enumeration property, at the service layer: the counter is keyed on the
     * submitted string alone, so an address that has no account throttles exactly
     * like one that does — same trigger point, same refusal, byte for byte.
     */
    @Test
    void loginBackoff_nonexistentEmailThrottlesIdenticallyToExistingOne() {
        for (int i = 0; i < 6; i++) {
            rateLimitService.recordLoginFailure("real@example.com");
            rateLimitService.recordLoginFailure("no-such-account@example.com");
        }

        Throwable real = catchThrowable(() -> rateLimitService.checkLoginBackoff("real@example.com"));
        Throwable ghost = catchThrowable(() -> rateLimitService.checkLoginBackoff("no-such-account@example.com"));

        assertThat(real).isInstanceOf(RateLimitExceededException.class);
        assertThat(ghost).hasSameClassAs(real).hasMessage(real.getMessage());
    }

    @Test
    void loginBackoff_isPerAccount_soOneVictimCannotBlockAnother() {
        for (int i = 0; i < 20; i++) {
            rateLimitService.recordLoginFailure("victim@example.com");
        }

        rateLimitService.checkLoginBackoff("bystander@example.com");
    }

    @Test
    void clearLoginFailures_afterSuccess_resetsTheCounter() {
        for (int i = 0; i < 4; i++) {
            rateLimitService.recordLoginFailure("victim@example.com");
        }
        rateLimitService.clearLoginFailures("victim@example.com");

        // Back to a full budget: 5 more failures must still leave the account usable.
        for (int i = 0; i < 5; i++) {
            rateLimitService.recordLoginFailure("victim@example.com");
        }
        rateLimitService.checkLoginBackoff("victim@example.com");
    }

    /**
     * An account nobody touches for a while forgets on its own — there is no admin
     * unlock because nothing stays locked. Same service, but a 1-second memory so the
     * decay is observable: 3 failures, an idle gap, 3 more. Without decay that is 6
     * failures and a refusal; with it the counter restarted and the account is fine.
     */
    @Test
    void loginBackoff_idleAccountRecoversOnceTheCounterExpires() throws InterruptedException {
        RateLimitService shortMemory =
                new RateLimitService(5, 10, 10, 10, 5, 50, 7, 30, 10, 5, 3, 5, 4, 5, 1);
        for (int i = 0; i < 3; i++) {
            shortMemory.recordLoginFailure("victim@example.com");
        }

        Thread.sleep(1_100);

        for (int i = 0; i < 3; i++) {
            shortMemory.recordLoginFailure("victim@example.com");
        }
        shortMemory.checkLoginBackoff("victim@example.com");
    }

    /** The login buckets are their own: exhausting them must not disturb the per-IP ceiling. */
    @Test
    void loginBackoff_isolatedFromTheAuthenticationBucket() {
        for (int i = 0; i < 20; i++) {
            rateLimitService.recordLoginFailure("ip-1");
        }

        rateLimitService.checkAuthLimit("ip-1");
    }
}
