package com.bvisionry.exercise;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A LIST cell's value: a JSON array of {@code {id, text}} entries. The id is
 * minted by the client and is what review comments anchor to, so it must
 * survive edits to the entry's text and to its siblings.
 *
 * <p>Values written before V187 were plain strings; that migration lifted them
 * into objects, so readers here handle exactly one shape.
 */
final class ExerciseListEntries {

    private ExerciseListEntries() {}

    /** The entries of a LIST cell value, or empty when it is not a list. */
    static List<Map<?, ?>> of(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .filter(Map.class::isInstance)
                .<Map<?, ?>>map(entry -> (Map<?, ?>) entry)
                .toList();
    }

    static String textOf(Map<?, ?> entry) {
        Object text = entry.get("text");
        return text == null ? "" : String.valueOf(text);
    }

    /** The text of one entry by id — empty when that entry is gone. */
    static Optional<String> textById(Object value, String entryId) {
        return of(value).stream()
                .filter(entry -> entryId.equals(String.valueOf(entry.get("id"))))
                .findFirst()
                .map(ExerciseListEntries::textOf);
    }

    /** Every entry's text, joined for a whole-cell snapshot. */
    static String joinedText(Object value) {
        return of(value).stream()
                .map(ExerciseListEntries::textOf)
                .filter(text -> !text.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    /** True when no entry carries text — a LIST cell's version of "empty". */
    static boolean isBlank(Object value) {
        return of(value).stream().allMatch(entry -> textOf(entry).isBlank());
    }
}
