package com.bvisionry.notification.push;

import com.bvisionry.notification.push.dto.NotificationItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /** Upper bound on a caller-supplied page size — the bell asks for 20. */
    private static final int MAX_SIZE = 100;

    /**
     * One page of the caller's history, newest first, optionally narrowed to
     * unread. Page and size are clamped here rather than trusted: they arrive
     * straight off the query string, and a negative page or a size of 10_000 is
     * a table scan per request.
     */
    @Transactional(readOnly = true)
    public Page<NotificationItem> page(UUID userId, int page, int size, boolean unreadOnly) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserNotification> rows = unreadOnly
                ? repository.findByUserIdAndReadAtIsNull(userId, pageable)
                : repository.findByUserId(userId, pageable);
        return rows.map(n -> new NotificationItem(n.getId(), n.getType().name(), n.getTitle(),
                n.getBody(), n.getUrl(), n.getReadAt(), n.getCreatedAt()));
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
