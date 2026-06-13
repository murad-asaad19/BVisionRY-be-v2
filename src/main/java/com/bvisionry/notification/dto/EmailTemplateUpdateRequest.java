package com.bvisionry.notification.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record EmailTemplateUpdateRequest(
        @NotNull Map<String, Object> values
) {}
