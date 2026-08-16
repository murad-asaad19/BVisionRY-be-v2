package com.bvisionry.pipeline.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * The pipeline slice's only WRITE into the enrollment slice: put a founder on a
 * course, and never anything else.
 *
 * <p><strong>Why raw SQL rather than {@code EnrollmentService}.</strong> The
 * ArchUnit ratchet ({@code noCrossFeatureDependencies}) forbids new
 * feature -> feature edges and the frozen-violations store is never written, so —
 * following {@code CourseCatalogReadRepository} in this same package and
 * {@code insights.BenchmarkReadRepository} — this depends on the SCHEMA and imports
 * no enrollment type. {@code EnrollmentService#enroll} could not be reused anyway:
 * it reads {@code SecurityUtils.getCurrentUserId()}, and auto-enrolment runs on the
 * evaluation's async thread where there is no principal at all.
 *
 * <p><strong>READ THIS BEFORE ADDING A SECOND METHOD.</strong> An INSERT that
 * cannot overwrite is the entire reason this is safe to call for someone else. The
 * policy is {@code auto_enrolment_on_reassessment: NEW_ENROLMENTS_ONLY} — never
 * duplicate, never unenrol — so nothing here may UPDATE or DELETE an
 * {@code enrollment} row. An update would reach {@code status} and
 * {@code progress_pct} and could silently reset a founder's completed course.
 */
@Repository
public class FounderEnrolmentWriteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FounderEnrolmentWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Enrols the founder unless they already are, leaving any existing enrolment
     * (and its progress) untouched.
     *
     * <p>The no-duplicate guarantee is the {@code uq_enrollment_user_course} index,
     * not a preceding SELECT: two evaluations finishing at once would both pass a
     * read-then-write check and one would get a constraint error. {@code ON CONFLICT
     * DO NOTHING} makes the loser a no-op instead. Same race
     * {@code EnrollmentService#createEnrollment} handles, resolved in the statement
     * rather than in a catch block.
     *
     * <p>Every other column is left to its schema default (ACTIVE, 0%, now(), NOT
     * required, no deadline), so the player, certificates and progress cannot tell
     * how someone arrived — which they must not. The one column that IS set is
     * {@code source} (V168, spec §3): a founder is entitled to know a course is on
     * their shelf because an assessment put it there. DO NOTHING on conflict keeps
     * that honest in the other direction too — a course they chose themselves, or
     * an admin assigned by name, keeps ITS source rather than being relabelled.
     *
     * @return {@code true} when a NEW row was written, {@code false} when the
     *         founder was already enrolled. The caller records which.
     */
    public boolean enrolIfAbsent(UUID userId, UUID courseId) {
        return jdbc.update("""
                INSERT INTO enrollment (user_id, course_id, source)
                VALUES (:userId, :courseId, 'AI_SUGGESTED')
                ON CONFLICT ON CONSTRAINT uq_enrollment_user_course DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("courseId", courseId)) > 0;
    }
}
