package com.bvisionry.reporting.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.reporting.dto.PlatformAnalyticsResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformAnalyticsServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private OverallSummaryRepository overallSummaryRepository;

    @InjectMocks
    private PlatformAnalyticsService platformAnalyticsService;

    @Test
    void getAnalytics_returnsAggregatedPlatformStats_rootOrgsOnly() {
        // Root-only variants: sub-org rows (always FREE) must not inflate the
        // org total or the tier mix.
        when(organizationRepository.countByParentOrganizationIsNull()).thenReturn(15L);
        when(organizationRepository.countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier.PREMIUM))
                .thenReturn(5L);
        when(organizationRepository.countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier.FREE))
                .thenReturn(10L);
        when(userRepository.count()).thenReturn(200L);
        when(submissionRepository.count()).thenReturn(500L);
        when(submissionRepository.countByStatus(SubmissionStatus.EVALUATED)).thenReturn(350L);

        PlatformAnalyticsResponse response = platformAnalyticsService.getAnalytics();

        assertThat(response.totalOrganizations()).isEqualTo(15);
        assertThat(response.totalUsers()).isEqualTo(200);
        assertThat(response.totalSubmissions()).isEqualTo(500);
        assertThat(response.evaluatedSubmissions()).isEqualTo(350);
        assertThat(response.completionRate()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(response.premiumOrganizations()).isEqualTo(5);
        assertThat(response.freeOrganizations()).isEqualTo(10);
    }
}
