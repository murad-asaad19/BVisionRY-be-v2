package com.bvisionry.common.event;

import java.util.UUID;

/**
 * Survey domain events. They live in {@code common} so the publishing and
 * consuming slices don't have to import each other (the architecture rules
 * forbid new cross-feature dependencies) — same pattern as
 * {@link WorkshopEvents} and {@link ProgramFlowEvents}.
 */
public final class SurveyEvents {

    private SurveyEvents() {
    }

    /**
     * A post-assessment survey response was hard-deleted by an admin.
     * Published by the {@code survey} slice; consumed by the {@code reporting}
     * slice to evict the member-results cache entry that embedded the
     * response.
     */
    public record PostAssessmentResponseDeleted(UUID submissionId) {
    }
}
