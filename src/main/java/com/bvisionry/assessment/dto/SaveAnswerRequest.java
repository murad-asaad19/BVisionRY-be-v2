package com.bvisionry.assessment.dto;

import jakarta.validation.constraints.Size;

/**
 * Single answer save. The length caps are a hard server-side ceiling enforced
 * even when a question's own config omits a {@code maxLength}: without them an
 * anonymous respondent could store megabytes of free text into the unbounded
 * TEXT columns and then have it fanned into per-pillar AI calls at submit
 * (storage bloat + AI cost amplification). Generous enough for any genuine
 * long-form answer.
 */
public record SaveAnswerRequest(
        @Size(max = 20_000, message = "Answer text is too long (maximum 20000 characters)")
        String responseText,
        @Size(max = 8_000, message = "Selected value is too long (maximum 8000 characters)")
        String selectedValue
) {}
