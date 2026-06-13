package com.bvisionry.survey.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record SurveyReorderRequest(
        @NotEmpty(message = "Ordered IDs list must not be empty")
        List<UUID> orderedIds
) {}
