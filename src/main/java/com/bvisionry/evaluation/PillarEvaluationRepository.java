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
     * All evaluations for a given org + pipeline (for dashboard overview).
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

}
