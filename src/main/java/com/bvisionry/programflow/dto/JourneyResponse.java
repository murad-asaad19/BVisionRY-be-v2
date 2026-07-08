package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.SubmissionStatus;

/** The learner journey for one cohort: audience-filtered modules with drip state + my progress. */
public record JourneyResponse(
        ProgramSettingsDto settings,
        Progress progress,
        GamificationDto gamification,
        List<JourneyModule> modules,
        UUID cohortId,
        boolean readOnly) {

    public record Progress(int done, int total) {
    }

    public record JourneyModule(
            UUID id,
            String name,
            String summary,
            LockState lockState,
            OffsetDateTime unlockAt,
            String previousModuleName,
            List<JourneyTask> tasks) {
    }

    public record JourneyTask(
            UUID id,
            String name,
            LocalDate dueDate,
            int questions,
            int steps,
            SubmissionStatus myStatus) {
    }

    public enum LockState {
        UNLOCKED,
        LOCKED_SEQUENTIAL,
        LOCKED_SCHEDULED
    }
}
