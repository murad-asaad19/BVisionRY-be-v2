package com.bvisionry.enrollment.dto;

import java.util.List;

/**
 * The course curriculum bundled with the viewer's progress, returned by
 * {@code GET /api/v1/courses/{slug}/learn}.
 */
public record LearnViewDto(
        String courseId,
        String slug,
        String title,
        EnrollmentDto enrollment,
        List<SectionView> sections) {

    public record SectionView(
            String id,
            String title,
            int sequence,
            List<LessonView> lessons) {
    }

    public record LessonView(
            String id,
            String title,
            String type,
            int durationMin,
            boolean preview,
            boolean completed) {
    }
}
