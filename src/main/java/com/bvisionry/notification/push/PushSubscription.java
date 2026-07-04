package com.bvisionry.notification.push;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One browser's Web Push subscription. A user has one row per browser profile
 * they enabled notifications on; the push-service {@code endpoint} is the
 * natural unique key. Rows are pruned when the push service reports the
 * subscription gone (HTTP 404/410).
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
public class PushSubscription extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String endpoint;

    /** Client public key (base64url) for RFC 8291 payload encryption. */
    @Column(nullable = false, columnDefinition = "text")
    private String p256dh;

    /** Client auth secret (base64url). */
    @Column(nullable = false, columnDefinition = "text")
    private String auth;
}
