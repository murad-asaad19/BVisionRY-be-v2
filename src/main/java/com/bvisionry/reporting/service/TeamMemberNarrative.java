package com.bvisionry.reporting.service;

import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.PillarEvaluation;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * One member's already-redacted narrative for the Team Insights exports. Both the
 * PDF and Excel writers consume this view instead of touching {@link OverallSummary}
 * / {@link PillarEvaluation} directly, so the member's name is scrubbed out of the
 * prose in exactly one place ({@link #from}). A narrative field added to this
 * record is naturally redacted for every export because it can only be populated
 * here — the writers never see the raw entity text.
 *
 * <p>Nullness is faithful to the source (a null narrative stays null) so each
 * writer can apply its own presentation. The one exception is a member with no
 * {@link OverallSummary}: its summary fields become empty rather than null so the
 * exports render blank cells/sections exactly as they did before.
 */
record TeamMemberNarrative(
        String summaryNarrative,
        List<String> strengths,
        List<String> developmentAreas,
        List<PillarNarrative> pillars) {

    /** Redacted per-pillar narrative, ordered by pillar name (case-insensitive). */
    record PillarNarrative(
            String pillarName,
            BigDecimal score,
            String maturityLabel,
            List<String> whatsWorking,
            List<String> whatCanImprove) {}

    /**
     * Build the redacted view for one member from their overall summary (may be
     * {@code null}) and pillar evaluations. {@code redactor} scrubs the member's
     * name from every free-text field; pass a {@link NarrativeRedactor#disabled()}
     * redactor on the names-shown path.
     */
    static TeamMemberNarrative from(OverallSummary summary,
                                    List<PillarEvaluation> evaluations,
                                    NarrativeRedactor redactor) {
        String summaryNarrative;
        List<String> strengths;
        List<String> developmentAreas;
        if (summary == null) {
            summaryNarrative = "";
            strengths = List.of();
            developmentAreas = List.of();
        } else {
            summaryNarrative = redactor.redact(summary.getSummaryNarrative());
            strengths = redactor.redact(summary.getStrengths());
            developmentAreas = redactor.redact(summary.getDevelopmentAreas());
        }

        List<PillarNarrative> pillars = evaluations.stream()
                .sorted(Comparator.comparing(e -> e.getPillar().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(e -> new PillarNarrative(
                        e.getPillar().getName(),
                        e.getScorePercentage(),
                        e.getMaturityLabel(),
                        redactor.redact(e.getAiWhatsWorking()),
                        redactor.redact(e.getAiWhatCanImprove())))
                .toList();

        return new TeamMemberNarrative(summaryNarrative, strengths, developmentAreas, pillars);
    }
}
