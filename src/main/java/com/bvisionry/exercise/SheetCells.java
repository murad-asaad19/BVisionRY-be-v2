package com.bvisionry.exercise;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.exercise.entity.ExerciseColumn;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rules of a SHEET's cell values — the sheet counterpart of what
 * {@link WorksheetBlocks} is for a worksheet's blocks. Shared by the member
 * submission path and the public link, so "filled in enough to submit" means
 * the same thing to a member and to an anonymous respondent.
 */
final class SheetCells {

    private SheetCells() {}

    /**
     * Values are kept as sent; keys that don't match a real column are dropped,
     * and so is an empty value. A LIST cell nobody typed into arrives as an
     * empty array — storing it would make the row differ from {@code {}}, so a
     * no-op autosave would count as a change and drag a REVIEWED sheet back
     * into the admin's queue. Absent and blank must persist identically.
     */
    static Map<String, Object> sanitize(Map<String, Object> cells, Set<String> columnIds) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (cells == null) {
            return clean;
        }
        cells.forEach((key, value) -> {
            if (!columnIds.contains(key) || value == null) {
                return;
            }
            if (value instanceof Collection<?> && ExerciseListEntries.isBlank(value)) {
                return;
            }
            clean.put(key, value);
        });
        return clean;
    }

    /** True when this cell value counts as "nothing was written here". */
    static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        // A LIST cell is a JSON array of entries — blank when none carries text.
        return value instanceof Collection<?>
                ? ExerciseListEntries.isBlank(value)
                : String.valueOf(value).isBlank();
    }

    /**
     * Sheet completeness: at least one row, and every required column filled in
     * every one of them.
     *
     * @param rows the live rows' cells, in display order
     */
    static void requireComplete(List<Map<String, Object>> rows,
                                Collection<ExerciseColumn> columns) {
        if (rows.isEmpty()) {
            throw new BadRequestException("Add at least one row before submitting.");
        }
        for (ExerciseColumn column : columns) {
            // Locked columns are admin-prefilled — nobody filling the sheet can
            // fix a blank one, so they are exempt from the required check.
            if (!column.isRequired() || column.isLocked()) {
                continue;
            }
            String key = column.getId().toString();
            for (Map<String, Object> cells : rows) {
                if (isBlank(cells == null ? null : cells.get(key))) {
                    throw new BadRequestException(
                            "\"" + column.getName() + "\" is required — fill it in every row before submitting.");
                }
            }
        }
    }
}
