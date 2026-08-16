package com.bvisionry.pipeline.validation;

import com.bvisionry.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MaturityThresholdValidator {

    /**
     * The one platform default band set for a new STANDARD pillar, mirroring the
     * {@code pillars.maturity_thresholds_json} column default (V3).
     *
     * <p>Maturity bands are per-pillar CONFIGURABLE data — this validator
     * deliberately accepts any contiguous 1..N-band partition of 0-100 with
     * free-text labels, and customers run bespoke vocabularies. There is no
     * platform-wide band set; this is only where a pillar STARTS when the
     * request omits thresholds, applied in exactly one place
     * ({@code PillarService#create}) so the default cannot drift between
     * callers again.
     *
     * <p>Key order is not meaningful: the column is {@code jsonb}, which
     * normalises key order on write.
     */
    public static final Map<String, List<Integer>> PLATFORM_DEFAULT = Map.of(
            "Emerging", List.of(0, 59),
            "Strong", List.of(60, 79),
            "Elite", List.of(80, 100));

    public void validate(Map<String, List<Integer>> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) {
            throw new BadRequestException("Maturity thresholds cannot be empty");
        }
        int expectedStart = 0;
        var sortedEntries = thresholds.entrySet().stream()
                .sorted((a, b) -> a.getValue().get(0).compareTo(b.getValue().get(0)))
                .toList();

        for (var entry : sortedEntries) {
            String label = entry.getKey();
            // Reject characters that would break the JSON contract emitted to the AI,
            // the XML attributes used to describe results, or be invisible to admins.
            if (label == null || label.isBlank()) {
                throw new BadRequestException("Maturity label cannot be blank");
            }
            if (label.length() > 50) {
                throw new BadRequestException("Maturity label '" + label + "' exceeds 50 characters");
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (c == '"' || c == '|' || c == '<' || c == '>' || c == '\n' || c == '\r' || c == '\t') {
                    throw new BadRequestException("Maturity label '" + label + "' contains disallowed character");
                }
            }

            List<Integer> range = entry.getValue();
            if (range == null || range.size() != 2) {
                throw new BadRequestException("Threshold '" + label + "' must have exactly 2 values [min, max]");
            }
            int min = range.get(0);
            int max = range.get(1);
            if (min < 0 || max > 100 || min > max) {
                throw new BadRequestException("Threshold '" + label + "' has invalid range [" + min + ", " + max + "]");
            }
            if (min != expectedStart) {
                throw new BadRequestException("Thresholds have a gap or overlap at " + expectedStart);
            }
            expectedStart = max + 1;
        }
        if (expectedStart != 101) {
            throw new BadRequestException("Thresholds must cover the full range 0-100, but end at " + (expectedStart - 1));
        }
    }
}
