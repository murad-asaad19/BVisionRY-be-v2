package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.RespondentFieldMode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Admin toggle of an exercise's public link and what it asks respondents for. */
public record UpdatePublicExerciseRequest(
        @NotNull(message = "isPublic is required")
        Boolean isPublic,

        @NotNull(message = "respondentNameMode is required")
        RespondentFieldMode respondentNameMode,

        @NotNull(message = "respondentEmailMode is required")
        RespondentFieldMode respondentEmailMode,

        /**
         * Survey offered on the thank-you screen, or null to unpair. Nullable
         * on purpose — unlike the two modes above, absent IS the meaningful
         * "no survey" value, so it cannot be @NotNull.
         */
        UUID postCompletionSurveyId
) {}
