package com.bvisionry.programflow.dto;

import java.util.UUID;

/** An active org member with their (optional) team. */
public record MemberDto(
        UUID id,
        String name,
        String email,
        UUID teamId,
        String teamName) {
}
