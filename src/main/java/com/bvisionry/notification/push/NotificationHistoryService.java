package com.bvisionry.notification.push;

import com.bvisionry.notification.push.dto.NotificationItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The bell's read side: list, unread count, mark read. Rows are written by
 * {@link PushNotificationService} on dispatch and purged by
 * {@link NotificationRetentionJob}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationHistoryService {

    private final UserNotificationRepository repository;

    @Transactional(readOnly = true)
    public List<NotificationItem> list(UUID userId, int limit) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, Limit.of(Math.clamp(limit, 1, 100)))
                .stream()
                .map(n -> new NotificationItem(n.getId(), n.getType().name(), n.getTitle(),
                        n.getBody(), n.getUrl(), n.getReadAt(), n.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    /** Idempotent; silently ignores ids that don't exist or aren't the caller's. */
    public void markRead(UUID userId, UUID notificationId) {
        repository.findByIdAndUserId(notificationId, userId).ifPresent(notification -> {
            if (notification.getReadAt() == null) {
                notification.setReadAt(Instant.now());
                repository.save(notification);
            }
        });
    }

    public void markAllRead(UUID userId) {
        repository.markAllRead(userId, Instant.now());
    }
}
