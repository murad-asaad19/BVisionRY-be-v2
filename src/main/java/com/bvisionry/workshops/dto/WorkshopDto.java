package com.bvisionry.workshops.dto;

import java.util.UUID;

import com.bvisionry.workshops.domain.Workshop;
import com.bvisionry.workshops.domain.WorkshopStatus;

public record WorkshopDto(
        UUID id,
        String name,
        int position,
        WorkshopStatus status,
        UUID postCompletionSurveyId,
        UUID preWorkshopSurveyId,
        String boardStyle,
        long exerciseCount,
        long memberCount,
        /** Submitted task work across the workshop; > 0 means delete is refused (reset first). */
        long submissionCount) {

    public static WorkshopDto from(Workshop w, long exerciseCount, long memberCount,
                                   long submissionCount) {
        return new WorkshopDto(w.getId(), w.getName(), w.getPosition(), w.getStatus(),
                w.getPostCompletionSurveyId(), w.getPreWorkshopSurveyId(),
                w.getBoardStyle(), exerciseCount, memberCount, submissionCount);
    }
}
