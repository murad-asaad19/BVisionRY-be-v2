package com.bvisionry.evaluation;

import com.bvisionry.evaluation.entity.PillarEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PillarEvaluationRepository extends JpaRepository<PillarEvaluation, UUID> {

    List<PillarEvaluation> findBySubmissionId(UUID submissionId);

    /** (pillar, stored maturity label, owner) — see {@link #findMaturityLabels}. */
    interface PillarMaturityView {
        UUID getPillarId();

        String getMaturityLabel();

        /** Null on an anonymous public (QR-link) submission — it has no account. */
        UUID getFounderId();
    }

    /**
     * The STORED maturity label of every pillar of a submission, plus who was
     * assessed.
     *
     * <p>Feeds {@code EvaluationEvents.SubmissionEvaluated}, whose consumer resolves
     * each pillar's band from the label rather than re-deriving one from the score
     * (RULING 4 / D-2). A projection, not {@link #findBySubmissionId}: the caller
     * runs post-commit with no open session, where reading {@code pe.getPillar()}
     * off a lazy proxy is a bug waiting for the first pillar whose row was loaded
     * rather than written this run.
     *
     * <p>{@code founderId} rides along rather than being read off the in-memory
     * {@code Submission} for a boundary reason: the publisher is a NEW method, and
     * a new {@code evaluation -> assessment} call from one is a new cross-feature
     * violation the ArchUnit ratchet fails on. A JPQL string is not a type
     * dependency, so the join happens here instead — LEFT so a public submission
     * still returns its labels alongside a null owner, which the caller reads as
     * "nobody to enrol".
     *
     * <p>Newest first. {@code uq_pillar_evaluations_submission_pillar} (V102) makes
     * one row per (submission, pillar) the invariant, so the consumer's per-pillar
     * merge is unreachable while that constraint holds — but this read feeds a
     * WRITE path, and "which of two rows won" must not be the planner's choice if it
     * ever stops holding. {@code evaluated_at} is NOT NULL with a default (V5), so
     * the ordering is total and costs nothing on the ~11 rows an evaluation has.
     */
    @Query("""
            SELECT pe.pillar.id AS pillarId, pe.maturityLabel AS maturityLabel, u.id AS founderId
            FROM PillarEvaluation pe
            LEFT JOIN pe.submission s
            LEFT JOIN s.user u
            WHERE s.id = :submissionId
            ORDER BY pe.evaluatedAt DESC
            """)
    List<PillarMaturityView> findMaturityLabels(@Param("submissionId") UUID submissionId);

    List<PillarEvaluation> findBySubmissionIdAndPillarId(UUID submissionId, UUID pillarId);

    List<PillarEvaluation> findBySubmissionIdAndPillarIdIn(UUID submissionId, Collection<UUID> pillarIds);

    /**
     * Average score for a pillar across all evaluated submissions for a pipeline (platform-wide).
     */
    @Query("""
            SELECT AVG(pe.scorePercentage) FROM PillarEvaluation pe
            JOIN pe.submission s
            JOIN s.assignment a
            WHERE a.pipeline.id = :pipelineId
            AND s.status = 'EVALUATED'
            AND pe.pillar.id = :pillarId
            """)
    BigDecimal findPlatformAverageByPillar(@Param("pipelineId") UUID pipelineId,
                                           @Param("pillarId") UUID pillarId);

    /**
     * Average score for a pillar within a specific organization.
     */
    @Query("""
            SELECT AVG(pe.scorePercentage) FROM PillarEvaluation pe
            JOIN pe.submission s
            JOIN s.assignment a
            WHERE a.pipeline.id = :pipelineId
            AND a.organization.id = :orgId
            AND s.status = 'EVALUATED'
            AND pe.pillar.id = :pillarId
            """)
    BigDecimal findOrgAverageByPillar(@Param("orgId") UUID orgId,
                                      @Param("pipelineId") UUID pipelineId,
                                      @Param("pillarId") UUID pillarId);

    /**
     * All scores for a pillar in a pipeline -- used for percentile calculation.
     */
    @Query("""
            SELECT pe.scorePercentage FROM PillarEvaluation pe
            JOIN pe.submission s
            JOIN s.assignment a
            WHERE a.pipeline.id = :pipelineId
            AND s.status = 'EVALUATED'
            AND pe.pillar.id = :pillarId
            ORDER BY pe.scorePercentage ASC
            """)
    List<BigDecimal> findAllScoresByPillar(@Param("pipelineId") UUID pipelineId,
                                           @Param("pillarId") UUID pillarId);

    /**
     * All evaluations for a given org + pipeline, as full entities. Used by the
     * PDF/Excel insight exports, which read the AI narrative columns. Dashboard
     * aggregations must use {@link #findScoreViewsByOrgAndPipeline} instead —
     * this query hydrates the heavy AI payload columns with every row.
     */
    @Query("""
            SELECT pe FROM PillarEvaluation pe
            JOIN FETCH pe.pillar
            JOIN FETCH pe.submission s
            JOIN FETCH s.user
            JOIN s.assignment a
            WHERE a.organization.id = :orgId
            AND a.pipeline.id = :pipelineId
            AND s.status = 'EVALUATED'
            """)
    List<PillarEvaluation> findByOrgAndPipeline(@Param("orgId") UUID orgId,
                                                 @Param("pipelineId") UUID pipelineId);

    /**
     * Score-only projection of {@link #findByOrgAndPipeline} for dashboard
     * aggregations: identical scope (org + pipeline, EVALUATED only), but
     * selects just the six aggregation columns instead of whole rows.
     */
    @Query("""
            SELECT new com.bvisionry.evaluation.PillarScoreView(
                s.id, p.id, p.name, p.iconKey, pe.scorePercentage, pe.maturityLabel)
            FROM PillarEvaluation pe
            JOIN pe.pillar p
            JOIN pe.submission s
            JOIN s.assignment a
            WHERE a.organization.id = :orgId
            AND a.pipeline.id = :pipelineId
            AND s.status = 'EVALUATED'
            """)
    List<PillarScoreView> findScoreViewsByOrgAndPipeline(@Param("orgId") UUID orgId,
                                                         @Param("pipelineId") UUID pipelineId);

}
