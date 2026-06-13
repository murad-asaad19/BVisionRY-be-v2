package com.bvisionry.assessment.dto;

public record SaveAnswerRequest(
        String responseText,
        String selectedValue
) {}
