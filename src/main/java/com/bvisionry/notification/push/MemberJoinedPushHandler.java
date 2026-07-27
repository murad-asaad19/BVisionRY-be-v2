package com.bvisionry.notification.push;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.organization.event.MemberJoinedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Pushes the admin-facing "new member joined" notification off the existing
 * {@link MemberJoinedEvent}. AFTER_COMMIT for the same reason as
 * {@code AutoAssignmentEventHandler}: a rolled-back join must not notify.
 */
@Component
@RequiredArgsConstructor
public class MemberJoinedPushHandler {

    private final PushNotificationService pushNotificationService;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberJoined(MemberJoinedEvent event) {
        // Name the org explicitly: super admins receive this across every
        // tenant, so "your organization" is meaningless to them. Fetch-join the
        // org so getName() is safe outside the (already-committed) transaction.
        User member = userRepository.findByIdWithOrganization(event.userId()).orElse(null);
        String memberName = member != null ? member.getName() : "A new member";
        String orgName = member != null && member.getOrganization() != null
                ? member.getOrganization().getName()
                : "your organization";
        // Read the event's org id ONCE. Each call to it is a frozen
        // cross-feature ArchUnit violation, and the store may only shrink — so
        // a local is the difference between pruning a line and adding one.
        UUID orgId = event.organizationId();
        // Both roles get the ONE canonical members URL. The org-admin slot used
        // to be "/app/admin/members", a page the web app does not have — every
        // org admin clicking this notification hit a 404. Members live in
        // sub-orgs, whose console an org admin may open, so this resolves.
        String membersUrl = "/app/admin/organizations/" + orgId + "/members";
        pushNotificationService.notifyOrgAdmins(orgId,
                NotificationType.MEMBER_JOINED,
                "New member joined",
                memberName + " joined " + orgName + ".",
                membersUrl,
                membersUrl);
    }
}
