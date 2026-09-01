package com.bvisionry.common.pdf.template;

import com.bvisionry.common.pdf.template.dto.PdfTemplateDto;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;

/**
 * Human-facing metadata for each PDF template — display name, purpose, the
 * variables its fields may use — plus the sample data the admin preview
 * renders with, so a preview never needs a real respondent's fill.
 */
public final class PdfTemplateMetadata {

    private PdfTemplateMetadata() {}

    /** The Thymeleaf skeleton the key renders with (without extension). */
    public static String templateName(PdfTemplateKey key) {
        return switch (key) {
            case PUBLIC_EXERCISE_ANSWERS -> "public-exercise-answers";
        };
    }

    public static String displayName(PdfTemplateKey key) {
        return switch (key) {
            case PUBLIC_EXERCISE_ANSWERS -> "Exercise Answers (PDF)";
        };
    }

    public static String description(PdfTemplateKey key) {
        return switch (key) {
            case PUBLIC_EXERCISE_ANSWERS ->
                    "The branded PDF a respondent downloads after filling a public "
                            + "exercise: the questions and their answers.";
        };
    }

    public static List<PdfTemplateDto.PdfTemplateVariable> variables(PdfTemplateKey key) {
        return switch (key) {
            case PUBLIC_EXERCISE_ANSWERS -> List.of(
                    new PdfTemplateDto.PdfTemplateVariable("exerciseName",
                            "The exercise's name, e.g. \"The Power Leak Worksheet\"."));
        };
    }

    /** Example variable values the preview substitutes into the fields. */
    public static Map<String, Object> sampleVariables(PdfTemplateKey key) {
        return switch (key) {
            case PUBLIC_EXERCISE_ANSWERS ->
                    Map.of("exerciseName", "The Power Leak Worksheet");
        };
    }

    /**
     * A fully-populated Thymeleaf context of representative sample data —
     * everything the skeleton needs except the {@code fields} map, which the
     * caller adds from the in-flight admin values.
     */
    public static Context sampleContext(PdfTemplateKey key) {
        return switch (key) {
            case PUBLIC_EXERCISE_ANSWERS -> publicExerciseAnswersSample();
        };
    }

    private static Context publicExerciseAnswersSample() {
        Context ctx = new Context();
        ctx.setVariable("exerciseName", "The Power Leak Worksheet");
        ctx.setVariable("respondentLine", "Sam Founder · sam@example.com · September 1, 2026");
        ctx.setVariable("items", List.of(
                SampleItem.text("Start here", "Where does your power leak today?",
                        "Shipping is always late and I blame the team."),
                SampleItem.text("Rewrite it", "Rewrite the sentence so you own it.", ""),
                SampleItem.list("Check your words", "Which of these show up in your language?",
                        List.of(new SampleChoice("The Invisible They", true),
                                new SampleChoice("Nobody's Fault", false),
                                new SampleChoice("The Dead End", true))),
                SampleItem.table("Sort it", "Split the situation into two piles.",
                        List.of("Hard facts", "Choices & responses"),
                        List.of(List.of("The deadline moved", "Re-plan the sprint")))));
        return ctx;
    }

    /**
     * Preview stand-ins mirroring the accessor surface the skeleton reads from
     * the exercise feature's own answer items ({@code kind()}, {@code label()},
     * {@code answered()}, …). Thymeleaf resolves those calls reflectively, so
     * the shared kernel can feed the template without importing feature types.
     */
    public record SampleItem(
            String label,
            String prompt,
            String kind,
            String text,
            List<SampleChoice> choices,
            List<String> columns,
            List<List<String>> rows
    ) {
        static SampleItem text(String label, String prompt, String text) {
            return new SampleItem(label, prompt, "TEXT", text, List.of(), List.of(), List.of());
        }

        static SampleItem list(String label, String prompt, List<SampleChoice> choices) {
            return new SampleItem(label, prompt, "LIST", null, choices, List.of(), List.of());
        }

        static SampleItem table(String label, String prompt,
                                List<String> columns, List<List<String>> rows) {
            return new SampleItem(label, prompt, "TABLE", null, List.of(), columns, rows);
        }

        public boolean answered() {
            return (text != null && !text.isBlank())
                    || choices.stream().anyMatch(SampleChoice::checked)
                    || !rows.isEmpty();
        }
    }

    public record SampleChoice(String label, boolean checked) {}
}
