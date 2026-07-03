package com.bvisionry.notification.push;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fans a notification out to every subscribed browser of its recipients,
 * honoring per-user opt-outs ({@link NotificationOptOut}). All entry points
 * are fire-and-forget on the push executor: like the async email sends they
 * sit next to, they never throw into the calling business flow.
 *
 * <p>{@code url} arguments are frontend-relative paths (e.g.
 * {@code /my/assessments/<id>}) — the service worker resolves them against
 * the web app's own origin, so no {@code FrontendUrls} dependency is needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PushSubscriptionRepository subscriptionRepository;
    private final NotificationOptOutRepository optOutRepository;
    private final UserRepository userRepository;
    private final WebPushSender sender;

    /** Notify a single member. */
    @Async("pushExecutor")
    public void notifyUser(UUID userId, NotificationType type, String title, String body, String url) {
        try {
            dispatch(List.of(userId), type, title, body, url);
        } catch (RuntimeException e) {
            log.warn("Push dispatch {} to user {} failed: {}", type, userId, e.getMessage());
        }
    }

    /**
     * Notify the org's active ORG_ADMINs plus all active SUPER_ADMINs about an
     * event in {@code orgId}. The two roles land on different routes for the
     * same thing (org admins use the flat {@code /app/admin/*} console, super
     * admins the {@code /app/admin/organizations/<id>/*} drill-in), hence the
     * two paths.
     */
    @Async("pushExecutor")
    public void notifyOrgAdmins(UUID orgId, NotificationType type, String title, String body,
                                String orgAdminUrl, String superAdminUrl) {
        try {
            List<UUID> orgAdmins = idsOf(userRepository
                    .findByOrganizationIdAndRoleAndStatus(orgId, UserRole.ORG_ADMIN, UserStatus.ACTIVE));
            List<UUID> superAdmins = idsOf(userRepository.findByRole(UserRole.SUPER_ADMIN).stream()
                    .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .toList());
            dispatch(orgAdmins, type, title, body, orgAdminUrl);
            dispatch(superAdmins, type, title, body, superAdminUrl);
        } catch (RuntimeException e) {
            log.warn("Push dispatch {} to admins of org {} failed: {}", type, orgId, e.getMessage());
        }
    }

    private static List<UUID> idsOf(List<User> users) {
        return users.stream().map(User::getId).toList();
    }

    private void dispatch(List<UUID> userIds, NotificationType type, String title, String body, String url) {
        if (!sender.isEnabled() || userIds.isEmpty()) {
            return;
        }
        Set<UUID> muted = optOutRepository.findByTypeAndUserIdIn(type, userIds).stream()
                .map(NotificationOptOut::getUserId)
                .collect(Collectors.toSet());
        List<UUID> recipients = userIds.stream().filter(id -> !muted.contains(id)).toList();
        if (recipients.isEmpty()) {
            return;
        }
        String payload = toJson(title, body, url);
        for (PushSubscription subscription : subscriptionRepository.findByUserIdIn(recipients)) {
            sender.send(subscription, payload);
        }
    }

    private String toJson(String title, String body, String url) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("title", title, "body", body, "url", url));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize push payload", e);
        }
    }
}
