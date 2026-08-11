package com.bvisionry.programflow.domain;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One org's participation in a platform cohort (spec §13) — the cohort
 * equivalent of a pipeline's org-level provision, plus the enrollment rule:
 * {@code autoEnroll} keeps enrolling members who join the org later, the
 * "auto assignation" mode. Each assigned org pays for a launched cohort from
 * its own billing-root plan; the quota consumption is service-level and the
 * ledger row is what proves it.
 */
@Entity
@Table(name = "cohort_orgs")
@IdClass(CohortOrgAssignment.Key.class)
@Getter
@Setter
public class CohortOrgAssignment {

    @Id
    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Id
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "auto_enroll", nullable = false)
    private boolean autoEnroll = false;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    /** Survives the assigning admin's deletion as NULL (DB: ON DELETE SET NULL). */
    @Column(name = "assigned_by")
    private UUID assignedBy;

    @PrePersist
    void onCreate() {
        if (assignedAt == null) {
            assignedAt = OffsetDateTime.now();
        }
    }

    public static class Key implements Serializable {
        private UUID cohortId;
        private UUID orgId;

        public Key() {
        }

        public Key(UUID cohortId, UUID orgId) {
            this.cohortId = cohortId;
            this.orgId = orgId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(cohortId, k.cohortId)
                    && Objects.equals(orgId, k.orgId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cohortId, orgId);
        }
    }
}
