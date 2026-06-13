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
        rateLimitService = new RateLimitService(5, 10, 10, 10, 5, 30, 10);
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
}
