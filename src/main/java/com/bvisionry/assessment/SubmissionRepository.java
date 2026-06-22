package com.bvisionry.assessment;

import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    @Query("SELECT s FROM Submission s JOIN FETCH s.user JOIN FETCH s.assignment a JOIN FETCH a.pipeline p JOIN FETCH a.organization LEFT JOIN FETCH p.pillars WHERE s.id = :id")
    Optional<Submission> findByIdWithAssignmentAndPipeline(@Param("id") UUID id);

    List<Submission> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Submission> findByAssignmentId(UUID assignmentId);

    Optional<Submission> findByAssignmentIdAndUserId(UUID assignmentId, UUID userId);

    /**
     * Returns the most recent submission for an (assignment, user) pair.
     * Multi-attempt pipelines allow N rows per pair — admin operations
     * (status display, reminder, retry-eval, answers view) should always
     * target the latest attempt rather than picking an arbitrary row.
     */
    Optional<Submission> findTopByAssignmentIdAndUserIdOrderByCreatedAtDesc(
            UUID assignmentId, UUID userId);

    /**
     * Loads the latest submission for an assignment's owning user, or throws
     * {@link BadRequestException} with {@code missingMessage} when none exists.
     * Callers that have already loaded an {@link Assignment} should prefer this
     * over composing {@code findTopBy...().orElseThrow(...)} themselves.
     */
    default Submission requireLatestForAssignment(Assignment assignment, String missingMessage) {
        return findTopByAssignmentIdAndUserIdOrderByCreatedAtDesc(
                assignment.getId(), assignment.getUser().getId())
                .orElseThrow(() -> new BadRequestException(missingMessage));
    }

    /**
     * Batched submission lookup for a set of assignments — used by list endpoints
     * to avoid N+1 round-trips when rendering an assignment table that needs each
     * assignment's submission status. Caller groups by {@code assignment.id}.
     */
    List<Submission> findByAssignmentIdIn(List<UUID> assignmentIds);

    @Query("SELECT s FROM Submission s WHERE s.user.id = :userId AND s.status = :status")
    List<Submission> findByUserIdAndStatus(
            @Param("userId") UUID userId,
            @Param("status") SubmissionStatus status);

    @Query("SELECT COUNT(s) FROM Submission s WHERE s.assignment.id = :assignmentId")
    long countByAssignmentId(@Param("assignmentId") UUID assignmentId);

    @Query("SELECT COUNT(s) FROM Submission s WHERE s.assignment.id = :assignmentId AND s.status = :status")
    long countByAssignmentIdAndStatus(
            @Param("assignmentId") UUID assignmentId,
            @Param("status") SubmissionStatus status);

    boolean existsByAssignmentIdAndUserId(UUID assignmentId, UUID userId);

    /**
     * All submissions for an org + pipeline (dashboard).
     */
    @Query("""
            SELECT s FROM Submission s
            JOIN FETCH s.user
            JOIN s.assignment a
            WHERE a.organization.id = :orgId
            AND a.pipeline.id = :pipelineId
            """)
    List<Submission> findByOrgAndPipeline(@Param("orgId") UUID orgId,
                                          @Param("pipelineId") UUID pipelineId);

    /**
     * Dashboard variant — eagerly loads user + assignment + pipeline so the
     * @Cacheable overview render doesn't trigger 2*N lazy loads when reading
     * {@code submission.assignment.pipeline.name} per row.
     */
    @Query("""
            SELECT s FROM Submission s
            JOIN FETCH s.user
            JOIN FETCH s.assignment a
            JOIN FETCH a.pipeline
            WHERE a.organization.id = :orgId
            AND a.pipeline.id = :pipelineId
            """)
    List<Submission> findByOrgAndPipelineForDashboard(@Param("orgId") UUID orgId,
                                                     @Param("pipelineId") UUID pipelineId);

    /**
     * Count submissions by status for an org + pipeline (completion stats).
     */
    @Query("""
            SELECT s.status, COUNT(s) FROM Submission s
            JOIN s.assignment a
            WHERE a.organization.id = :orgId
            AND a.pipeline.id = :pipelineId
            GROUP BY s.status
            """)
    List<Object[]> countByStatusForOrgPipeline(@Param("orgId") UUID orgId,
                                                @Param("pipelineId") UUID pipelineId);

    /**
     * Total evaluated submissions across entire platform.
     */
    long countByStatus(SubmissionStatus status);

    /**
     * Count evaluated submissions for an org + pipeline.
     */
    @Query("""
            SELECT COUNT(s) FROM Submission s
            JOIN s.assignment a
            WHERE a.organization.id = :orgId
            AND a.pipeline.id = :pipelineId
            AND s.status = 'EVALUATED'
            """)
    long countEvaluatedByOrgPipeline(@Param("orgId") UUID orgId,
                                     @Param("pipelineId") UUID pipelineId);

    /**
     * Finds the most recent submission for a (user, pipeline) pair, regardless of
     * which org the assignment belongs to. Used by the embedded-assessment player
     * to resolve whether the member already has a submission for a given pipeline.
     */
    @Query("""
            SELECT s FROM Submission s
            JOIN FETCH s.assignment a
            JOIN FETCH a.pipeline p
            WHERE s.user.id = :userId
              AND p.id = :pipelineId
            ORDER BY s.createdAt DESC
            """)
    List<Submission> findByUserIdAndPipelineIdOrderByCreatedAtDesc(
            @Param("userId") UUID userId,
            @Param("pipelineId") UUID pipelineId);

    /**
     * All submissions for a user -- used for GDPR data export.
     */
    @Query("""
            SELECT s FROM Submission s
            JOIN FETCH s.assignment a
            JOIN FETCH a.pipeline
            WHERE s.user.id = :userId
            ORDER BY s.createdAt DESC
            """)
    List<Submission> findAllByUserIdWithAssignment(@Param("userId") UUID userId);

    /**
     * IDs of all submissions belonging to a user within a single org. Used to
     * scope the "clear responses" admin action so it can't accidentally wipe a
     * user's history in unrelated orgs.
     */
    @Query("""
            SELECT s.id FROM Submission s
            WHERE s.user.id = :userId
            AND s.assignment.organization.id = :orgId
            """)
    List<UUID> findIdsByUserIdAndOrgId(@Param("userId") UUID userId,
                                       @Param("orgId") UUID orgId);

    /**
     * Bulk delete by ID — relies on DB-level ON DELETE CASCADE on
     * answers / pillar_evaluations / overall_summaries / survey_responses
     * to remove dependent rows. Returns the number of submissions deleted.
     */
    @Modifying
    @Query("DELETE FROM Submission s WHERE s.id IN :ids")
    int deleteAllByIdIn(@Param("ids") List<UUID> ids);

    // ---- Public-assessment (anonymous) submissions ----

    Optional<Submission> findByAccessToken(UUID accessToken);

    /**
     * Anonymous-session lookup that eagerly loads the public link + its
     * pipeline so the public taker flow resolves the pipeline without lazy
     * loads. To-one fetches only — pillars/questions load where needed.
     */
    @Query("""
            SELECT s FROM Submission s
            JOIN FETCH s.publicLink l
            JOIN FETCH l.pipeline
            WHERE s.accessToken = :accessToken
            """)
    Optional<Submission> findByAccessTokenWithPublicLink(@Param("accessToken") UUID accessToken);

    long countByPublicLinkId(UUID publicLinkId);

    @Query("""
            SELECT s FROM Submission s
            WHERE s.publicLink.id = :publicLinkId
            ORDER BY s.createdAt DESC
            """)
    Page<Submission> findByPublicLinkId(@Param("publicLinkId") UUID publicLinkId,
                                        Pageable pageable);

    /**
     * Evaluation-path lookup that works for BOTH member submissions
     * (assignment + user set) and public ones (publicLink set). All joins are
     * LEFT and to-one only — unlike {@link #findByIdWithAssignmentAndPipeline},
     * this must not fetch-join {@code pipeline.pillars}: with two pipeline
     * paths that would require two collection (bag) fetches in one query
     * (MultipleBagFetchException). Pillars lazy-load inside the caller's
     * transaction (EvaluationService.evaluateSubmission is @Transactional).
     */
    @Query("""
            SELECT s FROM Submission s
            LEFT JOIN FETCH s.user
            LEFT JOIN FETCH s.assignment a
            LEFT JOIN FETCH a.organization
            LEFT JOIN FETCH a.pipeline
            LEFT JOIN FETCH s.publicLink l
            LEFT JOIN FETCH l.pipeline
            WHERE s.id = :id
            """)
    Optional<Submission> findByIdWithAllRelations(@Param("id") UUID id);

    /**
     * Atomically claims a SUBMITTED submission for evaluation: stamps
     * {@code evaluation_claimed_at} only while the row is still SUBMITTED and not
     * already claimed within the staleness window. Returns 1 when this worker won
     * the claim, 0 when another worker already holds it (or the row is no longer
     * SUBMITTED) — the caller MUST skip evaluation on 0 so the AI evaluation never
     * runs twice for the same submission. A claim older than {@code staleAfterSeconds}
     * (a crashed worker that stopped heart-beating) can be reclaimed.
     *
     * <p>Native so both the stamp and the staleness comparison use the <em>database</em>
     * clock ({@code now()}): in a multi-instance deployment, comparing a peer's claim
     * timestamp against the local app-server clock would let cross-host clock skew
     * reclaim a still-active claim. Status is stored as text (EnumType.STRING).
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE submissions SET evaluation_claimed_at = now()
            WHERE id = :id
              AND status = 'SUBMITTED'
              AND (evaluation_claimed_at IS NULL
                   OR evaluation_claimed_at < now() - (:staleAfterSeconds * interval '1 second'))
            """, nativeQuery = true)
    int claimForEvaluation(@Param("id") UUID id,
                           @Param("staleAfterSeconds") long staleAfterSeconds);

    /**
     * Heartbeat: refresh the evaluation claim of a submission that is still being
     * worked on, so a legitimately long-running evaluation is never mistaken for a
     * crashed one and reclaimed by a competing worker. No-op once the row leaves
     * SUBMITTED (the terminal write clears the claim anyway). DB clock, as above.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE submissions SET evaluation_claimed_at = now()
            WHERE id = :id AND status = 'SUBMITTED'
            """, nativeQuery = true)
    int refreshEvaluationClaim(@Param("id") UUID id);

    /**
     * IDs of submissions stranded in SUBMITTED — committed for evaluation but not
     * picked up (executor overflow) or abandoned by a crashed/redeployed worker.
     * Drives {@link com.bvisionry.evaluation.EvaluationReaper}'s recovery sweep.
     *
     * <p>{@code graceSeconds} ignores freshly-submitted rows that the executor is
     * still working through normally; {@code staleSeconds} skips rows a live worker
     * still holds (its heartbeat keeps {@code evaluation_claimed_at} fresh). The
     * re-dispatch is idempotent regardless — {@link #claimForEvaluation} is the
     * authoritative gate — so a row briefly matched in error is simply skipped.
     * DB clock ({@code now()}) for the same cross-host-skew reason as the claim.
     */
    @Query(value = """
            SELECT id FROM submissions
            WHERE status = 'SUBMITTED'
              AND submitted_at < now() - (:graceSeconds * interval '1 second')
              AND (evaluation_claimed_at IS NULL
                   OR evaluation_claimed_at < now() - (:staleSeconds * interval '1 second'))
            ORDER BY submitted_at ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findStrandedSubmittedIds(@Param("graceSeconds") long graceSeconds,
                                        @Param("staleSeconds") long staleSeconds,
                                        @Param("limit") int limit);

    /**
     * Backlog size for the {@code bvisionry.ai.evaluation_backlog} gauge: how many
     * submissions are stuck in SUBMITTED past the grace window. A non-zero, growing
     * value means evaluation intake is outrunning drain — the launch-day alert.
     */
    @Query(value = """
            SELECT COUNT(*) FROM submissions
            WHERE status = 'SUBMITTED'
              AND submitted_at < now() - (:graceSeconds * interval '1 second')
            """, nativeQuery = true)
    long countStrandedSubmitted(@Param("graceSeconds") long graceSeconds);

    /**
     * Abandoned anonymous sessions: public submissions left IN_PROGRESS past the TTL.
     * Each reserved a response slot at session-create that must be released
     * ({@link com.bvisionry.publicassessment.PublicSubmissionReaper}). Returns
     * {@code [submissionId, publicLinkId]} pairs so the reaper can release the slot
     * on the right link and then bulk-delete the submissions. DB clock; oldest first.
     */
    @Query(value = """
            SELECT id, public_link_id FROM submissions
            WHERE status = 'IN_PROGRESS'
              AND public_link_id IS NOT NULL
              AND created_at < now() - (:ttlSeconds * interval '1 second')
            ORDER BY created_at ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findStaleInProgressPublicSessions(@Param("ttlSeconds") long ttlSeconds,
                                                     @Param("limit") int limit);
}
