package com.bvisionry.coaching.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One visibility grant for a coach: EITHER a whole cohort ({@code cohortId}
 * set) OR a single founder ({@code memberId} set) — never both, enforced by the
 * table's CHECK. A coach sees the union of their grants (policy
 * {@code coach_assignment_grain: COHORT_AND_DIRECT}).
 *
 * <p>Soft-coupled to identity by UUID (the programflow/catalog convention):
 * the FKs exist at the DB level, the Java slice never imports another feature's
 * entity.
 */
@Entity
@Table(name = "coach_assignments")
@Getter
@Setter
public class CoachAssignment {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(name = "cohort_id", updatable = false)
    private UUID cohortId;

    @Column(name = "member_id", updatable = false)
    private UUID memberId;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
