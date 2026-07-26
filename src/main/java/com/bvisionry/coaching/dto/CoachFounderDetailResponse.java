package com.bvisionry.coaching.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Founder drill-in for the coach console: pillar scores from the latest
 * evaluated assessment, per-module program progress, and the founder's
 * exercise submissions (the coach's review entry points).
 */
public record CoachFounderDetailResponse(
        CoachFounderSummary founder,
        List<CoachPillarScore> pillarScores,
        List<CoachModuleProgress> modules,
        List<CoachExerciseSubmission> exercises) {

    public record CoachPillarScore(
            String pillarName,
            BigDecimal scorePercentage,
            String maturityLabel,
            OffsetDateTime evaluatedAt) {}

    public record CoachModuleProgress(
            String cohortName,
            String moduleName,
            int totalTasks,
            int submittedTasks) {}

    public record CoachExerciseSubmission(
            UUID assignmentId,
            String exerciseName,
            /** Raw submission status (IN_PROGRESS/SUBMITTED/…); null = not started. */
            String status,
            OffsetDateTime submittedAt) {}
}
