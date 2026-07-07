package com.bvisionry.workshops.dto;

import java.util.List;
import java.util.UUID;

/** Teams tab payload: per-workshop teams with members (lead flagged) + unassigned org members. */
public record WorkshopTeamsResponse(List<TeamDto> teams, List<MemberDto> unassigned) {

    public record TeamDto(UUID id, String name, String card, List<MemberDto> members) {
    }

    public record MemberDto(UUID id, String name, String email, boolean lead) {
    }
}
