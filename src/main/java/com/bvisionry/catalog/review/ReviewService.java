package com.bvisionry.catalog.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.Review;
import com.bvisionry.catalog.repository.CourseRepository;
import com.bvisionry.catalog.repository.ReviewRepository;
import com.bvisionry.catalog.web.CourseNotFoundException;
import com.bvisionry.enrollment.repository.EnrollmentRepository;

/**
 * Application service for learner course reviews.
 *
 * <p>Each authenticated user may have at most one review per course (enforced by
 * the {@code uq_review_course_user} partial unique index from V83). Submitting a
 * second review upserts (overwrites) the existing one so the learner can update
 * their rating/comment at any time.
 *
 * <p>After every write the course aggregate ({@code rating}, {@code reviews_count})
 * is recomputed in the same transaction and saved through {@link CourseRepository}.
 */
@Service
public class ReviewService {

    private final CourseRepository courses;
    private final ReviewRepository reviews;
    private final EnrollmentRepository enrollments;

    public ReviewService(CourseRepository courses,
                         ReviewRepository reviews,
                         EnrollmentRepository enrollments) {
        this.courses = courses;
        this.reviews = reviews;
        this.enrollments = enrollments;
    }

    // -------------------------------------------------------------------------
    // Upsert review
    // -------------------------------------------------------------------------

    /**
     * Creates or updates the current user's review for the course identified by
     * {@code slug}.  The caller must be enrolled; otherwise a 403 is raised.
     *
     * @return the saved (or updated) review as a DTO.
     */
    @Transactional
    public ReviewDto upsert(String slug, int rating, String comment) {
        Course course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));

        UUID userId = SecurityUtils.getCurrentUserId();

        // Learner must be enrolled to leave a review.
        if (!enrollments.existsByUserIdAndCourseId(userId, course.getId())) {
            throw new AccessDeniedException("You must be enrolled to review this course");
        }

        // Find-or-create the user's own review.
        Review review = reviews.findByCourse_IdAndUserId(course.getId(), userId)
                .orElseGet(() -> {
                    Review r = new Review();
                    r.setOrgId(course.getOrgId());
                    r.setCourse(course);
                    r.setUserId(userId);
                    return r;
                });

        String authorName = SecurityUtils.getCurrentUser().getName();
        review.setRating(rating);
        review.setComment(comment);
        review.setAuthorName(authorName);
        reviews.save(review);

        recomputeAggregate(course);

        return toDto(review, course.getId());
    }

    // -------------------------------------------------------------------------
    // Get my review
    // -------------------------------------------------------------------------

    /**
     * Returns the current user's review for {@code slug}, or raises a 404.
     */
    @Transactional(readOnly = true)
    public ReviewDto getMyReview(String slug) {
        Course course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));

        UUID userId = SecurityUtils.getCurrentUserId();

        return reviews.findByCourse_IdAndUserId(course.getId(), userId)
                .map(r -> toDto(r, course.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No review found"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Recomputes {@code course.rating} and {@code course.reviews_count} from the
     * live review rows and saves the course entity.
     *
     * <p>The rating is rounded to 1 decimal place (mirrors the DB column
     * {@code numeric(2,1)}).
     */
    private void recomputeAggregate(Course course) {
        long count = reviews.countByCourseId(course.getId());
        Double avg = reviews.avgRatingByCourseId(course.getId());

        BigDecimal newRating = (avg == null)
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP);

        course.setRating(newRating);
        course.setReviewsCount((int) count);
        courses.save(course);
    }

    private ReviewDto toDto(Review r, UUID courseId) {
        return new ReviewDto(
                r.getId() == null ? null : r.getId().toString(),
                courseId.toString(),
                r.getRating(),
                r.getComment(),
                r.getAuthorName(),
                r.getUpdatedAt());
    }
}
