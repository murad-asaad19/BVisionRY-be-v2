package com.bvisionry.organization;

import com.bvisionry.audit.AuditRepository;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.organization.dto.AttentionItem;
import com.bvisionry.organization.dto.DashboardResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock OrganizationRepository orgRepo;
    @Mock UserRepository userRepo;
    @Mock AuditRepository auditRepo;
    @Mock AttentionRuleService attentionService;

    @InjectMocks DashboardService dashboardService;

    @Test
    void getDashboard_aggregatesKpisAndAttention() {
        when(orgRepo.countByParentOrganizationIsNull()).thenReturn(10L);
        when(orgRepo.countByIsActiveTrueAndParentOrganizationIsNull()).thenReturn(7L);
        when(orgRepo.countOnActiveTrial(any())).thenReturn(1L);
        when(orgRepo.countTrialsExpiringWithin(any(), any())).thenReturn(1L);
        when(orgRepo.countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier.PREMIUM)).thenReturn(5L);
        when(orgRepo.countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier.FREE)).thenReturn(5L);
        when(userRepo.count()).thenReturn(213L);
        when(auditRepo.countByActionTypeAndOccurredAtAfter(any(), any())).thenReturn(2L);
        when(attentionService.evaluate()).thenReturn(List.of());

        DashboardResponse resp = dashboardService.getDashboard();

        assertThat(resp.kpis().totalOrgs()).isEqualTo(10);
        assertThat(resp.kpis().activeCount()).isEqualTo(7);
        assertThat(resp.kpis().suspendedCount()).isEqualTo(3);  // total - active
        assertThat(resp.kpis().trialsExpiringSoon()).isEqualTo(1);
        assertThat(resp.kpis().totalMembers()).isEqualTo(213);
        // Tier mix: Premium = total PREMIUM - active trials
        assertThat(resp.tierMix().premium()).isEqualTo(4);  // 5 - 1
        assertThat(resp.tierMix().trial()).isEqualTo(1);
        assertThat(resp.tierMix().free()).isEqualTo(5);
    }
}
