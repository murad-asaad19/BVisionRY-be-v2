package com.bvisionry.reporting.service;

import com.bvisionry.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CacheInvalidationService {

    /**
     * Invalidate all dashboard and member result caches.
     * Called when a new evaluation completes.
     */
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.DASHBOARD_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.DASHBOARD_DISTRIBUTION, allEntries = true),
            @CacheEvict(value = CacheConfig.DASHBOARD_COMPLETION, allEntries = true),
            @CacheEvict(value = CacheConfig.MEMBER_RESULTS, allEntries = true),
            @CacheEvict(value = CacheConfig.MEMBER_HISTORY, allEntries = true),
            @CacheEvict(value = CacheConfig.PLATFORM_ANALYTICS, allEntries = true)
    })
    public void invalidateOnNewEvaluation() {
        // Annotation-driven -- no body needed
    }

    /**
     * Invalidate caches that could hold tier-sensitive or tier-derived data.
     * Called when an organization's subscription tier changes. The cache keys
     * are not scoped by orgId, so we evict all entries defensively.
     */
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.MEMBER_RESULTS, allEntries = true),
            @CacheEvict(value = CacheConfig.MEMBER_HISTORY, allEntries = true),
            @CacheEvict(value = CacheConfig.DASHBOARD_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.DASHBOARD_DISTRIBUTION, allEntries = true),
            @CacheEvict(value = CacheConfig.DASHBOARD_COMPLETION, allEntries = true),
            @CacheEvict(value = CacheConfig.PLATFORM_ANALYTICS, allEntries = true),
            @CacheEvict(value = CacheConfig.DASHBOARD, allEntries = true)
    })
    public void invalidateOnTierChange() {
        // Annotation-driven -- no body needed
    }

    /**
     * Invalidate the single member-results cache entry for one submission.
     * Called when a post-assessment survey response is recorded so the
     * embedded survey block on the results view reflects it immediately.
     */
    @CacheEvict(value = CacheConfig.MEMBER_RESULTS, key = "#submissionId")
    public void invalidateMemberResultsForSubmission(UUID submissionId) {
        // Annotation-driven -- no body needed
    }
}
