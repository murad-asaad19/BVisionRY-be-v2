package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptJoinLinkRequest(
        @NotBlank @Email String email,
        @Size(max = 255) String name,
        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password
) {}
