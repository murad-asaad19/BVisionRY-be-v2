package com.bvisionry.quiz.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.quiz.domain.Quiz;
import com.bvisionry.quiz.domain.QuizQuestion;

public interface QuizRepository extends JpaRepository<Quiz, UUID> {

    Optional<Quiz> findByContentId(UUID contentId);

    /**
     * Fetch quiz with questions and options eagerly (for authoring — includes is_correct).
     *
     * <p>TWO queries, not one. {@code questions} and {@code options} are both
     * Lists, i.e. Hibernate bags, and join-fetching two bags in a single query is
     * a {@link org.hibernate.loader.MultipleBagFetchException} — which is exactly
     * what every quiz with questions threw at runtime. The second query runs in
     * the SAME persistence context, so Hibernate stitches the options onto the
     * already-managed {@code QuizQuestion} instances by identity and the returned
     * graph is fully initialized. Ordering is unchanged: {@code @OrderBy} on both
     * collections is what actually orders them, and the explicit ORDER BY below
     * mirrors the query this replaced.
     *
     * <p>CALL INSIDE A TRANSACTION. Outside one, each query gets its own session
     * and the options never land on the questions the caller holds. Every caller
     * in {@code QuizService} is {@code @Transactional}.
     */
    default Optional<Quiz> findByContentIdWithQuestionsAndOptions(UUID contentId) {
        Optional<Quiz> quiz = findByContentIdWithQuestions(contentId);
        quiz.ifPresent(q -> findQuestionsWithOptions(q.getId()));
        return quiz;
    }

    @Query("""
            SELECT DISTINCT q FROM Quiz q
            LEFT JOIN FETCH q.questions qq
            WHERE q.contentId = :contentId
            ORDER BY qq.sequence ASC
            """)
    Optional<Quiz> findByContentIdWithQuestions(@Param("contentId") UUID contentId);

    @Query("""
            SELECT DISTINCT qq FROM QuizQuestion qq
            LEFT JOIN FETCH qq.options
            WHERE qq.quiz.id = :quizId
            ORDER BY qq.sequence ASC
            """)
    List<QuizQuestion> findQuestionsWithOptions(@Param("quizId") UUID quizId);
}
