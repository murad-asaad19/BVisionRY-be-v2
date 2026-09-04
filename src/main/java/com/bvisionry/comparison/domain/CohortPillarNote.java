package com.bvisionry.comparison.domain;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * The coach's one-paragraph read of a pillar for ONE org's slice of ONE cohort
 * — the "mechanism" sentence under a pillar on the Growth tab's category cards
 * (V214). Editorial by design: the numbers beside it are computed, this is the
 * judgement a human adds, so it is optional and never generated.
 *
 * <p>Keyed by (org, cohort, distance pillar): a platform cohort spans orgs and
 * each org reads its own slice, so their notes must not bleed into each other.
 * Ids are bare, the slice's usual soft coupling.
 */
@Entity
@Table(name = "cohort_pillar_notes")
@Getter
@Setter
@NoArgsConstructor
public class CohortPillarNote extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    @Column(name = "pillar_id", nullable = false, updatable = false)
    private UUID pillarId;

    @Column(name = "note", nullable = false, columnDefinition = "text")
    private String note;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
