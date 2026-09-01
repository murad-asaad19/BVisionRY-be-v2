package com.bvisionry.exercise;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.common.pdf.template.PdfTemplateKey;
import com.bvisionry.common.pdf.template.PdfTemplateRenderer;
import com.bvisionry.exercise.dto.PublicExerciseSubmitRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseColumnType;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.entity.WorksheetBlock;
import com.bvisionry.exercise.entity.WorksheetBlockType;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
import com.bvisionry.exercise.repository.PublicExerciseResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The render path of a public exercise download. The PDF bytes themselves are
 * Flying Saucer's business — what matters here is that the right questions and
 * answers reach the template, that nothing is written, and that the token
 * gates still hold.
 */
@ExtendWith(MockitoExtension.class)
class PublicExercisePdfServiceTest {

    @Mock private ExerciseTemplateRepository templateRepository;
    @Mock private PublicExerciseResponseRepository responseRepository;
    @Mock private PdfRenderer pdfRenderer;
    @Mock private PdfTemplateRenderer pdfTemplateRenderer;

    private PublicExercisePdfService service;
    private UUID token;
    private ExerciseTemplate template;

    @BeforeEach
    void setUp() {
        token = UUID.randomUUID();
        template = new ExerciseTemplate();
        template.setId(UUID.randomUUID());
        template.setName("The Power Leak Worksheet");
        template.setStatus(ExerciseTemplateStatus.PUBLISHED);
        template.setPublic(true);
        template.setPublicToken(token);

        // The gate and the sanitizers are the submit path's, reused as-is.
        PublicExerciseService publicExerciseService = new PublicExerciseService(
                templateRepository, responseRepository, null, null);
        service = new PublicExercisePdfService(publicExerciseService, pdfRenderer, pdfTemplateRenderer);

        lenient().when(templateRepository.findByPublicTokenWithColumns(token))
                .thenReturn(Optional.of(template));
        lenient().when(pdfRenderer.renderTemplate(any(), any()))
                .thenReturn(new byte[] {1, 2, 3});
        lenient().when(pdfTemplateRenderer.resolveFieldValues(any()))
                .thenReturn(Map.of());
        lenient().when(pdfTemplateRenderer.renderedFieldsWith(any(), any(), any()))
                .thenReturn(Map.of());
    }

    @SuppressWarnings("unchecked")
    private List<PublicExercisePdfService.AnswerItem> renderedItems() {
        ArgumentCaptor<Context> captor = ArgumentCaptor.forClass(Context.class);
        verify(pdfRenderer).renderTemplate(eq("public-exercise-answers"), captor.capture());
        return (List<PublicExercisePdfService.AnswerItem>)
                captor.getValue().getVariable("items");
    }

    private WorksheetBlock worksheet(WorksheetBlockType type, String label,
                                     Map<String, Object> config) {
        template.setKind(ExerciseTemplateKind.WORKSHEET);
        WorksheetBlock block = new WorksheetBlock(UUID.randomUUID(), type, label, false, config);
        List<WorksheetBlock> blocks = new java.util.ArrayList<>(
                template.getBlocks() == null ? List.of() : template.getBlocks());
        blocks.add(block);
        template.setBlocks(blocks);
        return block;
    }

    private ExerciseColumn column(String name) {
        ExerciseColumn column = new ExerciseColumn();
        column.setId(UUID.randomUUID());
        column.setName(name);
        column.setType(ExerciseColumnType.TEXT);
        column.setTemplate(template);
        template.getColumns().add(column);
        return column;
    }

    // ---- gates -----------------------------------------------------------

