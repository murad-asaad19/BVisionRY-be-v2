package com.bvisionry.reporting.service;

import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.pipeline.entity.Question;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Shared formatting for assessment answers — produces the human-readable label
 * used in Team Insights exports (Excel Q&A and PDF Personal Information sections).
 *
 * <p>Centralised so both formats render LIKERT scales, MULTI_INPUT grids, etc.
 * the same way; otherwise scale hints would drift between sheets and the PDF.
 */
public final class AssessmentAnswerFormatter {

    private static final String MULTI_SELECT_DELIMITER = "|||";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AssessmentAnswerFormatter() {}

    /** Raw text the user provided — single-select label or free-text response. */
    public static String answerLabel(Answer a) {
        if (a.getSelectedValue() != null && !a.getSelectedValue().isBlank()) return a.getSelectedValue();
        if (a.getResponseText() != null && !a.getResponseText().isBlank()) return a.getResponseText();
        return "Unspecified";
    }

    /**
     * Column header for an answer cell. Personal-pillar questions get a bare
     * prompt (the prompt is self-explanatory); pillar questions are prefixed
     * with the pillar name so the header makes sense in a wide Q&amp;A table.
     * Scaled questions get a scale hint appended so a bare "4" / "68" in the
     * cell below stays interpretable.
     */
    public static String buildQuestionHeader(Question q) {
        String base = q.getPillar().getType() == PillarType.PERSONAL
                ? q.getPromptText()
                : q.getPillar().getName() + " — " + q.getPromptText();
        String scale = scaleHint(q);
        return scale == null ? base : base + " (" + scale + ")";
    }

    /**
     * Render an answer for display. Scaled questions pair the raw score with
     * the scale label/maximum so cells stay readable without the question config.
     */
    @SuppressWarnings("unchecked")
    public static String formatAnswer(Question q, Answer ans) {
        String raw = answerLabel(ans);
        if (raw == null || raw.isBlank() || "Unspecified".equals(raw)) return "";
        Map<String, Object> config = q.getConfigJson();
        return switch (q.getType()) {
            case LIKERT -> {
                Integer score = tryParseInt(raw);
                if (score == null) yield raw;
                List<String> labels = (config != null && config.get("labels") instanceof List<?> l)
                        ? l.stream().map(String::valueOf).toList()
                        : List.of();
                int idx = score - 1;
                if (idx >= 0 && idx < labels.size()) {
                    yield score + " — " + labels.get(idx);
                }
                yield raw;
            }
            case SELF_RATING -> {
                Integer score = tryParseInt(raw);
                if (score == null) yield raw;
                int max = readInt(config, "max", 100);
                yield score + " / " + max;
            }
            case MULTIPLE_CHOICE -> raw.contains(MULTI_SELECT_DELIMITER)
                    ? String.join(", ", raw.split("\\|\\|\\|"))
                    : raw;
            case MULTI_INPUT -> formatMultiInput(raw, config);
            default -> raw;
        };
    }

    @SuppressWarnings("unchecked")
    private static String scaleHint(Question q) {
        Map<String, Object> config = q.getConfigJson();
        return switch (q.getType()) {
            case LIKERT -> {
                int size = 5;
                if (config != null && config.get("labels") instanceof List<?> labels && !labels.isEmpty()) {
                    size = labels.size();
                }
                yield "1–" + size;
            }
            case SELF_RATING -> {
                int min = readInt(config, "min", 0);
                int max = readInt(config, "max", 100);
                yield min + "–" + max;
            }
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static String formatMultiInput(String raw, Map<String, Object> config) {
        List<String> columns = (config != null && config.get("columns") instanceof List<?> cols)
                ? cols.stream().map(String::valueOf).toList()
                : List.of();
        List<String> rowLabels = (config != null && config.get("rows") instanceof List<?> rs)
                ? rs.stream().map(String::valueOf).toList()
                : List.of();

        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
        JsonNode rowsNode = root.path("rows");
        if (!rowsNode.isArray() || rowsNode.isEmpty()) return raw;

        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rowsNode.size(); r++) {
            JsonNode rowNode = rowsNode.get(r);
            if (rowNode == null || rowNode.isEmpty()) continue;
            String rowLabel = r < rowLabels.size() ? rowLabels.get(r) : "Row " + (r + 1);

            StringBuilder cells = new StringBuilder();
            rowNode.fieldNames().forEachRemaining(key -> {
                String value = rowNode.path(key).asText("");
                if (value.isBlank()) return;
                Integer colIdx = tryParseInt(key);
                String colLabel = colIdx != null && colIdx >= 0 && colIdx < columns.size()
                        ? columns.get(colIdx)
                        : key;
                if (cells.length() > 0) cells.append("; ");
                cells.append(colLabel).append(": ").append(value);
            });
            if (cells.length() == 0) continue;

            if (out.length() > 0) out.append('\n');
            out.append(rowLabel).append(" — ").append(cells);
        }
        return out.length() == 0 ? raw : out.toString();
    }

    private static int readInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            Integer parsed = tryParseInt(s);
            if (parsed != null) return parsed;
        }
        return defaultValue;
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
