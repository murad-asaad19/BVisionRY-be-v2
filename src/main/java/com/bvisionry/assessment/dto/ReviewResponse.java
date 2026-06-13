package com.bvisionry.assessment.dto;

import java.util.List;
import java.util.UUID;

public record ReviewResponse(
        UUID submissionId,
        boolean complete,
        int totalRequired,
        int answeredRequired,
        List<UnansweredQuestion> unansweredQuestions
) {
    public record UnansweredQuestion(
            UUID questionId,
            String promptText,
            UUID pillarId,
            String pillarName
    ) {}
}
