package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.RespondentFieldMode;
import com.bvisionry.exercise.entity.WorksheetBlock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an anonymous respondent is allowed to see of a public exercise.
 *
 * <p>A hand-picked subset of {@link ExerciseTemplateDetailResponse}, not a
 * reuse of it: that record carries {@code aiContext} (the staff-only brief for
 * the model) and the raw {@code minio://} cover marker, neither of which may
 * ever reach a public page.
 */
public record PublicExerciseDto(
        String name,
        ExerciseTemplateKind kind,
        /** WORKSHEET only: the ordered block list. Null for sheets. */
        List<WorksheetBlock> blocks,
        /** Serialised tiptap document — the brief above the exercise. */
        String description,
        /** The cover resolved for display; never the stored marker. */
        String coverImageUrl,
        /** SHEET only. */
        List<ExerciseColumnResponse> columns,
        /** Read-only sample row shown greyed out above the sheet, or null. */
        Map<String, Object> exampleRow,
        /** Rows the respondent starts with (e.g. "Round 1/2/3"), or null. */
        List<Map<String, Object>> starterRows,
        boolean allowAddRows,
        RespondentFieldMode respondentNameMode,
        RespondentFieldMode respondentEmailMode,
        /** Survey offered on the thank-you screen, or null when none applies. */
        PostCompletionSurvey postCompletionSurvey
) {
    /**
     * The paired survey, already resolved to what the thank-you CTA needs. A
     * public token rather than an id: the respondent is anonymous, so the only
     * survey they can reach is one with a public link.
     */
    public record PostCompletionSurvey(UUID token, String name) {}

    public static PublicExerciseDto from(ExerciseTemplate template, String coverImageUrl,
                                         PostCompletionSurvey postCompletionSurvey) {
        return new PublicExerciseDto(
                template.getName(),
                template.getKind(),
                template.getBlocks(),
                template.getDescription(),
                coverImageUrl,
                template.getColumns().stream().map(ExerciseColumnResponse::from).toList(),
                template.getExampleRow(),
                template.getStarterRows(),
                template.isAllowAddRows(),
                template.getRespondentNameMode(),
                template.getRespondentEmailMode(),
                postCompletionSurvey);
    }
}
