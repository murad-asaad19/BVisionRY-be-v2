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
 * {@code /app/assessments/<id>}) — the service worker resolves them against
 * the web app's own origin, so no {@code FrontendUrls} dependency is needed.
 * They must match a real route in the web app's {@code (app)} route group,
 * which is served under the {@code /app} prefix — a bare {@code /my/...} or
 * {@code /admin/...} path is a legacy v1 route and 404s in the current app.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PushSubscriptionRepository subscriptionRepository;
    private final NotificationOptOutRepository optOutRepository;
    private final UserNotificationRepository notificationRepository;
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
     * Notify a whole audience the caller has already resolved (a cohort, a
     * team) in ONE pass: one opt-out query, one history batch, one task.
     * Looping {@link #notifyUser} over the same list costs three statements
     * and a task per recipient, which saturates the push executor's queue and
     * pushes it onto its caller-runs policy at cohort scale.
     */
    @Async("pushExecutor")
    public void notifyUsers(List<UUID> userIds, NotificationType type, String title,
                            String body, String url) {
        try {
            dispatch(userIds, type, title, body, url);
        } catch (RuntimeException e) {
            log.warn("Push dispatch {} to {} users failed: {}", type, userIds.size(), e.getMessage());
        }
    }

    /**
     * Notify the org's active ORG_ADMINs plus all active SUPER_ADMINs about an
     * event in {@code orgId}.
     *
     * <p>Admins only. A submission also has coach recipients, but they are
     * per-founder rather than per-org, so they are resolved by
     * {@link CoachReviewNotifier} — a caller reporting a submission calls both.
     *
     * <p>The two URL parameters date from a web app in which the two roles
     * reached the same resource through different route families — org admins a
     * flat {@code /app/admin/*} console, super admins the
     * {@code /app/admin/organizations/<id>/*} drill-in. The web app has since
     * collapsed those into ONE canonical URL per resource, so callers should
     * normally pass the same path twice. The flat org-admin console had in fact
     * already been deleted, leaving two notification types deep-linking admins
     * to a 404; the parameter survives only because it still lets a caller send
     * the two roles to genuinely different places, and because narrowing this
     * signature would re-describe three frozen ArchUnit violations as new ones.
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
        if (userIds.isEmpty()) {
            return;
        }
        Set<UUID> muted = optOutRepository.findByTypeAndUserIdIn(type, userIds).stream()
                .map(NotificationOptOut::getUserId)
                .collect(Collectors.toSet());
        List<UUID> recipients = userIds.stream().filter(id -> !muted.contains(id)).toList();
        if (recipients.isEmpty()) {
            return;
        }
        // In-app history first: it exists for every recipient whether or not a
        // browser is subscribed (or push is configured at all).
        notificationRepository.saveAll(recipients.stream()
                .map(id -> historyRow(id, type, title, body, url))
                .toList());
        if (!sender.isEnabled()) {
            return;
        }
        String payload = toJson(title, body, url);
        for (PushSubscription subscription : subscriptionRepository.findByUserIdIn(recipients)) {
            sender.send(subscription, payload);
        }
    }

    private static UserNotification historyRow(UUID userId, NotificationType type,
                                               String title, String body, String url) {
        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setUrl(url);
        return notification;
    }

    private String toJson(String title, String body, String url) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("title", title, "body", body, "url", url));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize push payload", e);
        }
    }
}
