package com.bvisionry.notification.push;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a submission notification reaches lives in SQL — the assignment union
 * composed from {@code CoachAccess} — so it is tested against real Postgres.
 * This is the test that fails if a coach is ever told about a founder they do
 * not coach, or is never told about one they do.
 *
 * <p>{@code @Transactional} for the same reason as
 * {@link InactivityNudgeQueryIntegrationTest}: the Testcontainers Postgres is a
 * singleton shared by every IT class in the JVM, so rows seeded here roll back.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
@Transactional
class CoachReviewNotifierIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CoachReviewNotifier notifier;

    private UUID orgId;
    private UUID founderId;

    @BeforeEach
    void seed() {
        orgId = org("Coach Org");
        founderId = user(orgId, "MEMBER", "ACTIVE");
    }

    // --- the three grains all reach the coach --------------------------------

    @Test
    void aCoachWithADirectGrantOnTheFounderIsARecipient() {
        UUID coach = user(orgId, "COACH", "ACTIVE");
        grant(orgId, coach, null, founderId);

        assertThat(notifier.coachesOf(orgId, founderId)).containsExactly(coach);
    }

    @Test
    void aCoachWithAGrantOnTheFoundersCohortIsARecipient() {
        UUID coach = user(orgId, "COACH", "ACTIVE");
        UUID cohort = cohort(orgId);
        enrol(cohort, founderId);
        grant(orgId, coach, cohort, null);

        assertThat(notifier.coachesOf(orgId, founderId)).containsExactly(coach);
    }

    @Test
    void theOrgWideGrantReachesEveryFounderInTheOrg() {
        // V176's third grain: neither cohort_id nor member_id. The house coach
        // must hear about a founder who is in no cohort at all.
        UUID houseCoach = user(orgId, "COACH", "ACTIVE");
        grant(orgId, houseCoach, null, null);

        assertThat(notifier.coachesOf(orgId, founderId)).containsExactly(houseCoach);
    }

    // --- and nobody else -----------------------------------------------------

    @Test
    void aCoachWithoutAGrantOnThisFounderIsNotARecipient() {
        UUID granted = user(orgId, "COACH", "ACTIVE");
        UUID stranger = user(orgId, "COACH", "ACTIVE");
        // The stranger coaches a DIFFERENT founder in the same org — the case a
        // naive "all coaches of the org" fan-out would silently pass.
        grant(orgId, granted, null, founderId);
        grant(orgId, stranger, null, user(orgId, "MEMBER", "ACTIVE"));

        assertThat(notifier.coachesOf(orgId, founderId))
                .containsExactly(granted)
                .doesNotContain(stranger);
    }

    @Test
    void aStaleGrantOnASuspendedOrDemotedCoachReachesNobody() {
        UUID suspended = user(orgId, "COACH", "SUSPENDED");
        UUID demoted = user(orgId, "ORG_ADMIN", "ACTIVE");
        grant(orgId, suspended, null, founderId);
        grant(orgId, demoted, null, founderId);

        // The org admin still hears about the submission — through
        // notifyOrgAdmins, which this fan-out deliberately does not duplicate.
        assertThat(notifier.coachesOf(orgId, founderId)).isEmpty();
    }

    @Test
    void aCrossOrgGrantNeverReachesAForeignTenantsCoach() {
        // org_id + member_id in this org, coach_id in another: the shared
        // relation is satisfied entirely, and only cu.organization_id stops it.
        UUID foreignCoach = user(org("Other Tenant"), "COACH", "ACTIVE");
        grant(orgId, foreignCoach, null, founderId);

        assertThat(notifier.coachesOf(orgId, founderId)).isEmpty();
    }

    @Test
    void aFounderWhoLeftTheOrgOrWasDeactivatedDropsOutOfTheUnion() {
        UUID coach = user(orgId, "COACH", "ACTIVE");
        grant(orgId, coach, null, founderId);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", founderId);

        assertThat(notifier.coachesOf(orgId, founderId)).isEmpty();
    }

    // --- the deep link -------------------------------------------------------

    @Test
    void everyCoachDeepLinkIsARouteACoachMayOpen() {
        // An /app/admin/** URL here 404s for every recipient — the whole reason
        // this mapping is not left to callers.
        assertThat(CoachReviewNotifier.coachUrl(NotificationType.EXERCISE_ACTIVITY, founderId))
                .isEqualTo("/app/coach/queue");
        // The queue is exercise-only, so these two aim at the founder profile,
        // whose Work tab actually shows the submitted item.
        assertThat(CoachReviewNotifier.coachUrl(NotificationType.MEMBER_SUBMITTED, founderId))
                .isEqualTo("/app/coach/founders/" + founderId);
        assertThat(CoachReviewNotifier.coachUrl(NotificationType.PROGRAM_TASK_SUBMITTED, founderId))
                .isEqualTo("/app/coach/founders/" + founderId);
    }

    // --- fixtures ------------------------------------------------------------

    private UUID org(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, is_active) VALUES (?, ?, true)",
                id, name + " " + id);
        return id;
    }

    private UUID user(UUID org, String role, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id)
                VALUES (?, ?, 'Person', ?, ?, ?)
                """, id, id + "@coach.invalid", role, status, org);
        return id;
    }

    /**
     * A cohort belonging to {@code org} — which since V171 is TWO rows, not a
     * column: `cohorts.org_id` was dropped and the relationship moved to the
     * `cohort_orgs` join, because a cohort is a platform artifact that may span
     * organizations (spec §13). A single-table insert here compiles fine and
     * fails at runtime with "bad SQL grammar".
     */
    private UUID cohort(UUID org) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name) VALUES (?, 'Spring')", id);
        jdbc.update(
                "INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", id, org);
        return id;
    }

    private void enrol(UUID cohort, UUID user) {
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", cohort, user);
    }

    /**
     * A grant of any grain: cohort, direct founder, or (both null) org-wide.
     * The two nullable slots are cast explicitly — a bare {@code ?} bound to
     * null leaves the driver to infer a uuid column's type.
     */
    private void grant(UUID org, UUID coach, UUID cohort, UUID member) {
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, CAST(? AS uuid), CAST(? AS uuid))
                """, org, coach, cohort, member);
    }
}
