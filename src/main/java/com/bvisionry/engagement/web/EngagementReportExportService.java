package com.bvisionry.engagement.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.engagement.dto.EngagementRecordResponse.CategoryScore;
import com.bvisionry.engagement.dto.EngagementRecordResponse.CohortParticipationResponse;
import com.bvisionry.engagement.dto.EngagementRecordResponse.MemberParticipation;
import com.bvisionry.engagement.repository.EngagementReadRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The Engagement tab as a file: one row per cohort member with their
 * participation score, band and per-category done/total/percent, as a branded
 * PDF or an Excel workbook. The numbers come from the SAME read the tab
 * renders ({@link EngagementService#cohortParticipation(UUID, UUID)}), so the
 * document can never quote a figure the operator did not see on screen.
 *
 * <p>Mirrors the insight/workshop exports: {@code showNames=false} masks
 * identities as positional labels ("Member 1", …) that stay stable across PDF
 * and Excel because both walk the same name-ordered roster.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EngagementReportExportService {

    private static final DateTimeFormatter REPORT_DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final EngagementService engagement;
    private final EngagementReadRepository reads;
    private final PdfRenderer pdfRenderer;

    /** One member's row; {@code displayName} is already masked when names are hidden. */
    public record MemberRow(
            String displayName,
            BigDecimal score,
            String bandLabel,
            List<CategoryScore> categories) {
    }

    @Transactional(readOnly = true)
    public byte[] pdf(UUID orgId, UUID cohortId, boolean showNames) {
        CohortParticipationResponse data = engagement.cohortParticipation(cohortId, orgId);
        Context ctx = new Context();
        ctx.setVariable("cohortName", cohortName(orgId, cohortId));
        ctx.setVariable("reportDate", LocalDate.now().format(REPORT_DATE));
        ctx.setVariable("showNames", showNames);
        ctx.setVariable("columns", columns(data.members()));
        ctx.setVariable("rows", toRows(data.members(), showNames));
        ctx.setVariable("average", data.average());
        byte[] pdf = pdfRenderer.renderTemplate("engagement-report", ctx);
        log.info("Generated engagement report PDF for cohort {} ({} bytes)", cohortId, pdf.length);
        return pdf;
    }

    @Transactional(readOnly = true)
    public byte[] excel(UUID orgId, UUID cohortId, boolean showNames) {
        CohortParticipationResponse data = engagement.cohortParticipation(cohortId, orgId);
        List<MemberRow> rows = toRows(data.members(), showNames);
        List<CategoryScore> columns = columns(data.members());
        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(wb, columns, rows, data.average());
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate engagement report Excel", e);
        }
    }

    /** The cohort's name for export filenames, tenancy-checked like the exports themselves. */
    @Transactional(readOnly = true)
    public String cohortName(UUID orgId, UUID cohortId) {
        return reads.cohort(cohortId)
                .filter(c -> reads.assignedToOrg(cohortId, orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()))
                .name();
    }

    // ------------------------------------------------------------ data

    /**
     * Roster order is the read's own (by name, then email), and the positional
     * mask follows it — deterministic, so "Member N" is the same person on the
     * PDF and in the workbook.
     */
    private static List<MemberRow> toRows(List<MemberParticipation> members, boolean showNames) {
        List<MemberRow> rows = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            MemberParticipation m = members.get(i);
            rows.add(new MemberRow(
                    showNames ? m.memberName() : "Member " + (i + 1),
                    m.participation().score(),
                    m.participation().bandLabel(),
                    m.participation().categories()));
        }
        return rows;
    }

    /**
     * The category columns. Every member is scored against the one live config,
     * so all rows carry the same categories in the same order — the first row
     * IS the header.
     */
    private static List<CategoryScore> columns(List<MemberParticipation> members) {
        return members.isEmpty() ? List.of() : members.get(0).participation().categories();
    }

    // ------------------------------------------------------------ excel

    /** One sheet: header row, one row per member, the cohort average last. */
    private static void writeSheet(ExcelWorkbookBuilder wb, List<CategoryScore> columns,
                                   List<MemberRow> rows, BigDecimal average) {
        List<String> headers = new ArrayList<>();
        headers.add("Founder");
        for (CategoryScore c : columns) {
            headers.add(c.label() + " done");
            headers.add(c.label() + " total");
            headers.add(c.label() + " %");
        }
        headers.add("Participation score %");
        headers.add("Band");

        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Engagement")
                .headers(headers.toArray(String[]::new));
        for (MemberRow row : rows) {
            List<Object> cells = new ArrayList<>();
            cells.add(row.displayName());
            for (CategoryScore c : row.categories()) {
                cells.add(c.done());
                cells.add(c.total());
                // A null pct is a category with no denominator yet — blank, not zero.
                cells.add(c.pct());
            }
            cells.add(row.score());
            cells.add(row.bandLabel());
            s.row(cells.toArray());
        }
        if (average != null) {
            Object[] footer = new Object[headers.size()];
            footer[0] = "Cohort average";
            footer[headers.size() - 2] = average;
            s.row(footer);
        }
        s.autoSize();
    }
}
