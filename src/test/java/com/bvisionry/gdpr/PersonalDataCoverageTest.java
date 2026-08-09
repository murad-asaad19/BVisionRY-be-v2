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
            "coach_profiles.coach_id", "cohort_members.user_id",
            "course.instructor_id", "email_templates.updated_by", "enrollment.user_id",
            // enrolment_overrides (V157): user_id CASCADEs with the user row — "an admin
            // took this founder off this course" is about that founder and nobody else,
            // so it dies with them. Exported as course_removals for auto_enrolments'
            // reason: it is an automated-journey decision about them that the enrolment
            // row does not record. removed_by is SET NULL — an admin attribution on the
            // ORG's record, like assignments.assigned_by; erasing the admin must not
            // erase the fact that the removal happened.
            "enrolment_overrides.removed_by", "enrolment_overrides.user_id",
            "exercise_assignments.user_id", "exercise_comments.author_id",
            // founder_comparisons (V161): user_id CASCADEs with the user row —
            // the comparison is scores about that founder and nobody else, so it
            // dies with them (pillar rows cascade off the comparison). Exported
            // as distance_comparisons + distance_comparison_pillars.
            "founder_comparisons.user_id",
            "exercise_submissions.user_id", "insight_report_member_ids.member_id",
            "invitations.invited_by", "join_links.created_by", "notification_optouts.user_id",
            "notifications.user_id", "overall_summary_history.archived_by_admin_id",
            "password_reset_tokens.user_id", "pillar_evaluation_history.archived_by_admin_id",
            "pipeline_auto_assignments.created_by", "pipeline_auto_assignments.updated_by",
            "pipelines.created_by", "platform_settings.updated_by", "program_module_members.user_id",
            "program_submissions.user_id", "public_assessment_links.created_by",
            "push_subscriptions.user_id", "quiz_attempt.user_id", "refresh_tokens.user_id",
            "submission_pillar_unlocks.unlocked_by_admin_id", "submissions.user_id",
            "survey_responses.respondent_user_id", "surveys.created_by", "team_members.user_id",
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
     *   <li>{@code *_email_mode} — enum settings ("ASK"/"HIDE"), not addresses.</li>
     *   <li>{@code sso_registrations.email_domain} — a DNS domain a CUSTOMER
     *       organization was verified to own ("orgb.com"), never a person's
     *       address. It identifies no data subject, and a user erasure must not
     *       touch it: clearing it would break single sign-on for every remaining
     *       member of that tenant. Deliberately out of reach of both export and
     *       erasure; it goes when the organization goes (FK ON DELETE CASCADE).</li>
     * </ul>
     */
    private static final Set<String> KNOWN_EMAIL_COLUMNS = Set.of(
            "invitations.email", "lead_magnet_requests.email", "leads.email",
            "public_assessment_links.respondent_email_mode", "sso_registrations.email_domain",
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
