package com.bvisionry.notification.push.dto;

import jakarta.validation.constraints.NotNull;

public record UpdatePreferenceRequest(@NotNull Boolean enabled) {
}
