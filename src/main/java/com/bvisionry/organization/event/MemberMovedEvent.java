package com.bvisionry.organization.event;

import java.util.UUID;

/**
 * Published when a member is moved from one organization to another via the
 * super-admin move flow. From the target org's perspective the member is a
 * fresh arrival, so any auto-assign rule that matches their userType should
 * fire just as it would on a first join.
 *
 * <p>Distinct from {@link MemberJoinedEvent} so listeners can opt into either
 * surface independently — e.g. analytics / welcome-email listeners may want to
 * skip moves, while the auto-assign listener wants to handle both.
 *
 * <p>Listeners are expected to be {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)}: a rolled-back move must NOT leave behind orphan
 * assignments in the target org.
 */
public record MemberMovedEvent(
        UUID toOrganizationId,
        UUID fromOrganizationId,
        UUID userId,
        String userType) {
}
