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
        when(orgRepo.countRootOrgsByTier()).thenReturn(List.of(
                new Object[]{SubscriptionTier.FREE, 5L},
                new Object[]{SubscriptionTier.STARTER, 2L},
                new Object[]{SubscriptionTier.GROWTH, 3L}));
        when(userRepo.count()).thenReturn(213L);
        when(auditRepo.countByActionTypeAndOccurredAtAfter(any(), any())).thenReturn(2L);
        when(attentionService.evaluate()).thenReturn(List.of());

        DashboardResponse resp = dashboardService.getDashboard();

        assertThat(resp.kpis().totalOrgs()).isEqualTo(10);
        assertThat(resp.kpis().activeCount()).isEqualTo(7);
        assertThat(resp.kpis().suspendedCount()).isEqualTo(3);  // total - active
        assertThat(resp.kpis().trialsExpiringSoon()).isEqualTo(1);
        assertThat(resp.kpis().totalMembers()).isEqualTo(213);
        // Tier mix is now PER TIER. This used to assert a single "premium"
        // bucket derived by subtraction, and that arithmetic is exactly why the
        // console kept printing the removed PREMIUM name: with no `== PREMIUM`
        // anywhere, V156's deletion of the constant had nothing to flag.
        assertThat(resp.tierMix().byTier())
                .containsEntry(SubscriptionTier.FREE, 5L)
                .containsEntry(SubscriptionTier.STARTER, 2L)
                .containsEntry(SubscriptionTier.GROWTH, 3L);

        // A tier with no orgs is present as 0, not absent, so the console
        // renders a stable set of rows instead of a list that changes shape.
        assertThat(resp.tierMix().byTier())
                .containsEntry(SubscriptionTier.FOUNDER_SUCCESS, 0L)
                .containsOnlyKeys(SubscriptionTier.values());

        // Trials are a STATUS, not a tier: an org on trial still sits on one, so
        // it must NOT be subtracted out of its tier's count (5+2+3 = 10 = total).
        assertThat(resp.tierMix().onTrial()).isEqualTo(1);
        assertThat(resp.tierMix().total()).isEqualTo(10);
    }

    @Test
    void tierMix_reportsZeroForEveryTier_whenThePlatformHasNoOrgs() {
        when(orgRepo.countByParentOrganizationIsNull()).thenReturn(0L);
        when(orgRepo.countByIsActiveTrueAndParentOrganizationIsNull()).thenReturn(0L);
        when(orgRepo.countOnActiveTrial(any())).thenReturn(0L);
        when(orgRepo.countTrialsExpiringWithin(any(), any())).thenReturn(0L);
        when(orgRepo.countRootOrgsByTier()).thenReturn(List.of());
        when(userRepo.count()).thenReturn(0L);
        when(auditRepo.countByActionTypeAndOccurredAtAfter(any(), any())).thenReturn(0L);
        when(attentionService.evaluate()).thenReturn(List.of());

        DashboardResponse resp = dashboardService.getDashboard();

        assertThat(resp.tierMix().byTier()).containsOnlyKeys(SubscriptionTier.values());
        assertThat(resp.tierMix().byTier().values()).allMatch(v -> v == 0L);
        assertThat(resp.tierMix().total()).isZero();
    }
}
