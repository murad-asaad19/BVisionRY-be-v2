package com.bvisionry.organization.dto;

import java.util.UUID;

public record RemoveMemberResponse(
        UUID memberId,
        boolean assessmentsWiped,
        int assignmentsDeleted
) {}
