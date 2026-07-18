package com.bvisionry.organization;

import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.insights.InsightReportRepository;
import com.bvisionry.organization.dto.ChangeTierRequest;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.reporting.service.CacheInvalidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock InsightReportRepository insightReportRepository;
    @Mock AssignmentRepository assignmentRepository;
    @Mock InvitationRepository invitationRepository;
    @Mock JoinLinkRepository joinLinkRepository;
    @Mock CacheInvalidationService cacheInvalidationService;

    @InjectMocks OrganizationService organizationService;

    private Organization org;
    private UUID orgId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        org = new Organization();
        org.setId(orgId);
        org.setName("Test Organization");
        org.setSubscriptionTier(SubscriptionTier.FREE);
        org.setActive(true);
    }

    /**
     * Core bug-fix assertion: promoting an org directly to PREMIUM via changeTier must
     * CLEAR any leftover (already-past) trialEndsAt. Otherwise the stale trial date makes
     * the org match OrganizationRepository.findLapsedTrials(...) and TrialExpiryJob silently
     * downgrades the directly-promoted PREMIUM back to FREE.
     */
    @Test
    void changeTier_toPremium_clearsStaleTrialEndsAt() {
        // Leftover, already-past trial date from a prior trial.
        org.setTrialEndsAt(Instant.now().minus(2, ChronoUnit.DAYS));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(organizationRepository.findOrgStatsByIds(anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{orgId, 0L, null}));

        OrganizationResponse resp =
                organizationService.changeTier(orgId, new ChangeTierRequest(SubscriptionTier.PREMIUM), actorId);

        assertThat(resp.subscriptionTier()).isEqualTo(SubscriptionTier.PREMIUM);
        // The leftover trial marker must be gone — this is what keeps the sweep from downgrading it.
        assertThat(org.getTrialEndsAt()).isNull();
        assertThat(resp.trialEndsAt()).isNull();
        assertThat(org.isOnTrial()).isFalse();
        verify(cacheInvalidationService).invalidateOnTierChange();
    }

    /** Direct downgrade to FREE also clears any leftover trial marker (kept consistent with tier). */
    @Test
    void changeTier_toFree_clearsStaleTrialEndsAt() {
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        org.setTrialEndsAt(Instant.now().plus(5, ChronoUnit.DAYS));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(organizationRepository.findOrgStatsByIds(anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{orgId, 0L, null}));

        OrganizationResponse resp =
                organizationService.changeTier(orgId, new ChangeTierRequest(SubscriptionTier.FREE), actorId);

        assertThat(resp.subscriptionTier()).isEqualTo(SubscriptionTier.FREE);
        assertThat(org.getTrialEndsAt()).isNull();
    }

    @Test
    void changeTier_alreadyOnTargetTier_throws() {
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> organizationService.changeTier(
                orgId, new ChangeTierRequest(SubscriptionTier.PREMIUM), actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already on the PREMIUM tier");
    }

    // --- Sub-organizations --------------------------------------------------

    private Organization newSubOrg(UUID id, Organization parent) {
        Organization subOrg = new Organization();
        subOrg.setId(id);
        subOrg.setName("Sub Org");
        subOrg.setActive(true);
        subOrg.setParentOrganization(parent);
        return subOrg;
    }

    /** Sub-orgs have no billing identity — tier is managed on the parent. */
    @Test
    void changeTier_onSubOrganization_throws() {
        UUID subOrgId = UUID.randomUUID();
        Organization subOrg = newSubOrg(subOrgId, org);
        when(organizationRepository.findById(subOrgId)).thenReturn(Optional.of(subOrg));

        assertThatThrownBy(() -> organizationService.changeTier(
                subOrgId, new ChangeTierRequest(SubscriptionTier.PREMIUM), actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("parent organization");
    }

    @Test
    void toggleActive_onParent_cascadesToSubOrganizations() {
        UUID subOrgId = UUID.randomUUID();
        Organization subOrg = newSubOrg(subOrgId, org);
        var childMember = new com.bvisionry.auth.entity.User();
        childMember.setStatus(com.bvisionry.common.enums.UserStatus.ACTIVE);

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(organizationRepository.findByParentOrganizationId(orgId)).thenReturn(List.of(subOrg));
        when(userRepository.findByOrganizationId(orgId)).thenReturn(List.of());
        when(userRepository.findByOrganizationId(subOrgId)).thenReturn(List.of(childMember));
        when(organizationRepository.countByParentOrganizationId(orgId)).thenReturn(1L);
        when(organizationRepository.findOrgStatsByIds(anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{orgId, 0L, null}));

        organizationService.toggleActive(orgId, false, actorId);

        assertThat(org.isActive()).isFalse();
        assertThat(subOrg.isActive()).isFalse();
        assertThat(childMember.getStatus()).isEqualTo(com.bvisionry.common.enums.UserStatus.SUSPENDED);
    }

    @Test
    void hardDelete_parent_deletesSubOrganizationsFirst() {
        UUID subOrgId = UUID.randomUUID();
        Organization subOrg = newSubOrg(subOrgId, org);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.findById(subOrgId)).thenReturn(Optional.of(subOrg));
        when(organizationRepository.findByParentOrganizationId(orgId)).thenReturn(List.of(subOrg));
        when(organizationRepository.findByParentOrganizationId(subOrgId)).thenReturn(List.of());

        organizationService.hardDelete(orgId);

        var deletionOrder = inOrder(organizationRepository);
        deletionOrder.verify(organizationRepository).delete(subOrg);
        deletionOrder.verify(organizationRepository).delete(this.org);
    }
}
