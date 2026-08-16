package com.bvisionry.common.security;

import com.bvisionry.auth.entity.User;
import com.bvisionry.config.SecurityContextCurrentUserAccessor;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.PremiumRequiredException;
import com.bvisionry.organization.OrgHierarchyAdapter;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PremiumFeatureGuardTest {

    @Mock
    private OrganizationRepository organizationRepository;

    private PremiumFeatureGuard premiumFeatureGuard;

    @BeforeEach
    void setUp() {
        // Wire the guard through the REAL adapters so these tests exercise the
        // effective-tier (parent-inheritance) resolution and the real
        // super-admin determination, not stubs.
        premiumFeatureGuard = new PremiumFeatureGuard(
                new OrgHierarchyAdapter(organizationRepository),
                new SecurityContextCurrentUserAccessor());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticate a principal the way the JWT filters do: User + role authority. */
    private void authenticate(UserRole role) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setRole(role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role.name()))));
    }

    @Test
    void checkPremium_premiumOrg_doesNotThrow() {
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setSubscriptionTier(SubscriptionTier.GROWTH);
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
        org.setSubscriptionTier(SubscriptionTier.GROWTH);
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
        parent.setSubscriptionTier(SubscriptionTier.GROWTH);
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

    /* --------------------------------- V156: paid is three tiers, not one */

    /**
     * The non-regression that matters most about V156: the gate asks "does this
     * org PAY", so every sold plan clears it and nothing about benchmarks / ROI /
     * insights narrows. GROWTH is where every ex-PREMIUM org landed, so the
     * middle row of this loop IS the old {@code == PREMIUM} case.
     */
    @Test
    void everyPaidTierClearsTheGate_andOnlyFreeIsRefused() {
        for (SubscriptionTier tier : SubscriptionTier.values()) {
            UUID orgId = UUID.randomUUID();
            Organization org = new Organization();
            org.setSubscriptionTier(tier);
            when(organizationRepository.findWithParentById(orgId)).thenReturn(Optional.of(org));

            assertThat(premiumFeatureGuard.isPremium(orgId))
                    .describedAs("isPremium(%s)", tier)
                    .isEqualTo(tier != SubscriptionTier.FREE);
        }
    }

    /* ------------------------------------------------ governingTier */

    @Test
    void governingTier_resolvesTheParentsPlanForASubOrg() {
        UUID subOrgId = UUID.randomUUID();
        Organization parent = new Organization();
        parent.setSubscriptionTier(SubscriptionTier.STARTER);
        Organization subOrg = new Organization();
        subOrg.setSubscriptionTier(SubscriptionTier.FREE);
        subOrg.setParentOrganization(parent);
        when(organizationRepository.findWithParentById(subOrgId)).thenReturn(Optional.of(subOrg));

        assertThat(premiumFeatureGuard.governingTier(subOrgId)).contains(SubscriptionTier.STARTER);
    }

    /**
     * Empty means "no ceiling applies", NOT "FREE" — a caller that collapsed the
     * two would turn the operator bypass into the strictest possible refusal.
     * Nothing is stubbed, so the bypass must short-circuit before any tier read.
     */
    @Test
    void governingTier_isEmptyForSuperAdmin_withoutTouchingTheRepository() {
        authenticate(UserRole.SUPER_ADMIN);

        assertThat(premiumFeatureGuard.governingTier(UUID.randomUUID())).isEmpty();
    }

    /* ------------------------------------------- the super-admin bypass */

    @Test
    void superAdmin_bypassesTheGateOnAFreeOrg() {
        // No repository stubbing at all: the bypass must short-circuit BEFORE
        // the tier is ever resolved, which is what keeps demo and sales flows
        // working on unpaid orgs.
        authenticate(UserRole.SUPER_ADMIN);

        assertThatCode(() -> premiumFeatureGuard.checkPremium(UUID.randomUUID(), "benchmarks"))
                .doesNotThrowAnyException();
        assertThat(premiumFeatureGuard.isPremiumOrSuperAdmin(UUID.randomUUID())).isTrue();
    }

    @Test
    void orgAdminOfAFreeOrg_doesNotBypass() {
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setSubscriptionTier(SubscriptionTier.FREE);
        when(organizationRepository.findWithParentById(orgId)).thenReturn(Optional.of(org));
        authenticate(UserRole.ORG_ADMIN);

        assertThatThrownBy(() -> premiumFeatureGuard.checkPremium(orgId, "roi_report"))
                .isInstanceOf(PremiumRequiredException.class)
                .hasMessageContaining("roi_report");
    }

    /**
     * An empty security context must read as "not super admin" and must NOT
     * throw — the guard is called on paths where nothing is authenticated, and
     * an exception here would surface as a 500 instead of the 403 gate.
     */
    @Test
    void anonymousContext_isNotSuperAdmin_andDoesNotThrow() {
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setSubscriptionTier(SubscriptionTier.FREE);
        when(organizationRepository.findWithParentById(orgId)).thenReturn(Optional.of(org));
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> premiumFeatureGuard.checkPremium(orgId, "benchmarks"))
                .isInstanceOf(PremiumRequiredException.class);
    }

    /**
     * REGRESSION PIN for the relocation to {@code common}: the super-admin test
     * reads the PRINCIPAL's role, never a granted authority.
     *
     * <p>Several existing call sites authenticate a User with an EMPTY authority
     * list (MemberResultsServiceTest and AssignmentServiceTest both do), so an
     * authority-based implementation would silently answer "not super admin"
     * for a principal whose role IS SUPER_ADMIN. This test fails the moment
     * anyone makes that swap.
     */
    @Test
    void superAdminPrincipalWithNoGrantedAuthorities_stillBypasses() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setRole(UserRole.SUPER_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        assertThatCode(() -> premiumFeatureGuard.checkPremium(UUID.randomUUID(), "benchmarks"))
                .doesNotThrowAnyException();
    }

    /**
     * The authority string the JWT filters mint IS the enum name, and every
     * {@code @PreAuthorize("hasAuthority('SUPER_ADMIN')")} in the codebase is
     * that literal. Renaming the constant would break all of them silently, so
     * pin the exact wire string here.
     */
    @Test
    void superAdminAuthorityStringMatchesTheEnumName() {
        assertThat(UserRole.SUPER_ADMIN.name()).isEqualTo("SUPER_ADMIN");
    }
}
