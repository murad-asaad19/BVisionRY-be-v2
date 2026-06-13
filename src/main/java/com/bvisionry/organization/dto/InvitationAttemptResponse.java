package com.bvisionry.organization.dto;

import com.bvisionry.organization.entity.InvitationAcceptanceAttempt;

import java.time.Instant;
import java.util.UUID;

public record InvitationAttemptResponse(
        UUID id,
        Instant attemptedAt,
        boolean success,
        String errorCode,
        String errorMessage
) {
    public static InvitationAttemptResponse from(InvitationAcceptanceAttempt a) {
        return new InvitationAttemptResponse(
                a.getId(), a.getAttemptedAt(), a.isSuccess(),
                a.getErrorCode(), a.getErrorMessage());
    }
}
