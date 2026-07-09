package com.bvisionry.organization;

import com.bvisionry.audit.AuditRepository;
import com.bvisionry.audit.entity.AuditLog;
import com.bvisionry.common.enums.AttentionSeverity;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.organization.dto.AttentionItem;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttentionRuleServiceTest {

    @Mock OrganizationRepository orgRepo;
    @Mock AuditRepository auditRepo;
    @Mock AttentionThresholdsService thresholds;

    @InjectMocks AttentionRuleService service;

    @BeforeEach
    void seedDefaultThresholds() {
        when(thresholds.suspendedDays()).thenReturn(7);
        when(thresholds.trialExpiryWindowDays()).thenReturn(7);
        when(thresholds.trialJustExpiredWindowDays()).thenReturn(30);
        when(thresholds.idleDays()).thenReturn(14);
        when(thresholds.onboardingStalledHours()).thenReturn(24);
        // Default: no audit rows. Per-test cases override as needed.
        when(auditRepo.findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(any(), any(), any()))
                .thenReturn(null);
        // Default: no per-org stats — tests that need members/lastLogin override.
        when(orgRepo.findOrgStatsAll()).thenReturn(List.of());
    }

    private Organization org(String name, boolean active, SubscriptionTier tier,
                             Instant trialEndsAt, Instant created) {
        Organization o = new Organization();
        o.setId(UUID.randomUUID());
        o.setName(name);
        o.setActive(active);
        o.setSubscriptionTier(tier);
        o.setTrialEndsAt(trialEndsAt);
        o.setCreatedAt(created);
        o.setUpdatedAt(created);
        return o;
    }

    private void stubStats(Organization o, long memberCount, Instant lastLogin) {
        List<Object[]> rows = new ArrayList<>(orgRepo.findOrgStatsAll());
        rows.add(new Object[]{o.getId(), memberCount, lastLogin});
        when(orgRepo.findOrgStatsAll()).thenReturn(rows);
    }

    private AuditLog auditAt(Instant when) {
        AuditLog a = new AuditLog();
        a.setOccurredAt(when);
        return a;
    }

    // ---- SUSPENDED_TOO_LONG ------------------------------------------------

    @Test
    void suspendedTooLong_firesFromAuditWhenAvailable() {
        Organization o = org("Murad", false, SubscriptionTier.FREE, null,
                Instant.now().minus(2, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        when(auditRepo.findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
                any(), any(), org.mockito.ArgumentMatchers.eq(OrgAuditActions.ORGANIZATION_SUSPENDED)))
                .thenReturn(auditAt(Instant.now().minus(30, ChronoUnit.DAYS)));

        List<AttentionItem> items = service.evaluate();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).code()).isEqualTo("SUSPENDED_TOO_LONG");
        assertThat(items.get(0).severity()).isEqualTo(AttentionSeverity.CRITICAL);
    }

    @Test
    void suspendedTooLong_fallsBackToCreatedAtWhenNoAudit() {
        // No audit row, but org was created 29 days ago and is currently suspended.
        // updatedAt is recent (e.g. unrelated edit) — must NOT mask the alert.
        Organization o = org("Murad", false, SubscriptionTier.FREE, null,
                Instant.now().minus(29, ChronoUnit.DAYS));
        o.setUpdatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));

        List<AttentionItem> items = service.evaluate();

        assertThat(items).extracting(AttentionItem::code).contains("SUSPENDED_TOO_LONG");
    }

    @Test
    void suspendedRecently_doesNotFire() {
        Organization o = org("Fresh", false, SubscriptionTier.FREE, null,
                Instant.now().minus(2, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));

        List<AttentionItem> items = service.evaluate();

        assertThat(items).isEmpty();
    }

    // ---- TRIAL_EXPIRING ----------------------------------------------------

    @Test
    void trialExpiring_emitsWarning() {
        Organization o = org("Acme", true, SubscriptionTier.PREMIUM,
                Instant.now().plus(3, ChronoUnit.DAYS),
                Instant.now().minus(60, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        stubStats(o, 5L, Instant.now());

        List<AttentionItem> items = service.evaluate();

        assertThat(items).extracting(AttentionItem::code).contains("TRIAL_EXPIRING");
        assertThat(items).extracting(AttentionItem::severity).contains(AttentionSeverity.WARNING);
    }

    @Test
    void trialExpiringInOneDay_usesSingularDay() {
        Organization o = org("Acme", true, SubscriptionTier.PREMIUM,
                // Just over 1 day — DAYS.between truncates to 1.
                Instant.now().plus(36, ChronoUnit.HOURS),
                Instant.now().minus(60, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        stubStats(o, 5L, Instant.now());

        List<AttentionItem> items = service.evaluate();

        assertThat(items).filteredOn(i -> i.code().equals("TRIAL_EXPIRING"))
                .singleElement()
                .satisfies(i -> assertThat(i.headline()).isEqualTo("Trial ends in 1 day"));
    }

    // ---- TRIAL_JUST_EXPIRED ------------------------------------------------

    @Test
    void trialJustExpiredOneDayAgo_usesSingularDay() {
        Organization o = org("Test Organization", true, SubscriptionTier.FREE, null,
                Instant.now().minus(60, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        when(auditRepo.findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
                any(), any(), org.mockito.ArgumentMatchers.eq(OrgAuditActions.TRIAL_EXPIRED)))
                .thenReturn(auditAt(Instant.now().minus(36, ChronoUnit.HOURS)));

        List<AttentionItem> items = service.evaluate();

        assertThat(items).filteredOn(i -> i.code().equals("TRIAL_JUST_EXPIRED"))
                .singleElement()
                .satisfies(i -> assertThat(i.headline()).isEqualTo("Trial expired 1 day ago"));
    }

    // ---- IDLE_ORG ----------------------------------------------------------

    @Test
    void idle_firesWhenLastLoginOldEnough() {
        Organization o = org("Stale", true, SubscriptionTier.FREE, null,
                Instant.now().minus(90, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        stubStats(o, 3L, Instant.now().minus(20, ChronoUnit.DAYS));

        List<AttentionItem> items = service.evaluate();

        assertThat(items).filteredOn(i -> i.code().equals("IDLE_ORG"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.headline()).isEqualTo("Idle for over 14 days");
                    assertThat(i.detail()).isEqualTo("Possible churn — check in.");
                });
    }

    @Test
    void neverLoggedIn_oldOrg_firesWithDistinctHeadline() {
        // Org has members but no one has ever logged in, and it's older than the
        // idle threshold — fire IDLE with the "never logged in" wording instead
        // of the misleading "Idle for over N days".
        Organization o = org("Razan Org", true, SubscriptionTier.PREMIUM, null,
                Instant.now().minus(26, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        stubStats(o, 2L, null);

        List<AttentionItem> items = service.evaluate();

        assertThat(items).filteredOn(i -> i.code().equals("IDLE_ORG"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.headline()).isEqualTo("No member has logged in yet");
                    assertThat(i.detail()).contains("Onboarding may have stalled");
                });
    }

    @Test
    void neverLoggedIn_brandNewOrg_doesNotFireIdle() {
        // Brand-new org with members invited but not yet logged in must NOT
        // immediately misreport as "Idle for over 14 days".
        Organization o = org("Just Created", true, SubscriptionTier.FREE, null,
                Instant.now().minus(2, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        stubStats(o, 2L, null);

        List<AttentionItem> items = service.evaluate();

        assertThat(items).extracting(AttentionItem::code).doesNotContain("IDLE_ORG");
    }

    @Test
    void onTrial_isNotFlaggedIdle() {
        Organization o = org("Trialing", true, SubscriptionTier.PREMIUM,
                Instant.now().plus(10, ChronoUnit.DAYS),
                Instant.now().minus(60, ChronoUnit.DAYS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        stubStats(o, 3L, Instant.now().minus(30, ChronoUnit.DAYS));

        List<AttentionItem> items = service.evaluate();

        assertThat(items).extracting(AttentionItem::code).doesNotContain("IDLE_ORG");
    }

    // ---- ONBOARDING_STALLED ------------------------------------------------

    @Test
    void onboardingStalled_firesForRecentEmptyOrg() {
        Organization o = org("Empty", true, SubscriptionTier.FREE, null,
                Instant.now().minus(2, ChronoUnit.HOURS));
        when(orgRepo.findByParentOrganizationIsNull()).thenReturn(List.of(o));
        stubStats(o, 0L, null);

        List<AttentionItem> items = service.evaluate();

        assertThat(items).extracting(AttentionItem::code).contains("ONBOARDING_STALLED");
    }
}
