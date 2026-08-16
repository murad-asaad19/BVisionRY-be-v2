package com.bvisionry.comparison.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.common.excel.XlsxResponse;
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
 * trajectory scores so far, no teased sections. Serves three doors: the
 * member's own (names always shown — they are theirs), and the two staff
 * doors, where {@code showNames} follows the same masking convention as every
 * other export ({@code false} → the founder appears as "Member" on the
 * document AND in the filename; the handler, not this service, decides who
 * may pass {@code true} — see {@code ExportNameGuard}).
 *
 * <p>The shift narratives need no redaction pass: the AI prompt carries ONLY
 * the pillar name and the assessment text blocks (see
 * {@link ShiftNarrativeService}), so unlike the assessment narratives the
 * generated prose never addresses the founder by name. A coach hand-editing a
 * name into a body is the known ceiling of this masking.
 */
@Service
@RequiredArgsConstructor
public class MyGrowthExportService {

    /** Same masked label the other exports use ({@code MemberDisplayNameResolver}). */
    private static final String MASKED_LABEL = "Member";

    /** Zoned per request via {@link #zoneOrUtc} — the reader's wall-clock dates, not the server's. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy");

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
                               String closingAction) {}

    /**
     * @param staffVoice third-person copy for the two STAFF doors — "GROWTH
     *        REPORT" / "Where they were…" instead of "MY GROWTH" / "Where you
     *        were…". A document an admin generated ABOUT a member must not
     *        address the reader as its subject; the member's own door keeps the
     *        member voice verbatim. One flag through the one renderer, so the
     *        numbers can never fork between the voices.
     * @param zone the READER's zone for every rendered date (the {@code tz}
     *        request param) — the web UI formats browser-local, and a PDF that
     *        says "May 21" beside a screen that says "May 22" reads as a bug.
     */
    @Transactional(readOnly = true)
    public byte[] pdf(UUID userId, boolean showNames, boolean staffVoice, ZoneId zone) {
        MyComparisonResponse data = queries.myComparison(userId);
        String name = displayName(userId, showNames);
        FounderComparisonDto c = data.comparison();
        DateTimeFormatter dates = DATE.withZone(zone);

        Context ctx = new Context();
        ctx.setVariable("memberName", name);
        ctx.setVariable("cohortName", data.cohortName());
        ctx.setVariable("state", data.state());
        ctx.setVariable("done", "done".equals(data.state()) && c != null);
        ctx.setVariable("pending", "pending".equals(data.state()));
        ctx.setVariable("staffVoice", staffVoice);
        ctx.setVariable("reportDate", LocalDate.now(zone).format(
                DateTimeFormatter.ofPattern("MMMM d, yyyy")));
        ctx.setVariable("trajectory", trajectoryRows(data, dates));
        if (c != null) {
            ctx.setVariable("overallBefore", score(c.overallBefore()));
            ctx.setVariable("overallAfter", score(c.overallAfter()));
            ctx.setVariable("overallShift", shiftLabel(c.overallDelta(), c.overallBandLabel()));
            ctx.setVariable("baselineDate", instant(c.baselineEvaluatedAt(), dates));
            ctx.setVariable("distanceDate", instant(c.distanceEvaluatedAt(), dates));
            ctx.setVariable("computedAt", instant(c.computedAt(), dates));
            ctx.setVariable("pillars", pillarRows(c.pillars()));
        }
        ctx.setVariable("narratives", narrativeRows(c));
        return pdfRenderer.renderTemplate("growth-report", ctx);
    }

    /** Same {@code zone} contract as {@link #pdf}; the Excel copy is voice-neutral already. */
    @Transactional(readOnly = true)
    public byte[] excel(UUID userId, boolean showNames, ZoneId zone) {
        MyComparisonResponse data = queries.myComparison(userId);
        String name = displayName(userId, showNames);
        FounderComparisonDto c = data.comparison();
        DateTimeFormatter dates = DATE.withZone(zone);

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
                overview.labeledRow("Baseline evaluated", instant(c.baselineEvaluatedAt(), dates));
                overview.labeledRow("Distance evaluated", instant(c.distanceEvaluatedAt(), dates));
                overview.labeledRow("Computed at", instant(c.computedAt(), dates));
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
                    sheet.headers("Pillar", "Kind", "Narrative", "Next step");
                    for (NarrativeRow n : narratives) {
                        sheet.row(n.pillarName(), n.kindLabel(), n.body(),
                                n.closingAction() == null ? "—" : n.closingAction());
                    }
                    sheet.autoSize();
                }
            }

            ExcelWorkbookBuilder.SheetBuilder trajectory = wb.newSheet("Trajectory");
            trajectory.headers("Assessment", "Evaluated", "Overall score");
            for (TrajectoryRow t : trajectoryRows(data, dates)) {
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

    /**
     * Download filename for a growth export, from the founder's own name —
     * shared by the member's identity-scoped door and the two staff doors
     * (spec §11), so an admin holding three founders' reports can tell them
     * apart. Sanitised because a display name is user input. Masked exports
     * get the masked label here too — a filename outlives the download and
     * would otherwise leak the very name the document withholds.
     */
    public String reportFilename(UUID userId, String extension, boolean showNames) {
        return XlsxResponse.sanitizeFilename(displayName(userId, showNames).replace(' ', '_'))
                + "_Growth_Report." + extension;
    }

    /** Content-disposition shape shared by every PDF door here. */
    public static ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename, String mode) {
        String disposition = ("preview".equals(mode) ? "inline" : "attachment")
                + "; filename=\"" + filename + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private String displayName(UUID userId, boolean showNames) {
        if (!showNames) {
            return MASKED_LABEL;
        }
        String name = reads.userNames(Set.of(userId)).get(userId);
        return name == null || name.isBlank() ? MASKED_LABEL : name;
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
                        n.closingAction()))
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

    private List<TrajectoryRow> trajectoryRows(MyComparisonResponse data, DateTimeFormatter dates) {
        return data.trajectory().stream()
                .map(t -> new TrajectoryRow(t.pipelineName(), instant(t.evaluatedAt(), dates),
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

    private static String instant(Instant value, DateTimeFormatter dates) {
        return value == null ? "—" : dates.format(value);
    }

    /**
     * The {@code tz} request param → zone. Garbage falls back to UTC rather
     * than 400ing: the timezone is a rendering nicety, and a mistyped query
     * param must not cost anyone their report.
     */
    public static ZoneId zoneOrUtc(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
