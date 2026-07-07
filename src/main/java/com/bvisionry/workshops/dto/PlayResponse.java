package com.bvisionry.workshops.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.bvisionry.workshops.domain.WorkshopStatus;

/**
 * The learner's complete play state for one workshop — a single endpoint keeps
 * the client dumb and the SORT answer key server-side.
 *
 * <p>{@code view} drives the screen: {@code TASK} (the client further splits
 * envelope → instruction sheet → running task via {@code task.startedAt} and
 * local "opened" state), {@code WAITING} (member, lead hasn't shared yet),
 * {@code LEAD_DONE}, {@code MEMBER_DONE} (with {@code recap}), or
 * {@code FINISHED} (workshop closed by the admin). {@code thankYou} is set
 * whenever the learner's queue is exhausted or the workshop is finished.
 */
public record PlayResponse(
        UUID workshopId,
        String workshopName,
        WorkshopStatus workshopStatus,
        String role,
        UUID teamId,
        String teamName,
        String view,
        OffsetDateTime helpRequestedAt,
        ExerciseInfo exercise,
        TaskView task,
        List<RecapRow> recap,
        ThankYou thankYou) {

    public record ExerciseInfo(UUID id, String title, int index, int total) {
    }

    /** The current task, shaped per type; unrelated fields stay null. */
    public record TaskView(
            UUID id,
            String type,
            String title,
            int pipelineIndex,
            int pipelineSize,
            int taskNo,
            int taskCount,
            List<String> steps,
            String envTitle,
            String envText,
            String celebrate,
            OffsetDateTime startedAt,
            /** Soft time budget in minutes; null = untimed. The timer never blocks submission. */
            Integer durationMin,
            // SORT
            String instructions,
            String leftLabel,
            String rightLabel,
            List<CardDto> cards,
            SortGate gate,
            // WEIGHT
            List<ScoredCard> weightRows,
            // TOP
            List<ScoredCard> topRows,
            boolean lastLeadTask,
            UUID sourceWeightTaskId,
            List<ScoredCard> sourceWeightRows,
            // QUESTION
            String prompt,
            ResponseDto response) {
    }

    /** A sort card as the player sees it — the answer key never leaves the server. */
    public record CardDto(String id, String text) {
    }

    /** Failed-attempt gate state: shown after grading until all cards are correct. */
    public record SortGate(int attempts, int correctCount, int wrongCount, int total, boolean narrowed) {
    }

    public record ScoredCard(String id, String text, int weight) {
    }

    public record ResponseDto(String cardId, String text) {
    }

    /** A member's answered QUESTION task, editable from the done screen. */
    public record RecapRow(
            UUID taskId,
            String taskTitle,
            String prompt,
            String cardId,
            String cardText,
            String text,
            List<ScoredCard> topRows) {
    }

    public record ThankYou(String surveyName, UUID surveyToken) {
    }
}
