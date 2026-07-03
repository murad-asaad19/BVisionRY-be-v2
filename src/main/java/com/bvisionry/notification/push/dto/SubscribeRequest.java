package com.bvisionry.notification.push.dto;

import jakarta.validation.constraints.NotBlank;

/** A browser's PushSubscription, as produced by {@code subscription.toJSON()}. */
public record SubscribeRequest(
        @NotBlank String endpoint,
        @NotBlank String p256dh,
        @NotBlank String auth) {
}
