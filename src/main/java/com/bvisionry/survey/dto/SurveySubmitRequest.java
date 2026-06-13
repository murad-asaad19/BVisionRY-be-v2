package com.bvisionry.survey.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SurveySubmitRequest(
        @Email(message = "Invalid email format")
        String respondentEmail,

        @Size(max = 255, message = "Name must be 255 characters or fewer")
        String respondentName,

        @NotEmpty(message = "Answers list must not be empty")
        @Valid
        List<SurveyAnswerSubmitDto> answers
) {}
