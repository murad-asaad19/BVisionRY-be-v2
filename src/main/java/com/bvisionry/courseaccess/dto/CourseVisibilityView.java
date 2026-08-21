package com.bvisionry.courseaccess.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of the platform Course visibility screen (spec §3, §2.5). */
public record CourseVisibilityView(UUID courseId,
                                   String title,
                                   String category,
                                   Integer lessonsCount,
                                   String state,
                                   String visibility,
                                   String minTier,
                                   List<UUID> orgIds,
                                   Instant updatedAt,
                                   String updatedByName) {
}
