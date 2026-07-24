package com.bvisionry.reporting.service;

import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.PremiumRequiredException;
import com.bvisionry.organization.OrgHierarchyAdapter;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PremiumFeatureGuardTest {

    @Mock
    private OrganizationRepository organizationRepository;

    private PremiumFeatureGuard premiumFeatureGuard;

    @BeforeEach
    void setUp() {
        // Wire the guard through the REAL hierarchy adapter so these tests
        // exercise the effective-tier (parent-inheritance) resolution, not a stub.
        premiumFeatureGuard = new PremiumFeatureGuard(new OrgHierarchyAdapter(organizationRepository));
    }

    @Test
    void checkPremium_premiumOrg_doesNotThrow() {
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        when(organizationRepository.findWithParentById(orgId)).thenReturn(Optional.of(org));

        premiumFeatureGuard.checkPremium(orgId, "pillar_detail");
        // no exception -- test passes
    }

    @Test
    void checkPremium_freeOrg_throwsPremiumRequired() {
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setSubscriptionTier(SubscriptionTier.FREE);
        when(organizationRepository.findWithParentById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> premiumFeatureGuard.checkPremium(orgId, "pillar_detail"))
                .isInstanceOf(PremiumRequiredException.class)
                .hasMessageContaining("pillar_detail");
    }

    @Test
    void isPremium_premiumOrg_returnsTrue() {
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        when(organizationRepository.findWithParentById(orgId)).thenReturn(Optional.of(org));

        assertThat(premiumFeatureGuard.isPremium(orgId)).isTrue();
    }

    @Test
    void isPremium_freeOrg_returnsFalse() {
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setSubscriptionTier(SubscriptionTier.FREE);
        when(organizationRepository.findWithParentById(orgId)).thenReturn(Optional.of(org));

        assertThat(premiumFeatureGuard.isPremium(orgId)).isFalse();
    }

    /** Sub-orgs inherit the parent's plan: a FREE sub-org under a PREMIUM parent is premium. */
    @Test
    void checkPremium_freeSubOrgUnderPremiumParent_doesNotThrow() {
        UUID subOrgId = UUID.randomUUID();
        Organization parent = new Organization();
        parent.setSubscriptionTier(SubscriptionTier.PREMIUM);
        Organization subOrg = new Organization();
        subOrg.setSubscriptionTier(SubscriptionTier.FREE);
        subOrg.setParentOrganization(parent);
        when(organizationRepository.findWithParentById(subOrgId)).thenReturn(Optional.of(subOrg));

        premiumFeatureGuard.checkPremium(subOrgId, "pillar_detail");
        // no exception -- inherited premium
    }

    @Test
    void checkPremium_freeSubOrgUnderFreeParent_throwsPremiumRequired() {
        UUID subOrgId = UUID.randomUUID();
        Organization parent = new Organization();
        parent.setSubscriptionTier(SubscriptionTier.FREE);
        Organization subOrg = new Organization();
        subOrg.setSubscriptionTier(SubscriptionTier.FREE);
        subOrg.setParentOrganization(parent);
        when(organizationRepository.findWithParentById(subOrgId)).thenReturn(Optional.of(subOrg));

        assertThatThrownBy(() -> premiumFeatureGuard.checkPremium(subOrgId, "pillar_detail"))
                .isInstanceOf(PremiumRequiredException.class);
    }
}
