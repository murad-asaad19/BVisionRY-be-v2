package com.bvisionry.reporting.service;

import com.bvisionry.common.event.SurveyEvents;
import com.bvisionry.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CacheInvalidationService {

    /**
     * Invalidate exactly the cache entries one completed evaluation can have
     * changed: the submission's org+pipeline dashboards, the submission's own
     * cached results, the respondent's history, and the (single-entry) global
     * platform analytics. Other organizations' warm dashboards survive — the
     * previous {@code allEntries = true} form meant any evaluation anywhere
     * cold-started every org's dashboards under sustained throughput.
     *
     * <p>Dashboard keys mirror {@code TeamDashboardService}'s SpEL
     * ({@code #orgId + '-' + #pipelineId}); if that key shape ever changes,
     * {@code CacheInvalidationScopingTest} fails.</p>
     *
     * @param orgId        the submission's organization — {@code null} for anonymous
     *                     public/QR submissions, which appear on no org dashboard
     * @param pipelineId   the evaluated pipeline
     * @param userId       the respondent — {@code null} for anonymous submissions
     * @param submissionId the evaluated submission
     */
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.DASHBOARD_OVERVIEW, key = "#orgId + '-' + #pipelineId",
                    condition = "#orgId != null && #pipelineId != null"),
            @CacheEvict(value = CacheConfig.DASHBOARD_DISTRIBUTION, key = "#orgId + '-' + #pipelineId",
                    condition = "#orgId != null && #pipelineId != null"),
            @CacheEvict(value = CacheConfig.DASHBOARD_COMPLETION, key = "#orgId + '-' + #pipelineId",
                    condition = "#orgId != null && #pipelineId != null"),
            @CacheEvict(value = CacheConfig.MEMBER_RESULTS, key = "#submissionId",
                    condition = "#submissionId != null"),
            @CacheEvict(value = CacheConfig.MEMBER_HISTORY, key = "#userId",
                    condition = "#userId != null"),
            @CacheEvict(value = CacheConfig.PLATFORM_ANALYTICS, allEntries = true)
    })
    public void invalidateOnNewEvaluation(UUID orgId, UUID pipelineId, UUID userId, UUID submissionId) {
        // Annotation-driven -- no body needed
    }

    /**
     * Coarse flush for member removal / permanent deletion / move between orgs.
     * These are admin-rate events (unlike evaluations), and a removed member's
     * submissions can span many pipelines — enumerating them just to evict
     * per-key isn't worth the extra queries, so everything is flushed.
     */
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.DASHBOARD_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.DASHBOARD_DISTRIBUTION, allEntries = true),
            @CacheEvict(value = CacheConfig.DASHBOARD_COMPLETION, allEntries = true),
            @CacheEvict(value = CacheConfig.MEMBER_RESULTS, allEntries = true),
            @CacheEvict(value = CacheConfig.MEMBER_HISTORY, allEntries = true),
            @CacheEvict(value = CacheConfig.PLATFORM_ANALYTICS, allEntries = true)
    })
    public void invalidateOnMemberChange() {
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

    /**
     * Evict the member-results entry embedding a post-assessment survey
     * response that an admin just hard-deleted, so the cached view stops
     * serving the deleted answers. After-commit so a concurrent reader can't
     * re-populate the cache from the still-open transaction's pre-commit view;
     * {@code fallbackExecution} keeps it working without a transaction (unit
     * tests).
     */
    @TransactionalEventListener(fallbackExecution = true)
    @CacheEvict(value = CacheConfig.MEMBER_RESULTS, key = "#event.submissionId()")
    public void onPostAssessmentResponseDeleted(SurveyEvents.PostAssessmentResponseDeleted event) {
        // Annotation-driven -- no body needed
    }
}
