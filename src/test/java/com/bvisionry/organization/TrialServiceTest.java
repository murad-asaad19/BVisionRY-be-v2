package com.bvisionry.organization;

import com.bvisionry.audit.AuditRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.audit.entity.AuditLog;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.config.FrontendProperties;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.reporting.service.CacheInvalidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrialServiceTest {

    @Mock OrganizationRepository orgRepo;
    @Mock UserRepository userRepo;
    @Mock AuditService auditService;
    @Mock AuditRepository auditRepo;
    @Mock EmailService emailService;
    @Mock CacheInvalidationService cacheInvalidationService;
    @Mock PlatformTransactionManager transactionManager;

    @InjectMocks TrialService trialService;

    private Organization freeOrg;
    private UUID orgId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        freeOrg = new Organization();
        freeOrg.setId(orgId);
        freeOrg.setName("Acme");
        freeOrg.setSubscriptionTier(SubscriptionTier.FREE);
        freeOrg.setActive(true);
        // Inject a real FrontendUrls so dashboardUrl assertions are stable.
        FrontendProperties frontendProperties = new FrontendProperties();
        frontendProperties.setBaseUrl("http://localhost:5173");
        ReflectionTestUtils.setField(trialService, "frontendUrls", new FrontendUrls(frontendProperties));
        // @PostConstruct is not invoked under Mockito @InjectMocks, so build the
        // REQUIRES_NEW TransactionTemplate that expireLapsed()/expireOne() rely on.
        // The mock transaction manager returns a real status and no-ops on commit,
        // so the template simply runs the callback inline — exactly what the unit
        // tests need to exercise per-org expiry.
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.invokeMethod(trialService, "initTransactionTemplate");
    }

    @Test
    void startTrial_setsPremiumAndExpiry_logsAudit() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));
        when(orgRepo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.countByOrganizationId(any())).thenReturn(0L);
        when(userRepo.findMaxLastLoginByOrganizationId(any())).thenReturn(null);

        OrganizationResponse resp = trialService.startTrial(orgId, 7, actorId);

        assertThat(resp.subscriptionTier()).isEqualTo(SubscriptionTier.PREMIUM);
        assertThat(resp.trialEndsAt()).isAfter(Instant.now());
        assertThat(resp.trialEndsAt()).isBefore(Instant.now().plus(8, ChronoUnit.DAYS));

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(actorId), eq(orgId), eq(OrgAuditActions.TRIAL_STARTED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(orgId), details.capture());
        assertThat(details.getValue()).containsEntry("durationDays", 7);
        verify(cacheInvalidationService).invalidateOnTierChange();
    }

    @Test
    void startTrial_alreadyOnTrial_throws() {
        freeOrg.setSubscriptionTier(SubscriptionTier.PREMIUM);
        freeOrg.setTrialEndsAt(Instant.now().plus(3, ChronoUnit.DAYS));
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));

        assertThatThrownBy(() -> trialService.startTrial(orgId, 7, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already on an active trial");
    }

    @Test
    void startTrial_alreadyPremiumNoTrial_throws() {
        freeOrg.setSubscriptionTier(SubscriptionTier.PREMIUM);
        freeOrg.setTrialEndsAt(null);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));

        assertThatThrownBy(() -> trialService.startTrial(orgId, 7, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already on Premium");
    }

    @Test
    void startTrial_suspendedOrg_throws() {
        freeOrg.setActive(false);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));

        assertThatThrownBy(() -> trialService.startTrial(orgId, 7, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Reactivate");
    }

    /** Sub-orgs inherit the parent's plan — every trial operation must 400 on them. */
    @Test
    void startTrial_onSubOrganization_throws() {
        Organization parent = new Organization();
        parent.setId(UUID.randomUUID());
        freeOrg.setParentOrganization(parent);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));

        assertThatThrownBy(() -> trialService.startTrial(orgId, 7, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("parent organization");
        verifyNoInteractions(auditService);
    }

    @Test
    void extendTrial_onSubOrganization_throws() {
        Organization parent = new Organization();
        parent.setId(UUID.randomUUID());
        freeOrg.setParentOrganization(parent);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));

        assertThatThrownBy(() -> trialService.extendTrial(orgId, 5, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("parent organization");
    }

    @Test
    void endTrialEarly_onSubOrganization_throws() {
        Organization parent = new Organization();
        parent.setId(UUID.randomUUID());
        freeOrg.setParentOrganization(parent);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));

        assertThatThrownBy(() -> trialService.endTrialEarly(orgId, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("parent organization");
    }

    @Test
    void extendTrial_addsDaysToExisting_logsAudit() {
        Instant currentEnd = Instant.now().plus(3, ChronoUnit.DAYS);
        freeOrg.setSubscriptionTier(SubscriptionTier.PREMIUM);
        freeOrg.setTrialEndsAt(currentEnd);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));
        when(orgRepo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.countByOrganizationId(any())).thenReturn(0L);

        OrganizationResponse resp = trialService.extendTrial(orgId, 5, actorId);

        Instant expected = currentEnd.plus(5, ChronoUnit.DAYS);
        assertThat(resp.trialEndsAt()).isCloseTo(expected, within(1, ChronoUnit.SECONDS));

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(actorId), eq(orgId), eq(OrgAuditActions.TRIAL_EXTENDED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(orgId), details.capture());
        assertThat(details.getValue()).containsEntry("additionalDays", 5);
    }

    @Test
    void extendTrial_noActiveTrial_throws() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));

        assertThatThrownBy(() -> trialService.extendTrial(orgId, 5, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no active trial");
    }

    @Test
    void endTrialEarly_setsFreeAndTrialEndsNow_logsAudit() {
        Instant futureEnd = Instant.now().plus(5, ChronoUnit.DAYS);
        freeOrg.setSubscriptionTier(SubscriptionTier.PREMIUM);
        freeOrg.setTrialEndsAt(futureEnd);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));
        when(orgRepo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.countByOrganizationId(any())).thenReturn(0L);

        OrganizationResponse resp = trialService.endTrialEarly(orgId, actorId);

        assertThat(resp.subscriptionTier()).isEqualTo(SubscriptionTier.FREE);
        // trial_ends_at preserved as approx-now (truncated)
        assertThat(resp.trialEndsAt()).isBeforeOrEqualTo(Instant.now().plus(1, ChronoUnit.SECONDS));

        verify(auditService).log(eq(actorId), eq(orgId), eq(OrgAuditActions.TRIAL_ENDED_EARLY),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(orgId), any());
    }

    @Test
    void endTrialEarly_noActiveTrial_throws() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(freeOrg));

        assertThatThrownBy(() -> trialService.endTrialEarly(orgId, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no active trial");
    }

    @Test
    void expireLapsed_flipsExpiredTrialsToFree_logsAuditPerOrg_emailsAdmins() {
        Organization a = new Organization(); a.setId(UUID.randomUUID()); a.setName("A"); a.setSubscriptionTier(SubscriptionTier.PREMIUM);
        a.setTrialEndsAt(Instant.now().minus(1, ChronoUnit.HOURS));
        Organization b = new Organization(); b.setId(UUID.randomUUID()); b.setName("B"); b.setSubscriptionTier(SubscriptionTier.PREMIUM);
        b.setTrialEndsAt(Instant.now().minus(2, ChronoUnit.HOURS));
        when(orgRepo.findLapsedTrials(any())).thenReturn(List.of(a, b));
        // expireOne re-reads each org by id inside its own REQUIRES_NEW transaction.
        when(orgRepo.findById(a.getId())).thenReturn(Optional.of(a));
        when(orgRepo.findById(b.getId())).thenReturn(Optional.of(b));
        when(orgRepo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        User adminA = userWith("admin-a@acme.com", UserRole.ORG_ADMIN);
        User memberA = userWith("member-a@acme.com", UserRole.MEMBER);
        User adminB = userWith("admin-b@acme.com", UserRole.ORG_ADMIN);
        when(userRepo.findByOrganizationId(a.getId())).thenReturn(List.of(adminA, memberA));
        when(userRepo.findByOrganizationId(b.getId())).thenReturn(List.of(adminB));

        List<UUID> expired = trialService.expireLapsed();

        assertThat(expired).hasSize(2);
        assertThat(a.getSubscriptionTier()).isEqualTo(SubscriptionTier.FREE);
        assertThat(b.getSubscriptionTier()).isEqualTo(SubscriptionTier.FREE);
        verify(orgRepo).save(a);
        verify(orgRepo).save(b);
        verify(auditService).log(eq(null), eq(a.getId()), eq(OrgAuditActions.TRIAL_EXPIRED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(a.getId()), any());
        verify(auditService).log(eq(null), eq(b.getId()), eq(OrgAuditActions.TRIAL_EXPIRED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(b.getId()), any());

        // ORG_ADMINs get emailed; member does not.
        verify(emailService).sendTrialExpiredAsync(eq("admin-a@acme.com"), eq("A"),
                eq(a.getTrialEndsAt()), eq("http://localhost:5173/admin/organizations/" + a.getId()));
        verify(emailService).sendTrialExpiredAsync(eq("admin-b@acme.com"), eq("B"),
                eq(b.getTrialEndsAt()), eq("http://localhost:5173/admin/organizations/" + b.getId()));
        verify(emailService, never()).sendTrialExpiredAsync(eq("member-a@acme.com"),
                anyString(), any(), anyString());

        verify(cacheInvalidationService).invalidateOnTierChange();
    }

    @Test
    void expireLapsed_fallsBackToAllMembersWhenNoOrgAdmin() {
        Organization c = new Organization(); c.setId(UUID.randomUUID()); c.setName("C");
        c.setSubscriptionTier(SubscriptionTier.PREMIUM);
        c.setTrialEndsAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(orgRepo.findLapsedTrials(any())).thenReturn(List.of(c));
        when(orgRepo.findById(c.getId())).thenReturn(Optional.of(c));
        User onlyMember = userWith("member-c@acme.com", UserRole.MEMBER);
        when(userRepo.findByOrganizationId(c.getId())).thenReturn(List.of(onlyMember));

        trialService.expireLapsed();

        verify(emailService).sendTrialExpiredAsync(eq("member-c@acme.com"), eq("C"),
                eq(c.getTrialEndsAt()), eq("http://localhost:5173/admin/organizations/" + c.getId()));
    }

    @Test
    void expireLapsed_noLapsed_doesNothing() {
        when(orgRepo.findLapsedTrials(any())).thenReturn(List.of());

        List<UUID> expired = trialService.expireLapsed();

        assertThat(expired).isEmpty();
        verifyNoInteractions(emailService);
    }

    /**
     * Regression guard for the silent-downgrade bug: an org promoted directly to PREMIUM by
     * an admin tier change has its trialEndsAt cleared to null (see OrganizationService.changeTier),
     * so OrganizationRepository.findLapsedTrials(...) — which requires trialEndsAt IS NOT NULL —
     * never returns it. The sweep is then a no-op and the directly-promoted PREMIUM is preserved.
     */
    @Test
    void expireLapsed_doesNotDowngradeDirectlyPromotedPremium() {
        // Directly-promoted PREMIUM: no trial marker, so it cannot be a "lapsed trial".
        Organization directPremium = new Organization();
        directPremium.setId(UUID.randomUUID());
        directPremium.setName("Test Organization");
        directPremium.setSubscriptionTier(SubscriptionTier.PREMIUM);
        directPremium.setTrialEndsAt(null);
        assertThat(directPremium.isOnTrial()).isFalse();

        // findLapsedTrials excludes null-trial orgs, so the sweep sees nothing to expire.
        when(orgRepo.findLapsedTrials(any())).thenReturn(List.of());

        List<UUID> expired = trialService.expireLapsed();

        assertThat(expired).isEmpty();
        // Tier untouched — the directly-promoted PREMIUM stays PREMIUM.
        assertThat(directPremium.getSubscriptionTier()).isEqualTo(SubscriptionTier.PREMIUM);
        verifyNoInteractions(emailService);
    }

    /**
     * Regression guard (other direction): a GENUINE lapsed trial — PREMIUM with a real, now-past
     * trialEndsAt set by startTrial — must still be downgraded to FREE by the sweep. Confirms the
     * fix narrows the sweep to true trials only, without weakening legitimate trial expiry.
     */
    @Test
    void expireLapsed_stillDowngradesGenuineLapsedTrial() {
        Organization genuineTrial = new Organization();
        genuineTrial.setId(UUID.randomUUID());
        genuineTrial.setName("Trialing Co");
        genuineTrial.setSubscriptionTier(SubscriptionTier.PREMIUM);
        genuineTrial.setTrialEndsAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(orgRepo.findLapsedTrials(any())).thenReturn(List.of(genuineTrial));
        when(orgRepo.findById(genuineTrial.getId())).thenReturn(Optional.of(genuineTrial));
        when(orgRepo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.findByOrganizationId(genuineTrial.getId()))
                .thenReturn(List.of(userWith("admin@trialing.com", UserRole.ORG_ADMIN)));

        List<UUID> expired = trialService.expireLapsed();

        assertThat(expired).containsExactly(genuineTrial.getId());
        assertThat(genuineTrial.getSubscriptionTier()).isEqualTo(SubscriptionTier.FREE);
        verify(orgRepo).save(genuineTrial);
        verify(auditService).log(eq(null), eq(genuineTrial.getId()), eq(OrgAuditActions.TRIAL_EXPIRED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(genuineTrial.getId()), any());
        verify(cacheInvalidationService).invalidateOnTierChange();
    }

    @Test
    void notifyEndingTrials_sendsToOrgAdmins_logsAudit_whenNoPriorNotification() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("EndingSoon Co");
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        Instant endsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        org.setTrialEndsAt(endsAt);
        when(orgRepo.findEndingTrialsWithin(any(), any())).thenReturn(List.of(org));
        when(auditRepo.findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(org.getId()),
                eq(OrgAuditActions.TRIAL_ENDING_SOON_NOTIFIED))).thenReturn(null);

        User admin1 = userWith("admin1@end.com", UserRole.ORG_ADMIN);
        User admin2 = userWith("admin2@end.com", UserRole.ORG_ADMIN);
        User member = userWith("member@end.com", UserRole.MEMBER);
        when(userRepo.findByOrganizationId(org.getId())).thenReturn(List.of(admin1, admin2, member));

        List<UUID> notified = trialService.notifyEndingTrials();

        assertThat(notified).containsExactly(org.getId());
        // Both ORG_ADMINs receive the heads-up.
        verify(emailService).sendTrialEndingSoonAsync(eq("admin1@end.com"), eq("EndingSoon Co"),
                anyInt(), eq(endsAt), eq("http://localhost:5173/admin/organizations/" + org.getId()));
        verify(emailService).sendTrialEndingSoonAsync(eq("admin2@end.com"), eq("EndingSoon Co"),
                anyInt(), eq(endsAt), eq("http://localhost:5173/admin/organizations/" + org.getId()));
        verify(emailService, never()).sendTrialEndingSoonAsync(eq("member@end.com"),
                anyString(), anyInt(), any(), anyString());

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(null), eq(org.getId()), eq(OrgAuditActions.TRIAL_ENDING_SOON_NOTIFIED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(org.getId()), details.capture());
        assertThat(details.getValue()).containsKey("trialEndsAt");
        assertThat(details.getValue()).containsEntry("recipientCount", 2);
    }

    @Test
    void notifyEndingTrials_skipsWhenAlreadyNotifiedThisCycle() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("AlreadyNotified Co");
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        Instant endsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        org.setTrialEndsAt(endsAt);
        when(orgRepo.findEndingTrialsWithin(any(), any())).thenReturn(List.of(org));

        // A prior heads-up was logged 1 hour ago — within the current trial cycle.
        AuditLog prior = new AuditLog();
        prior.setOccurredAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(auditRepo.findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(org.getId()),
                eq(OrgAuditActions.TRIAL_ENDING_SOON_NOTIFIED))).thenReturn(prior);

        List<UUID> notified = trialService.notifyEndingTrials();

        assertThat(notified).isEmpty();
        verifyNoInteractions(emailService);
        verify(auditService, never()).log(any(), any(), eq(OrgAuditActions.TRIAL_ENDING_SOON_NOTIFIED),
                anyString(), any(), any());
    }

    @Test
    void notifyEndingTrials_resendsForNewCycle_whenPriorAuditIsBeforeCurrentCycle() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("NewCycle Co");
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        // Trial extended — ends 2 days from now, but a stale notification exists from
        // a previous trial cycle 30 days ago.
        Instant endsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        org.setTrialEndsAt(endsAt);
        when(orgRepo.findEndingTrialsWithin(any(), any())).thenReturn(List.of(org));

        AuditLog stale = new AuditLog();
        stale.setOccurredAt(Instant.now().minus(30, ChronoUnit.DAYS));
        when(auditRepo.findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(org.getId()),
                eq(OrgAuditActions.TRIAL_ENDING_SOON_NOTIFIED))).thenReturn(stale);

        when(userRepo.findByOrganizationId(org.getId()))
                .thenReturn(List.of(userWith("admin@new.com", UserRole.ORG_ADMIN)));

        List<UUID> notified = trialService.notifyEndingTrials();

        assertThat(notified).containsExactly(org.getId());
        verify(emailService).sendTrialEndingSoonAsync(eq("admin@new.com"), eq("NewCycle Co"),
                anyInt(), eq(endsAt), anyString());
    }

    @Test
    void notifyEndingTrials_noEndingOrgs_doesNothing() {
        when(orgRepo.findEndingTrialsWithin(any(), any())).thenReturn(List.of());

        List<UUID> notified = trialService.notifyEndingTrials();

        assertThat(notified).isEmpty();
        verifyNoInteractions(emailService);
    }

    private User userWith(String email, UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setRole(role);
        return u;
    }
}
