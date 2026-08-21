package com.bvisionry.common.progress;

/**
 * The single SQL source of truth for "how far through this course is this
 * enrolment?".
 *
 * <p><strong>Why this exists.</strong> {@code enrollment.progress_pct} is a
 * cached number: {@code EnrollmentService#recomputeProgress} writes it when a
 * learner marks a lesson complete, and nothing else ever touches it. Its
 * denominator — the course's lesson set — belongs to a different slice and
 * changes without asking: an author adds, deletes or replaces lessons, and
 * {@code content_progress.content_id} cascade-deletes with them. Every surface
 * that read the column therefore showed a percentage that was true on the day of
 * the learner's last click and false ever since (a founder's journey and player
 * header both read "60% complete" over a sidebar with nothing ticked). Six read
 * sites across five slices had the same stale number, so the fix belongs in the
 * SQL they share, not in one of them.
 *
 * <p>Like {@link com.bvisionry.common.coursevisibility.CourseVisibilityAccess}
 * and {@link com.bvisionry.common.programaccess.TaskCompletion} this lives in
 * {@code common} because the ArchUnit ratchet forbids feature -> feature imports:
 * it depends on the SCHEMA only and imports no catalog or enrollment type.
 */
public final class CourseProgressSql {

    /**
     * Integer SQL expression: the completion percent of the enrolment row the
     * composing query exposes as {@code e} (same convention as
     * {@code VISIBLE_TO_ORG}'s {@code c}), counted against the course's CURRENT
     * lessons. Inner aliases are prefixed {@code pg*} so they never shadow a
     * composing query's own.
     *
     * <p>Two arms are deliberate and neither is a fallback to "whatever was
     * cached" out of timidity:
     *
     * <ul>
     *   <li><strong>COMPLETED pins to 100.</strong> The status and the
     *       certificate are the durable record of finishing; publishing a tenth
     *       lesson into a nine-lesson course must not walk a certificate holder
     *       back to 90%.</li>
     *   <li><strong>A course with no lessons keeps the stored number.</strong>
     *       There is no denominator, so the percent is undefined rather than
     *       zero — exactly the case {@code recomputeProgress} already declines to
     *       write ({@code if (total == 0) return;}). Reporting 0% for a
     *       lesson-less course would be a new lie replacing the old one.</li>
     * </ul>
     */
    public static final String LIVE_PROGRESS_PCT = """
            CASE WHEN e.status = 'COMPLETED' THEN 100 ELSE COALESCE((
                     SELECT ROUND(100.0 * COUNT(pgp.id) / NULLIF(COUNT(pgc.id), 0))::int
                       FROM content pgc
                       JOIN section pgs ON pgs.id = pgc.section_id
                       LEFT JOIN content_progress pgp
                              ON pgp.content_id = pgc.id
                             AND pgp.enrollment_id = e.id
                             AND pgp.completed
                      WHERE pgs.course_id = e.course_id),
                   e.progress_pct) END""";

    private CourseProgressSql() {
    }
}
