package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.RespondentFieldMode;
import com.bvisionry.survey.entity.SurveyVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH-style update for survey metadata. Allowed in any status — used both
 * for inline-rename of the survey name and for toggling the visibility on
 * a published survey (which mints/clears the public token as a side effect).
 *
 * <p>{@code visibility}, {@code respondentEmailMode} and {@code respondentNameMode}
 * are all optional: when null, the existing value is preserved.
 */
public record SurveyMetadataUpdateRequest(
        @NotBlank(message = "Survey name is required")
        @Size(max = 255, message = "Survey name must be at most 255 characters")
        String name,

        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        SurveyVisibility visibility,

        RespondentFieldMode respondentEmailMode,

        RespondentFieldMode respondentNameMode
) {}
