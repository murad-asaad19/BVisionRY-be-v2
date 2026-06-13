package com.bvisionry.upgrade.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpgradeRequestRecipientsRequest(
        @Size(max = 50, message = "At most 50 recipients can be configured")
        List<@Email(message = "Invalid email address") String> recipients
) {}
