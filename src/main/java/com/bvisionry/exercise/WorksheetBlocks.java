package com.bvisionry.exercise;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.exercise.entity.WorksheetBlock;
import com.bvisionry.exercise.entity.WorksheetBlockType;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Rules of a WORKSHEET's block document and its answers map — the worksheet
 * counterpart of what {@link ExerciseListEntries} is for LIST cells. All
 * value shapes are documented on {@link WorksheetBlockType}.
 */
final class WorksheetBlocks {

    private WorksheetBlocks() {}

    /**
     * Authoring-time shape check. Per-type config is validated only where a
     * missing piece would break members ({@code options}/{@code columns} ids —
     * answers and comment anchors key on them); prose fields stay free-form.
     */
    static void validate(List<WorksheetBlock> blocks) {
        if (blocks == null) {
            return;
        }
        Set<UUID> ids = new HashSet<>();
        for (WorksheetBlock block : blocks) {
            if (block == null || block.id() == null) {
                throw new BadRequestException("Every block needs an id.");
            }
            if (!ids.add(block.id())) {
                throw new BadRequestException("Duplicate block id: " + block.id());
            }
            if (block.type() == null) {
                throw new BadRequestException("Every block needs a type.");
            }
            switch (block.type()) {
                case CHECKBOXES -> requireIdentifiedEntries(block, "options");
                case TABLE -> requireIdentifiedEntries(block, "columns");
                case CONTENT, TEXT -> { }
            }
        }
    }

    /** {@code config.<key>}: a non-empty list of maps, each with a unique id. */
    private static void requireIdentifiedEntries(WorksheetBlock block, String key) {
        Object raw = block.config() != null ? block.config().get(key) : null;
        if (!(raw instanceof Collection<?> entries) || entries.isEmpty()) {
            throw new BadRequestException(
                    "A " + block.type() + " block needs at least one entry in config." + key + ".");
        }
        Set<String> ids = new HashSet<>();
        for (Object entry : entries) {
            Object id = entry instanceof Map<?, ?> map ? map.get("id") : null;
            if (id == null || String.valueOf(id).isBlank() || !ids.add(String.valueOf(id))) {
                throw new BadRequestException(
                        "Every entry of config." + key + " needs a unique id.");
            }
        }
    }

    /**
     * Once members hold answers against these blocks, ids and types are frozen
     * (an id change orphans answers and comment anchors; a type change makes
     * the stored answer unreadable) — and so are the existing option/column
     * ids inside a block's config, which member answers key on the same way.
     * Reorders, label/prompt edits, and NEW options/columns stay allowed —
     * same spirit as the sheet's column lock.
     */
    static void requireStructureCompatible(List<WorksheetBlock> existing,
                                           List<WorksheetBlock> updated) {
        Map<UUID, WorksheetBlockType> before = new HashMap<>();
        for (WorksheetBlock block : existing == null ? List.<WorksheetBlock>of() : existing) {
            before.put(block.id(), block.type());
        }
        Map<UUID, WorksheetBlockType> after = new HashMap<>();
        for (WorksheetBlock block : updated == null ? List.<WorksheetBlock>of() : updated) {
            after.put(block.id(), block.type());
        }
        if (!before.equals(after)) {
            throw new BadRequestException(
                    "This exercise has been assigned — blocks can no longer be added, removed "
                            + "or change type.");
        }
        for (WorksheetBlock block : existing == null ? List.<WorksheetBlock>of() : existing) {
            String key = switch (block.type()) {
                case CHECKBOXES -> "options";
                case TABLE -> "columns";
                case CONTENT, TEXT -> null;
            };
            if (key == null) {
                continue;
            }
            WorksheetBlock now = byId(updated, block.id()).orElseThrow();
            if (!entryLabels(now, key, "id").keySet()
                    .containsAll(entryLabels(block, key, "id").keySet())) {
                throw new BadRequestException(
                        "This exercise has been assigned — existing " + key + " can no longer "
                                + "be removed (member answers are stored against them).");
            }
        }
    }

    /**
     * A worksheet is submittable when it has any answer at all and every
     * required block carries one — the worksheet's version of the sheet's
     * "required column filled in every row" check. Shared by the member submit
     * and the public link.
     */
    static void requireComplete(Map<String, Object> rawAnswers, List<WorksheetBlock> rawBlocks) {
        Map<String, Object> answers = rawAnswers != null ? rawAnswers : Map.of();
        List<WorksheetBlock> blocks = rawBlocks != null ? rawBlocks : List.of();
        // A worksheet of only CONTENT blocks collects nothing — reading it IS
        // completing it, so an empty answers map must not block the submit.
        boolean collectsAnswers = blocks.stream()
                .anyMatch(b -> b.type() != WorksheetBlockType.CONTENT);
        if (collectsAnswers && answers.isEmpty()) {
            throw new BadRequestException("Fill in the worksheet before submitting.");
        }
        for (WorksheetBlock block : blocks) {
            if (!block.required() || block.type() == WorksheetBlockType.CONTENT) {
                continue;
            }
            if (isBlank(answers.get(block.id().toString()))) {
                throw new BadRequestException(
                        "\"" + block.label() + "\" is required — fill it in before submitting.");
            }
        }
    }

