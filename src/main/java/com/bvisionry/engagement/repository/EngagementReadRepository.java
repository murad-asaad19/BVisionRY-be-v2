package com.bvisionry.engagement.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.programaccess.ProgramAudience;
import com.bvisionry.common.programaccess.TaskCompletion;

/**
 * Cross-feature reads for sessions + participation (spec §4), raw SQL through
 * {@link NamedParameterJdbcTemplate} — same stance as
 * {@code founderprofile.FounderProfileReadRepository}: the ArchUnit ratchet
 * forbids new feature→feature imports, so this class depends on the schema.
 *
 * <p><strong>Tenancy:</strong> every entry point either carries {@code orgId}
 * in its predicate or is keyed on a cohort/member id the CALLER has already
 * proved is in-org (controllers gate first; services re-anchor via
 * {@link #cohort} / {@link #isOrgMember}).
 */
@Repository
public class EngagementReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public EngagementReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------------------------------------------------------------- guards */

    public record CohortRef(UUID id, String name) {}

    /** The cohort — platform-scoped (spec §13); empty means absent → 404. */
    public Optional<CohortRef> cohort(UUID cohortId) {
        return jdbc.query("""
                SELECT id, name FROM cohorts
                WHERE id = :cohortId
                """,
                new MapSqlParameterSource("cohortId", cohortId),
                (rs, i) -> new CohortRef(rs.getObject("id", UUID.class), rs.getString("name")))
                .stream().findFirst();
    }

    /** The cohort's lifecycle status — the write-guard input (V167). */
    public Optional<String> cohortStatus(UUID cohortId) {
        return jdbc.query("""
                SELECT status FROM cohorts
                WHERE id = :cohortId
                """,
                new MapSqlParameterSource("cohortId", cohortId),
                (rs, i) -> rs.getString("status"))
                .stream().findFirst();
    }


    public boolean isCohortMember(UUID cohortId, UUID memberId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM cohort_members
                               WHERE cohort_id = :cohortId AND user_id = :memberId)
                """,
                new MapSqlParameterSource("cohortId", cohortId).addValue("memberId", memberId),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /* ---------------------------------------------------------------- roster */

    public record RosterRow(UUID id, String name, String email) {}

    /** The cohort's members at read time — the expected attendees (spec §4). */
    public List<RosterRow> roster(UUID cohortId) {
        return roster(cohortId, null);
    }

    /**
     * {@link #roster(UUID)}, optionally cut to one org's own members — the org
     * console never sees another org's people (spec §13.7).
     */
    public List<RosterRow> roster(UUID cohortId, UUID orgId) {
        return jdbc.query("""
                SELECT u.id, u.name, u.email
                FROM cohort_members cm
                JOIN users u ON u.id = cm.user_id
                            AND u.status = 'ACTIVE'
                            AND\s""" + com.bvisionry.common.programaccess.Learner.ROLE_PREDICATE.formatted("u") + """

                WHERE cm.cohort_id = :cohortId
                  AND (CAST(:orgId AS uuid) IS NULL OR u.organization_id = CAST(:orgId AS uuid))
                ORDER BY u.name, u.email
                """,
                new MapSqlParameterSource("cohortId", cohortId).addValue("orgId", orgId),
                (rs, i) -> new RosterRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email")));
    }

    /** Spec §13.7 tenant guard: is the platform cohort assigned to this org? */
    public boolean assignedToOrg(UUID cohortId, UUID orgId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cohort_orgs WHERE cohort_id = :cohortId AND org_id = :orgId)",
                new MapSqlParameterSource("cohortId", cohortId).addValue("orgId", orgId),
                Boolean.class));
    }

    public record MarkerName(UUID markedBy, String name) {}

    /** Display names for the admins who marked attendance (§7b attribution). */
    public List<MarkerName> markerNames(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT id, name FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                (rs, i) -> new MarkerName(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    /* --------------------------------------------------------- participation */

    public record Counts(int total, int done) {}

    /**
     * Assignments denominator/numerator for one founder × cohort: LIVE
     * program tasks of THIS cohort whose module audience includes the member
     * — done-state per TASK TYPE from the owning slice (typed task spine) —
     * plus direct exercise assignments targeting them. An assignment TAGGED to
     * a LIVE cohort task (V173) is excluded: it IS that task's state, already
     * counted in the program half, not extra direct work. Direct assignments
     * are org-level, so they still count in each cohort's assignments row for a
     * multi-cohort member.
     *
     * <p>Done-semantics source of truth: {@code programflow.web.ProgramRules}
     * via the shared {@link TaskCompletion#DONE_FOR_USER} fragment (the same
     * rule the coach console, the ROI report and the due-reminder job count
     * against). Exercises count when SUBMITTED or REVIEWED. A
     * CHANGES_REQUESTED copy (back with the member) and a NOT_SUBMITTED
     * record (closed missing work, V208) never count — either would inflate
     * the participation score.
     */
    public Counts assignmentCounts(UUID cohortId, UUID memberId) {
        Counts program = jdbc.queryForObject("""
                SELECT count(*) AS total, count(*) FILTER (WHERE %s) AS done
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id AND m.cohort_id = :cohortId
                WHERE t.status = 'LIVE'
                  AND %s
                  AND %s
                """.formatted(TaskCompletion.DONE_FOR_USER.formatted(":memberId"),
                        ProgramAudience.INCLUDES_USER.formatted(":memberId"),
                        TaskCompletion.COUNTS_FOR_USER.formatted(":memberId")),
                new MapSqlParameterSource("cohortId", cohortId).addValue("memberId", memberId),
                (rs, i) -> new Counts(rs.getInt("total"), rs.getInt("done")));
        Counts exercises = jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE es.status IN ('SUBMITTED', 'REVIEWED')) AS done
                FROM exercise_assignments ea
                LEFT JOIN exercise_submissions es ON es.assignment_id = ea.id
                                                 AND es.user_id = :memberId
                WHERE ea.user_id = :memberId
                  AND ea.organization_id = (SELECT organization_id FROM users
                                            WHERE id = :memberId)
                  AND NOT EXISTS (
                      SELECT 1 FROM program_tasks t
                      JOIN program_modules m ON m.id = t.module_id
                      JOIN cohort_members cm ON cm.cohort_id = m.cohort_id
                                            AND cm.user_id = :memberId
                      WHERE t.id = ea.program_task_id AND t.status = 'LIVE')
                """,
                new MapSqlParameterSource("memberId", memberId),
                (rs, i) -> new Counts(rs.getInt("total"), rs.getInt("done")));
        return new Counts(program.total() + exercises.total(), program.done() + exercises.done());
    }

    public record SessionTypeCounts(String type, int held, int attended) {}

    /**
     * The member is an expected attendee of session {@code s}: no narrowing
     * rows = the whole cohort is expected, otherwise they must be listed.
     * A 1:1 naming one founder must not sit in anyone else's denominator.
     */
    private static final String MEMBER_IS_EXPECTED = """
            (NOT EXISTS (SELECT 1 FROM session_expected_attendees sea
                         WHERE sea.session_id = s.id)
             OR EXISTS (SELECT 1 FROM session_expected_attendees sea
                        WHERE sea.session_id = s.id AND sea.member_id = :memberId))""";

    /**
     * Held sessions (session_date in the past) per type in the cohort where
     * this member was expected, with how many they attended. Future sessions
     * are excluded from the denominator by design.
     */
    public List<SessionTypeCounts> sessionCounts(UUID cohortId, UUID memberId) {
        return jdbc.query("""
                SELECT s.type, count(*) AS held, count(sa.member_id) AS attended
                FROM sessions s
                LEFT JOIN session_attendance sa ON sa.session_id = s.id
                                               AND sa.member_id = :memberId
                WHERE s.cohort_id = :cohortId AND s.session_date <= now()
                  AND %s
                GROUP BY s.type
                """.formatted(MEMBER_IS_EXPECTED),
                new MapSqlParameterSource("cohortId", cohortId).addValue("memberId", memberId),
                (rs, i) -> new SessionTypeCounts(rs.getString("type"), rs.getInt("held"),
                        rs.getInt("attended")));
    }

    public record HistoryRow(UUID sessionId, String type, String title, Instant sessionDate,
                             Instant markedAt) {}

    /** Held sessions the member was expected at, newest first, with their §7b stamp. */
    public List<HistoryRow> sessionHistory(UUID cohortId, UUID memberId) {
        return jdbc.query("""
                SELECT s.id, s.type, s.title, s.session_date, sa.marked_at
                FROM sessions s
                LEFT JOIN session_attendance sa ON sa.session_id = s.id
                                               AND sa.member_id = :memberId
                WHERE s.cohort_id = :cohortId AND s.session_date <= now()
                  AND %s
                ORDER BY s.session_date DESC
                """.formatted(MEMBER_IS_EXPECTED),
                new MapSqlParameterSource("cohortId", cohortId).addValue("memberId", memberId),
                (rs, i) -> new HistoryRow(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("title"), instant(rs, "session_date"),
                        instant(rs, "marked_at")));
    }

    /** One scheduled session as its own attendee sees it. */
    public record MySessionRow(UUID id, UUID cohortId, String cohortName, String type,
            String title, Instant sessionDate, boolean attended) {}

    /**
     * Every session across the caller's cohorts that the caller is expected at
     * — the member-facing schedule ("Bvisionry Labs").
     *
     * <p>Deliberately narrower than {@link #sessionHistory}: it carries no
     * roster, no other member's attendance and no marker names. Spec §4 keeps
     * the Engagement Record off the member's report; "when are we meeting, and
     * did I make it" is the schedule, not the score, and one founder's own
     * presence is already theirs to see.
     *
     * <p>DRAFT cohorts are invisible to members everywhere else in the product,
     * so their sessions are excluded here rather than leaking a cohort the
     * member has not been launched into.
     */
    public List<MySessionRow> mySessions(UUID memberId) {
        return jdbc.query("""
                SELECT s.id, s.cohort_id, c.name AS cohort_name, s.type, s.title,
                       s.session_date, (sa.member_id IS NOT NULL) AS attended
                FROM sessions s
                JOIN cohorts c ON c.id = s.cohort_id
                JOIN cohort_members cm ON cm.cohort_id = s.cohort_id
                                      AND cm.user_id = :memberId
                LEFT JOIN session_attendance sa ON sa.session_id = s.id
                                               AND sa.member_id = :memberId
                WHERE c.status <> 'DRAFT'
                  AND %s
                ORDER BY s.session_date DESC
                """.formatted(MEMBER_IS_EXPECTED),
                new MapSqlParameterSource("memberId", memberId),
                (rs, i) -> new MySessionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("cohort_id", UUID.class),
                        rs.getString("cohort_name"),
                        rs.getString("type"),
                        rs.getString("title"),
                        instant(rs, "session_date"),
                        rs.getBoolean("attended")));
    }

    /** The member's cohorts within the org — the engagement record's grain. */
    public List<CohortRef> memberCohorts(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT c.id, c.name FROM cohorts c
                JOIN cohort_members cm ON cm.cohort_id = c.id
                WHERE cm.user_id = :memberId AND EXISTS (SELECT 1 FROM cohort_orgs cox WHERE cox.cohort_id = c.id AND cox.org_id = :orgId)
                ORDER BY c.position, c.name
                """,
                params(orgId, memberId),
                (rs, i) -> new CohortRef(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    /**
     * The cohort's taggable distance pillar ids — fully-mapped pairs only,
     * exactly {@code TaskSpineRepository.mappedDistancePillarIds}'s rule (a
     * one-sided row is not a pair to tag against, and unmap must kill the tag).
     * Raw SQL for this class's usual reason: the mapping belongs to the
     * comparison slice and the ArchUnit ratchet forbids the import.
     */
    public List<UUID> mappedDistancePillarIds(UUID cohortId) {
        return jdbc.query("""
                SELECT distance_pillar_id FROM comparison_pillar_mappings
                WHERE cohort_id = :cohortId
                  AND distance_pillar_id IS NOT NULL
                  AND baseline_pillar_id IS NOT NULL
                """,
                new MapSqlParameterSource("cohortId", cohortId),
                (rs, i) -> rs.getObject("distance_pillar_id", UUID.class));
    }

    /* -------------------------------------------------------- platform config */

    /** The raw config document — parsed (with defaults) by the caller. */
    public Optional<String> settingJson(String key) {
        return jdbc.query("SELECT value_text FROM platform_settings WHERE key = :key",
                new MapSqlParameterSource("key", key),
                (rs, i) -> rs.getString("value_text"))
                .stream().findFirst().filter(s -> s != null && !s.isBlank());
    }

    /* ---------------------------------------------------------------- helpers */

    private static MapSqlParameterSource params(UUID orgId, UUID memberId) {
        return new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
