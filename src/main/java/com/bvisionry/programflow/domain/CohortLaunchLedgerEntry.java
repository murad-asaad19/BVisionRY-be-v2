package com.bvisionry.programflow.domain;

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
 * One cohort launch, forever (spec §8: append-only; deleting a cohort never
 * refunds quota, so {@code cohortId} is a bare UUID with NO foreign key — the
 * row must survive the cohort's deletion). {@code orgId} is the BILLING ROOT
 * org: sub-org cohorts consume the root family's quota.
 */
@Entity
@Table(name = "cohort_launch_ledger")
@Getter
@Setter
public class CohortLaunchLedgerEntry {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    @Column(name = "launched_at", nullable = false, updatable = false)
    private OffsetDateTime launchedAt;

    @Column(name = "launched_by", updatable = false)
    private UUID launchedBy;

    @PrePersist
    void onCreate() {
        if (launchedAt == null) {
            launchedAt = OffsetDateTime.now();
        }
    }
}
