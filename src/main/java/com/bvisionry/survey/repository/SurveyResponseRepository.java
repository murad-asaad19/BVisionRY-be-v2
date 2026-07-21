package com.bvisionry.survey.repository;

import com.bvisionry.survey.entity.SurveyResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, UUID> {

    long countBySurveyId(UUID surveyId);

    Optional<SurveyResponse> findByIdAndSurveyId(UUID id, UUID surveyId);

    List<SurveyResponse> findBySurveyIdOrderBySubmittedAtDesc(UUID surveyId);

    @Query("""
            SELECT r.survey.id, COUNT(r)
            FROM SurveyResponse r
            WHERE r.survey.id IN :surveyIds
            GROUP BY r.survey.id
            """)
    List<Object[]> countBySurveyIdIn(@Param("surveyIds") Collection<UUID> surveyIds);

    @Query("""
            SELECT MIN(r.submittedAt), MAX(r.submittedAt)
            FROM SurveyResponse r WHERE r.survey.id = :surveyId
            """)
    Object[] findFirstAndLastSubmittedAt(@Param("surveyId") UUID surveyId);

    Optional<SurveyResponse> findFirstBySubmissionIdOrderBySubmittedAtDesc(UUID submissionId);

    boolean existsBySurveyIdAndSubmissionId(UUID surveyId, UUID submissionId);

    /**
     * Resolves the survey response that a gift assessment was emailed to. The
     * gift token travels in the {@code /a/<link>?g=<token>} URL; when the
     * respondent starts the assessment we look the response up here and stamp the
     * resulting submission onto it. Unique column, so at most one row.
     */
    Optional<SurveyResponse> findByGiftToken(UUID giftToken);

    /**
     * Same lookup as {@link #findByGiftToken} but eager-fetches the survey and the
     * bound gift submission, so the public {@code recover} endpoint can read
     * {@code survey.giftPublicAssessmentLinkId} and the submission's status/token
     * without firing a lazy SELECT per association on the taker hot path.
     */
    @Query("""
            SELECT r FROM SurveyResponse r
            JOIN FETCH r.survey
            LEFT JOIN FETCH r.giftSubmission
            WHERE r.giftToken = :giftToken
            """)
    Optional<SurveyResponse> findByGiftTokenWithSurveyAndSubmission(@Param("giftToken") UUID giftToken);

    /**
     * {@code (responseId, submissionId, submissionStatus)} for the gifted-assessment
     * submissions tied to the given responses on a specific gift link. Reads the
     * persisted {@code giftSubmission} link (set when the respondent opens the gift
     * email), scoped by the link as a safety net. Replaces the old email-based
     * matching.
     */
    @Query("""
            SELECT r.id, s.id, s.status FROM SurveyResponse r
            JOIN r.giftSubmission s
            WHERE r.id IN :responseIds
              AND s.publicLink.id = :giftLinkId
            """)
    List<Object[]> findGiftSubmissionRefs(@Param("responseIds") Collection<UUID> responseIds,
                                          @Param("giftLinkId") UUID giftLinkId);

    /**
     * The linked post-assessment submission id for a response, or null when the
     * response has none (or the response does not exist). Scalar projection so
     * callers in the survey slice don't have to touch the assessment-slice
     * {@code Submission} entity (the architecture rules forbid new
     * cross-feature dependencies).
     */
    @Query("SELECT r.submission.id FROM SurveyResponse r WHERE r.id = :id")
    UUID findSubmissionIdByResponseId(@Param("id") UUID id);

    /**
     * Single-statement hard delete. A bulk JPQL delete (rather than
     * {@code delete(entity)}) skips Hibernate's per-row cascade over the
     * {@code answers} collection — the {@code survey_answers} FK is
     * {@code ON DELETE CASCADE}, so the database removes the answer rows
     * as part of this one statement.
     */
    @Modifying
    @Query("DELETE FROM SurveyResponse r WHERE r.id = :id")
    void hardDeleteById(@Param("id") UUID id);

    /**
     * Filtered + paginated lookup used by the admin "responses" table. Pushes the
     * search (email/name LIKE) and date-range filters into the database so we don't
     * load every response into memory just to slice a page off the end.
     *
     * <p>{@code qLower} should be the lowercased search term wrapped in
     * {@code %...%}, or {@code null} for "no search". The {@code from}/{@code to}
     * bounds are inclusive (matches the previous in-memory semantics:
     * {@code !submittedAt.isBefore(from) && !submittedAt.isAfter(to)}).
     */
    @Query("""
            SELECT r FROM SurveyResponse r
            WHERE r.survey.id = :surveyId
              AND (CAST(:qLower AS string) IS NULL
                   OR LOWER(r.respondentEmail) LIKE :qLower
                   OR LOWER(r.respondentName)  LIKE :qLower)
              AND (CAST(:from AS timestamp) IS NULL OR r.submittedAt >= :from)
              AND (CAST(:to   AS timestamp) IS NULL OR r.submittedAt <= :to)
            ORDER BY r.submittedAt DESC
            """)
    Page<SurveyResponse> findFiltered(
            @Param("surveyId") UUID surveyId,
            @Param("qLower") String qLower,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * Returns the dedup keys (cookieId / ipHash) that appear more than once across
     * the same filter the page query uses. Used to flag {@code possibleDuplicate}
     * on individual rows without scanning the full filtered set in memory.
     *
     * <p>Two arrays come back, one per dedup column; combine them with the
     * {@code "cookie:"} / {@code "ip:"} prefixes the service uses.
     */
    @Query("""
            SELECT r.cookieId FROM SurveyResponse r
            WHERE r.survey.id = :surveyId
              AND (CAST(:qLower AS string) IS NULL
                   OR LOWER(r.respondentEmail) LIKE :qLower
                   OR LOWER(r.respondentName)  LIKE :qLower)
              AND (CAST(:from AS timestamp) IS NULL OR r.submittedAt >= :from)
              AND (CAST(:to   AS timestamp) IS NULL OR r.submittedAt <= :to)
              AND r.cookieId IS NOT NULL
            GROUP BY r.cookieId
            HAVING COUNT(r) > 1
            """)
    List<String> findDuplicateCookieIds(
            @Param("surveyId") UUID surveyId,
            @Param("qLower") String qLower,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            SELECT r.ipHash FROM SurveyResponse r
            WHERE r.survey.id = :surveyId
              AND (CAST(:qLower AS string) IS NULL
                   OR LOWER(r.respondentEmail) LIKE :qLower
                   OR LOWER(r.respondentName)  LIKE :qLower)
              AND (CAST(:from AS timestamp) IS NULL OR r.submittedAt >= :from)
              AND (CAST(:to   AS timestamp) IS NULL OR r.submittedAt <= :to)
              AND r.ipHash IS NOT NULL
              AND r.cookieId IS NULL
            GROUP BY r.ipHash
            HAVING COUNT(r) > 1
            """)
    List<String> findDuplicateIpHashes(
            @Param("surveyId") UUID surveyId,
            @Param("qLower") String qLower,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
