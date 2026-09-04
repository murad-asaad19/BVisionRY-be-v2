package com.bvisionry.gdpr;

import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-evolution tripwire for {@code PersonalDataRepository}.
 *
 * <p>That class maps personal data by hand-written SQL, so it silently goes
 * stale when someone adds a table: new COLUMNS are picked up by {@code SELECT *},
 * new TABLES are not. Three real gaps shipped that way before this test existed
 * — {@code ai_call_logs} (no FK, held verbatim answers), the profiling history
 * tables, and the email-keyed public-flow rows.
 *
 * <p>So: enumerate every column that points at {@code users(id)} and every column
 * that looks like an email address, and compare against the allowlists below. A
 * failure is not a bug in this test — it means a new home for personal data
 * appeared and somebody has to decide what the export and the erasure do with
 * it, then add it here with that decision recorded in the comment.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class PersonalDataCoverageTest extends AbstractPostgresIntegrationTest {

    /**
     * Every FK to {@code users(id)}. Each is covered by a deliberate decision —
     * see the class doc of {@code PersonalDataRepository} for the CASCADE /
     * SET NULL / explicit-statement breakdown.
     */
    private static final Set<String> KNOWN_USER_REFERENCES = Set.of(
            // announcements (V148): author_id / flagged_by SET NULL — a cohort
            // broadcast is the ORGANIZATION's record of what was said to a
            // cohort, so it outlives the author's erasure with the attribution
            // dropped, like assignments.assigned_by and join_links.created_by.
            "announcements.author_id", "announcements.flagged_by",
            // cohort_orgs (V171): assigned_by SET NULL — the org's participation
            // in a platform cohort outlives the assigning admin's erasure with
            // the attribution dropped, like assignments.assigned_by.
            "cohort_orgs.assigned_by",
            "ai_configurations.updated_by", "assignments.assigned_by", "assignments.user_id",
            "audit_logs.actor_id",
            // auto_enrolments (V151): user_id CASCADEs with the user row — the record of
            // why a founder was put on a course is about that founder and nobody else, so
            // it dies with them. Exported as course_recommendations: it is an automated
            // decision about them (Art. 15(1)(h)) and the enrolment row alone does not
            // say which pillar band caused it.
            "auto_enrolments.user_id", "certificate.user_id",
            // coach_assignments (V147): coach_id / member_id CASCADE with the
            // user row and are exported for both sides; assigned_by SET NULL —
            // an admin attribution on the org's record, like assignments.assigned_by.
            "coach_assignments.assigned_by", "coach_assignments.coach_id",
            "coach_assignments.member_id",
            // coach_notes (V162): coach_id / member_id CASCADE with the user row
            // — the note is the coach's judgement ABOUT the member, so it dies
            // with either party. Exported for both sides as coach_notes (for
            // the member it is data about them; for the coach, content they
            // authored).
            "coach_notes.coach_id", "coach_notes.member_id",
            // coach_profiles (V153): coach_id IS the PK and CASCADEs with the
            // user row — the booking link is about that coach and nobody else,
            // so it dies with them. Exported as coach_profile: it is content
            // they authored about themselves.
            "coach_profiles.coach_id",
            // coach_availability_rules / _blocks (V215) and
            // coach_calendar_connections (V216): coach_id / user_id CASCADE with
            // the user row — a coach's own calendar dies with them. Exported as
            // coach_availability_rules / _blocks / coach_calendar_connection
            // (the connection without its refresh token: a credential is not
            // Art. 15 data about the subject).
            "coach_availability_rules.coach_id", "coach_availability_blocks.coach_id",
            "coach_calendar_connections.user_id",
            // sessions (V215/V216): member_id CASCADEs — a 1:1 session row is
            // about that founder and dies with them (exported as
            // coaching_sessions); coach_id is SET NULL, a staff attribution on
            // the cohort's record, like sessions.created_by below.
            "sessions.member_id", "sessions.coach_id",
            "cohort_members.user_id",
            // cohort_launch_ledger / cohort_launch_grants (V167): launched_by /
            // granted_by SET NULL — the launch and the grant are the
            // ORGANIZATION's billing/audit record (spec §8: the ledger is
            // append-only and never refunded), so they outlive the actor's
            // erasure with the attribution dropped, exactly like
            // assignments.assigned_by. Not exported: an operator attribution
            // on the org's record, not data about the data subject.
            "cohort_launch_ledger.launched_by", "cohort_launch_grants.granted_by",
            "course.instructor_id",
            // course.org_visibility_updated_by (V168): SET NULL — "who last set
            // this course's org visibility" is a platform-operator attribution on
            // the CATALOG's record, exactly like platform_settings.updated_by.
            // Not exported: it is about a course, not about the data subject.
            "course.org_visibility_updated_by",
            "email_templates.updated_by",
            // enrollment.assigned_by (V168, spec §3): SET NULL — an admin
            // attribution on the ORG's record ("your admin assigned this"), like
            // assignments.assigned_by. Erasing the admin must not erase the fact
            // that the member was assigned the course. Not exported for the
            // ADMIN; the enrolment itself is already exported for the LEARNER.
            "enrollment.assigned_by",
            "enrollment.user_id",
            // enrolment_overrides (V157): user_id CASCADEs with the user row — "an admin
            // took this founder off this course" is about that founder and nobody else,
            // so it dies with them. Exported as course_removals for auto_enrolments'
            // reason: it is an automated-journey decision about them that the enrolment
            // row does not record. removed_by is SET NULL — an admin attribution on the
            // ORG's record, like assignments.assigned_by; erasing the admin must not
            // erase the fact that the removal happened.
            "enrolment_overrides.removed_by", "enrolment_overrides.user_id",
            // org_course_rules (V168, spec §3): created_by SET NULL — the rule is
            // the ORGANIZATION's curation decision and must survive the admin who
            // made it, with the attribution dropped. There is no user_id at all:
            // that is the whole point of a read-time rule (it names no member),
            // so nothing here is personal data about a learner and nothing is
            // exported.
            "org_course_rules.created_by", "org_course_rules.updated_by",
            "exercise_assignments.user_id", "exercise_comments.author_id",
            // founder_comparisons (V161): user_id CASCADEs with the user row —
            // the comparison is scores about that founder and nobody else, so it
            // dies with them (pillar rows cascade off the comparison). Exported
            // as distance_comparisons + distance_comparison_pillars.
            "founder_comparisons.user_id",
            "exercise_submissions.user_id",
            // exercise_submissions.quality_tagged_by (V170, spec §4): SET NULL —
            // the tag is a REVIEWER's judgement recorded on the ORG's copy of
            // the member's work, like assignments.assigned_by, so erasing the
            // reviewer drops the attribution and the tag survives. The TAG
            // ITSELF is exported to the member (the exercise_submissions section
            // is a SELECT *): the product hides it from their screens, but it is
            // an assessment about them, so an Art. 15 request gets it — a UI
            // choice is not a lawful basis for withholding.
            "exercise_submissions.quality_tagged_by",
            "insight_report_member_ids.member_id",
            "invitations.invited_by", "join_links.created_by", "notification_optouts.user_id",
            "notifications.user_id", "overall_summary_history.archived_by_admin_id",
            "password_reset_tokens.user_id", "pillar_evaluation_history.archived_by_admin_id",
            "pipeline_auto_assignments.created_by", "pipeline_auto_assignments.updated_by",
            "pipelines.created_by", "platform_settings.updated_by", "program_module_members.user_id",
            "program_submissions.user_id", "public_assessment_links.created_by",
            "push_subscriptions.user_id", "quiz_attempt.user_id", "refresh_tokens.user_id",
            "submission_pillar_unlocks.unlocked_by_admin_id", "submissions.user_id",
            // sessions / session_attendance / session_expected_attendees (V163,
            // pre-existing gap closed here): member_id CASCADEs with the user
            // row — a presence tick / expected-attendee row is about that member
            // and nobody else. Attendance is exported as session_attendance
            // (already wired in PersonalDataRepository); the expected-attendee
            // row is scheduling metadata with no content beyond the enrolment
            // itself. sessions.created_by / session_attendance.marked_by are
            // SET NULL — admin attributions on the ORG's record, like
            // assignments.assigned_by.
            "sessions.created_by", "session_attendance.marked_by",
            "session_attendance.member_id", "session_expected_attendees.member_id",
            // shift_narratives (V169, spec §6): user_id CASCADEs with the user
            // row — the narrative is prose about that founder and nobody else,
            // so it dies with them. Exported as shift_narratives (AI-written
            // content ABOUT them, Art. 15). approved_by / edited_by are SET NULL
            // — a reviewer attribution on the ORG's record, like
            // assignments.assigned_by: erasing the reviewer must not erase the
            // fact that the narrative was approved before the founder saw it.
            "shift_narratives.user_id", "shift_narratives.approved_by",
            "shift_narratives.edited_by",
            // member_growth_summaries (V190, spec §3): the same decision as
            // shift_narratives, for the same reason — the summary is AI prose
            // about that founder alone, so user_id CASCADEs and it is exported
            // as growth_summaries; approved_by / edited_by are SET NULL reviewer
            // attributions on the ORG's record.
            "member_growth_summaries.user_id", "member_growth_summaries.approved_by",
            "member_growth_summaries.edited_by",
            "survey_responses.respondent_user_id", "surveys.created_by",
            "upgrade_requests.requested_by", "users.invited_by",
            "workshop_task_submissions.user_id", "workshop_team_members.user_id");

    /**
     * Every column holding an email address, FK or not — the shape that escapes
     * the FK walk entirely.
     *
     * <ul>
     *   <li>{@code users.email} — the account's own address, erased with the row.</li>
     *   <li>{@code invitations.email}, {@code submissions.respondent_email},
     *       {@code survey_responses.respondent_email} — addressed to an email with
     *       no account link. Deliberately out of reach of BOTH the export and the
     *       erasure: {@code users.email} is unverified, so keying on it would let
     *       anyone register an address and read then destroy its data. See the
     *       identity note on {@code PersonalDataRepository}.</li>
     *   <li>{@code leads.email}, {@code lead_magnet_requests.email} — marketing
     *       contacts under a separate purpose; deliberately out of an ACCOUNT
     *       deletion's reach.</li>
     *   <li>{@code public_exercise_responses.respondent_email} — the same shape as
     *       {@code survey_responses.respondent_email}: an anonymous public-link
     *       fill with no {@code user_id}, carrying only a self-declared address.
     *       Out of an ACCOUNT deletion's reach for the reason the identity note
     *       on {@code PersonalDataRepository} gives, not by oversight.</li>
     *   <li>{@code *_email_mode} — enum settings (NONE/OPTIONAL/REQUIRED, or
     *       "ASK"/"HIDE"), not addresses.</li>
     *   <li>{@code sso_registrations.email_domain} — a DNS domain a CUSTOMER
     *       organization was verified to own ("orgb.com"), never a person's
     *       address. It identifies no data subject, and a user erasure must not
     *       touch it: clearing it would break single sign-on for every remaining
     *       member of that tenant. Deliberately out of reach of both export and
     *       erasure; it goes when the organization goes (FK ON DELETE CASCADE).</li>
     * </ul>
     */
    private static final Set<String> KNOWN_EMAIL_COLUMNS = Set.of(
            // coach_calendar_connections.account_email (V216): the Google
            // account the coach connected. Reached by erasure through the
            // user_id CASCADE, so no explicit statement is needed.
            "coach_calendar_connections.account_email",
            "exercise_templates.respondent_email_mode", "invitations.email",
            "lead_magnet_requests.email", "leads.email",
            "public_assessment_links.respondent_email_mode",
            "public_exercise_responses.respondent_email", "sso_registrations.email_domain",
            "submissions.respondent_email", "survey_responses.respondent_email",
            "surveys.respondent_email_mode", "users.email");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void everyForeignKeyIntoUsersHasAnErasureDecision() {
        List<String> actual = jdbc.queryForList("""
                SELECT c.conrelid::regclass::text || '.' ||
                       (SELECT string_agg(a.attname, ',')
                          FROM unnest(c.conkey) k
                          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k)
                  FROM pg_constraint c
                 WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass
                """, String.class);

        assertThat(new TreeSet<>(actual))
                .as("a new FK to users(id) appeared: decide what export and erasure do with it, "
                        + "wire it into PersonalDataRepository, then add it to KNOWN_USER_REFERENCES")
                .isEqualTo(new TreeSet<>(KNOWN_USER_REFERENCES));
    }

    @Test
    void everyEmailBearingColumnHasAnErasureDecision() {
        List<String> actual = jdbc.queryForList("""
                SELECT table_name || '.' || column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name ILIKE '%email%'
                """, String.class);

        assertThat(new TreeSet<>(actual))
                .as("a new email-bearing column appeared. It has no FK to lean on, so erasure "
                        + "will not reach it unless PersonalDataRepository matches it explicitly")
                .isEqualTo(new TreeSet<>(KNOWN_EMAIL_COLUMNS));
    }
}
