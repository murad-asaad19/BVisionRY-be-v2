package com.bvisionry.aiconfig.service;

import com.bvisionry.aiconfig.repository.AiEvaluationCacheRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the content-hash cache: cache-key determinism and separator behaviour, plus the
 * disabled no-op contract (no repository interaction at all when the feature flag is off).
 */
@ExtendWith(MockitoExtension.class)
class AiEvaluationCacheServiceTest {

    @Mock
    private AiEvaluationCacheRepository repository;

    @Test
    void cacheKey_isDeterministic_lowercaseHex64() {
        String a = AiEvaluationCacheService.cacheKey("anthropic/claude-sonnet-4", 0.3, "system", "user");
        String b = AiEvaluationCacheService.cacheKey("anthropic/claude-sonnet-4", 0.3, "system", "user");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void cacheKey_variesWithEachInput() {
        String base = AiEvaluationCacheService.cacheKey("m", 0.3, "sys", "usr");

        assertThat(AiEvaluationCacheService.cacheKey("m2", 0.3, "sys", "usr")).isNotEqualTo(base);
        assertThat(AiEvaluationCacheService.cacheKey("m", 0.7, "sys", "usr")).isNotEqualTo(base);
        assertThat(AiEvaluationCacheService.cacheKey("m", 0.3, "sys2", "usr")).isNotEqualTo(base);
        assertThat(AiEvaluationCacheService.cacheKey("m", 0.3, "sys", "usr2")).isNotEqualTo(base);
    }

    @Test
    void cacheKey_separatorPreventsBoundaryCollision() {
        // Without the ' ' separator, ("ab","c") and ("a","bc") would both hash "m0.3abc".
        String k1 = AiEvaluationCacheService.cacheKey("m", 0.3, "ab", "c");
        String k2 = AiEvaluationCacheService.cacheKey("m", 0.3, "a", "bc");

        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void disabled_lookupIsEmpty_storeIsNoOp_noRepositoryInteraction() {
        AiEvaluationCacheService disabled =
                new AiEvaluationCacheService(repository, false, 30, 1000, 10000);

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.lookup("some-key")).isEmpty();
        disabled.store("some-key", "pillar-evaluation", "model", "{}");

        verifyNoInteractions(repository);
    }
}
