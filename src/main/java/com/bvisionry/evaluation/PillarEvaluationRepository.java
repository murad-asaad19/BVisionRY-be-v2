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

    /**
     * All pillar evaluations for a submission, ordered by the pipeline's pillar
     * display order. The ORDER BY is load-bearing: results pages render rows in
     * the sequence this query returns, and without it a partial re-evaluation's
     * in-place UPDATE physically relocates the row, making re-evaluated pillars
     * jump position in the UI.
     */
    @Query("""
            SELECT pe FROM PillarEvaluation pe
            LEFT JOIN FETCH pe.pillar p
            WHERE pe.submission.id = :submissionId
            ORDER BY p.displayOrder, p.name
            """)
    List<PillarEvaluation> findBySubmissionId(@Param("submissionId") UUID submissionId);

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
