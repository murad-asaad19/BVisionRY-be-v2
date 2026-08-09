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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One pillar row of a stored comparison. Pillar ids are bare (no FK) and the
 * name is snapshotted so the row stays readable after builder edits.
 */
@Entity
@Table(name = "founder_comparison_pillars")
@Getter
@Setter
@NoArgsConstructor
public class FounderComparisonPillar extends BaseEntity {

    @Column(name = "comparison_id", nullable = false)
    private UUID comparisonId;

    @Column(name = "baseline_pillar_id")
    private UUID baselinePillarId;

    @Column(name = "distance_pillar_id")
    private UUID distancePillarId;

    @Column(name = "pillar_name_snapshot", nullable = false)
    private String pillarNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private PillarComparisonState state;

    @Column(name = "before_pct")
    private BigDecimal beforePct;

    @Column(name = "after_pct")
    private BigDecimal afterPct;

    @Column(name = "delta")
    private BigDecimal delta;

    @Column(name = "band_key")
    private String bandKey;

    @Column(name = "band_label")
    private String bandLabel;

    @Column(name = "maturity_before")
    private String maturityBefore;

    @Column(name = "maturity_after")
    private String maturityAfter;
}
