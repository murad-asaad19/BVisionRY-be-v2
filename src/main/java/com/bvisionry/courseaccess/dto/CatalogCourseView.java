package com.bvisionry.courseaccess.dto;

import java.util.UUID;

/** A course the member's org may browse — PUBLISHED and visible (spec §3). */
public record CatalogCourseView(UUID courseId, String title, String slug,
                                String category, String level, Integer lessonsCount) {
}
