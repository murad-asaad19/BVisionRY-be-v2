package com.bvisionry.workshops.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin assignments surface: each team's pinned card hands, keyed by SORT
 * task id ({@code taskId → [cardId…]}). Card texts/sides come from the builder
 * payload — the frontend joins by card id.
 */
public record AssignmentsResponse(List<TeamAssignments> teams) {

    public record TeamAssignments(UUID teamId, String teamName, Map<String, List<String>> deals) {
    }
}
