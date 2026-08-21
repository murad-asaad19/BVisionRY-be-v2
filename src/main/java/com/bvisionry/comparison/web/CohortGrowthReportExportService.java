package com.bvisionry.comparison.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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

    /**
     * One column per kind, taken FROM the enum rather than listed here: the
     * aggregate seeds a count for every {@link NarrativeKind}, so a hand-kept
     * header list silently drops any kind added later (V202 added two).
     *
     * <p>Ordered by {@link MyGrowthExportService#KIND_ORDER} — the reading arc —
     * not by enum order, so this sheet and the member growth sheet tell the same
     * story left-to-right. A kind the arc does not name still gets a column; it
     * simply sorts last rather than vanishing.
     */
    private static void writeNarrativeTags(ExcelWorkbookBuilder wb, CohortGrowthAggregateDto data) {
        List<NarrativeKind> kinds = Arrays.stream(NarrativeKind.values())
                .sorted(Comparator.comparingInt(k -> {
                    int at = MyGrowthExportService.KIND_ORDER.indexOf(k.name());
                    return at < 0 ? Integer.MAX_VALUE : at;
                }))
                .toList();
        List<String> headers = new ArrayList<>(List.of("Pillar", "Members with approved narrative"));
        kinds.stream().map(k -> MyGrowthExportService.kindLabel(k.name())).forEach(headers::add);
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Narrative tags")
                .headers(headers.toArray(String[]::new));
        for (PillarAggregateDto p : data.pillars()) {
            List<Object> cells = new ArrayList<>(
                    List.of(p.pillarName(), p.membersWithApprovedNarrative()));
            kinds.stream().map(k -> p.kindCounts().get(k)).forEach(cells::add);
            s.row(cells.toArray());
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
