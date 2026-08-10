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

    /** The cohort, org-scoped — empty means "not your cohort" → 404. */
    public Optional<CohortRef> cohort(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT id, name FROM cohorts
                WHERE id = :cohortId AND org_id = :orgId
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("cohortId", cohortId),
                (rs, i) -> new CohortRef(rs.getObject("id", UUID.class), rs.getString("name")))
                .stream().findFirst();
    }

    /** True when the id is an org-scoped MEMBER — the engagement read's 404 anchor. */
    public boolean isOrgMember(UUID orgId, UUID memberId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users
                               WHERE id = :memberId AND organization_id = :orgId
                                 AND role = 'MEMBER')
                """,
                params(orgId, memberId), Boolean.class);
        return Boolean.TRUE.equals(exists);
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
        return jdbc.query("""
                SELECT u.id, u.name, u.email
                FROM cohort_members cm
                JOIN users u ON u.id = cm.user_id AND u.role = 'MEMBER'
                WHERE cm.cohort_id = :cohortId
                ORDER BY u.name, u.email
                """,
                new MapSqlParameterSource("cohortId", cohortId),
                (rs, i) -> new RosterRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email")));
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
     * plus direct exercise assignments targeting them. A direct exercise
     * assignment whose template a cohort EXERCISE task already covers is
     * excluded (it IS the cohort task's state, not extra direct work).
     * Direct assignments are org-level, so they still count in each cohort's
     * assignments row for a multi-cohort member.
     *
     * <p>Done-semantics source of truth: {@code programflow.web.ProgramRules}
     * via the shared {@link TaskCompletion#DONE_FOR_USER} fragment (the same
     * rule the coach console, the ROI report and the due-reminder job count
     * against). Exercises count when SUBMITTED or REVIEWED — a
     * CHANGES_REQUESTED copy is back with the member and does NOT count,
     * matching the journey.
     */
    public Counts assignmentCounts(UUID orgId, UUID cohortId, UUID memberId) {
        Counts program = jdbc.queryForObject("""
                SELECT count(*) AS total, count(*) FILTER (WHERE %s) AS done
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id AND m.cohort_id = :cohortId
                WHERE t.status = 'LIVE'
                  AND %s
                """.formatted(TaskCompletion.DONE_FOR_USER.formatted(":memberId"),
                        ProgramAudience.INCLUDES_USER.formatted(":memberId")),
                new MapSqlParameterSource("cohortId", cohortId).addValue("memberId", memberId),
                (rs, i) -> new Counts(rs.getInt("total"), rs.getInt("done")));
        Counts exercises = jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE es.status IN ('SUBMITTED', 'REVIEWED')) AS done
                FROM exercise_assignments ea
                LEFT JOIN exercise_submissions es ON es.assignment_id = ea.id
                                                 AND es.user_id = :memberId
                WHERE ea.organization_id = :orgId AND ea.user_id = :memberId
                  AND NOT EXISTS (
                      SELECT 1 FROM cohort_members cm
                      JOIN program_modules m ON m.cohort_id = cm.cohort_id
                      JOIN program_tasks t ON t.module_id = m.id
                      WHERE cm.user_id = :memberId AND t.status = 'LIVE'
                        AND t.task_type = 'EXERCISE' AND t.ref_id = ea.template_id
                        AND %s)
                """.formatted(ProgramAudience.INCLUDES_USER.formatted(":memberId")),
                params(orgId, memberId),
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

    /** The member's cohorts within the org — the engagement record's grain. */
    public List<CohortRef> memberCohorts(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT c.id, c.name FROM cohorts c
                JOIN cohort_members cm ON cm.cohort_id = c.id
                WHERE cm.user_id = :memberId AND c.org_id = :orgId
                ORDER BY c.position, c.name
                """,
                params(orgId, memberId),
                (rs, i) -> new CohortRef(rs.getObject("id", UUID.class), rs.getString("name")));
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
