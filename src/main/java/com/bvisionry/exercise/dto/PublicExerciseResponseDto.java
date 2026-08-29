package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.PublicExerciseResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One public response, for the admin console. The list view sends
 * {@code answers}/{@code rows} null (they are only read on the detail screen),
 * so a page of 20 responses does not ship 20 filled worksheets.
 */
public record PublicExerciseResponseDto(
        UUID id,
        String respondentName,
        String respondentEmail,
        Instant submittedAt,
        Map<String, Object> answers,
        List<Map<String, Object>> rows
) {
    public static PublicExerciseResponseDto summary(PublicExerciseResponse response) {
        return new PublicExerciseResponseDto(
                response.getId(),
                response.getRespondentName(),
                response.getRespondentEmail(),
                response.getSubmittedAt(),
                null,
                null);
    }

    public static PublicExerciseResponseDto detail(PublicExerciseResponse response) {
        return new PublicExerciseResponseDto(
                response.getId(),
                response.getRespondentName(),
                response.getRespondentEmail(),
                response.getSubmittedAt(),
                response.getAnswers(),
                response.getSheetRows());
    }
}
