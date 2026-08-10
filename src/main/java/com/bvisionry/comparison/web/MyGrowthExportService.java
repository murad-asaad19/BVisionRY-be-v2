package com.bvisionry.comparison.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.comparison.dto.FounderComparisonDto;
import com.bvisionry.comparison.dto.FounderComparisonDto.ComparisonPillarDto;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import com.bvisionry.comparison.repository.ComparisonReadRepository;

import lombok.RequiredArgsConstructor;

/**
 * Member-facing PDF + Excel of the growth report (redesign spec §5 layout,
 * §11: "My Growth includes PDF and Excel exports"). Renders whatever the
 * lifecycle state honestly has: {@code done} → the full comparison lead +
 * pillar-shift table + trajectory; {@code pending}/{@code none} → the
 * trajectory scores so far, no teased sections. Identity-scoped — the caller
 * IS the founder, so names are always their own.
 */
@Service
@RequiredArgsConstructor
public class MyGrowthExportService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC);

    private final ComparisonQueryService queries;
    private final ComparisonReadRepository reads;
    private final PdfRenderer pdfRenderer;

    /** Display-ready pillar row — formatting lives here so both exports agree. */
    public record PillarRow(String pillar, String before, String after, String shift,
                            String maturity) {}

    /** Display-ready trajectory point. */
    public record TrajectoryRow(String assessment, String evaluated, String score) {}

    /**
     * Display-ready approved narrative (spec §5 layout item 4). Only APPROVED
     * narratives ever reach here — {@code FounderComparisonDto.narratives} is
     * filtered in the service, so neither export can leak a draft.
     */
    public record NarrativeRow(String pillarName, String kindLabel, String body,
                               String closingAction, String approved) {}

    @Transactional(readOnly = true)
    public byte[] pdf(UUID userId) {
        MyComparisonResponse data = queries.myComparison(userId);
        String name = memberName(userId);
        FounderComparisonDto c = data.comparison();

        Context ctx = new Context();
        ctx.setVariable("memberName", name);
        ctx.setVariable("cohortName", data.cohortName());
        ctx.setVariable("state", data.state());
        ctx.setVariable("done", "done".equals(data.state()) && c != null);
        ctx.setVariable("pending", "pending".equals(data.state()));
        ctx.setVariable("reportDate", LocalDate.now().format(
                DateTimeFormatter.ofPattern("MMMM d, yyyy")));
        ctx.setVariable("trajectory", trajectoryRows(data));
        if (c != null) {
            ctx.setVariable("overallBefore", score(c.overallBefore()));
            ctx.setVariable("overallAfter", score(c.overallAfter()));
            ctx.setVariable("overallShift", shiftLabel(c.overallDelta(), c.overallBandLabel()));
            ctx.setVariable("baselineDate", instant(c.baselineEvaluatedAt()));
            ctx.setVariable("distanceDate", instant(c.distanceEvaluatedAt()));
            ctx.setVariable("computedAt", instant(c.computedAt()));
            ctx.setVariable("pillars", pillarRows(c.pillars()));
        }
        ctx.setVariable("narratives", narrativeRows(c));
        return pdfRenderer.renderTemplate("growth-report", ctx);
    }

    @Transactional(readOnly = true)
    public byte[] excel(UUID userId) {
        MyComparisonResponse data = queries.myComparison(userId);
        String name = memberName(userId);
        FounderComparisonDto c = data.comparison();

        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            ExcelWorkbookBuilder.SheetBuilder overview = wb.newSheet("Overview");
            overview.headers("Field", "Value");
            overview.labeledRow("Member", name);
            if (data.cohortName() != null) {
                overview.labeledRow("Cohort", data.cohortName());
            }
            overview.labeledRow("Report state", switch (data.state()) {
                case "done" -> "Distance comparison complete";
                case "pending" -> "Distance assessment pending";
                default -> "Latest results (no distance pair designated)";
            });
            if (c != null) {
                overview.labeledRow("Overall before", score(c.overallBefore()));
                overview.labeledRow("Overall after", score(c.overallAfter()));
                overview.labeledRow("Overall shift",
                        shiftLabel(c.overallDelta(), c.overallBandLabel()));
                overview.labeledRow("Baseline evaluated", instant(c.baselineEvaluatedAt()));
                overview.labeledRow("Distance evaluated", instant(c.distanceEvaluatedAt()));
                overview.labeledRow("Computed at", instant(c.computedAt()));
            }
            overview.autoSize();

            if (c != null) {
                ExcelWorkbookBuilder.SheetBuilder shifts = wb.newSheet("Pillar shifts");
                shifts.headers("Pillar", "Before", "After", "Shift", "Maturity");
                for (PillarRow p : pillarRows(c.pillars())) {
                    shifts.row(p.pillar(), p.before(), p.after(), p.shift(), p.maturity());
                }
                shifts.autoSize();

                List<NarrativeRow> narratives = narrativeRows(c);
                if (!narratives.isEmpty()) {
                    ExcelWorkbookBuilder.SheetBuilder sheet = wb.newSheet("Shift narratives");
                    sheet.headers("Pillar", "Kind", "Narrative", "Next step", "Approved");
                    for (NarrativeRow n : narratives) {
                        sheet.row(n.pillarName(), n.kindLabel(), n.body(),
                                n.closingAction() == null ? "—" : n.closingAction(), n.approved());
                    }
                    sheet.autoSize();
                }
            }

            ExcelWorkbookBuilder.SheetBuilder trajectory = wb.newSheet("Trajectory");
            trajectory.headers("Assessment", "Evaluated", "Overall score");
            for (TrajectoryRow t : trajectoryRows(data)) {
                trajectory.row(t.assessment(), t.evaluated(), t.score());
            }
            trajectory.autoSize();

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate growth Excel report", e);
        }
    }

    /* -------------------------------------------------------------- helpers */

    private String memberName(UUID userId) {
        String name = reads.userNames(Set.of(userId)).get(userId);
        return name == null || name.isBlank() ? "Member" : name;
    }

    private List<PillarRow> pillarRows(List<ComparisonPillarDto> pillars) {
        return pillars.stream().map(p -> new PillarRow(
                p.pillarName(),
                score(p.beforePct()),
                score(p.afterPct()),
                switch (p.state()) {
                    case "NEWLY_MEASURED" -> "Newly measured — no baseline";
                    case "NOT_REMEASURED" -> "Not re-measured";
                    default -> shiftLabel(p.delta(), p.bandLabel());
                },
                maturity(p.maturityBefore(), p.maturityAfter())))
                .toList();
    }

    private List<NarrativeRow> narrativeRows(FounderComparisonDto c) {
        if (c == null || c.narratives() == null) {
            return List.of();
        }
        return c.narratives().stream()
                .map(n -> new NarrativeRow(n.pillarName(), kindLabel(n.kind()), n.body(),
                        n.closingAction(),
                        // §7b: the approval stamp travels onto the export too.
                        n.approvedAt() == null ? "—" : "Approved " + instant(n.approvedAt())))
                .toList();
    }

    /** {@code CARRIED_FORWARD} → "Carried forward" — display only, the key is identity. */
    static String kindLabel(String kind) {
        if (kind == null || kind.isBlank()) {
            return "";
        }
        String words = kind.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private List<TrajectoryRow> trajectoryRows(MyComparisonResponse data) {
        return data.trajectory().stream()
                .map(t -> new TrajectoryRow(t.pipelineName(), instant(t.evaluatedAt()),
                        score(t.overallScore())))
                .toList();
    }

    private static String score(BigDecimal value) {
        return value == null ? "—" : value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String shiftLabel(BigDecimal delta, String bandLabel) {
        if (delta == null) {
            return "—";
        }
        String signed = (delta.signum() > 0 ? "+" : "")
                + delta.setScale(0, RoundingMode.HALF_UP).toPlainString();
        return bandLabel == null ? signed : signed + " · " + bandLabel;
    }

    private static String maturity(String before, String after) {
        if (before == null && after == null) {
            return "—";
        }
        return (before == null ? "—" : before) + " → " + (after == null ? "—" : after);
    }

    private static String instant(Instant value) {
        return value == null ? "—" : DATE.format(value);
    }
}
