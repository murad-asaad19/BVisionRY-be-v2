package com.bvisionry.notification.push;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One in-app notification for one recipient — the bell's history record.
 * Written by {@link PushNotificationService} alongside (and independent of)
 * the web-push fan-out, so history exists even for users with no browser
 * subscribed. Named UserNotification because the web-push library already
 * owns {@code Notification} in this package's import space.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class UserNotification extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 64)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** Frontend-relative deep link. */
    @Column(nullable = false, columnDefinition = "text")
    private String url;

    /** Null = unread. */
    @Column(name = "read_at")
    private Instant readAt;
}
