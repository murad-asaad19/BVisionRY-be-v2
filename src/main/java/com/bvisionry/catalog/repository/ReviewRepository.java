package com.bvisionry.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bvisionry.catalog.domain.Review;

/**
 * Access to {@link Review}.
 */
@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    List<Review> findByCourseIdOrderByCreatedAtDesc(UUID courseId);

    long countByCourseId(UUID courseId);

    /** Find a user's own review for a course (null user_id rows are excluded). */
    Optional<Review> findByCourse_IdAndUserId(UUID courseId, UUID userId);

    /** Compute the average rating for a course (returns null when there are no reviews). */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.course.id = :courseId")
    Double avgRatingByCourseId(@Param("courseId") UUID courseId);
}
