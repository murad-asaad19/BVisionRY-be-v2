package com.bvisionry.comparison.domain;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.scoringconfig.ScoringBands;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * The stored distance-comparison object per founder × designated pair (spec
 * §5), computed ONCE when the distance submission is evaluated. A SNAPSHOT:
 * band keys/labels and the config used ({@link #configSnapshot}) are stamped
 * at compute time; later config or pipeline edits never rewrite it — only the
 * explicit, logged "Recompute cohort" action does.
 */
@Entity
@Table(name = "founder_comparisons")
@Getter
@Setter
@NoArgsConstructor
public class FounderComparison extends BaseEntity {

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "baseline_submission_id", nullable = false)
    private UUID baselineSubmissionId;

    @Column(name = "distance_submission_id", nullable = false)
    private UUID distanceSubmissionId;

    @Column(name = "overall_before")
    private BigDecimal overallBefore;

    @Column(name = "overall_after")
    private BigDecimal overallAfter;

    @Column(name = "overall_delta")
    private BigDecimal overallDelta;

    @Column(name = "overall_band_key")
    private String overallBandKey;

    @Column(name = "overall_band_label")
    private String overallBandLabel;

    /** The shift bands (keys + labels + ranges) this comparison was computed with. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", columnDefinition = "jsonb")
    private List<ScoringBands.Band> configSnapshot;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
}
