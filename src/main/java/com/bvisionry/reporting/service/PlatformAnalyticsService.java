package com.bvisionry.reporting.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.config.CacheConfig;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.reporting.dto.PlatformAnalyticsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAnalyticsService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final SubmissionRepository submissionRepository;
    private final OverallSummaryRepository overallSummaryRepository;

    /**
     * Platform-wide analytics for Super Admin dashboard.
     * Cached for 15 minutes.
     */
    @Cacheable(value = CacheConfig.PLATFORM_ANALYTICS)
    public PlatformAnalyticsResponse getAnalytics() {
        // Root orgs only: sub-org rows are always FREE satellites of their
        // parent and would inflate both the total and the tier mix (keeps this
        // consistent with DashboardService).
        long totalOrgs = organizationRepository.countByParentOrganizationIsNull();
        long totalUsers = userRepository.count();
        long totalSubmissions = submissionRepository.count();
        long evaluatedSubmissions = submissionRepository.countByStatus(SubmissionStatus.EVALUATED);

        BigDecimal completionRate = totalSubmissions == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(evaluatedSubmissions)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalSubmissions), 2, RoundingMode.HALF_UP);

        long premiumOrgs = organizationRepository
                .countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier.PREMIUM);
        long freeOrgs = organizationRepository
                .countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier.FREE);

        BigDecimal avgScore = overallSummaryRepository.findPlatformAverageScore();
        if (avgScore == null) avgScore = BigDecimal.ZERO;

        return new PlatformAnalyticsResponse(
                totalOrgs, totalUsers, totalSubmissions, evaluatedSubmissions,
                completionRate, avgScore, premiumOrgs, freeOrgs
        );
    }
}
