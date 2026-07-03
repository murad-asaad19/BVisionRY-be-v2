package com.bvisionry.notification.push;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A user muted one notification type. Absence of a row = enabled (the
 * default), so new users and new {@link NotificationType} values are
 * enabled without backfill.
 */
@Entity
@Table(name = "notification_optouts")
@Getter
@Setter
public class NotificationOptOut extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 64)
    private NotificationType type;
}
