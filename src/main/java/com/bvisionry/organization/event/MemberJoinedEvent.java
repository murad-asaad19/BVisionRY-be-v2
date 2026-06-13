package com.bvisionry.organization.event;

import java.util.UUID;

/**
 * Published when a user *first joins* an organization (invitation accept,
 * join-link accept, or any future first-join flow).
 *
 * <p>Deliberately NOT fired on status reinstatement (SUSPENDED/DEACTIVATED →
 * ACTIVE). A reinstated member keeps the assignments they had before
 * suspension; auto-assign does not retroactively cover the suspension
 * window. Admins explicitly opt in to "give them this pipeline now" by
 * re-running the assign flow if needed.
 *
 * <p>Listeners are expected to be {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} so they run only after the membership write has durably
 * landed. {@code userType} is included on the event so listeners can filter
 * without re-loading the user.
 */
public record MemberJoinedEvent(UUID organizationId, UUID userId, String userType) {
}
