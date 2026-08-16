package com.bvisionry.notification.push;

import java.util.List;
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
 * The inactivity predicate and the send-once guard live in SQL, so they are
 * tested against real Postgres — {@link InactivityNudgeJobTest} mocks the
 * repository and cannot evaluate either. This is the test that fails if the
 * nudge ever picks the wrong founder, the wrong tenant, or the same founder
 * twice in one quiet period.
 *
 * <p>{@code @Transactional}: the Testcontainers Postgres is a singleton shared
 * by every IT class in the JVM, so every row seeded here is rolled back rather
 * than left for the next class to trip over.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
@Transactional
class InactivityNudgeQueryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserNotificationRepository repository;

    private UUID orgId;
    private UUID courseId;
    private UUID contentId;

    @BeforeEach
    void seed() {
        orgId = org("Nudge Org", 14, true);
        courseId = course(orgId, "Fundraising Basics");
        contentId = lesson(orgId, courseId);
    }

    // --- the predicate -------------------------------------------------------

    @Test
    void selectsAFounderWhoseLastCompletedLessonIsOlderThanTheWindow() {
        UUID stalled = member(orgId, "MEMBER", "ACTIVE");
        completedLessonDaysAgo(enrol(stalled, courseId, "ACTIVE", 60), 30);

        assertThat(repository.findStalledLearners(orgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(stalled);
    }

    @Test
    void ignoresAFounderWhoCompletedALessonInsideTheWindow() {
        UUID active = member(orgId, "MEMBER", "ACTIVE");
        // Enrolled long ago, but progressed two days ago: the fallback to
        // enrolled_at must NOT win over a real progress event.
        completedLessonDaysAgo(enrol(active, courseId, "ACTIVE", 60), 2);

        assertThat(repository.findStalledLearners(orgId)).isEmpty();
    }

    @Test
    void aFounderWhoHasCompletedNothingIsMeasuredFromTheEnrolmentItself() {
        // The case the job exists for: enrolled and never started. With no
        // content_progress row there is nothing to take a MAX of, so without
        // the enrolled_at fallback this founder is invisible forever.
        UUID neverStarted = member(orgId, "MEMBER", "ACTIVE");
        enrol(neverStarted, courseId, "ACTIVE", 30);

        assertThat(repository.findStalledLearners(orgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(neverStarted);

        UUID justEnrolled = member(orgId, "MEMBER", "ACTIVE");
        enrol(justEnrolled, courseId, "ACTIVE", 1);

        assertThat(repository.findStalledLearners(orgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(neverStarted);
    }

    @Test
    void ignoresEnrolmentsThatAreNoLongerActive() {
        UUID completedIt = member(orgId, "MEMBER", "ACTIVE");
        enrol(completedIt, courseId, "COMPLETED", 90);
        UUID cancelled = member(orgId, "MEMBER", "ACTIVE");
        enrol(cancelled, courseId, "CANCELLED", 90);

        assertThat(repository.findStalledLearners(orgId)).isEmpty();
    }

    @Test
    void reportsTheStalestCourseOnceEvenWhenSeveralHaveStalled() {
        UUID founder = member(orgId, "MEMBER", "ACTIVE");
        UUID secondCourse = course(orgId, "Unit Economics");
        lesson(orgId, secondCourse);
        enrol(founder, courseId, "ACTIVE", 30);
        enrol(founder, secondCourse, "ACTIVE", 90);

        // One notification per founder per window, naming the worst offender —
        // three stalled enrolments must not mean three bell entries.
        assertThat(repository.findStalledLearners(orgId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getUserId()).isEqualTo(founder);
                    assertThat(row.getCourseTitle()).isEqualTo("Unit Economics");
                    assertThat(row.getCourseSlug()).isNotBlank();
                    assertThat(row.getStalledDays()).isEqualTo(90);
                });
    }

    /**
     * The regression that made the feature silently useless for its own
     * population. Every founder can enrol on the shared "Bvisionry Academy"
     * catalog (V77) — a DESIGNED cross-org surface — and those enrolments are
     * systematically older than the org's own course. Stalest-wins therefore
     * named a foreign course, and the send-once guard then suppressed that
     * founder for N days, so the org's own course never nudged at all.
     */
    @Test
    void prefersTheFoundersOwnOrgCourseOverAnOlderSharedCatalogEnrolment() {
        UUID academyOrg = org("Bvisionry Academy", 14, true);
        UUID academyCourse = course(academyOrg, "Shared Catalog Course");
        lesson(academyOrg, academyCourse);
        UUID founder = member(orgId, "MEMBER", "ACTIVE");

        // The Academy enrolment is FIVE TIMES staler — stalest-wins would pick
        // it every time.
        enrol(founder, academyCourse, "ACTIVE", 150);
        enrol(founder, courseId, "ACTIVE", 30);

        assertThat(repository.findStalledLearners(orgId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getCourseTitle()).isEqualTo("Fundraising Basics");
                    assertThat(row.getStalledDays()).isEqualTo(30);
                });
    }

    /**
     * …but preference is an ORDER BY, not a WHERE. The shared catalog is
     * legitimate content, so a founder whose ONLY stalled enrolment is an
     * Academy course still gets nudged — filtering on {@code c.org_id} would
     * have dropped them.
     */
    @Test
    void stillNudgesWhenTheOnlyStalledEnrolmentIsASharedCatalogCourse() {
        UUID academyOrg = org("Bvisionry Academy", 14, true);
        UUID academyCourse = course(academyOrg, "Shared Catalog Course");
        lesson(academyOrg, academyCourse);
        UUID founder = member(orgId, "MEMBER", "ACTIVE");
        enrol(founder, academyCourse, "ACTIVE", 40);

        assertThat(repository.findStalledLearners(orgId))
                .singleElement()
                .satisfies(row -> assertThat(row.getCourseTitle())
                        .isEqualTo("Shared Catalog Course"));
    }

    @Test
    void ignoresCoursesTheFounderCouldNotResume() {
        // Not published: the player will not serve it, so the nudge would send
        // the founder somewhere they cannot act.
        UUID draft = course(orgId, "Draft Course", "DRAFT");
        lesson(orgId, draft);
        UUID onDraft = member(orgId, "MEMBER", "ACTIVE");
        enrol(onDraft, draft, "ACTIVE", 60);

        // No lessons at all: content_progress can never gain a row, so this
        // founder's "last activity" is frozen at enrolled_at forever and they
        // would be nudged every N days for the rest of time with no possible
        // escape. The one genuinely unescapable case, so it is excluded.
        UUID empty = course(orgId, "Empty Course");
        UUID onEmpty = member(orgId, "MEMBER", "ACTIVE");
        enrol(onEmpty, empty, "ACTIVE", 60);

        assertThat(repository.findStalledLearners(orgId)).isEmpty();
    }

    /**
     * The exact window boundary, asserted a minute either side rather than at
     * {@code now() - 14 days} exactly: the cutoff is recomputed at query time,
     * microseconds after the row is written, so "exactly 14 days ago" would
     * always land just outside and the test would pass for the wrong reason.
     */
    @Test
    void theWindowBoundaryIsTheOrgsOwnNDays() {
        UUID justInside = member(orgId, "MEMBER", "ACTIVE");
        enrolAt(justInside, courseId, "now() - interval '14 days' + interval '1 minute'");
        assertThat(repository.findStalledLearners(orgId)).isEmpty();

        UUID justOutside = member(orgId, "MEMBER", "ACTIVE");
        enrolAt(justOutside, courseId, "now() - interval '14 days' - interval '1 minute'");
        assertThat(repository.findStalledLearners(orgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(justOutside);
    }

    @Test
    void aMutedFounderDropsOutOfTheSweepEntirely() {
        UUID muted = member(orgId, "MEMBER", "ACTIVE");
        enrol(muted, courseId, "ACTIVE", 60);
        jdbc.update("""
                INSERT INTO notification_optouts (user_id, notification_type)
                VALUES (?, 'INACTIVITY_NUDGE')
                """, muted);

        // Dispatch would drop them anyway, but they never get a history row, so
        // without this they would be re-selected on every run forever AND the
        // job's "nudged N founder(s)" log would count a notification nobody got.
        assertThat(repository.findStalledLearners(orgId)).isEmpty();
    }

    @Test
    void mutingADifferentTypeDoesNotSuppressTheNudge() {
        UUID founder = member(orgId, "MEMBER", "ACTIVE");
        enrol(founder, courseId, "ACTIVE", 60);
        jdbc.update("""
                INSERT INTO notification_optouts (user_id, notification_type)
                VALUES (?, 'ANNOUNCEMENT')
                """, founder);

        assertThat(repository.findStalledLearners(orgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(founder);
    }

    // --- send-once -----------------------------------------------------------

    @Test
    void aNudgeAlreadySentInsideTheWindowSuppressesTheNext() {
        UUID founder = member(orgId, "MEMBER", "ACTIVE");
        enrol(founder, courseId, "ACTIVE", 60);
        nudgeSentDaysAgo(founder, 3);

        // This is the whole idempotency mechanism: the notification history row
        // IS the "last nudged at" record, so a daily run cannot nudge daily.
        assertThat(repository.findStalledLearners(orgId)).isEmpty();
    }

    @Test
    void aNudgeOlderThanTheWindowLetsTheNextOneThrough() {
        UUID founder = member(orgId, "MEMBER", "ACTIVE");
        enrol(founder, courseId, "ACTIVE", 60);
        nudgeSentDaysAgo(founder, 20);

        assertThat(repository.findStalledLearners(orgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(founder);
    }

    @Test
    void anotherNotificationTypeIsNotMistakenForANudge() {
        UUID founder = member(orgId, "MEMBER", "ACTIVE");
        enrol(founder, courseId, "ACTIVE", 60);
        notification(founder, "ANNOUNCEMENT", 1);

        assertThat(repository.findStalledLearners(orgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(founder);
    }

    // --- tenancy and roster --------------------------------------------------

    @Test
    void neverReturnsAnotherTenantsFounder() {
        UUID otherOrgId = org("Other Org", 14, true);
        UUID theirs = member(otherOrgId, "MEMBER", "ACTIVE");
        // Enrolled on THIS org's course — the cross-org enrolment ghost. Org
        // membership is decided by the users row, never by the enrolment, so
        // the sweep of orgId must not see them.
        enrol(theirs, courseId, "ACTIVE", 60);
        UUID ours = member(orgId, "MEMBER", "ACTIVE");
        enrol(ours, courseId, "ACTIVE", 60);

        assertThat(repository.findStalledLearners(orgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(ours);
        assertThat(repository.findStalledLearners(otherOrgId))
                .extracting(StalledLearnerRow::getUserId)
                .containsExactly(theirs);
    }

    @Test
    void ignoresSuspendedAccountsAndStaff() {
        UUID suspended = member(orgId, "MEMBER", "SUSPENDED");
        enrol(suspended, courseId, "ACTIVE", 60);
        UUID admin = member(orgId, "ORG_ADMIN", "ACTIVE");
        enrol(admin, courseId, "ACTIVE", 60);
        UUID coach = member(orgId, "COACH", "ACTIVE");
        enrol(coach, courseId, "ACTIVE", 60);

        assertThat(repository.findStalledLearners(orgId)).isEmpty();
    }

    // --- the org switch ------------------------------------------------------

    @Test
    void onlyActiveOrgsWithAPositiveWindowAreSwept() {
        UUID disabled = org("Nudges Off", 0, true);
        UUID suspendedOrg = org("Suspended", 14, false);

        List<UUID> swept = repository.findOrgIdsWithNudgesEnabled();

        assertThat(swept).contains(orgId).doesNotContain(disabled, suspendedOrg);
    }

    @Test
    void aDisabledOrgYieldsNoFoundersEvenIfAskedDirectly() {
        UUID disabled = org("Nudges Off", 0, true);
        UUID theirCourse = course(disabled, "Ignored Course");
        // A real, resumable course — otherwise this asserts the lesson filter,
        // not the off switch, and would keep passing if the switch broke.
        lesson(disabled, theirCourse);
        UUID founder = member(disabled, "MEMBER", "ACTIVE");
        enrol(founder, theirCourse, "ACTIVE", 90);

        // Defence in depth: the org list already excludes it, and the row query
        // re-checks, so a caller passing an arbitrary org id still gets nothing.
        assertThat(repository.findStalledLearners(disabled)).isEmpty();
    }

    // --- fixtures ------------------------------------------------------------

    private UUID org(String name, int nudgeDays, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organizations (id, name, is_active, inactivity_nudge_days)
                VALUES (?, ?, ?, ?)
                """, id, name + " " + id, active, nudgeDays);
        return id;
    }

    private UUID member(UUID org, String role, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id)
                VALUES (?, ?, 'Founder', ?, ?, ?)
                """, id, id + "@nudge.invalid", role, status, org);
        return id;
    }

    /** A PUBLISHED course (the column's own default) with no lessons yet. */
    private UUID course(UUID org, String title) {
        return course(org, title, "PUBLISHED");
    }

    private UUID course(UUID org, String title, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title, state) VALUES (?, ?, ?, ?, ?)",
                id, org, "nudge-" + id, title, state);
        return id;
    }

    private UUID lesson(UUID org, UUID course) {
        UUID sectionId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO section (id, org_id, course_id, title) VALUES (?, ?, ?, 'Module 1')",
                sectionId, org, course);
        jdbc.update("INSERT INTO content (id, org_id, section_id, title) VALUES (?, ?, ?, 'Lesson 1')",
                id, org, sectionId);
        return id;
    }

    private UUID enrol(UUID user, UUID course, String status, int enrolledDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO enrollment (id, user_id, course_id, status, enrolled_at)
                VALUES (?, ?, ?, ?, now() - make_interval(days => ?))
                """, id, user, course, status, enrolledDaysAgo);
        return id;
    }

    /** ACTIVE enrolment whose enrolled_at is a raw SQL expression — boundary cases. */
    private void enrolAt(UUID user, UUID course, String enrolledAtSql) {
        jdbc.update("""
                INSERT INTO enrollment (user_id, course_id, status, enrolled_at)
                VALUES (?, ?, 'ACTIVE', %s)
                """.formatted(enrolledAtSql), user, course);
    }

    private void completedLessonDaysAgo(UUID enrolment, int daysAgo) {
        jdbc.update("""
                INSERT INTO content_progress (enrollment_id, content_id, completed, completed_at)
                VALUES (?, ?, true, now() - make_interval(days => ?))
                """, enrolment, contentId, daysAgo);
    }

    private void nudgeSentDaysAgo(UUID user, int daysAgo) {
        notification(user, "INACTIVITY_NUDGE", daysAgo);
    }

    private void notification(UUID user, String type, int daysAgo) {
        jdbc.update("""
                INSERT INTO notifications (user_id, notification_type, title, body, url, created_at)
                VALUES (?, ?, 'T', 'B', '/app', now() - make_interval(days => ?))
                """, user, type, daysAgo);
    }
}
