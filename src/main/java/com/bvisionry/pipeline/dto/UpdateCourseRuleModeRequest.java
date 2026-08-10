package com.bvisionry.pipeline.dto;

import jakarta.validation.constraints.NotNull;

/** Spec §3: flip one AI rule between Suggest and Auto-assign. */
public record UpdateCourseRuleModeRequest(@NotNull String mode) {
}
