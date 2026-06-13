package com.bvisionry.reporting.service;

import com.bvisionry.evaluation.entity.PillarEvaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Number / aggregation helpers shared by {@link TeamInsightsPdfService} and
 * {@link TeamInsightsExcelService}. Centralising these keeps the two exports
 * showing the same percentage rounding, the same em-dash placeholder for
 * empty inputs, and the same pillar ordering — when these drift, customers
 * notice that the PDF and Excel of the same report disagree.
 */
public final class TeamInsightsFormatter {

    private TeamInsightsFormatter() {}

    /**
     * One-decimal percentage like {@code "67.5%"}. Trailing zeros are stripped
     * so whole-number rates render as {@code "68%"} rather than {@code "68.0%"}.
     * Returns {@code "0%"} when the denominator is zero so callers don't have
     * to guard divide-by-zero at every site.
     */
    public static String formatRate(int numerator, int denominator) {
        if (denominator == 0) return "0%";
        BigDecimal rate = BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
        return rate.stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * Average of a list of percentages, rendered like {@link #formatRate}.
     * Empty / null input returns the em-dash placeholder so the cell isn't
     * silently blank when nothing has been evaluated yet.
     */
    public static String averagePercent(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return "—";
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
        return avg.stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * Whole-number percentage like {@code "68%"}. Used by the PDF export which
     * intentionally drops the decimal {@link #averagePercent} keeps — members
     * read the PDF, analysts read the Excel.
     */
    public static String wholePercent(BigDecimal value) {
        if (value == null) return "—";
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /**
     * Stable pillar id → name ordering keyed by case-insensitive name. Used
     * to drive both the row order in summary tables and the column order in
     * per-member breakdowns so reports read top-to-bottom in a predictable way.
     */
    public static Map<UUID, String> buildPillarOrder(
            Map<UUID, List<PillarEvaluation>> evalsBySubmission) {
        Map<UUID, String> order = new LinkedHashMap<>();
        evalsBySubmission.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(e -> e.getPillar().getName(), String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> order.putIfAbsent(e.getPillar().getId(), e.getPillar().getName()));
        return order;
    }
}
