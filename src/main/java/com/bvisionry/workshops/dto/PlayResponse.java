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
        /** The team's card key (null = frontend default) and its order, for the member's team-card badge. */
        String teamCard,
        int teamPosition,
        String view,
        OffsetDateTime helpRequestedAt,
        ExerciseInfo exercise,
        TaskView task,
        List<RecapRow> recap,
        List<SortRecap> sortRecaps,
        List<TaskAnswers> teamAnswers,
        /**
         * Whether this learner had any tasks to perform in this workshop at
         * all — independent of {@code recap}/{@code sortRecaps}/{@code
         * teamAnswers}, which only cover TOP/QUESTION/lead-SORT completions.
         * A pipeline of only WEIGHT/SURVEY/member-SORT tasks leaves all three
         * empty even though real work was done, so the done screen needs this
         * to tell "genuinely nothing to do" apart from "nothing to recap".
         */
        boolean hasTasks,
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
            List<AnswerDto> answers) {
    }

    /** A sort card as the player sees it — the answer key never leaves the server. */
    public record CardDto(String id, String text) {
    }

    /** Failed-attempt gate state: shown after grading until all cards are correct. */
    public record SortGate(int attempts, int correctCount, int wrongCount, int total, boolean narrowed) {
    }

    public record ScoredCard(String id, String text, int weight) {
    }

    public record AnswerDto(String cardId, String text) {
    }

    /** One answered card: the card + the member's response, in shared-card order. */
    public record RecapAnswer(String cardId, String cardText, String text) {
    }

    /** A member's answered QUESTION task, editable from the done screen. */
    public record RecapRow(
            UUID taskId,
            String taskTitle,
            String prompt,
            List<RecapAnswer> answers,
            List<ScoredCard> topRows) {
    }

    /** The team's shared sort result — every dealt card under its pile label. */
    public record SortRecap(
            UUID taskId,
            String taskTitle,
            String leftLabel,
            String rightLabel,
            List<CardDto> left,
            List<CardDto> right) {
    }

    /** Everyone's completed answers for one QUESTION task — revealed as they arrive. */
    public record TaskAnswers(UUID taskId, String taskTitle, String prompt, List<MemberAnswers> members) {
    }

    public record MemberAnswers(UUID userId, String name, boolean lead, List<RecapAnswer> answers) {
    }

    public record ThankYou(String surveyName, UUID surveyToken) {
    }
}
