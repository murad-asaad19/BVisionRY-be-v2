package com.bvisionry.comparison.domain;

import com.bvisionry.common.entity.BaseEntity;
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
import java.util.List;
import java.util.UUID;

/**
 * One founder's overall growth summary for a cohort (spec §3), synthesised from
 * their APPROVED per-pillar narratives and carrying the same draft → approved
 * review gate.
 *
 * <p>Anchored to {@code (cohortId, userId)} — the stable comparison identity,
 * not the comparison row a recompute deletes and rebuilds (see V190 and
 * {@link ShiftNarrative}). Exactly one row per member per cohort: regenerating
 * overwrites it rather than accumulating drafts nobody asked for.
 */
@Entity
@Table(name = "member_growth_summaries")
@Getter
@Setter
@NoArgsConstructor
public class MemberGrowthSummary extends BaseEntity {

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * The overall breakdown (V193) — the same kinds the per-pillar
     * narratives carry, merged across every pillar. The shape everything
     * generated or edited since V193 uses.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", columnDefinition = "jsonb")
    private List<ShiftNarrative.Item> items;

    /**
     * The pre-V193 single paragraph. Null on rows written since, kept on the
     * ones already approved and still produced by installations whose
     * customised template asks for prose — V193's CHECK guarantees a row
     * carries one shape or the other, and every read path renders both.
     */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

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
}
