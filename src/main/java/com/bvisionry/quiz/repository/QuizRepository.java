package com.bvisionry.quiz.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.quiz.domain.Quiz;

public interface QuizRepository extends JpaRepository<Quiz, UUID> {

    Optional<Quiz> findByContentId(UUID contentId);

    /**
     * Fetch quiz with questions and options eagerly (for authoring — includes is_correct).
     */
    @Query("""
            SELECT DISTINCT q FROM Quiz q
            LEFT JOIN FETCH q.questions qq
            LEFT JOIN FETCH qq.options
            WHERE q.contentId = :contentId
            ORDER BY qq.sequence ASC
            """)
    Optional<Quiz> findByContentIdWithQuestionsAndOptions(@Param("contentId") UUID contentId);
}
