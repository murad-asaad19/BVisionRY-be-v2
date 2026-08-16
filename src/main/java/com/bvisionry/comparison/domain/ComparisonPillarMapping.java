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

import java.util.UUID;

/**
 * One pillar correspondence within a COHORT's (baseline, distance) pipeline
 * pair (spec §5; cohort-scoped since V182 — each cohort owns its mapping).
 * Bare UUIDs only — no JPA relations across features. A row with a null
 * {@code distancePillarId} is "not re-measured"; a null
 * {@code baselinePillarId} is "newly measured"; both null is forbidden (DB
 * CHECK).
 */
@Entity
@Table(name = "comparison_pillar_mappings")
@Getter
@Setter
@NoArgsConstructor
public class ComparisonPillarMapping extends BaseEntity {

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "baseline_pipeline_id", nullable = false)
    private UUID baselinePipelineId;

    @Column(name = "distance_pipeline_id", nullable = false)
    private UUID distancePipelineId;

    @Column(name = "baseline_pillar_id")
    private UUID baselinePillarId;

    @Column(name = "distance_pillar_id")
    private UUID distancePillarId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private MappingSource source = MappingSource.AUTO;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
