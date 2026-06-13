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

/**
 * Snapshot of a {@link PillarEvaluation} taken right before partial re-evaluation
 * overwrites the live row. Self-contained — mirrors the live table's columns
 * rather than holding an FK to the (about-to-be-deleted) row, so a snapshot
 * survives independent of any subsequent re-eval cycles.
 *
 * <p>{@code versionNumber} orders successive snapshots per (submission, pillar):
 * 1 for the first archive, 2 for the second, etc. The current live row is
 * always the most recent eval and is not represented here.
 */
@Entity
@Table(name = "pillar_evaluation_history")
@Getter
@Setter
@NoArgsConstructor
public class PillarEvaluationHistory extends BaseEntity {

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

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "archived_at", nullable = false)
    private Instant archivedAt = Instant.now();

    /**
     * Why this snapshot was taken. Currently always {@code PILLAR_REEVAL}, but
     * left as a string so future archive triggers (e.g. full retry, manual
     * admin export) can use distinct codes without a schema change.
     */
    @Column(name = "archived_reason", nullable = false, length = 32)
    private String archivedReason;

    @Column(name = "archived_by_admin_id")
    private UUID archivedByAdminId;

    /**
     * Build a snapshot from the live {@link PillarEvaluation} that is about to
     * be replaced. Mirrors every column on the live row; archive metadata
     * ({@code versionNumber}, {@code archivedReason}, {@code archivedByAdminId})
     * is set by the caller.
     */
    public static PillarEvaluationHistory fromLive(PillarEvaluation src) {
        PillarEvaluationHistory snap = new PillarEvaluationHistory();
        snap.setSubmission(src.getSubmission());
        snap.setPillar(src.getPillar());
        snap.setScorePercentage(src.getScorePercentage());
        snap.setMaturityLabel(src.getMaturityLabel());
        snap.setAiScoreMeans(src.getAiScoreMeans());
        snap.setAiWhatsWorking(src.getAiWhatsWorking());
        snap.setAiWhatCanImprove(src.getAiWhatCanImprove());
        snap.setAiBusinessRelevance(src.getAiBusinessRelevance());
        snap.setAiModelUsed(src.getAiModelUsed());
        snap.setAiRawResponse(src.getAiRawResponse());
        snap.setAiTemperature(src.getAiTemperature());
        snap.setAiSystemPromptVersionId(src.getAiSystemPromptVersionId());
        snap.setAiRubricSnapshot(src.getAiRubricSnapshot());
        snap.setAiEvidence(src.getAiEvidence());
        snap.setSelfAssessmentGap(src.getSelfAssessmentGap());
        snap.setEvaluatedAt(src.getEvaluatedAt());
        return snap;
    }
}
