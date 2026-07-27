package com.bvisionry.enrollment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
