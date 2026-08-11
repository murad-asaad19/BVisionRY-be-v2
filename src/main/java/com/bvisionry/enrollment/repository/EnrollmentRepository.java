package com.bvisionry.enrollment.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.progress.CourseProgressSql;
import com.bvisionry.enrollment.domain.Enrollment;

/**
 * <strong>CANCELLED means "an admin removed them", and every read here already
 * excludes it.</strong> V79 has always allowed the value; nothing wrote it until
 * the enrolment override (V157) did, and an override the player, the quiz gate or
 * the lesson-body gate did not honour would not be an override at all.
 *
 * <p>The exclusion lives in the DEFAULT-named methods on purpose. Six call sites
 * across four slices ask this repository "is this person on this course"
 * ({@code QuizService}, {@code ReviewService}, {@code EmbeddedAssessmentService},
 * and {@code EnrollmentService} three times), none of them knew about removal, and
 * a seventh will be written by someone who does not either. Making the obvious name
 * the safe one fixes all of them at once and pre-empts the next one;
 * {@link #findAnyByUserIdAndCourseId} is the deliberate, awkwardly-named opt-out
 * for the two callers that must see a removed row.
 */
@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    /**
     * The founder's LIVE enrolment in this course, or empty if they never had one
     * <em>or an admin removed them from it</em>.
     */
    @Query("""
            SELECT e FROM Enrollment e
            WHERE e.userId = :userId AND e.courseId = :courseId
              AND e.status <> com.bvisionry.enrollment.domain.EnrollmentStatus.CANCELLED
            """)
    Optional<Enrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);

    /**
     * The row whatever its status, removals included. Exactly two callers may use
     * this, and both need the removed row rather than in spite of it:
     * {@code EnrollmentService#enroll}, which must find it to reactivate it (the
     * row still occupies {@code uq_enrollment_user_course}, so an INSERT would
     * fail), and the admin removal itself.
     */
    Optional<Enrollment> findAnyByUserIdAndCourseId(UUID userId, UUID courseId);

    /** The founder's live enrolments — what "My Courses" is. Removals are absent. */
    @Query("""
            SELECT e FROM Enrollment e
            WHERE e.userId = :userId
              AND e.status <> com.bvisionry.enrollment.domain.EnrollmentStatus.CANCELLED
            """)
    List<Enrollment> findByUserId(UUID userId);

    /** The access gate behind lesson bodies, quizzes and reviews. False after a removal. */
    @Query("""
            SELECT COUNT(e) > 0 FROM Enrollment e
            WHERE e.userId = :userId AND e.courseId = :courseId
              AND e.status <> com.bvisionry.enrollment.domain.EnrollmentStatus.CANCELLED
            """)
    boolean existsByUserIdAndCourseId(UUID userId, UUID courseId);

    /**
     * An admin has taken this member off this course ({@code enrolment_overrides},
     * V157). Native and un-mapped on purpose: the table belongs to no slice and
     * the ArchUnit ratchet forbids importing the {@code pipeline} repository that
     * also reads it, so this depends on the SCHEMA like every other cross-slice
     * read in the codebase.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM enrolment_overrides"
            + " WHERE user_id = :userId AND course_id = :courseId)", nativeQuery = true)
    boolean isRemovedByAdmin(UUID userId, UUID courseId);

    /**
     * Completion percent counted against each course's CURRENT lesson set — see
     * {@link CourseProgressSql}. Native and sharing that one expression with the
     * five raw-SQL read sites in other slices, because {@code progress_pct} is a
     * cache the lesson set can invalidate behind its back and a second formula
     * here would drift from theirs.
     */
    @Query(value = "SELECT e.id AS id, " + CourseProgressSql.LIVE_PROGRESS_PCT
            + " AS pct FROM enrollment e WHERE e.id IN (:ids)", nativeQuery = true)
    List<LivePct> livePct(Collection<UUID> ids);

    /** One row of {@link #livePct}. */
    interface LivePct {
        UUID getId();

        int getPct();
    }
}
