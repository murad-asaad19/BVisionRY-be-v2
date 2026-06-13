package com.bvisionry.enrollment.web;

import com.bvisionry.common.exception.ResourceNotFoundException;

/**
 * Thrown when a user attempts to access the learn view without an active enrollment.
 * Extends {@link ResourceNotFoundException} so the global handler maps it to 404.
 */
public class NotEnrolledException extends ResourceNotFoundException {

    public NotEnrolledException(String slug) {
        super("Enrollment", "course=" + slug);
    }
}
