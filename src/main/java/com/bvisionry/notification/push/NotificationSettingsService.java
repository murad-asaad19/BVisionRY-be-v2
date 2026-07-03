package com.bvisionry.notification.push;

import com.bvisionry.common.enums.UserRole;
import com.bvisionry.notification.push.dto.PreferenceItem;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Current-user notification settings: browser push subscriptions and per-type
 * opt-outs. Dispatch-side logic lives in {@link PushNotificationService}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationSettingsService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final NotificationOptOutRepository optOutRepository;

    /**
     * Upsert by endpoint: browsers re-POST the same subscription on every
     * enable, and an endpoint changes owner when a different account signs in
     * on the same browser profile.
     */
    public void subscribe(UUID userId, String endpoint, String p256dh, String auth) {
        PushSubscription subscription = subscriptionRepository.findByEndpoint(endpoint)
                .orElseGet(PushSubscription::new);
        subscription.setUserId(userId);
        subscription.setEndpoint(endpoint);
        subscription.setP256dh(p256dh);
        subscription.setAuth(auth);
        subscriptionRepository.save(subscription);
    }

    public void unsubscribe(UUID userId, String endpoint) {
        subscriptionRepository.deleteByEndpointAndUserId(endpoint, userId);
    }

    @Transactional(readOnly = true)
    public List<PreferenceItem> preferencesFor(UUID userId, UserRole role) {
        Set<NotificationType> muted = optOutRepository.findByUserId(userId).stream()
                .map(NotificationOptOut::getType)
                .collect(Collectors.toSet());
        return NotificationType.visibleTo(role).stream()
                .map(type -> new PreferenceItem(
                        type.name(), type.getLabel(), type.getDescription(), !muted.contains(type)))
                .toList();
    }

    public void setPreference(UUID userId, UserRole role, NotificationType type, boolean enabled) {
        if (type.isAdminOnly() && !NotificationType.isAdminRole(role)) {
            throw new AccessDeniedException("This notification type is admin-only.");
        }
        if (enabled) {
            optOutRepository.deleteByUserIdAndType(userId, type);
            return;
        }
        if (optOutRepository.existsByUserIdAndType(userId, type)) {
            return;
        }
        NotificationOptOut optOut = new NotificationOptOut();
        optOut.setUserId(userId);
        optOut.setType(type);
        try {
            optOutRepository.save(optOut);
        } catch (DataIntegrityViolationException ignored) {
            // Concurrent toggle-off already inserted the row — same outcome.
        }
    }
}
