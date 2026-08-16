package com.bvisionry.courseaccess.repository;

import com.bvisionry.common.enums.EnrollmentSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every WRITE the course-wiring surfaces make into another slice's tables:
 * {@code enrollment} and {@code enrolment_overrides}.
 *
 * <p>Raw SQL for the ArchUnit reason (see {@code CourseAccessReadRepository}),
 * and — like {@code pipeline.FounderEnrolmentWriteRepository} — deliberately
 * narrow: nothing here may touch {@code progress_pct}, {@code completed_at} or
 * {@code content_progress}. An assignment changes WHY someone has a course and
 * never how far through it they are, so no admin action can reset work.
 * Removal is a status flip to CANCELLED, never a DELETE, for the same reason.
 */
@Repository
public class CourseAssignmentWriteRepository {

    /** {@code ARRAY['DIRECT','ORG_RULE',...]} in precedence order, from the enum. */
    private static final String SOURCE_RANKING = Arrays.stream(EnrollmentSource.values())
            .map(s -> "'" + s.name() + "'")
            .collect(Collectors.joining(",", "ARRAY[", "]"));

    /**
     * The un-cancel derivation shared with {@code EnrollmentService.reactivateIfRemoved}
     * and {@code TaskSpineRepository.ensureEnrollment}: a member who had FINISHED
     * comes back COMPLETED, not ACTIVE.
     */
    private static final String REACTIVATE = """
            CASE WHEN enrollment.status = 'CANCELLED'
                 THEN CASE WHEN enrollment.completed_at IS NOT NULL THEN 'COMPLETED' ELSE 'ACTIVE' END
                 ELSE enrollment.status END""";

    private final NamedParameterJdbcTemplate jdbc;

    public CourseAssignmentWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Put the member on the course, or strengthen the enrollment they already
     * have. Idempotent and safe to call for someone else.
     *
     * <p>On conflict the row is UPGRADED, never downgraded:
     * <ul>
     *   <li>{@code source} moves only towards a STRONGER claim — accepting an AI
     *       suggestion for a course an admin already assigned by name must not
     *       erase "your admin assigned this";</li>
     *   <li>{@code required} is OR'd — an optional path cannot cancel a required one;</li>
     *   <li>{@code deadline} takes the earliest ({@code LEAST} ignores NULLs in
     *       PostgreSQL, so "no deadline" never wins over a real one);</li>
     *   <li>a CANCELLED row is reactivated with its progress intact.</li>
     * </ul>
     *
     * @return true when a NEW row was written.
     */
    public boolean upsert(UUID userId, UUID courseId, EnrollmentSource source,
                          boolean required, Instant deadline, UUID assignedBy) {
        return jdbc.update("""
                INSERT INTO enrollment (user_id, course_id, status, source, required, deadline, assigned_by)
                VALUES (:userId, :courseId, 'ACTIVE', :source, :required, :deadline, :assignedBy)
                ON CONFLICT ON CONSTRAINT uq_enrollment_user_course DO UPDATE
                   SET source = CASE WHEN array_position(%1$s, EXCLUDED.source)
                                          < array_position(%1$s, enrollment.source)
                                     THEN EXCLUDED.source ELSE enrollment.source END,
                       required = enrollment.required OR EXCLUDED.required,
                       deadline = LEAST(enrollment.deadline, EXCLUDED.deadline),
                       assigned_by = COALESCE(EXCLUDED.assigned_by, enrollment.assigned_by),
                       status = %2$s
                """.formatted(SOURCE_RANKING, REACTIVATE),
                params(userId, courseId)
                        .addValue("source", source.name())
                        .addValue("required", required)
                        .addValue("deadline", timestamp(deadline))
                        .addValue("assignedBy", assignedBy)) > 0;
    }

    /** Flip required/optional and re-date (spec §11: both mutable post-assignment). */
    public int setRequiredAndDeadline(UUID orgId, UUID courseId, EnrollmentSource source,
                                      boolean required, Instant deadline) {
        return jdbc.update("""
                UPDATE enrollment SET required = :required, deadline = :deadline
                 WHERE course_id = :courseId AND source = :source AND status <> 'CANCELLED'
                   AND user_id IN (SELECT id FROM users WHERE organization_id = :orgId)
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("courseId", courseId)
                        .addValue("source", source.name()).addValue("required", required)
                        .addValue("deadline", timestamp(deadline)));
    }

    /** "Remove for everyone" on a non-rule row: cancel every matching enrollment in the org. */
    public int cancelForOrg(UUID orgId, UUID courseId, EnrollmentSource source) {
        return jdbc.update("""
                UPDATE enrollment SET status = 'CANCELLED'
                 WHERE course_id = :courseId AND source = :source AND status <> 'CANCELLED'
                   AND user_id IN (SELECT id FROM users WHERE organization_id = :orgId)
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("courseId", courseId)
                        .addValue("source", source.name()));
    }

