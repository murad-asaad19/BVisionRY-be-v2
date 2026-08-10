package com.bvisionry.common.scoringconfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Band documents for the platform "Scoring &amp; Labels" config (spec §7).
 *
 * <p>Lives in {@code common} because two slices must agree on the shape: the
 * {@code platform} slice edits and validates the documents, and the
 * {@code comparison} slice resolves shift bands at compute time (it reads the
 * stored JSON via raw SQL — the ArchUnit ratchet forbids a comparison→platform
 * import). Pure types + pure functions; no Spring, no repositories.
 *
 * <p>A band is {@code [min, max]} on integer cut-offs, with {@code null min} =
 * open low end (−∞) and {@code null max} = open high end (+∞). Resolution uses
 * the SORTED mins only ("the last band whose min ≤ value"), so a fractional
 * value between two integer cut-offs (e.g. 4.5 between 4 and 5) still lands in
 * a band. Keys are stable identity ({@code decline}, {@code band_1}, …);
 * labels are presentation and may be renamed freely.
 */
public final class ScoringBands {

    /** platform_settings key for the mindset-shift bands document. */
    public static final String SHIFT_BANDS_KEY = "scoring.shift_bands";

    private ScoringBands() {
    }

    public record Band(String key, String label, Integer min, Integer max) {
    }

    /** Shipped defaults (spec §5): &lt;0 Decline · 0–4 Low · 5–14 Moderate · ≥15 High. */
    public static List<Band> defaultShiftBands() {
        return List.of(
                new Band("decline", "Decline", null, -1),
                new Band("band_1", "Low", 0, 4),
                new Band("band_2", "Moderate", 5, 14),
                new Band("band_3", "High", 15, null));
    }

    /**
     * The band {@code value} falls into: the last band (by ascending min,
     * null-min first) whose lower cut-off is ≤ {@code value}. Returns null only
     * when the list is empty or the value is below every closed lower bound —
     * a validated document (open low end) always resolves.
     */
    public static Band resolve(List<Band> bands, BigDecimal value) {
        Band match = null;
        for (Band b : sortedByMin(bands)) {
            if (b.min() == null || value.compareTo(BigDecimal.valueOf(b.min())) >= 0) {
                match = b;
            }
        }
        return match;
    }

    /**
     * Structural validation for shift bands: ≥1 band, unique non-blank keys,
     * non-blank labels, exactly one open low end and one open high end, and
     * contiguity ({@code next.min == prev.max + 1}). Returns field errors keyed
     * {@code bands[i].field}; empty = valid.
     */
    public static Map<String, String> validateShiftBands(List<Band> bands) {
        Map<String, String> errors = common(bands);
        if (!errors.isEmpty() || bands.isEmpty()) {
            return errors;
        }
        List<Band> sorted = sortedByMin(bands);
        if (sorted.get(0).min() != null) {
            errors.put("bands", "The lowest band must be open-ended (no minimum) to cover declines.");
        }
        if (sorted.get(sorted.size() - 1).max() != null) {
            errors.put("bands", "The highest band must be open-ended (no maximum).");
        }
        checkContiguity(sorted, errors);
        return errors;
    }

    /**
     * Structural validation for percent bands (participation): ≥1 band, unique
     * non-blank keys, non-blank labels, closed cut-offs covering exactly 0–100,
     * contiguous. Returns field errors; empty = valid.
     */
    public static Map<String, String> validatePercentBands(List<Band> bands) {
        Map<String, String> errors = common(bands);
        if (!errors.isEmpty() || bands.isEmpty()) {
            return errors;
        }
        for (int i = 0; i < bands.size(); i++) {
            if (bands.get(i).min() == null) {
                errors.put("bands[" + i + "].min", "Minimum is required.");
            }
            if (bands.get(i).max() == null) {
                errors.put("bands[" + i + "].max", "Maximum is required.");
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        List<Band> sorted = sortedByMin(bands);
        if (sorted.get(0).min() != 0) {
            errors.put("bands", "Bands must start at 0.");
        }
        if (sorted.get(sorted.size() - 1).max() != 100) {
            errors.put("bands", "Bands must end at 100.");
        }
        checkContiguity(sorted, errors);
        return errors;
    }

    private static Map<String, String> common(List<Band> bands) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (bands == null || bands.isEmpty()) {
            errors.put("bands", "At least one band is required.");
            return errors;
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < bands.size(); i++) {
            Band b = bands.get(i);
            if (b.key() == null || b.key().isBlank()) {
                errors.put("bands[" + i + "].key", "Key is required.");
            } else if (!keys.add(b.key())) {
                errors.put("bands[" + i + "].key", "Duplicate key '" + b.key() + "'.");
            }
            if (b.label() == null || b.label().isBlank()) {
                errors.put("bands[" + i + "].label", "Label is required.");
            }
            if (b.min() != null && b.max() != null && b.min() > b.max()) {
                errors.put("bands[" + i + "].min", "Minimum must not exceed maximum.");
            }
        }
        return errors;
    }

    private static void checkContiguity(List<Band> sorted, Map<String, String> errors) {
        for (int i = 1; i < sorted.size(); i++) {
            Integer prevMax = sorted.get(i - 1).max();
            Integer min = sorted.get(i).min();
            if (prevMax == null || min == null || min != prevMax + 1) {
                errors.put("bands", "Bands must be contiguous (each band starts where the previous ends).");
                return;
            }
        }
    }

    private static List<Band> sortedByMin(List<Band> bands) {
        List<Band> sorted = new ArrayList<>(bands);
        sorted.sort((a, b) -> {
            if (a.min() == null && b.min() == null) {
                return 0;
            }
            if (a.min() == null) {
                return -1;
            }
            if (b.min() == null) {
                return 1;
            }
            return Integer.compare(a.min(), b.min());
        });
        return sorted;
    }
}
