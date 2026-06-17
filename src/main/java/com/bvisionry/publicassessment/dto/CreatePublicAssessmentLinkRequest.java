package com.bvisionry.publicassessment.dto;

import com.bvisionry.survey.entity.RespondentFieldMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Admin payload to publish a PUBLISHED pipeline as a public link. Optional
 * fields default in the service: modes to {@code NONE}, results visibility to
 * {@code true}, {@code maxResponses} null = unlimited.
 */
public record CreatePublicAssessmentLinkRequest(
        @NotNull(message = "Pipeline id is required")
        UUID pipelineId,

        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must be at most 255 characters")
        String title,

        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        RespondentFieldMode respondentEmailMode,

        RespondentFieldMode respondentNameMode,

        RespondentFieldMode genderMode,

        Boolean showResultsToRespondent,

        @Positive(message = "Max responses must be a positive number")
        Integer maxResponses
) {}
