package com.bvisionry.common.pdf.template;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.common.pdf.template.dto.PdfTemplateDto;
import com.bvisionry.common.pdf.template.dto.PdfTemplateUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.context.Context;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.bvisionry.common.pdf.template.PdfTemplateKey.PUBLIC_EXERCISE_ANSWERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin surface of PDF templates: only genuine customizations persist, a
 * save of pure defaults is a reset, and preview enforces the same validation
 * as a save before any bytes render.
 */
@ExtendWith(MockitoExtension.class)
class PdfTemplateServiceTest {

    @Mock private PdfTemplateRepository repository;
    @Mock private PdfRenderer pdfRenderer;

    private PdfTemplateService service;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PdfTemplateSchemaRegistry schemaRegistry = new PdfTemplateSchemaRegistry();
        PdfTemplateRenderer renderer = new PdfTemplateRenderer(repository, schemaRegistry);
        service = new PdfTemplateService(repository, renderer, schemaRegistry, pdfRenderer);
    }

    @Test
    void get_withoutARow_returnsDefaultsUncustomized() {
        when(repository.findById(PUBLIC_EXERCISE_ANSWERS)).thenReturn(Optional.empty());

        PdfTemplateDto dto = service.get(PUBLIC_EXERCISE_ANSWERS);

        assertThat(dto.customized()).isFalse();
        assertThat(dto.values()).isEqualTo(dto.defaultValues());
        assertThat(dto.values()).containsEntry("documentTitle", "{{exerciseName}}");
    }

    @Test
    void update_persistsOnlyFieldsThatDifferFromDefaults() {
        when(repository.findById(PUBLIC_EXERCISE_ANSWERS)).thenReturn(Optional.empty());

        service.update(PUBLIC_EXERCISE_ANSWERS, new PdfTemplateUpdateRequest(Map.of(
                "documentTitle", "{{exerciseName}} — My Copy",
                "notAnsweredLabel", "Not answered.")), actor);

        ArgumentCaptor<PdfTemplate> captor = ArgumentCaptor.forClass(PdfTemplate.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFieldValues())
                .containsOnly(Map.entry("documentTitle", "{{exerciseName}} — My Copy"));
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo(actor);
    }

    @Test
    void update_withEverythingAtDefault_deletesTheRow() {
        when(repository.findById(PUBLIC_EXERCISE_ANSWERS)).thenReturn(Optional.empty());

        service.update(PUBLIC_EXERCISE_ANSWERS, new PdfTemplateUpdateRequest(Map.of(
                "documentTitle", "{{exerciseName}}",
                "footerText", "{{exerciseName}} · www.bvisionry.com")), actor);

        verify(repository).deleteById(PUBLIC_EXERCISE_ANSWERS);
        verify(repository, never()).save(any());
    }

    @Test
    void update_rejectsUnknownFields() {
        assertThatThrownBy(() -> service.update(PUBLIC_EXERCISE_ANSWERS,
                new PdfTemplateUpdateRequest(Map.of("nefarious", "x")), actor))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown field");
    }

    @Test
    void update_rejectsValuesOverTheFieldMaxLength() {
        assertThatThrownBy(() -> service.update(PUBLIC_EXERCISE_ANSWERS,
                new PdfTemplateUpdateRequest(Map.of("notAnsweredLabel", "x".repeat(81))), actor))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maximum length");
    }

    @Test
    void update_rejectsVariablesNotAllowedInTheField() {
        assertThatThrownBy(() -> service.update(PUBLIC_EXERCISE_ANSWERS,
                new PdfTemplateUpdateRequest(Map.of("notAnsweredLabel", "{{exerciseName}}")), actor))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void reset_deletesTheRow() {
        when(repository.findById(PUBLIC_EXERCISE_ANSWERS)).thenReturn(Optional.empty());

        PdfTemplateDto dto = service.reset(PUBLIC_EXERCISE_ANSWERS);

        verify(repository).deleteById(PUBLIC_EXERCISE_ANSWERS);
        assertThat(dto.customized()).isFalse();
    }

    @Test
    void preview_enforcesTheSameValidationAsASave() {
        assertThatThrownBy(() -> service.preview(PUBLIC_EXERCISE_ANSWERS,
                Map.of("documentTitle", "{{unknownVariable}}")))
                .isInstanceOf(BadRequestException.class);
        verify(pdfRenderer, never()).renderTemplate(any(), any());
    }

    @Test
    void preview_substitutesSampleVariablesIntoTheInFlightValues() {
        when(pdfRenderer.renderTemplate(eq("public-exercise-answers"), any()))
                .thenReturn(new byte[] {1, 2, 3});

        byte[] pdf = service.preview(PUBLIC_EXERCISE_ANSWERS,
                Map.of("documentTitle", "{{exerciseName}} — Draft"));

        assertThat(pdf).containsExactly(1, 2, 3);
        ArgumentCaptor<Context> captor = ArgumentCaptor.forClass(Context.class);
        verify(pdfRenderer).renderTemplate(eq("public-exercise-answers"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) captor.getValue().getVariable("fields");
        assertThat(fields).containsEntry("documentTitle", "The Power Leak Worksheet — Draft");
        // Untouched fields fall back to their (substituted) defaults.
        assertThat(fields).containsEntry("footerText", "The Power Leak Worksheet · www.bvisionry.com");
    }

    @Test
    void renderedFields_mergeAdminOverridesOverDefaults() {
        PdfTemplate row = new PdfTemplate();
        row.setKey(PUBLIC_EXERCISE_ANSWERS);
        row.setFieldValues(Map.of("footerText", "Confidential — {{exerciseName}}"));
        when(repository.findById(PUBLIC_EXERCISE_ANSWERS)).thenReturn(Optional.of(row));

        PdfTemplateRenderer renderer = new PdfTemplateRenderer(repository, new PdfTemplateSchemaRegistry());
        Map<String, String> fields = renderer.renderedFields(PUBLIC_EXERCISE_ANSWERS,
                Map.of("exerciseName", "Power Leak"));

        assertThat(fields).containsEntry("footerText", "Confidential — Power Leak");
        assertThat(fields).containsEntry("documentTitle", "Power Leak");
        assertThat(fields).containsEntry("notAnsweredLabel", "Not answered.");
    }
}
