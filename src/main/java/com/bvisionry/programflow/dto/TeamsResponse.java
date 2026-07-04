package com.bvisionry.programflow.dto;

import java.util.List;
import java.util.UUID;

/** Teams & members screen payload. */
public record TeamsResponse(
        List<TeamDto> teams,
        List<MemberDto> unassigned) {

    public record TeamDto(UUID id, String name, List<MemberDto> members) {
    }
}
