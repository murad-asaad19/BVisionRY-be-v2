package com.bvisionry.notification.push.dto;

import java.time.Instant;
import java.util.UUID;

/** One bell/history entry. {@code readAt} null = unread. */
public record NotificationItem(
        UUID id,
        String type,
        String title,
        String body,
        String url,
        Instant readAt,
        Instant createdAt) {
}
