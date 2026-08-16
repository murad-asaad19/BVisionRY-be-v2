package com.bvisionry.comparison.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.participation.ParticipationBandsPort;
import com.bvisionry.common.programaccess.OrgCohortAccess;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto.PillarAggregateDto;

import lombok.RequiredArgsConstructor;

/**
 * The cohort's key growth stats as a workbook (operator ask: "accurate and
 * easy to copy from", for pulling numbers into external reporting). Reads the
 * SAME aggregate the cohort Growth tab renders plus the participation band
 * breakdown through {@link ParticipationBandsPort}, so the file can never
 * quote a figure the screen did not show. Aggregates only — no member names,
 * so no show-names guard.
 */
@Service
@RequiredArgsConstructor
public class CohortGrowthReportExportService {

    private final CohortGrowthAggregateService aggregates;
    private final ParticipationBandsPort participation;
    private final OrgCohortAccess orgCohorts;

    @Transactional(readOnly = true)
    public byte[] excel(UUID orgId, UUID cohortId) {
        CohortGrowthAggregateDto data = aggregates.aggregate(orgId, cohortId);
        ParticipationBandsPort.CohortBands bands = participation.cohortBands(orgId, cohortId);
        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeOverview(wb, cohortName(orgId, cohortId), data, bands);
            writePillars(wb, data);
            writeNarrativeTags(wb, data);
            writeParticipation(wb, bands);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate cohort growth report Excel", e);
        }
    }

    /** The cohort's name for the export filename, tenancy-checked. */
    @Transactional(readOnly = true)
    public String cohortName(UUID orgId, UUID cohortId) {
        return orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
    }

    private static void writeOverview(ExcelWorkbookBuilder wb, String cohortName,
                                      CohortGrowthAggregateDto data,
                                      ParticipationBandsPort.CohortBands bands) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Overview")
                .headers("Metric", "Value");
        s.row("Cohort", cohortName);
        s.row("Members with a computed comparison", data.membersMeasured());
        s.row("Average overall before %", data.avgOverallBefore());
        s.row("Average overall after %", data.avgOverallAfter());
        s.row("Average overall shift", data.avgOverallDelta());
        s.row("Average participation score %", bands.average());
        s.autoSize();
    }

    /** Rows arrive ranked by average shift — the ranking IS the row order. */
    private static void writePillars(ExcelWorkbookBuilder wb, CohortGrowthAggregateDto data) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Pillar averages")
                .headers("Pillar", "Members measured", "Avg before %", "Avg after %", "Avg shift");
        for (PillarAggregateDto p : data.pillars()) {
            s.row(p.pillarName(), p.measuredCount(), p.avgBefore(), p.avgAfter(), p.avgDelta());
        }
        s.autoSize();
    }

    private static void writeNarrativeTags(ExcelWorkbookBuilder wb, CohortGrowthAggregateDto data) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Narrative tags")
                .headers("Pillar", "Members with approved narrative", "Resolved",
                        "Carried forward", "New", "Persisted", "Faded");
        for (PillarAggregateDto p : data.pillars()) {
            s.row(p.pillarName(), p.membersWithApprovedNarrative(),
                    p.kindCounts().get(NarrativeKind.RESOLVED),
                    p.kindCounts().get(NarrativeKind.CARRIED_FORWARD),
                    p.kindCounts().get(NarrativeKind.NEW),
                    p.kindCounts().get(NarrativeKind.PERSISTED),
                    p.kindCounts().get(NarrativeKind.FADED));
        }
        s.autoSize();
    }

    private static void writeParticipation(ExcelWorkbookBuilder wb,
                                           ParticipationBandsPort.CohortBands bands) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Participation bands")
                .headers("Band", "Members");
        for (ParticipationBandsPort.BandCount b : bands.bands()) {
            s.row(b.bandLabel(), b.members());
        }
        if (bands.unscored() > 0) {
            s.row("No score yet", bands.unscored());
        }
        s.autoSize();
    }
}
