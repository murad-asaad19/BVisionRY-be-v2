package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.RespondentFieldMode;
import com.bvisionry.survey.entity.SurveyVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * PATCH-style update for survey metadata. Allowed in any status — used both
 * for inline-rename of the survey name and for toggling the visibility on
 * a published survey (which mints/clears the public token as a side effect).
 *
 * <p>{@code visibility}, {@code respondentEmailMode} and {@code respondentNameMode}
 * are all optional: when null, the existing value is preserved.
 *
 * <p>The gift public assessment is clearable, so a bare null can't mean both
 * "keep" and "remove": {@code giftPublicAssessmentLinkId} sets the gift when
 * non-null, and {@code clearGiftPublicAssessmentLink} removes it. This lets
 * partial callers (e.g. the published-survey visibility toggle) omit both and
 * leave the gift untouched.
 */
public record SurveyMetadataUpdateRequest(
        @NotBlank(message = "Survey name is required")
        @Size(max = 255, message = "Survey name must be at most 255 characters")
        String name,

        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        SurveyVisibility visibility,

        RespondentFieldMode respondentEmailMode,

        RespondentFieldMode respondentNameMode,

        UUID giftPublicAssessmentLinkId,

        /*
         * Boxed (not primitive) so an OMITTED flag deserializes cleanly: Jackson
         * cannot map an absent JSON property onto a record's primitive component
         * (it would pass null and trip FAIL_ON_NULL_FOR_PRIMITIVES) — as a
         * primitive, EVERY partial metadata PATCH that left this flag out was
         * rejected 400, which broke the published-survey visibility toggle.
         * The compact constructor coalesces null → false.
         */
        Boolean clearGiftPublicAssessmentLink
) {
    public SurveyMetadataUpdateRequest {
        clearGiftPublicAssessmentLink = clearGiftPublicAssessmentLink != null && clearGiftPublicAssessmentLink;
    }
}
