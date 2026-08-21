package com.bvisionry.courseaccess.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Assign a course to an organization (spec §3), reusing the exercise audience
 * dialog's semantics.
 *
 * @param audience {@code ORG} writes ONE org_course_rules row that covers every
 *        current and future member; {@code MEMBERS} writes a DIRECT enrollment
 *        per selected member.
 */
public record AssignCourseRequest(@NotNull UUID courseId,
                                  @NotNull String audience,
                                  List<UUID> memberIds,
                                  boolean required,
                                  Instant deadline) {

    public static final String AUDIENCE_ORG = "ORG";
    public static final String AUDIENCE_MEMBERS = "MEMBERS";
}