    static Optional<WorksheetBlock> byId(List<WorksheetBlock> blocks, UUID blockId) {
        if (blocks == null || blockId == null) {
            return Optional.empty();
        }
        return blocks.stream().filter(b -> blockId.equals(b.id())).findFirst();
    }

    /**
     * Keys that match no answer-collecting block are dropped, values are
     * coerced to their block type's documented shape (junk shapes are
     * dropped), and so are empty values and blank TABLE rows — absent and
     * blank must persist identically, or a no-op autosave would count as a
     * change and drag a REVIEWED worksheet back into the admin's queue
     * (same invariant as the sheet's cell sanitizer).
     */
    static Map<String, Object> sanitizeAnswers(Map<String, Object> answers,
                                               List<WorksheetBlock> blocks) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (answers == null) {
            return clean;
        }
        Map<String, WorksheetBlock> byId = new HashMap<>();
        for (WorksheetBlock block : blocks == null ? List.<WorksheetBlock>of() : blocks) {
            byId.put(block.id().toString(), block);
        }
        answers.forEach((key, value) -> {
            WorksheetBlock block = byId.get(key);
            Object typed = block == null ? null : typedValue(block, value);
            if (!isBlank(typed)) {
                clean.put(key, typed);
            }
        });
        return clean;
    }

    /**
     * The value coerced to its block type's shape, or null when it can't be —
     * a wrong-shaped value must not persist, or it would satisfy the
     * required-block submit gate while every UI renders it as unanswered.
     */
    private static Object typedValue(WorksheetBlock block, Object value) {
        return switch (block.type()) {
            case CONTENT -> null; // collects nothing — no legal answer shape
            case TEXT -> value instanceof String ? value : null;
            case CHECKBOXES -> value instanceof Collection<?> ids
                    ? ids.stream().filter(id -> id instanceof String s && !s.isBlank()).toList()
                    : null;
            case TABLE -> value instanceof Collection<?> rows
                    ? rows.stream()
                            .filter(row -> row instanceof Map<?, ?> cells && !rowBlank(cells))
                            .toList()
                    : null;
        };
    }

    /** A TABLE row (columnId → text) with no cell content. */
    private static boolean rowBlank(Map<?, ?> row) {
        return row.values().stream()
                .allMatch(cell -> cell == null || String.valueOf(cell).isBlank());
    }

    /** "No answer": null, blank string, or a collection with no substance. */
    static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().allMatch(entry -> {
                if (entry == null) {
                    return true;
                }
                // A TABLE row is a map columnId → text; blank when every cell is.
                if (entry instanceof Map<?, ?> row) {
                    return rowBlank(row);
                }
                return String.valueOf(entry).isBlank();
            });
        }
        return String.valueOf(value).isBlank();
    }

    /**
     * One block's answer as human-readable text — for comment snapshots and
     * the AI narrative. Ids are resolved to their labels (a raw option id in a
     * snapshot is noise nobody can read back).
     */
    static String answerText(WorksheetBlock block, Object value) {
        if (isBlank(value)) {
            return "";
        }
        return switch (block.type()) {
            case CONTENT -> "";
            case TEXT -> String.valueOf(value);
            case CHECKBOXES -> {
                Map<String, String> labels = entryLabels(block, "options", "label");
                yield joinTexts(value, id -> labels.getOrDefault(id, id));
            }
            case TABLE -> {
                Map<String, String> names = entryLabels(block, "columns", "name");
                if (!(value instanceof Collection<?> rows)) {
                    yield String.valueOf(value);
                }
                StringBuilder text = new StringBuilder();
                for (Object rawRow : rows) {
                    if (!(rawRow instanceof Map<?, ?> row)) {
                        continue;
                    }
                    StringBuilder line = new StringBuilder();
                    // Iterate the CONFIGURED columns so cells come out in
                    // column order, not the answer map's insertion order.
                    for (Map.Entry<String, String> col : names.entrySet()) {
                        Object cell = row.get(col.getKey());
                        if (cell == null || String.valueOf(cell).isBlank()) {
                            continue;
                        }
                        if (!line.isEmpty()) {
                            line.append(" | ");
                        }
                        line.append(col.getValue()).append(": ").append(cell);
                    }
                    if (!line.isEmpty()) {
                        if (!text.isEmpty()) {
                            text.append("\n");
                        }
                        text.append(line);
                    }
                }
                yield text.toString();
            }
        };
    }

    /** config.&lt;key&gt; entries as id → &lt;labelKey&gt;, in configured order. */
    static Map<String, String> entryLabels(WorksheetBlock block, String key,
                                                   String labelKey) {
        Map<String, String> labels = new LinkedHashMap<>();
        Object raw = block.config() != null ? block.config().get(key) : null;
        if (raw instanceof Collection<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("id") != null) {
                    Object label = map.get(labelKey);
                    labels.put(String.valueOf(map.get("id")),
                            label == null ? String.valueOf(map.get("id")) : String.valueOf(label));
                }
            }
        }
        return labels;
    }

    private static String joinTexts(Object value, java.util.function.UnaryOperator<String> resolve) {
        if (!(value instanceof Collection<?> collection)) {
            return resolve.apply(String.valueOf(value));
        }
        return collection.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .filter(id -> !id.isBlank())
                .map(resolve)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
