package com.bvisionry.catalog.web;

import com.bvisionry.common.exception.ResourceNotFoundException;

/**
 * Thrown when a course slug does not resolve to a published catalog course.
 * Extends {@link ResourceNotFoundException} so the global exception handler
 * translates it to a 404 response automatically.
 */
public class CourseNotFoundException extends ResourceNotFoundException {

    private final String slug;

    public CourseNotFoundException(String slug) {
        super("Course", "slug=" + slug);
        this.slug = slug;
    }

    public String getSlug() {
        return slug;
    }
}
