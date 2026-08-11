package com.bvisionry.organization;

import com.bvisionry.common.progress.CourseProgressSql;
import com.bvisionry.organization.dto.MemberCourseResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The org admin's view of one member's courses, and the write that takes them off
 * one ({@code enrolment_override}, roadmap §7 item 10).
 *
 * <p><strong>Every statement here is keyed on a user id the CALLER already
 * proved is theirs.</strong> Nothing in this class checks tenancy and nothing in
 * it may be called without {@code MemberService#findMemberInOrg} having run
 * first — that guard is what turns an arbitrary {@code memberId} in a URL into a
 * member of the caller's own org, and it is the only thing standing between a
 * removal endpoint and a cross-tenant write. It lives in the service for the
 * same reason the {@code require*} convention exists: one guarded entry point
 * beats a predicate repeated in four statements.
 *
 * <p><strong>Why raw SQL.</strong> This reads {@code enrollment}, {@code course},
 * {@code auto_enrolments} and {@code pillars} — four other slices' tables. The
 * ArchUnit ratchet ({@code noCrossFeatureDependencies}) forbids new
 * feature -&gt; feature edges and the frozen-violations store is never written, so
 * this depends on the SCHEMA and imports no type from any of them. Same pattern as
 * {@code insights.BenchmarkReadRepository}, {@code common.gdpr.PersonalDataRepository}
 * and the enrolment engine's own {@code pipeline.FounderEnrolmentWriteRepository}.
 */
@Repository
public class MemberCourseRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public MemberCourseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every course this member holds, newest enrolment first, each with the reason
     * it is there.
     *
     * <p>The correlated sub-select is the "why": the newest {@code ENROLLED} ledger
     * row for this (founder, course) names the pillar whose weak band asked for it,
     * and {@code NULL} means they enrolled themselves. It mirrors
     * {@code FounderRecommendationService}'s de-duplication — newest decision wins,
     * id as the tie-break, because one evaluation writes its rows in a single loop
     * and {@code created_at} can repeat inside it.
     *
     * <p>Removed rows are kept and flagged rather than filtered; see
     * {@link MemberCourseResponse}.
     *
     * <p>ponytail: one query with a correlated sub-select, no join-and-group. A
     * founder has a handful of courses; revisit if a cohort view ever needs this
     * for a whole roster at once.
     */
    public List<MemberCourseResponse> findCoursesFor(UUID userId) {
        List<MemberCourseResponse> rows = new ArrayList<>();
        jdbc.query("""
                SELECT c.id, c.title, c.slug,
                       e.status, %1$s AS progress_pct, e.enrolled_at, e.completed_at,
                       (SELECT p.name
                          FROM auto_enrolments a
                          JOIN pillars p ON p.id = a.pillar_id
                         WHERE a.user_id = e.user_id AND a.course_id = e.course_id
                           AND a.outcome = 'ENROLLED'
                         ORDER BY a.created_at DESC, a.id ASC
                         LIMIT 1) AS pillar_name,
                       o.created_at AS removed_at,
                       o.reason     AS removed_reason
                  FROM enrollment e
                  JOIN course c ON c.id = e.course_id
                  LEFT JOIN enrolment_overrides o
                         ON o.user_id = e.user_id AND o.course_id = e.course_id
                 WHERE e.user_id = :userId
                 ORDER BY e.enrolled_at DESC, c.title ASC
                """.formatted(CourseProgressSql.LIVE_PROGRESS_PCT),
                new MapSqlParameterSource("userId", userId),
                rs -> {
                    String status = rs.getString("status");
                    rows.add(new MemberCourseResponse(
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            rs.getString("slug"),
                            status,
                            rs.getInt("progress_pct"),
                            rs.getObject("enrolled_at", OffsetDateTime.class),
                            rs.getObject("completed_at", OffsetDateTime.class),
                            rs.getString("pillar_name"),
                            "CANCELLED".equals(status),
                            rs.getObject("removed_at", OffsetDateTime.class),
                            rs.getString("removed_reason")));
                });
        return rows;
    }

    /**
     * Take the member off the course, durably.
     *
     * <p><strong>Two statements, and the order is load-bearing.</strong> The
     * override is written FIRST. The caller wraps both in one transaction, so this
     * only decides which half survives a failure the transaction cannot cover — and
     * the asymmetry is stark: override-then-cancel can leave "the engine is blocked
     * but the founder still has the course", which an admin sees and can retry;
     * cancel-then-override leaves "the founder lost the course and the next
     * assessment silently gives it back", which is precisely the failure this whole
     * feature exists to prevent.
     *
     * <p><strong>CANCELLED, not DELETE, and this is the judgement call.</strong>
     * {@code content_progress}, {@code quiz_attempts} and {@code certificates} all
     * hang off {@code enrollment.id} with ON DELETE CASCADE, so deleting the row
     * would erase every lesson the founder completed, every quiz they passed and
     * the certificate they earned — permanently, from a click in a dropdown. The
     * status flip touches ONE column: {@code progress_pct}, {@code completed_at}
     * and every child row are left exactly as they were, so a removal made by
     * mistake is undone by putting the status back, and a founder who re-enrols
     * themselves resumes mid-course rather than starting over
     * ({@code EnrollmentService#reactivateIfRemoved}).
     *
     * <p>Both statements are idempotent: removing an already-removed member updates
     * no row and inserts no second override.
     *
     * @return {@code true} if a live enrolment was actually cancelled.
     */
    public boolean removeFromCourse(UUID userId, UUID courseId, UUID actorId, String reason) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("courseId", courseId);

        jdbc.update("""
                INSERT INTO enrolment_overrides (user_id, course_id, removed_by, reason)
                VALUES (:userId, :courseId, :actorId, :reason)
                ON CONFLICT ON CONSTRAINT uq_enrolment_overrides DO NOTHING
                """,
                new MapSqlParameterSource(params.getValues())
                        .addValue("actorId", actorId)
                        .addValue("reason", reason));

        return jdbc.update("""
                UPDATE enrollment SET status = 'CANCELLED'
                 WHERE user_id = :userId AND course_id = :courseId AND status <> 'CANCELLED'
                """, params) > 0;
    }

    /** Whether the member holds this course at all — the 404 for a bad course id. */
    public boolean hasEnrolment(UUID userId, UUID courseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM enrollment WHERE user_id = :userId AND course_id = :courseId)",
                new MapSqlParameterSource("userId", userId).addValue("courseId", courseId),
                Boolean.class));
    }
}
