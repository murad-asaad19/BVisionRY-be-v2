package com.bvisionry.workshops.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

/** The complete new order (all ids of the parent, in display order). */
public record ReorderRequest(
        @NotEmpty List<UUID> orderedIds) {
}
