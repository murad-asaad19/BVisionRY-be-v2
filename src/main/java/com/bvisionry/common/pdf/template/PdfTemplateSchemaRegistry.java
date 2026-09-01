package com.bvisionry.common.pdf.template;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editable-field schema of every PDF template: which pieces of text an
 * admin may change, their limits, their allowed {@code {{variables}}}, and the
 * shipped defaults. The document layout itself is a locked Thymeleaf skeleton
 * ({@link PdfTemplateMetadata#templateName}) that only ever reads these fields
 * through escaping expressions — admins edit copy, never markup.
 */
@Component
public class PdfTemplateSchemaRegistry {

    public List<PdfTemplateField> schemaFor(PdfTemplateKey key) {
        return switch (key) {
            case PUBLIC_EXERCISE_ANSWERS -> publicExerciseAnswers();
        };
    }

    /** The shipped default for every field of the template, keyed by field id. */
    public Map<String, String> defaultValues(PdfTemplateKey key) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (PdfTemplateField field : schemaFor(key)) {
            defaults.put(field.id(), field.defaultValue());
        }
        return defaults;
    }

    private static List<PdfTemplateField> publicExerciseAnswers() {
        return List.of(
                new PdfTemplateField(
                        "documentTitle",
                        "Document title",
                        "The large heading in the masthead.",
                        120,
                        List.of("exerciseName"),
                        "{{exerciseName}}",
                        "Masthead"),
                new PdfTemplateField(
                        "introNote",
                        "Intro note",
                        "Optional line under the masthead. Leave empty to hide it.",
                        400,
                        List.of("exerciseName"),
                        "",
                        "Masthead"),
                new PdfTemplateField(
                        "notAnsweredLabel",
                        "Unanswered marker",
                        "Shown under a question the respondent left empty.",
                        80,
                        List.of(),
                        "Not answered.",
                        "Answers"),
                new PdfTemplateField(
                        "footerText",
                        "Footer line",
                        "The single line at the bottom of the document.",
                        160,
                        List.of("exerciseName"),
                        "{{exerciseName}} · www.bvisionry.com",
                        "Footer")
        );
    }
}
