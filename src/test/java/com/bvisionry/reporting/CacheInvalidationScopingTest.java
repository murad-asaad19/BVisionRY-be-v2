package com.bvisionry.reporting;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bvisionry.config.CacheConfig;
import com.bvisionry.reporting.service.CacheInvalidationService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SCOPE of cache eviction on evaluation completion.
 *
 * <p>An evaluation for one submission must evict only the entries it can have
 * changed — its own org+pipeline dashboards, its own submission's results, its
 * own user's history — and must NOT flush other organizations' warm entries.
 * (The old {@code allEntries = true} behavior meant any single evaluation
 * anywhere on the platform cold-started every org's dashboards.)</p>
 */
class CacheInvalidationScopingTest {

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID PIPELINE = UUID.randomUUID();
    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();
    private static final UUID SUBMISSION_A = UUID.randomUUID();
    private static final UUID SUBMISSION_B = UUID.randomUUID();

    private AnnotationConfigApplicationContext context;
    private CacheManager cacheManager;
    private CacheInvalidationService service;

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheConfig.DASHBOARD_OVERVIEW,
                    CacheConfig.DASHBOARD_DISTRIBUTION,
                    CacheConfig.DASHBOARD_COMPLETION,
                    CacheConfig.MEMBER_RESULTS,
                    CacheConfig.MEMBER_HISTORY,
                    CacheConfig.PLATFORM_ANALYTICS,
                    CacheConfig.DASHBOARD);
        }

        @Bean
        CacheInvalidationService cacheInvalidationService() {
            return new CacheInvalidationService();
        }
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        cacheManager = context.getBean(CacheManager.class);
        service = context.getBean(CacheInvalidationService.class);

        // Warm entries for two orgs / users / submissions on every cache the
        // evaluation path evicts. Dashboard keys mirror TeamDashboardService's
        // SpEL: "#orgId + '-' + #pipelineId".
        for (String cacheName : new String[]{
                CacheConfig.DASHBOARD_OVERVIEW,
                CacheConfig.DASHBOARD_DISTRIBUTION,
                CacheConfig.DASHBOARD_COMPLETION}) {
            cache(cacheName).put(ORG_A + "-" + PIPELINE, "warm");
            cache(cacheName).put(ORG_B + "-" + PIPELINE, "warm");
        }
        cache(CacheConfig.MEMBER_RESULTS).put(SUBMISSION_A, "warm");
        cache(CacheConfig.MEMBER_RESULTS).put(SUBMISSION_B, "warm");
        cache(CacheConfig.MEMBER_HISTORY).put(USER_A, "warm");
        cache(CacheConfig.MEMBER_HISTORY).put(USER_B, "warm");
        cache(CacheConfig.PLATFORM_ANALYTICS).put("analytics", "warm");
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void evictsOnlyTheAffectedOrgPipelineUserAndSubmission() {
        service.invalidateOnNewEvaluation(ORG_A, PIPELINE, USER_A, SUBMISSION_A);

        for (String cacheName : new String[]{
                CacheConfig.DASHBOARD_OVERVIEW,
                CacheConfig.DASHBOARD_DISTRIBUTION,
                CacheConfig.DASHBOARD_COMPLETION}) {
            assertThat(cache(cacheName).get(ORG_A + "-" + PIPELINE))
                    .as("%s: affected org entry must be evicted", cacheName).isNull();
            assertThat(cache(cacheName).get(ORG_B + "-" + PIPELINE))
                    .as("%s: other org's warm entry must SURVIVE", cacheName).isNotNull();
        }
        assertThat(cache(CacheConfig.MEMBER_RESULTS).get(SUBMISSION_A)).isNull();
        assertThat(cache(CacheConfig.MEMBER_RESULTS).get(SUBMISSION_B)).isNotNull();
        assertThat(cache(CacheConfig.MEMBER_HISTORY).get(USER_A)).isNull();
        assertThat(cache(CacheConfig.MEMBER_HISTORY).get(USER_B)).isNotNull();
        // Platform analytics is a single global aggregate — always refreshed.
        assertThat(cache(CacheConfig.PLATFORM_ANALYTICS).get("analytics")).isNull();
    }

    @Test
    void publicSubmissionWithoutOrgOrUserEvictsOnlySubmissionAndAnalytics() {
        service.invalidateOnNewEvaluation(null, PIPELINE, null, SUBMISSION_A);

        assertThat(cache(CacheConfig.DASHBOARD_OVERVIEW).get(ORG_A + "-" + PIPELINE))
                .as("no org on the submission → no dashboard entry to evict").isNotNull();
        assertThat(cache(CacheConfig.MEMBER_HISTORY).get(USER_A)).isNotNull();
        assertThat(cache(CacheConfig.MEMBER_RESULTS).get(SUBMISSION_A)).isNull();
        assertThat(cache(CacheConfig.PLATFORM_ANALYTICS).get("analytics")).isNull();
    }

    @Test
    void tierChangeStillFlushesEverything() {
        service.invalidateOnTierChange();

        assertThat(cache(CacheConfig.DASHBOARD_OVERVIEW).get(ORG_A + "-" + PIPELINE)).isNull();
        assertThat(cache(CacheConfig.DASHBOARD_OVERVIEW).get(ORG_B + "-" + PIPELINE)).isNull();
        assertThat(cache(CacheConfig.MEMBER_RESULTS).get(SUBMISSION_B)).isNull();
        assertThat(cache(CacheConfig.MEMBER_HISTORY).get(USER_B)).isNull();
    }

    private Cache cache(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).isNotNull();
        return cache;
    }
}
