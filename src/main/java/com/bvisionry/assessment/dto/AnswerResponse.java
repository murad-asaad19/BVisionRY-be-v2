package com.bvisionry.assessment.dto;

import java.time.Instant;
import java.util.UUID;

public record AnswerResponse(
        UUID id,
        UUID questionId,
        String responseText,
        String selectedValue,
        Instant updatedAt
) {}
