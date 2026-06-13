package com.bvisionry.evaluation.entity;

import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.entity.BaseEntity;
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

/**
 * Snapshot of an {@link OverallSummary} taken right before partial re-evaluation
 * regenerates it. Mirrors the live table's columns so the snapshot is
 * self-contained.
 */
@Entity
@Table(name = "overall_summary_history")
@Getter
@Setter
@NoArgsConstructor
public class OverallSummaryHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
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
    private Instant generatedAt;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "archived_at", nullable = false)
    private Instant archivedAt = Instant.now();

    @Column(name = "archived_reason", nullable = false, length = 32)
    private String archivedReason;

    @Column(name = "archived_by_admin_id")
    private UUID archivedByAdminId;

    /**
     * Build a snapshot from the live {@link OverallSummary} that is about to be
     * regenerated. Archive metadata is set by the caller.
     */
    public static OverallSummaryHistory fromLive(OverallSummary src) {
        OverallSummaryHistory snap = new OverallSummaryHistory();
        snap.setSubmission(src.getSubmission());
        snap.setOverallScorePercentage(src.getOverallScorePercentage());
        snap.setSummaryNarrative(src.getSummaryNarrative());
        snap.setStrengths(src.getStrengths());
        snap.setDevelopmentAreas(src.getDevelopmentAreas());
        snap.setRecommendations(src.getRecommendations());
        snap.setCorePattern(src.getCorePattern());
        snap.setMovingForwardNarrative(src.getMovingForwardNarrative());
        snap.setAiModelUsed(src.getAiModelUsed());
        snap.setAiTemperature(src.getAiTemperature());
        snap.setAiSystemPromptVersionId(src.getAiSystemPromptVersionId());
        snap.setAiSummaryPromptSnapshot(src.getAiSummaryPromptSnapshot());
        snap.setGeneratedAt(src.getGeneratedAt());
        return snap;
    }
}
