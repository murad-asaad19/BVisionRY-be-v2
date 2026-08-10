package com.bvisionry.comparison.domain;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.scoringconfig.NarrativeWording;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One AI-written qualitative shift narrative for a founder × mapped pillar
 * (spec §6), with the draft → approved review gate.
 *
 * <p>Anchored to {@code (cohortId, userId, distancePillarId)} and NOT to the
 * comparison row: {@code ComparisonComputeService.recomputeCohort} deletes and
 * rebuilds {@code founder_comparisons}, so a foreign key there would cascade
 * approved narratives away on every recompute. §7 says the opposite — recompute
 * returns them to DRAFT (see
 * {@code ShiftNarrativeService#revertApprovalsForCohort}).
 */
@Entity
@Table(name = "shift_narratives")
@Getter
@Setter
@NoArgsConstructor
public class ShiftNarrative extends BaseEntity {

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "baseline_pillar_id", nullable = false)
    private UUID baselinePillarId;

    /** Stable pillar identity within the pair — the anchor that survives recompute. */
    @Column(name = "distance_pillar_id", nullable = false)
    private UUID distancePillarId;

    @Column(name = "pillar_name_snapshot", nullable = false)
    private String pillarNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private NarrativeKind kind;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** The forward-looking next step — mandatory while {@link #decline} is true. */
    @Column(name = "closing_action", columnDefinition = "TEXT")
    private String closingAction;

    /**
     * Is this a DECLINING pillar (§6's forward-looking-close rule)? Derived from
     * the pillar's delta sign, never from the band key — §7 lets a super admin
     * rename or delete any band, and a guardrail keyed on the string
     * {@code "decline"} would go silently dead the moment they did. Re-stamped
     * by "Recompute cohort" so an edited band always binds.
     */
    @Column(name = "decline", nullable = false)
    private boolean decline;

    /** The band label/key in force at generation — DISPLAY ONLY, never a guard. */
    @Column(name = "band_key")
    private String bandKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NarrativeStatus status = NarrativeStatus.DRAFT;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "edited_by")
    private UUID editedBy;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "ai_model_used")
    private String aiModelUsed;

    @Column(name = "ai_temperature")
    private BigDecimal aiTemperature;

    @Column(name = "ai_prompt_version_id")
    private UUID aiPromptVersionId;

    /** §7 snapshot: the narrative wording + band label in force at generation. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", columnDefinition = "jsonb")
    private Snapshot configSnapshot;

    /** What this narrative was generated with — config edits apply forward only. */
    public record Snapshot(NarrativeWording wording, String bandKey, String bandLabel) {
    }
}
