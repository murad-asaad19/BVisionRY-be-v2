package com.bvisionry.notification.push;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.organization.event.MemberJoinedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
        String memberName = userRepository.findById(event.userId())
                .map(User::getName)
                .orElse("A new member");
        pushNotificationService.notifyOrgAdmins(event.organizationId(),
                NotificationType.MEMBER_JOINED,
                "New member joined",
                memberName + " joined your organization.",
                "/app/admin/members",
                "/app/admin/organizations/" + event.organizationId() + "/members");
    }
}
