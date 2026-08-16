package com.bvisionry.common.event;

import java.util.UUID;

/**
 * Membership domain events, published by the {@code organization} slice and
 * consumed by {@code assessment} (auto-assign), {@code programflow}
 * (cohort auto-enroll) and {@code notification} (the admin push). They live in
 * {@code common} so none of those four has to import another (the architecture
 * rules forbid new cross-feature dependencies) — same pattern as
 * {@link ProgramFlowEvents}, and the same reason they carry plain ids and a
 * display string rather than entities.
 *
 * <p>Every listener is expected to be {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)}: these fire on writes that can still roll back, and a rolled-back
 * membership must leave behind no assignment, no enrollment and no notification.
 */
public final class OrganizationEvents {

    private OrganizationEvents() {
    }

    /**
     * A user *first joined* an organization (invitation accept, join-link
     * accept, or any future first-join flow).
     *
     * <p>Deliberately NOT fired on status reinstatement (SUSPENDED/DEACTIVATED →
     * ACTIVE). A reinstated member keeps the assignments they had before
     * suspension; auto-assign does not retroactively cover the suspension
     * window. Admins explicitly opt in to "give them this pipeline now" by
     * re-running the assign flow if needed.
     *
     * @param userType carried on the event so listeners can filter without
     *                 re-loading the user
     */
    public record MemberJoined(UUID organizationId, UUID userId, String userType) {
    }

    /**
     * A member was moved from one organization to another via the super-admin
     * move flow. From the target org's perspective the member is a fresh
     * arrival, so any auto-assign rule that matches their userType should fire
     * just as it would on a first join.
     *
     * <p>Distinct from {@link MemberJoined} so listeners can opt into either
     * surface independently — e.g. analytics / welcome-email listeners may want
     * to skip moves, while the auto-assign listener wants to handle both.
     */
    public record MemberMoved(
            UUID toOrganizationId,
            UUID fromOrganizationId,
            UUID userId,
            String userType) {
    }
}
