package com.bvisionry.workshops.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The live results board: the workshop's "road" (every task of every exercise,
 * flattened in play order) and each team's position on it.
 *
 * <p>Phase drives the card rendering: {@code LEAD} = only the lead can act →
 * one merged team card at {@code stepIndex}; {@code MEMBER} = results shared →
 * one numbered chip per participant at their own {@code stepIndex};
 * {@code DONE} = everyone finished (the goal). A null {@code stepIndex} means
 * "past the last step" (finished).
 */
public record WorkshopLiveResponse(List<StepDto> steps, List<TeamDto> teams) {

    public record StepDto(UUID taskId, String taskTitle, String taskType, String assignee,
                          UUID exerciseId, String exerciseTitle) {
    }

    public record TeamDto(UUID id, String name, String card, String phase,
                          Integer stepIndex, OffsetDateTime helpRequestedAt,
                          List<ParticipantDto> members) {
    }

    /**
     * One team participant. {@code number} is the stable 1-based chip number
     * (member order); {@code waiting} = their next task sits in an exercise the
     * lead hasn't shared yet — they're queued at the share gate.
     */
    public record ParticipantDto(UUID userId, String name, int number, boolean lead,
                                 Integer stepIndex, boolean waiting) {
    }
}
