package com.bvisionry.evaluation.entity;

import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
@Table(name = "overall_summaries")
@Getter
@Setter
@NoArgsConstructor
public class OverallSummary extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false, unique = true)
    private Submission submission;

    @Column(name = "overall_score_percentage", nullable = false)
    private BigDecimal overallScorePercentage;

    @Column(name = "summary_narrative", columnDefinition = "TEXT")
    private String summaryNarrative;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> strengths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "development_areas", columnDefinition = "jsonb")
    private List<String> developmentAreas;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> recommendations;

    @Column(name = "core_pattern", columnDefinition = "TEXT")
    private String corePattern;

    @Column(name = "moving_forward_narrative", columnDefinition = "TEXT")
    private String movingForwardNarrative;

    @Column(name = "ai_model_used")
    private String aiModelUsed;

    @Column(name = "ai_temperature")
    private BigDecimal aiTemperature;

    @Column(name = "ai_system_prompt_version_id")
    private UUID aiSystemPromptVersionId;

    @Column(name = "ai_summary_prompt_snapshot", columnDefinition = "TEXT")
    private String aiSummaryPromptSnapshot;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();
}
