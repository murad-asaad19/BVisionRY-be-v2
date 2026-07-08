package com.bvisionry.programflow.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Board drag-and-drop: destination module + index within its task list. */
public record MoveTaskRequest(@NotNull UUID moduleId, @Min(0) int position) {
}
