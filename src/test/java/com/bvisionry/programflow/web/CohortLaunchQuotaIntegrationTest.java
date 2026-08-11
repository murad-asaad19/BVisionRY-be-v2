package com.bvisionry.programflow.web;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.SubscriptionTier.LaunchPeriodUnit;
import com.bvisionry.common.exception.LaunchQuotaExceededException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.domain.CohortLaunchLedgerEntry;
import com.bvisionry.programflow.domain.CohortStatus;
import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.CohortPlanUsageResponse;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.GrantLaunchRequest;
import com.bvisionry.programflow.dto.LaunchLedgerResponse;
import com.bvisionry.programflow.dto.OnboardingChecklistResponse;
import com.bvisionry.programflow.repository.CohortLaunchLedgerRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The launch-time calendar-period quota end to end against real Postgres with
 * Flyway applied (redesign spec §8, V167). Replaces the retired
 * CohortAllowanceIntegrationTest: creation is now ALWAYS free (drafts), and
 * the meter fires on the DRAFT → LAUNCHED transition, appending to the
 * never-refunded ledger.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class CohortLaunchQuotaIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private CohortService cohortService;
    @Autowired private LaunchQuotaService quotaService;
    @Autowired private CohortLaunchLedgerRepository ledger;
    @Autowired private OrganizationRepository orgs;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private Organization root;
    private Organization subA;
    private Organization subB;

    @BeforeEach
    void seed() {
        root = saveOrg("Root Accelerator", SubscriptionTier.STARTER, null);
        subA = saveOrg("Cohort Surface A", SubscriptionTier.FREE, root);
        subB = saveOrg("Cohort Surface B", SubscriptionTier.FREE, root);
        TestAuthentication.authenticateAsOrgAdmin(users, subA);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /* ---------------------------------------------------- drafts are free */

    @Test
    void draftsAreFreeOnEveryTier_evenFree() {
        setTier(root, SubscriptionTier.FREE);
        for (int i = 0; i < 3; i++) {
            CohortDto draft = cohortService.create(req("Draft " + i));
            assertThat(draft.status()).isEqualTo(CohortStatus.DRAFT);
            assertThat(draft.launchedAt()).isNull();
        }
        assertThat(ledger.countByOrgId(root.getId())).isZero();
    }

    /* ------------------------------------------------- the quota itself */

    @Test
    void starter_secondLaunchInTheQuarter_is409WithNextQuarterStart() {
        launch(subA, "Spring 26");

        UUID second = draft(subA, "Summer 26");
        assertThatThrownBy(() -> cohortService.launch(second))
                .isInstanceOfSatisfying(LaunchQuotaExceededException.class, ex -> {
                    assertThat(ex.getTier()).isEqualTo(SubscriptionTier.STARTER);
                    assertThat(ex.getNextAvailableDate()).isEqualTo(
                            LaunchPeriodUnit.QUARTER.nextPeriodStart(LocalDate.now(ZoneOffset.UTC)));
                })
                .hasMessageContaining("Starter")
                .hasMessageContaining("per quarter");
    }

    @Test
    void grant_thenLaunchSucceeds_andDeleteNeverRefunds() {
        UUID first = launch(subA, "Spring 26");
        UUID second = draft(subA, "Summer 26");
        assertThatThrownBy(() -> cohortService.launch(second))
                .isInstanceOf(LaunchQuotaExceededException.class);

        TestAuthentication.authenticateAsSuperAdmin(users);
        CohortPlanUsageResponse afterGrant = quotaService.grantExtraLaunch(
                subA.getId(), new GrantLaunchRequest("pilot extension"));
        assertThat(afterGrant.grantsThisPeriod()).isEqualTo(1);
        assertThat(afterGrant.remaining()).isEqualTo(1);

        TestAuthentication.authenticateAsOrgAdmin(users, subA);
        assertThatCode(() -> cohortService.launch(second))
                .doesNotThrowAnyException();

        // Deleting a launched cohort keeps the ledger row: still no room.
        cohortService.delete(first);
        assertThat(ledger.countByOrgId(root.getId())).isEqualTo(2);
        UUID third = draft(subA, "Autumn 26");
        assertThatThrownBy(() -> cohortService.launch(third))
                .isInstanceOf(LaunchQuotaExceededException.class);
    }

    /** Growth meters a MONTH: a launch 35 days ago is out of window, one now is in. */
    @Test
    void growth_monthBoundary() {
        setTier(root, SubscriptionTier.GROWTH);
        seedLedger(root, 35);

        assertThatCode(() -> cohortService.launch(draft(subA, "First this month")))
                .doesNotThrowAnyException();
        UUID second = draft(subA, "Second this month");
        assertThatThrownBy(() -> cohortService.launch(second))
                .isInstanceOfSatisfying(LaunchQuotaExceededException.class, ex ->
                        assertThat(ex.getNextAvailableDate()).isEqualTo(
                                LaunchPeriodUnit.MONTH.nextPeriodStart(LocalDate.now(ZoneOffset.UTC))))
                .hasMessageContaining("per month");
    }

    /** Trial = 1 launch TOTAL, lifetime — a rolled calendar period gives nothing back. */
    @Test
    void trial_oneLaunchTotal_evenAfterThePeriodRolls() {
        setTier(root, SubscriptionTier.GROWTH);
        root.setTrialEndsAt(Instant.now().plusSeconds(7L * 24 * 3600));
        orgs.saveAndFlush(root);

        launch(subA, "Trial cohort");
        // Simulate the calendar rolling: push the launch far into the past.
        jdbc.update("UPDATE cohort_launch_ledger SET launched_at = now() - interval '200 days' "
                + "WHERE org_id = ?", root.getId());

        UUID second = draft(subA, "Post-roll attempt");
        assertThatThrownBy(() -> cohortService.launch(second))
                .isInstanceOfSatisfying(LaunchQuotaExceededException.class, ex -> {
                    assertThat(ex.getNextAvailableDate()).isNull(); // no date will help
                })
                .hasMessageContaining("Trial");
    }

    @Test
    void free_zeroLaunches_draftAllowedLaunchRefused() {
        setTier(root, SubscriptionTier.FREE);
        UUID cohortId = draft(subA, "Free draft");

        assertThatThrownBy(() -> cohortService.launch(cohortId))
                .isInstanceOfSatisfying(LaunchQuotaExceededException.class, ex -> {
                    assertThat(ex.getTier()).isEqualTo(SubscriptionTier.FREE);
                    assertThat(ex.getNextAvailableDate()).isNull();
                });
    }

    /* ----------------------------------------------- billing-family rule */

    @Test
    void aSiblingSubOrgSharesTheRootsMeter() {
        launch(subA, "A's cohort");
        UUID inB = draft(subB, "B's cohort");
        assertThatThrownBy(() -> cohortService.launch(inB))
                .isInstanceOf(LaunchQuotaExceededException.class)
                .hasMessageContaining("share the parent organization's plan");
    }

    @Test
    void anUnrelatedFamilysLaunchesDoNotCount() {
        Organization otherRoot = saveOrg("Other Customer", SubscriptionTier.STARTER, null);
        seedLedger(otherRoot, 1);

        assertThatCode(() -> cohortService.launch(draft(subA, "Ours")))
                .doesNotThrowAnyException();
    }

    /* -------------------------------------------------- super admin path */

    /** Bypasses the refusal but is still ledgered — a launched cohort is a launched cohort. */
    @Test
    void superAdmin_bypassesTheRefusal_butStillWritesTheLedger() {
        launch(subA, "Quota spender");
        TestAuthentication.authenticateAsSuperAdmin(users);

        assertThatCode(() -> cohortService.launch(draft(subA, "Operator demo")))
                .doesNotThrowAnyException();
        assertThat(ledger.countByOrgId(root.getId())).isEqualTo(2);
    }

    /* -------------------------------------------------------- usage read */

    @Test
    void usageRead_tellsTheWholeCardStory() {
        CohortPlanUsageResponse before = quotaService.usage(subA.getId());
        assertThat(before.tier()).isEqualTo(SubscriptionTier.STARTER);
        assertThat(before.periodLabel()).isEqualTo("quarter");
        assertThat(before.allowance()).isEqualTo(1);
        assertThat(before.used()).isZero();
        assertThat(before.remaining()).isEqualTo(1);
        assertThat(before.nextAvailableDate()).isNull();
        assertThat(before.nextResetDate()).isEqualTo(
                LaunchPeriodUnit.QUARTER.nextPeriodStart(LocalDate.now(ZoneOffset.UTC)));

        launch(subA, "Spring 26");
        CohortPlanUsageResponse after = quotaService.usage(subA.getId());
        assertThat(after.used()).isEqualTo(1);
        assertThat(after.remaining()).isZero();
        assertThat(after.nextAvailableDate()).isEqualTo(after.nextResetDate());

        // The read answers identically for any org of the family.
        assertThat(quotaService.usage(root.getId()).used()).isEqualTo(1);
    }

    @Test
    void founderSuccess_isUnlimited() {
        setTier(root, SubscriptionTier.FOUNDER_SUCCESS);
        for (int i = 0; i < 3; i++) {
            launch(subA, "Unlimited " + i);
        }
        CohortPlanUsageResponse usage = quotaService.usage(subA.getId());
        assertThat(usage.allowance()).isNull();
        assertThat(usage.remaining()).isNull();
        assertThat(usage.periodLabel()).isEqualTo("unlimited");
        assertThat(usage.used()).isEqualTo(3);
    }

    @Test
    void ledgerHistory_carriesNamesAndSurvivesCohortDeletion() {
        UUID cohortId = launch(subA, "Spring 26");
        TestAuthentication.authenticateAsSuperAdmin(users);
        quotaService.grantExtraLaunch(subA.getId(), new GrantLaunchRequest(null));

        LaunchLedgerResponse history = quotaService.history(subA.getId());
        assertThat(history.launches()).hasSize(1);
        assertThat(history.launches().get(0).cohortName()).isEqualTo("Spring 26");
        assertThat(history.launches().get(0).launchedByName()).isEqualTo("Test Org Admin");
        assertThat(history.grants()).hasSize(1);
        assertThat(history.grants().get(0).grantedByName()).isEqualTo("Test Super Admin");

        TestAuthentication.authenticateAsOrgAdmin(users, subA);
        cohortService.delete(cohortId);
        LaunchLedgerResponse afterDelete = quotaService.history(subA.getId());
        assertThat(afterDelete.launches()).hasSize(1);
        assertThat(afterDelete.launches().get(0).cohortName()).isNull(); // outlives the cohort
    }

    /* ------------------------------------------------ stalled onboarding */

    @Test
    void stalledOnboarding_flagsOldOrgsWithZeroLaunches_boundaryAtThresholdHours() {
        assertThat(quotaService.usage(subA.getId()).stalledOnboarding())
                .as("fresh org is not stalled").isFalse();

        // Older than the 24h default with zero launches ever → stalled.
        jdbc.update("UPDATE organizations SET created_at = now() - interval '25 hours' WHERE id = ?",
                root.getId());
        assertThat(quotaService.usage(subA.getId()).stalledOnboarding()).isTrue();

        // Inside the threshold → not stalled (boundary).
        jdbc.update("UPDATE organizations SET created_at = now() - interval '23 hours' WHERE id = ?",
                root.getId());
        assertThat(quotaService.usage(subA.getId()).stalledOnboarding()).isFalse();

        // Old again, but one launch ever → not stalled.
        jdbc.update("UPDATE organizations SET created_at = now() - interval '25 hours' WHERE id = ?",
                root.getId());
        launch(subA, "First launch");
        assertThat(quotaService.usage(subA.getId()).stalledOnboarding()).isFalse();
    }

    /* --------------------------------------------- onboarding checklist */

    @Test
    void onboardingChecklist_flipsItemByItem() {
        // The org-admin user persisted by TestAuthentication is ORG_ADMIN, not
        // a MEMBER, so the fresh family starts all-false.
        OnboardingChecklistResponse fresh = quotaService.checklist(subA.getId());
        assertThat(fresh.invitedMembers()).isFalse();
        assertThat(fresh.assignedFirstAssessment()).isFalse();
        assertThat(fresh.createdAndLaunchedCohort()).isFalse();
        assertThat(fresh.assignedCoach()).isFalse();

        jdbc.update("""
                INSERT INTO users (id, email, name, password_hash, role, status, organization_id)
                VALUES (?, 'founder@checklist.invalid', 'Founder', 'x', 'MEMBER', 'ACTIVE', ?)
                """, UUID.randomUUID(), subA.getId());
        launch(subA, "Launched");
        UUID coachId = TestAuthentication.authenticateAsOrgAdmin(users, subA).getId();
        jdbc.update("INSERT INTO coach_assignments (org_id, coach_id, member_id) VALUES (?, ?, ?)",
                subA.getId(), coachId,
                jdbc.queryForObject("SELECT id FROM users WHERE email = 'founder@checklist.invalid'",
                        UUID.class));

        OnboardingChecklistResponse after = quotaService.checklist(subA.getId());
        assertThat(after.invitedMembers()).isTrue();
        assertThat(after.createdAndLaunchedCohort()).isTrue();
        assertThat(after.assignedCoach()).isTrue();
        assertThat(after.assignedFirstAssessment()).isFalse(); // still no assessment
    }

    /* ------------------------------------------------------ V156 pin (kept) */

    /**
     * V156 is EXPAND-ONLY: it widens the tier CHECK and retires PREMIUM in the
     * data, but leaves 'PREMIUM' in the allowed set so an old instance still
     * serving during a rolling deploy can write it without hitting a constraint
     * violation on a live request. (Moved from the retired
     * CohortAllowanceIntegrationTest — same reasoning, same context.)
     */
    @Test
    void v156_isExpandOnly_theRetiredPremiumValueIsStillWritable() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO organizations (id, name, subscription_tier, is_active) "
                        + "VALUES (?, ?, 'PREMIUM', true)",
                UUID.randomUUID(), "Written by a pre-deploy instance"))
                .doesNotThrowAnyException();
    }

    /* --------------------------------------------------------------- helpers */

    private static CreateCohortRequest req(String name) {
        return new CreateCohortRequest(name);
    }

    /** Spec §13: create the platform draft, then assign the org (its enrollment rule empty). */
    private UUID draft(Organization org, String name) {
        UUID id = cohortService.create(req(name)).id();
        cohortService.assignOrg(id, new com.bvisionry.programflow.dto.AssignOrgRequest(
                org.getId(), false, java.util.List.of(), false));
        return id;
    }

    private UUID launch(Organization org, String name) {
        UUID id = draft(org, name);
        CohortDto launched = cohortService.launch(id);
        assertThat(launched.status()).isEqualTo(CohortStatus.LAUNCHED);
        assertThat(launched.launchedAt()).isNotNull();
        return id;
    }

    private Organization saveOrg(String name, SubscriptionTier tier, Organization parent) {
        Organization o = new Organization();
        o.setName(name);
        o.setSubscriptionTier(tier);
        o.setActive(true);
        o.setParentOrganization(parent);
        return orgs.saveAndFlush(o);
    }

    private void setTier(Organization org, SubscriptionTier tier) {
        org.setSubscriptionTier(tier);
        orgs.saveAndFlush(org);
    }

    /** A ledger row {@code daysAgo} days old — pre-existing consumption. */
    private void seedLedger(Organization rootOrg, int daysAgo) {
        CohortLaunchLedgerEntry e = new CohortLaunchLedgerEntry();
        e.setOrgId(rootOrg.getId());
        e.setCohortId(UUID.randomUUID());
        e.setLaunchedAt(OffsetDateTime.now().minusDays(daysAgo));
        ledger.saveAndFlush(e);
    }
}
