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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.comparison.domain.NarrativeKind;
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
     * Display-ready approved narrative (spec §5 layout item 4, §2 breakdown).
     * Only APPROVED narratives ever reach here —
     * {@code FounderComparisonDto.narratives} is filtered in the service, so
     * neither export can leak a draft.
     */
    public record NarrativeRow(String pillarName, String scoreLine, List<NarrativeGroup> groups,
                               String closingAction) {}

    /** One observation of the breakdown — or the whole of a pre-V189 narrative. */
    public record NarrativeItemRow(String kindLabel, String text) {}

    /**
     * Every observation of one kind, under one heading (spec §2 presentation,
     * operator review 2026-08-19).
     *
     * <p>The model routinely returns several observations sharing a kind, and
     * rendering each with its own label repeated "RESOLVED" three times down the
     * page — the classification read as noise rather than structure. One heading,
     * bullets underneath.
     *
     * @param shift the before → after move the kind describes, which is what
     *        makes the label legible: "Carried forward" is ambiguous on its own,
     *        {@code Strength → Strength} is not
     * @param gloss the same thing as a sentence, for readers who want it spelled out
     */
    public record NarrativeGroup(String kindLabel, String shift, String gloss,
                                 List<String> texts) {}

    /**
     * What each kind IS. Every kind is defined in the prompt as exactly one
     * transition between a strength and a growth edge, so the export states the
     * transition rather than leaving the reader to infer it from a bare tag.
     * Same vocabulary and same wording as the web (`KIND_SHIFT` in
     * {@code narrative-types.ts}) — the member reads both.
     */
    private static final Map<String, String[]> KIND_META = Map.of(
            "NEW", new String[] {"Not present → Strength",
                    "A strength in the later reading with no earlier equivalent."},
            // The ONLY kind whose "after" is two outcomes: the prompt defines
            // RESOLVED as a growth edge "absent from AFTER, OR now appears there
            // as a strength". Naming only the flattering half would overstate
            // what the evidence shows.
            "RESOLVED", new String[] {"Growth edge → Strength or gone",
                    "A growth edge named earlier that no longer holds them back — it now "
                            + "reads as a strength, or has stopped appearing altogether."},
            // No arrow where nothing moved: `X → X` made a non-event look like a
            // shift. An arrow now always means something actually changed.
            "CARRIED_FORWARD", new String[] {"Strength · held",
                    "A strength they had before and still have."},
            "PERSISTED", new String[] {"Growth edge · still open",
                    "A growth edge that is still an active edge."},
            "FADED", new String[] {"Strength → Not present",
                    "A strength named earlier that no longer appears."},
            // The two negative moves (V202). Both MOVE, so both keep an arrow —
            // the state on either side is genuinely different.
            "EMERGED", new String[] {"Not present → Growth edge",
                    "A growth edge in the later reading with no earlier equivalent."},
            "REGRESSED", new String[] {"Strength → Growth edge",
                    "A strength they had before that now reads as a growth edge."});

    /**
     * Reading order: what they gained, resolved, held, still carry, newly picked
     * up, let slip, lost. Fixed rather than the model's order, so the same kind
     * sits in the same place on every pillar and the seven read as one arc —
     * the same arc `NARRATIVE_KIND_ORDER` gives the web.
     */
    static final List<String> KIND_ORDER = List.of("NEW", "RESOLVED",
            "CARRIED_FORWARD", "PERSISTED", "EMERGED", "REGRESSED", "FADED");

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
        // Spec §3: the overall summary leads the report. Approved-only already,
        // straight off the member payload.
        ctx.setVariable("growthSummary", c == null ? null : c.growthSummary());
        ctx.setVariable("growthSummaryItems", summaryItemRows(c));
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
                if (c.growthSummary() != null) {
                    overview.labeledRow("Growth summary", c.growthSummary());
                }
                // The V193 breakdown: one labelled row per KIND, so a kind with
                // three observations is one row of three bullets rather than
                // three rows repeating the same label.
                for (NarrativeGroup group : summaryItemRows(c)) {
                    overview.labeledRow(
                            "Growth summary · " + kindHeader(group.kindLabel()),
                            group.texts().stream().map(t -> "• " + t)
                                    .collect(Collectors.joining("\n\n")));
                }
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
                    writeNarrativeSheet(wb, narratives);
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

    /**
     * The overall summary's breakdown (V193), display-ready. Empty on a
     * pre-V193 summary — that one is a paragraph, rendered as {@code
     * growthSummary} instead, so the two never both appear.
     */
    private static List<NarrativeGroup> summaryItemRows(FounderComparisonDto c) {
        if (c == null || c.growthSummaryItems() == null) {
            return List.of();
        }
        return groupByKind(c.growthSummaryItems().stream()
                .map(i -> new NarrativeItemRow(i.kind(), i.text()))
                .toList());
    }

    /**
     * One group per kind, in {@link #KIND_ORDER}, carrying every observation of
     * that kind. Unknown kinds — a hand-edited row, or one newer than this build
     * — keep a group of their own at the end with no transition rather than
     * being dropped or given an invented one.
     *
     * @param items raw observations whose {@code kindLabel} is still the KEY
     *        ({@code CARRIED_FORWARD}), not the display label
     */
    private static List<NarrativeGroup> groupByKind(List<NarrativeItemRow> items) {
        Map<String, List<String>> byKind = new LinkedHashMap<>();
        for (NarrativeItemRow item : items) {
            byKind.computeIfAbsent(item.kindLabel(), k -> new ArrayList<>()).add(item.text());
        }
        List<String> order = new ArrayList<>(KIND_ORDER.stream().filter(byKind::containsKey).toList());
        byKind.keySet().stream().filter(k -> !KIND_ORDER.contains(k)).forEach(order::add);

        return order.stream().map(kind -> {
            String[] meta = KIND_META.get(kind);
            return new NarrativeGroup(kindLabel(kind),
                    meta == null ? null : meta[0],
                    meta == null ? null : meta[1],
                    byKind.get(kind));
        }).toList();
    }

    /**
     * One row per PILLAR, one column per kind.
     *
     * <p>The long shape (a row per observation) repeated the pillar name on
     * every row and repeated the closing action — which is per pillar, not per
     * observation — beside each one, so a breakdown read as one near-duplicate
     * row per kind. Wide, the pillar is the row key the reader scans
     * for and each kind is answerable at a glance across pillars.
     *
     * <p>A pillar may carry more than one observation of a kind; those share
     * the cell, blank-line separated. Columns are the kinds in {@link #KIND_ORDER}
     * — the reading arc, not enum order — plus, defensively, any unknown kind
     * present in the data, so a corrupt
     * row loses its column heading rather than its text.
     */
    private static void writeNarrativeSheet(ExcelWorkbookBuilder wb, List<NarrativeRow> narratives) {
        // Column order is KIND_ORDER (the reading arc), not enum order, so the
        // sheet and the PDF tell the same story left-to-right.
        List<String> kinds = new ArrayList<>(KIND_ORDER.stream().map(
                MyGrowthExportService::kindLabel).toList());
        Arrays.stream(NarrativeKind.values()).map(k -> kindLabel(k.name()))
                .filter(label -> !kinds.contains(label)).forEach(kinds::add);
        narratives.stream().flatMap(n -> n.groups().stream())
                .map(NarrativeGroup::kindLabel)
                .filter(label -> !label.isBlank() && !kinds.contains(label))
                .forEach(kinds::add);

        List<String> headers = new ArrayList<>();
        headers.add("Pillar");
        headers.add("Before → after");
        // The header carries the transition, so a reader never has to guess
        // whether "Carried forward" is a strength or a gap. A legend sheet would
        // put the answer one navigation away from the question.
        kinds.stream().map(MyGrowthExportService::kindHeader).forEach(headers::add);
        headers.add("Next step");
        ExcelWorkbookBuilder.SheetBuilder sheet = wb.newSheet("Shift narratives")
                .headers(headers.toArray(String[]::new));

        for (NarrativeRow n : narratives) {
            List<Object> cells = new ArrayList<>();
            cells.add(n.pillarName());
            cells.add(n.scoreLine() == null ? "—" : n.scoreLine());
            for (String kind : kinds) {
                cells.add(n.groups().stream()
                        .filter(g -> kind.equals(g.kindLabel()))
                        .flatMap(g -> g.texts().stream())
                        // Several observations of one kind share the cell, one
                        // per line with a bullet so the cell reads as a list
                        // rather than a wall.
                        .map(text -> "• " + text)
                        .collect(Collectors.joining("\n\n")));
            }
            cells.add(n.closingAction() == null ? "—" : n.closingAction());
            sheet.row(cells.toArray());
        }
        sheet.autoSize();
    }

    /**
     * The two shapes a narrative can arrive in, flattened to one. A pre-V189
     * row has no {@code items} and its single kind + paragraph IS the whole
     * breakdown — rendering it as one observation keeps every export honest
     * without a data migration that would invent a split nobody made.
     */
    private List<NarrativeRow> narrativeRows(FounderComparisonDto c) {
        if (c == null || c.narratives() == null) {
            return List.of();
        }
        // The pillar's numbers, printed by CODE next to the AI's prose — the
        // narrative itself never states a number (operator decision 2026-08-19:
        // deterministic figures belong to the report layout, not the model).
        Map<UUID, ComparisonPillarDto> byDistancePillar = c.pillars() == null ? Map.of()
                : c.pillars().stream()
                        .filter(p -> p.distancePillarId() != null)
                        .collect(Collectors.toMap(ComparisonPillarDto::distancePillarId,
                                p -> p, (a, b) -> a));
        return c.narratives().stream()
                .map(n -> new NarrativeRow(n.pillarName(),
                        scoreLine(byDistancePillar.get(n.distancePillarId())),
                        groupByKind(n.items() == null || n.items().isEmpty()
                                ? List.of(new NarrativeItemRow(n.kind(), n.body()))
                                : n.items().stream()
                                        .map(i -> new NarrativeItemRow(i.kind(), i.text()))
                                        .toList()),
                        n.closingAction()))
                .toList();
    }

    /** "44 → 69 · +25 · High" — same formatting as the pillar table, or null when unmapped. */
    private static String scoreLine(ComparisonPillarDto pillar) {
        if (pillar == null || pillar.beforePct() == null || pillar.afterPct() == null) {
            return null;
        }
        return score(pillar.beforePct()) + " → " + score(pillar.afterPct())
                + " · " + shiftLabel(pillar.delta(), pillar.bandLabel());
    }

    /**
     * A column header that explains itself: "Carried forward (strength →
     * strength)". Falls back to the bare label for a kind with no known
     * transition.
     */
    private static String kindHeader(String label) {
        return KIND_ORDER.stream()
                .filter(k -> kindLabel(k).equals(label))
                .findFirst()
                .map(k -> label + " (" + KIND_META.get(k)[0].toLowerCase() + ")")
                .orElse(label);
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
