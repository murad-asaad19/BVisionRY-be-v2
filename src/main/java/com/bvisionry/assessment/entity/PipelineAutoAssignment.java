package com.bvisionry.assessment.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pipeline;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
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

    /** Null means "every member TYPE in the org". Non-null filters by {@code users.user_type}. */
    @Column(name = "user_type", length = 64)
    private String userType;

    /**
     * Which ROLES this rule may materialise assignments for.
     *
     * <p>Orthogonal to {@link #userType}, which is the org's own member taxonomy
     * (LEADER, FOUNDER, …): that answers "which kind of member", this answers
     * "is this person a measured subject at all". Before V158 the question was
     * never asked, so an "applies to all members" rule assigned founder
     * assessments to coaches and org admins — who then appeared in the cohort's
     * Team Score Grid and its completion denominator.
     *
     * <p>Defaults to {@code MEMBER} alone, and V158 backfills every pre-existing
     * rule to the same, because a founder-readiness assessment measures
     * founders. An admin may deliberately widen it (a coach-development track is
     * the real case) — that is an explicit choice rather than the default.
     *
     * <p>EAGER because the rule is read in one transaction and applied in
     * another ({@code AFTER_COMMIT} → {@code REQUIRES_NEW}), so a lazy set would
     * be detached at the point the guard needs it. Safe as a fetch strategy: a
     * {@code Set} is not a bag, so it cannot trigger MultipleBagFetchException
     * alongside the existing {@code JOIN FETCH}es, and it holds at most one row
     * per {@link UserRole}.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "pipeline_auto_assignment_roles",
            joinColumns = @JoinColumn(name = "auto_assignment_id"))
    @Column(name = "role", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Set<UserRole> targetRoles = EnumSet.of(UserRole.MEMBER);

    /**
     * Whether this rule should fire for someone holding {@code role}.
     *
     * <p>An empty set matches NOBODY rather than everybody. That direction is
     * deliberate: this guard exists because the absent-filter case silently
     * meant "everyone", and re-introducing a permissive empty case would restore
     * exactly the bug V158 closes.
     */
    public boolean appliesToRole(UserRole role) {
        return role != null && targetRoles != null && targetRoles.contains(role);
    }

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
