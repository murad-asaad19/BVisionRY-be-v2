package com.bvisionry.notification.push;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dispatch gating: opt-outs mute, role fan-out targets the right users with
 * the right deep link, and a disabled sender short-circuits entirely.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushNotificationServiceTest {

    @Mock private PushSubscriptionRepository subscriptionRepository;
    @Mock private NotificationOptOutRepository optOutRepository;
    @Mock private UserNotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private WebPushSender sender;

    private PushNotificationService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PushNotificationService(subscriptionRepository, optOutRepository,
                notificationRepository, userRepository, sender);
        when(sender.isEnabled()).thenReturn(true);
        when(optOutRepository.findByTypeAndUserIdIn(any(), anyCollection())).thenReturn(List.of());
        when(subscriptionRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
    }

    private static User user(UUID id, UserRole role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private static PushSubscription subscription(UUID userId) {
        PushSubscription subscription = new PushSubscription();
        subscription.setUserId(userId);
        subscription.setEndpoint("https://push.example/" + userId);
        return subscription;
    }

    @Test
    void notifyUserSendsPayloadToEachSubscription() {
        when(subscriptionRepository.findByUserIdIn(List.of(userId)))
                .thenReturn(List.of(subscription(userId), subscription(userId)));

        service.notifyUser(userId, NotificationType.ASSESSMENT_ASSIGNED, "Title", "Body", "/my/x");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sender, org.mockito.Mockito.times(2)).send(any(), payload.capture());
        assertThat(payload.getValue())
                .contains("\"title\":\"Title\"")
                .contains("\"url\":\"/my/x\"");
    }

    @Test
    void notifyUserSkipsOptedOutUserEntirely() {
        NotificationOptOut optOut = new NotificationOptOut();
        optOut.setUserId(userId);
        optOut.setType(NotificationType.RESULTS_READY);
        when(optOutRepository.findByTypeAndUserIdIn(eq(NotificationType.RESULTS_READY), anyCollection()))
                .thenReturn(List.of(optOut));

        service.notifyUser(userId, NotificationType.RESULTS_READY, "Title", "Body", "/my/x");

        verify(sender, never()).send(any(), any());
        verify(subscriptionRepository, never()).findByUserIdIn(anyCollection());
        // Muted = no push AND no history row.
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    void notifyUserWritesHistoryRow() {
        service.notifyUser(userId, NotificationType.ASSESSMENT_ASSIGNED, "Title", "Body", "/my/x");

        ArgumentCaptor<List<UserNotification>> rows = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        UserNotification row = rows.getValue().get(0);
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getType()).isEqualTo(NotificationType.ASSESSMENT_ASSIGNED);
        assertThat(row.getTitle()).isEqualTo("Title");
        assertThat(row.getUrl()).isEqualTo("/my/x");
        assertThat(row.getReadAt()).isNull();
    }

    @Test
    void notifyOrgAdminsTargetsActiveAdminsWithRoleSpecificUrls() {
        UUID orgAdminId = UUID.randomUUID();
        UUID superAdminId = UUID.randomUUID();
        when(userRepository.findByOrganizationIdAndRoleAndStatus(orgId, UserRole.ORG_ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(user(orgAdminId, UserRole.ORG_ADMIN, UserStatus.ACTIVE)));
        when(userRepository.findByRole(UserRole.SUPER_ADMIN)).thenReturn(List.of(
                user(superAdminId, UserRole.SUPER_ADMIN, UserStatus.ACTIVE),
                user(UUID.randomUUID(), UserRole.SUPER_ADMIN, UserStatus.SUSPENDED)));
        when(subscriptionRepository.findByUserIdIn(List.of(orgAdminId)))
                .thenReturn(List.of(subscription(orgAdminId)));
        when(subscriptionRepository.findByUserIdIn(List.of(superAdminId)))
                .thenReturn(List.of(subscription(superAdminId)));

        service.notifyOrgAdmins(orgId, NotificationType.MEMBER_SUBMITTED, "Title", "Body",
                "/app/admin/assignments", "/app/admin/organizations/" + orgId + "/assignments");

        ArgumentCaptor<PushSubscription> target = ArgumentCaptor.forClass(PushSubscription.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sender, org.mockito.Mockito.times(2)).send(target.capture(), payload.capture());
        assertThat(target.getAllValues()).extracting(PushSubscription::getUserId)
                .containsExactly(orgAdminId, superAdminId);
        assertThat(payload.getAllValues().get(0)).contains("/app/admin/assignments");
        assertThat(payload.getAllValues().get(1)).contains("/app/admin/organizations/" + orgId);
    }

    @Test
    void disabledSenderStillWritesHistoryButSkipsPush() {
        when(sender.isEnabled()).thenReturn(false);

        service.notifyUser(userId, NotificationType.ASSESSMENT_ASSIGNED, "Title", "Body", "/my/x");

        verify(notificationRepository).saveAll(any());
        verify(sender, never()).send(any(), any());
        verify(subscriptionRepository, never()).findByUserIdIn(anyCollection());
    }

    @Test
    void adminOnlyTypesAreHiddenFromMembersAndVisibleToAdmins() {
        // Pin the exact member-visible set by hand (NOT derived from isAdminOnly,
        // which is the very flag this test polices): a new type mislabeled as
        // member-visible — or a member type forgotten here — must break this,
        // forcing the author to reconcile the adminOnly flag deliberately.
        assertThat(NotificationType.visibleTo(UserRole.MEMBER))
                .containsExactly(
                        NotificationType.ASSESSMENT_ASSIGNED,
                        NotificationType.ASSESSMENT_REMINDER,
                        NotificationType.RESULTS_READY,
                        NotificationType.COHORT_ENROLLED,
                        NotificationType.PROGRAM_MODULE_ASSIGNED,
                        NotificationType.PROGRAM_MODULE_UNLOCKED,
                        NotificationType.PROGRAM_TASK_DUE,
                        NotificationType.WORKSHOP_RESULTS_SHARED)
                .noneMatch(NotificationType::isAdminOnly);
        assertThat(NotificationType.visibleTo(UserRole.ORG_ADMIN))
                .containsExactlyInAnyOrder(NotificationType.values());
        assertThat(NotificationType.visibleTo(UserRole.SUPER_ADMIN))
                .containsExactlyInAnyOrder(NotificationType.values());
    }
}