    @Test
    void render_closedLink_is404() {
        template.setPublic(false);

        assertThatThrownBy(() -> service.render(token,
                new PublicExerciseSubmitRequest(null, null, Map.of(), null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void render_neverStoresAResponse() {
        worksheet(WorksheetBlockType.TEXT, "Start here", Map.of("prompt", "Your situation?"));

        service.render(token, new PublicExerciseSubmitRequest(
                null, null, Map.of(), null));

        verify(responseRepository, never()).save(any());
    }

    /** The whole point of the download: it works when nothing is being saved. */
    @Test
    void render_worksWhenResponsesAreNotSaved() {
        template.setSavePublicResponses(false);
        WorksheetBlock block = worksheet(WorksheetBlockType.TEXT, "Start here",
                Map.of("prompt", "Your situation?"));

        service.render(token, new PublicExerciseSubmitRequest(
                null, null, Map.of(block.id().toString(), "Shipping is late."), null));

        assertThat(renderedItems()).singleElement()
                .satisfies(item -> assertThat(item.text()).isEqualTo("Shipping is late."));
    }

    // ---- what reaches the template ---------------------------------------

    @Test
    void render_skipsContentBlocks_keepingOnlyQuestions() {
        worksheet(WorksheetBlockType.CONTENT, "What power means",
                Map.of("body", "{\"type\":\"doc\"}"));
        worksheet(WorksheetBlockType.TEXT, "Start here", Map.of("prompt", "Your situation?"));

        service.render(token, new PublicExerciseSubmitRequest(null, null, Map.of(), null));

        assertThat(renderedItems()).singleElement()
                .satisfies(item -> assertThat(item.label()).isEqualTo("Start here"));
    }

    /**
     * EVERY option travels, ticked or not: the ones the respondent passed over
     * are part of the question, and a list of only the ticked ones reads as if
     * the rest were never offered.
     */
    @Test
    void render_checkboxesCarryEveryOptionWithItsState() {
        WorksheetBlock block = worksheet(WorksheetBlockType.CHECKBOXES, "Check your words",
                Map.of("options", List.of(
                        Map.of("id", "a", "label", "The Invisible They"),
                        Map.of("id", "b", "label", "Nobody's Fault"))));

        service.render(token, new PublicExerciseSubmitRequest(
                null, null, Map.of(block.id().toString(), List.of("b")), null));

        assertThat(renderedItems()).singleElement()
                .satisfies(item -> assertThat(item.choices()).containsExactly(
                        new PublicExercisePdfService.Choice("The Invisible They", false),
                        new PublicExercisePdfService.Choice("Nobody's Fault", true)));
    }

    /** Nothing ticked is still a rendered checklist, not an "unanswered" note. */
    @Test
    void render_checkboxesWithNothingTickedStillCarryTheOptions() {
        worksheet(WorksheetBlockType.CHECKBOXES, "Check your words",
                Map.of("options", List.of(Map.of("id", "a", "label", "The Invisible They"))));

        service.render(token, new PublicExerciseSubmitRequest(null, null, Map.of(), null));

        assertThat(renderedItems()).singleElement().satisfies(item -> {
            assertThat(item.choices()).containsExactly(
                    new PublicExercisePdfService.Choice("The Invisible They", false));
            assertThat(item.answered()).isFalse();
        });
    }

    @Test
    void render_unansweredQuestionIsMarkedNotAnswered() {
        worksheet(WorksheetBlockType.TEXT, "Rewrite it", Map.of("prompt", "Rewrite it"));

        service.render(token, new PublicExerciseSubmitRequest(null, null, Map.of(), null));

        assertThat(renderedItems()).singleElement()
                .satisfies(item -> assertThat(item.answered()).isFalse());
    }

    @Test
    void render_sheetBecomesOneTableInColumnOrder() {
        template.setKind(ExerciseTemplateKind.SHEET);
        ExerciseColumn idea = column("Idea");
        ExerciseColumn owner = column("Owner");

        service.render(token, new PublicExerciseSubmitRequest(null, null, null,
                List.of(Map.of(
                        owner.getId().toString(), "Sam",
                        idea.getId().toString(), "Ship weekly"))));

        assertThat(renderedItems()).singleElement().satisfies(item -> {
            assertThat(item.columns()).containsExactly("Idea", "Owner");
            assertThat(item.rows()).containsExactly(List.of("Ship weekly", "Sam"));
        });
    }

    @Test
    void render_dropsAnswersForBlocksThatAreNotInTheExercise() {
        worksheet(WorksheetBlockType.TEXT, "Start here", Map.of("prompt", "Your situation?"));

        service.render(token, new PublicExerciseSubmitRequest(null, null,
                Map.of(UUID.randomUUID().toString(), "injected"), null));

        assertThat(renderedItems()).singleElement()
                .satisfies(item -> assertThat(item.answered()).isFalse());
    }

    /**
     * Pins the variable-name seam between this service and the PDF-template
     * schema: the fields are substituted with {@code exerciseName}, the one
     * variable the schema's defaults reference. Mustache renders a missing
     * variable as empty, so a renamed key here would ship blank titles and
     * footers with every other test still green.
     */
    @Test
    void render_passesTheExerciseNameVariableToTheTemplateFields() {
        worksheet(WorksheetBlockType.TEXT, "Start here", Map.of("prompt", "Your situation?"));

        service.render(token, new PublicExerciseSubmitRequest(null, null, Map.of(), null));

        verify(pdfTemplateRenderer).renderedFieldsWith(
                eq(PdfTemplateKey.PUBLIC_EXERCISE_ANSWERS), any(),
                eq(Map.of("exerciseName", "The Power Leak Worksheet")));
    }

    @Test
    void render_filenameSanitizesTheExerciseNameAndKeepsTheExtension() {
        template.setName("Q1: Power / Leak");
        worksheet(WorksheetBlockType.TEXT, "Start here", Map.of());

        var pdf = service.render(token,
                new PublicExerciseSubmitRequest(null, null, Map.of(), null));

        assertThat(pdf.filename()).isEqualTo("Q1_Power__Leak.pdf");
    }
}
