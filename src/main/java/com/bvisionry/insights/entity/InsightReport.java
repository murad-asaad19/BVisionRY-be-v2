package com.bvisionry.insights.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.InsightReportStatus;
import com.bvisionry.common.exception.BadRequestException;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "insight_reports")
@Getter
@Setter
@NoArgsConstructor
public class InsightReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", columnDefinition = "jsonb")
    private Map<String, Object> reportJson;

    @Column(name = "ai_model_used")
    private String aiModelUsed;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InsightReportStatus status = InsightReportStatus.GENERATING;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    /**
     * Subset of member (user) IDs the report was generated for. Empty means
     * "all evaluated members in the pipeline" — legacy reports created before
     * the member-selection feature land here. Populated only at generation
     * time; used by the PDF/Excel services to keep exports consistent with the
     * AI-aggregated content.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "insight_report_member_ids",
            joinColumns = @JoinColumn(name = "insight_report_id"))
    @Column(name = "member_id", nullable = false)
    private Set<UUID> memberIds = new HashSet<>();

    public void assertReadyForExport() {
        if (status != InsightReportStatus.COMPLETED || reportJson == null) {
            throw new BadRequestException("Insight report is not ready for export (status=" + status + ")");
        }
    }
}
