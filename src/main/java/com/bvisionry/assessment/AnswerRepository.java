package com.bvisionry.assessment;

import com.bvisionry.assessment.entity.Answer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnswerRepository extends JpaRepository<Answer, UUID> {

    List<Answer> findBySubmissionId(UUID submissionId);

    @Query("SELECT a FROM Answer a JOIN FETCH a.question q JOIN FETCH q.pillar WHERE a.submission.id = :submissionId")
    List<Answer> findBySubmissionIdWithQuestionAndPillar(@Param("submissionId") UUID submissionId);

    Optional<Answer> findBySubmissionIdAndQuestionId(UUID submissionId, UUID questionId);

    long countBySubmissionId(UUID submissionId);

    /**
     * Batched answered-count lookup grouped by submission. Replaces a per-submission
     * {@link #countBySubmissionId} loop in list endpoints. Returns rows shaped
     * {@code (submissionId, count)} — caller bundles them into a {@code Map}.
     */
    @Query("""
            SELECT a.submission.id, COUNT(a)
            FROM Answer a
            WHERE a.submission.id IN :submissionIds
            GROUP BY a.submission.id
            """)
    List<Object[]> findAnsweredCountsBySubmissionIds(@Param("submissionIds") List<UUID> submissionIds);

    /**
     * All answers for multiple submissions -- used for GDPR data export.
     */
    List<Answer> findBySubmissionIdIn(List<UUID> submissionIds);

    /**
     * Fetch the system-managed answer (e.g. GENDER) for a batch of submissions in one round-trip.
     */
    @Query("""
            SELECT a FROM Answer a
            JOIN a.question q
            WHERE a.submission.id IN :submissionIds
            AND q.systemKey = :systemKey
            """)
    List<Answer> findBySubmissionIdsAndSystemKey(
            @Param("submissionIds") List<UUID> submissionIds,
            @Param("systemKey") String systemKey);

    /**
     * Fetch several system-managed answers (e.g. FIRST_NAME + LAST_NAME) for a batch
     * of submissions in one round-trip. The question is fetched eagerly so callers
     * can split the rows by system key.
     */
    @Query("""
            SELECT a FROM Answer a
            JOIN FETCH a.question q
            WHERE a.submission.id IN :submissionIds
            AND q.systemKey IN :systemKeys
            """)
    List<Answer> findBySubmissionIdsAndSystemKeys(
            @Param("submissionIds") List<UUID> submissionIds,
            @Param("systemKeys") List<String> systemKeys);

    /**
     * All answers for a batch of submissions with question + pillar eagerly fetched --
     * used by the Team Insights Excel export to build the Q&A sheet.
     */
    @Query("""
            SELECT a FROM Answer a
            JOIN FETCH a.question q
            JOIN FETCH q.pillar
            WHERE a.submission.id IN :submissionIds
            """)
    List<Answer> findBySubmissionIdsWithQuestionAndPillar(
            @Param("submissionIds") List<UUID> submissionIds);
}