    /** One member off one course. */
    public boolean cancel(UUID userId, UUID courseId) {
        return jdbc.update("""
                UPDATE enrollment SET status = 'CANCELLED'
                 WHERE user_id = :userId AND course_id = :courseId AND status <> 'CANCELLED'
                """, params(userId, courseId)) > 0;
    }

    /**
     * The exclusion row of spec §3 — member-level beats org-level.
     *
     * <p>Reuses V157's {@code enrolment_overrides} rather than inventing a
     * second opt-out table, which buys two things for free: the AI engine
     * already consults it (so one exclusion silences the rule AND any future
     * suggestion for that course), and it already carries who / when / why.
     */
    public void exclude(UUID userId, UUID courseId, UUID actorId, String reason) {
        jdbc.update("""
                INSERT INTO enrolment_overrides (user_id, course_id, removed_by, reason, scope)
                VALUES (:userId, :courseId, :actorId, :reason, 'MEMBER')
                ON CONFLICT ON CONSTRAINT uq_enrolment_overrides DO NOTHING
                """,
                params(userId, courseId).addValue("actorId", actorId).addValue("reason", reason));
    }

    /**
     * The org-wide counterpart of {@link #exclude}: one removed-by-admin override
     * row per member the matching cancel is about to hit, so "remove for
     * everyone" actually HOLDS — a CANCELLED row alone is one click from undone,
     * because the self-enrol path reactivates any CANCELLED row that has no
     * override ({@code EnrollmentService#reactivateIfRemoved}).
     *
     * <p><strong>Must run BEFORE {@link #cancelForOrg}</strong> — the predicate
     * is "live enrollment of this source", which the status flip destroys. No
     * {@code reason}: that column is the admin's own words, and an org-wide
     * removal carries none.
     *
     * <p>{@code scope = 'ORG'} (V184): these blanket rows are undone by the next
     * org-wide assign ({@link #clearOrgScopeExclusions}); DO NOTHING on conflict
     * so an existing by-name MEMBER row is never downgraded to ORG.
     */
    public int excludeAllEnrolled(UUID orgId, UUID courseId, EnrollmentSource source, UUID actorId) {
        return jdbc.update("""
                INSERT INTO enrolment_overrides (user_id, course_id, removed_by, scope)
                SELECT e.user_id, e.course_id, :actorId, 'ORG'
                  FROM enrollment e
                 WHERE e.course_id = :courseId AND e.source = :source AND e.status <> 'CANCELLED'
                   AND e.user_id IN (SELECT id FROM users WHERE organization_id = :orgId)
                ON CONFLICT ON CONSTRAINT uq_enrolment_overrides DO NOTHING
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("courseId", courseId)
                        .addValue("source", source.name()).addValue("actorId", actorId));
    }

    /**
     * An explicit by-name assignment clears a previous removal — an admin
     * assigning Lina this course today outranks an admin having removed her from
     * it last month, and leaving the row would let the rule re-hide it.
     */
    public void clearExclusion(UUID userId, UUID courseId) {
        jdbc.update("DELETE FROM enrolment_overrides WHERE user_id = :userId AND course_id = :courseId",
                params(userId, courseId));
    }

    /**
     * The org-wide counterpart of {@link #clearExclusion}: an org-wide assign
     * outranks a previous org-wide removal, so the blanket {@code scope = 'ORG'}
     * rows that removal stamped are deleted — while a by-name removal
     * ({@code scope = 'MEMBER'}) still survives, exactly as it survives a rule
     * delete/re-create.
     */
    public int clearOrgScopeExclusions(UUID orgId, UUID courseId) {
        return jdbc.update("""
                DELETE FROM enrolment_overrides
                 WHERE course_id = :courseId AND scope = 'ORG'
                   AND user_id IN (SELECT id FROM users WHERE organization_id = :orgId)
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("courseId", courseId));
    }

    /** §7b: stamp the moment the member took the suggestion, so the ledger keeps the story. */
    public int acceptSuggestion(UUID userId, UUID courseId, Instant at) {
        return jdbc.update("""
                UPDATE auto_enrolments SET accepted_at = :at, updated_at = :at
                 WHERE user_id = :userId AND course_id = :courseId
                   AND outcome = 'SUGGESTED' AND accepted_at IS NULL
                """, params(userId, courseId).addValue("at", timestamp(at)));
    }

    /** Members of the org, for a direct assignment's audience. */
    public List<UUID> membersIn(UUID orgId, Collection<UUID> candidateIds) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id FROM users WHERE organization_id = :orgId AND id IN (:ids)
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("ids", List.copyOf(candidateIds)),
                (rs, i) -> rs.getObject("id", UUID.class));
    }

    private static MapSqlParameterSource params(UUID userId, UUID courseId) {
        return new MapSqlParameterSource("userId", userId).addValue("courseId", courseId);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
