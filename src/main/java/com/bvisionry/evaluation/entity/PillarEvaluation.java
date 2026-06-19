package com.bvisionry.evaluation.entity;

import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.pipeline.entity.Pillar;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pillar_evaluations")
@Getter
@Setter
@NoArgsConstructor
public class PillarEvaluation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pillar_id", nullable = false)
    private Pillar pillar;

    @Column(name = "score_percentage", nullable = false)
    private BigDecimal scorePercentage;

    @Column(name = "maturity_label", nullable = false)
    private String maturityLabel;

    @Column(name = "ai_score_means", columnDefinition = "TEXT")
    private String aiScoreMeans;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_whats_working", columnDefinition = "jsonb")
    private List<String> aiWhatsWorking;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_what_can_improve", columnDefinition = "jsonb")
    private List<String> aiWhatCanImprove;

    @Column(name = "ai_business_relevance", columnDefinition = "TEXT")
    private String aiBusinessRelevance;

    @Column(name = "ai_model_used")
    private String aiModelUsed;

    @Column(name = "ai_raw_response", columnDefinition = "TEXT")
    private String aiRawResponse;

    @Column(name = "ai_temperature")
    private BigDecimal aiTemperature;

    @Column(name = "ai_system_prompt_version_id")
    private UUID aiSystemPromptVersionId;

    @Column(name = "ai_rubric_snapshot", columnDefinition = "TEXT")
    private String aiRubricSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_evidence", columnDefinition = "jsonb")
    private List<PillarEvaluationResult.Evidence> aiEvidence;

    @Column(name = "self_assessment_gap")
    private Integer selfAssessmentGap;

    /**
     * True when the AI could not produce a valid evaluation for this pillar even
     * after repair retries — the row holds a placeholder zero score, not a real
     * one. Drives the submission's NEEDS_REVIEW state and gives admins per-pillar
     * visibility into what failed.
     */
    @Column(name = "ai_failed", nullable = false)
    private boolean aiFailed = false;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt = Instant.now();
}
