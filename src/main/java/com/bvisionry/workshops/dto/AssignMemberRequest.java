package com.bvisionry.workshops.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Move a member onto a team ({@code teamId} null = unassign from the workshop). */
public record AssignMemberRequest(
        @NotNull UUID userId,
        UUID teamId) {
}
