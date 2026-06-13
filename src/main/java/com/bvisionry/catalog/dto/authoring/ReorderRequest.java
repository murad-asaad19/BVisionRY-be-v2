package com.bvisionry.catalog.dto.authoring;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * Ordered list of IDs for drag-and-drop reorder operations.
 */
public record ReorderRequest(@NotNull List<String> ids) {
}
