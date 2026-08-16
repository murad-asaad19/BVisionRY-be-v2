package com.bvisionry.comparison.domain;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.InsightReportStatus;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One cohort-level growth report (spec §4) — the staff-facing, cohort-scoped
 * sibling of {@code InsightReport}, and shaped like it on purpose: the same
 * GENERATING → COMPLETED / FAILED lifecycle, the same {@code reportJson} blob,
 * and history rather than one row in place. There is NO review gate: §4 makes
 * this staff-only and auto-published.
 *
 * <p>Carries no user reference at all — coverage is two counters, which is what
 * the "narratives included for N/M members" line needs and nothing more.
 */
@Entity
@Table(name = "cohort_growth_summaries")
@Getter
@Setter
@NoArgsConstructor
public class CohortGrowthSummary extends BaseEntity {

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InsightReportStatus status = InsightReportStatus.GENERATING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", columnDefinition = "jsonb")
    private Map<String, Object> reportJson;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    /** Were REAL names sent to the provider for this run? §4: any org admin may ask. */
    @Column(name = "include_names", nullable = false)
    private boolean includeNames;

    /** Members with a computed comparison in this org's slice of the cohort. */
    @Column(name = "members_total", nullable = false)
    private int membersTotal;

    /** …of which how many contributed at least one APPROVED narrative. */
    @Column(name = "members_with_narratives", nullable = false)
    private int membersWithNarratives;

    @Column(name = "ai_model_used")
    private String aiModelUsed;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();
}
