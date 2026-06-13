package com.bvisionry.enrollment.dto;

/**
 * Full content payload for a single lesson (body + media URLs),
 * returned by {@code GET /api/v1/courses/{slug}/content/{contentId}}.
 */
public record LessonContentDto(
        String id,
        String title,
        String type,
        /** Tiptap JSON document (PAGE / slide types). May be null. */
        String body,
        /** HLS or direct video URL (VIDEO type). May be null. */
        String videoUrl,
        /** PDF or other asset URL (PDF / DOCUMENT types). May be null. */
        String assetUrl,
        int durationMin,
        boolean preview) {
}
