package com.bvisionry.common.event;

import java.util.List;
import java.util.UUID;

/**
 * Communication domain events. They live in {@code common} so the publishing
 * and consuming slices don't have to import each other (the architecture rules
 * forbid new cross-feature dependencies) — same pattern as
 * {@link ProgramFlowEvents}, whose push handler is the closest sibling.
 */
public final class CommunicationEvents {

    private CommunicationEvents() {
    }

    /**
     * A cohort announcement was posted. Published by the {@code communication}
     * slice; consumed by the {@code notification} slice's push handler, which
     * fans it out to each recipient through the ordinary preference-respecting
     * dispatch (there is no second delivery path).
     *
     * @param announcementId the post itself — carried into the notification's
     *                       deep link so a recipient can report what they read
     * @param cohortName   display name of the target cohort
     * @param authorName   who broadcast it, for the notification title
     * @param body         the plain-text body as stored (a post carrying
     *                     markup never got this far)
     * @param recipientIds cohort members at send time, author excluded
     */
    public record AnnouncementPosted(UUID announcementId, UUID cohortId, String cohortName,
                                     String authorName, String body, List<UUID> recipientIds) {
    }
}
