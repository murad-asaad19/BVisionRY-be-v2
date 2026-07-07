package com.bvisionry.workshops.dto;

import java.util.UUID;

import com.bvisionry.workshops.domain.WorkshopStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateWorkshopRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull WorkshopStatus status,
        UUID postCompletionSurveyId) {
}
