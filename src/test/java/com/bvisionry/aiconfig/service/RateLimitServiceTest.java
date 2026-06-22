package com.bvisionry.aiconfig.service;

import com.bvisionry.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceTest {

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        // Args: tryItOut, evaluation, auth, surveySubmit, publicAssessment,
        // publicAssessmentSave, businessCard, refresh, accept, contact, leadMagnet.
        // No StringRedisTemplate is wired here, so all checks use the in-memory path.
        rateLimitService = new RateLimitService(5, 10, 10, 10, 5, 50, 7, 30, 10, 3, 5);
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
}
