package com.bvisionry.courseaccess.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Spec §11: the required flag is mutable post-assignment ("Convert to
 * optional" / "Convert to required" on the course row menu). {@code source}
 * names WHICH row of the Courses tab is being edited.
 */
public record UpdateOrgCourseRequest(@NotNull String source, @NotNull Boolean required, Instant deadline) {
}
