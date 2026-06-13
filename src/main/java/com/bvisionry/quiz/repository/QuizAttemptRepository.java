package com.bvisionry.quiz.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.quiz.domain.QuizAttempt;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {

    /**
     * Count attempts by a user for a specific quiz content item within an enrollment.
     */
    int countByEnrollmentIdAndContentId(UUID enrollmentId, UUID contentId);

    /**
     * List all attempts by a user for a content item, ordered newest first.
     */
    List<QuizAttempt> findByEnrollmentIdAndContentIdOrderBySubmittedAtDesc(
            UUID enrollmentId, UUID contentId);
}
