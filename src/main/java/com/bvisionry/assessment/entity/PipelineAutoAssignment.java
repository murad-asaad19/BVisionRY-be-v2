package com.bvisionry.assessment.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pipeline;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent rule: "auto-assign this pipeline to every current and future
 * ACTIVE member in this organization (optionally filtered by user_type)."
 *
 * <p>Materialised assignments live in the {@code assignments} table and are
 * created by the member-joined event listener at join time, reusing the
 * existing per-member assignment creation path.
 */
@Entity
@Table(name = "pipeline_auto_assignments")
@Getter
@Setter
@NoArgsConstructor
public class PipelineAutoAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    /** Null means "everyone in the org". Non-null filters by {@code users.user_type}. */
    @Column(name = "user_type", length = 64)
    private String userType;

    /** Absolute deadline (not relative to join time) applied to materialised assignments; null = no deadline. */
    private Instant deadline;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    /**
     * The most recent admin to edit this rule (null until the first update).
     * Kept separate from {@link #createdBy} so the original author is not
     * overwritten on every deadline / scope tweak.
     */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /**
     * Check-in count stamped on every assignment materialised from this rule.
     * Mirrors {@link com.bvisionry.assessment.entity.Assignment#maxCheckIns}
     * so a future joiner picked up by the rule receives the same cap the
     * original batch members got at rule-creation time.
     */
    @Column(name = "max_check_ins", nullable = false)
    private int maxCheckIns = 1;
}
