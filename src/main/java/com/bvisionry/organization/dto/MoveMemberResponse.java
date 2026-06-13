package com.bvisionry.organization.dto;

import java.util.UUID;

public record MoveMemberResponse(
        UUID memberId,
        UUID fromOrganizationId,
        UUID toOrganizationId,
        int movedAssignments
) {}
