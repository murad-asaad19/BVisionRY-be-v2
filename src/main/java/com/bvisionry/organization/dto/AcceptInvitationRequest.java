package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
        @Size(max = 255) String name,
        @Size(min = 8, message = "Password must be at least 8 characters") String password
) {}
