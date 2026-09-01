package com.bvisionry.exercise;

import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.common.pdf.template.PdfTemplateKey;
import com.bvisionry.common.pdf.template.PdfTemplateMetadata;
import com.bvisionry.common.pdf.template.PdfTemplateRenderer;
import com.bvisionry.exercise.dto.PublicExerciseSubmitRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.WorksheetBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A respondent's own copy of a public exercise, as a branded PDF: the
 * questions and their answers, nothing else.
 *
 * <p><strong>Renders, never stores.</strong> The fill arrives in the request
 * and leaves as bytes — no {@code PublicExerciseResponse} is written, and the
 * template's {@code savePublicResponses} flag is deliberately NOT consulted:
 * the download exists precisely so a respondent can keep their work when the
 * link stores nothing. Anything worth persisting goes through
 * {@link PublicExerciseService#submit}, which is the only writer.
 *
 * <p>The fill is put through the same sanitizers as a submit (unknown blocks
 * and columns dropped, locked cells restored, the content ceiling enforced) —
 * a public endpoint must not render arbitrary caller-supplied text, and the
 * PDF should show exactly what a submit would have kept. Completeness is NOT
 * required: a half-finished worksheet is still worth printing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicExercisePdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final PublicExerciseService publicExerciseService;
    private final PdfRenderer pdfRenderer;
    private final PdfTemplateRenderer pdfTemplateRenderer;

    /** The rendered document plus the filename the browser should save it as. */
    public record RenderedPdf(String filename, byte[] bytes) {}

    /** One checklist option and whether the respondent ticked it. */
    public record Choice(String label, boolean checked) {}

    /**
     * One question and its answer. {@code kind} picks the shape the template
     * renders: {@code TEXT} (one paragraph), {@code LIST} (the checklist) or
     * {@code TABLE} (a grid, also used for the whole of a SHEET exercise).
     */
    public record AnswerItem(
            String label,
            String prompt,
            String kind,
            String text,
            /**
             * EVERY option of a checklist, ticked or not — the options the
             * respondent did not pick are part of the question, and a list of
             * only the ticked ones reads as if the rest were never offered.
             */
            List<Choice> choices,
            List<String> columns,
            List<List<String>> rows
    ) {
        static AnswerItem text(String label, String prompt, String text) {
            return new AnswerItem(label, prompt, "TEXT", text, List.of(), List.of(), List.of());
        }

        static AnswerItem list(String label, String prompt, List<Choice> choices) {
            return new AnswerItem(label, prompt, "LIST", null, choices, List.of(), List.of());
        }

        static AnswerItem table(String label, String prompt,
                                List<String> columns, List<List<String>> rows) {
            return new AnswerItem(label, prompt, "TABLE", null, List.of(), columns, rows);
        }

        /**
         * True when the respondent put something in. A checklist is exempt:
         * it always renders its boxes, and an all-unticked list shows that on
         * its face without a second "Not answered." line.
         */
        public boolean answered() {
            return (text != null && !text.isBlank())
                    || choices.stream().anyMatch(Choice::checked)
                    || !rows.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public RenderedPdf render(UUID token, PublicExerciseSubmitRequest fill) {
        ExerciseTemplate template = publicExerciseService.requirePublicTemplate(token);
        PdfTemplateKey templateKey = PdfTemplateKey.PUBLIC_EXERCISE_ANSWERS;

        Context ctx = new Context();
        ctx.setVariable("exerciseName", template.getName());
        // The date rides inside respondentLine — the masthead prints one line.
        ctx.setVariable("respondentLine", respondentLine(fill));
        ctx.setVariable("items", template.getKind() == ExerciseTemplateKind.WORKSHEET
                ? worksheetItems(template, fill)
                : sheetItems(template, fill));
        // Admin-editable copy (title, intro note, footer, "not answered" marker) —
        // customized through the PDF-templates admin console, defaults otherwise.
        ctx.setVariable("fields", pdfTemplateRenderer.renderedFieldsWith(
                templateKey,
                pdfTemplateRenderer.resolveFieldValues(templateKey),
                Map.of("exerciseName", template.getName())));

        byte[] pdf = pdfRenderer.renderTemplate(PdfTemplateMetadata.templateName(templateKey), ctx);
        log.info("Rendered public exercise PDF for template {} ({} bytes)",
                template.getId(), pdf.length);
        return new RenderedPdf(filename(template.getName()), pdf);
    }

    /**
     * Sanitize the NAME then append the extension — the shared sanitizer
     * strips dots, so running it over "x.pdf" would leave the file
     * extensionless. An exercise named entirely in non-Latin characters
     * sanitizes to nothing, hence the fallback.
     */
    private static String filename(String exerciseName) {
        String base = XlsxResponse.sanitizeFilename(exerciseName);
        return (base.isBlank() ? "Exercise" : base) + ".pdf";
    }

    // ------------------------------------------------------------ worksheet

    /**
     * One item per answerable block, in worksheet order. CONTENT blocks are
     * skipped: they are the page's own prose, and this document is the
     * questions and the answers.
     */
    private List<AnswerItem> worksheetItems(ExerciseTemplate template,
                                            PublicExerciseSubmitRequest fill) {
        List<WorksheetBlock> blocks = template.getBlocks() == null
                ? List.of() : template.getBlocks();
        Map<String, Object> answers = WorksheetBlocks.sanitizeAnswers(fill.answers(), blocks);
        PublicExerciseService.requireWithinContentLimit(answers);

        List<AnswerItem> items = new ArrayList<>();
        for (WorksheetBlock block : blocks) {
            Object value = answers.get(block.id().toString());
            String label = block.label() == null ? "" : block.label();
            String prompt = configText(block, "prompt");
            switch (block.type()) {
                case CONTENT -> { /* page prose, not a question */ }
                case TEXT -> items.add(AnswerItem.text(label, prompt,
                        value instanceof String s ? s : ""));
                case CHECKBOXES -> {
                    Set<String> ticked = Set.copyOf(texts(value));
                    items.add(AnswerItem.list(label, prompt,
                            WorksheetBlocks.entryLabels(block, "options", "label")
                                    .entrySet().stream()
                                    .map(option -> new Choice(option.getValue(),
                                            ticked.contains(option.getKey())))
                                    .toList()));
                }
                case TABLE -> {
                    Map<String, String> columns =
                            WorksheetBlocks.entryLabels(block, "columns", "name");
                    items.add(AnswerItem.table(label, prompt,
                            List.copyOf(columns.values()),
                            tableRows(value, columns.keySet())));
                }
            }
        }
        return items;
    }

    // ---------------------------------------------------------------- sheet

    /** A SHEET is one grid: its columns in display order, and the filled rows. */
    private List<AnswerItem> sheetItems(ExerciseTemplate template,
                                        PublicExerciseSubmitRequest fill) {
        List<Map<String, Object>> rows =
                publicExerciseService.sanitizeRows(fill.rows(), template);
        PublicExerciseService.requireWithinContentLimit(rows);

        List<ExerciseColumn> columns = template.getColumns();
        List<List<String>> body = rows.stream()
                .map(row -> columns.stream()
                        .map(column -> cellText(row.get(column.getId().toString())))
                        .toList())
                .toList();
        return List.of(AnswerItem.table(null, null,
                columns.stream().map(ExerciseColumn::getName).toList(), body));
    }

    // ---------------------------------------------------------------- utils

    /** A TABLE answer as rows of cells, in the block's configured column order. */
    private static List<List<String>> tableRows(Object value, Set<String> columnIds) {
        if (!(value instanceof Collection<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> columnIds.stream()
                        .map(id -> cellText(((Map<?, ?>) row).get(id)))
                        .toList())
                .toList();
    }

    /** A CHECKBOXES answer as the ticked ids. */
    private static List<String> texts(Object value) {
        return value instanceof Collection<?> items
                ? items.stream().map(String::valueOf).toList()
                : List.of();
    }

    /** One cell as display text — a LIST column stores an array. */
    private static String cellText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> entries) {
            return entries.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
        }
        return String.valueOf(value);
    }

    private static String configText(WorksheetBlock block, String key) {
        Object value = block.config() == null ? null : block.config().get(key);
        return value instanceof String s ? s : "";
    }

    /**
     * The masthead's byline: whichever of name and email the respondent gave,
     * then the date. Joined here rather than in the template because SpEL
     * coerces the operands of {@code and}/{@code or} to boolean, so combining
     * two optional strings in the markup fails on any non-empty value.
     */
    private static String respondentLine(PublicExerciseSubmitRequest fill) {
        return Stream.of(fill.respondentName(), fill.respondentEmail(),
                        LocalDate.now().format(DATE))
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" · "));
    }
}
