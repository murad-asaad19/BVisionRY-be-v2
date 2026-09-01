package com.bvisionry.common.pdf.template;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.common.pdf.template.dto.PdfTemplateDto;
import com.bvisionry.common.pdf.template.dto.PdfTemplateSummaryDto;
import com.bvisionry.common.pdf.template.dto.PdfTemplateUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin-facing operations on editable PDF templates. Rendering for real
 * documents lives with each feature's PDF service, which pulls the effective
 * field values through {@link PdfTemplateRenderer}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PdfTemplateService {

    // Finds {{var}} tokens that are plain variable references (no #, /, ^, &, !, >).
    private static final Pattern VARIABLE_TOKEN =
            Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)\\s*}}");

    private final PdfTemplateRepository repository;
    private final PdfTemplateRenderer renderer;
    private final PdfTemplateSchemaRegistry schemaRegistry;
    private final PdfRenderer pdfRenderer;

    @Transactional(readOnly = true)
    public List<PdfTemplateSummaryDto> listAll() {
        return Arrays.stream(PdfTemplateKey.values())
                .map(this::summarize)
                .toList();
    }

    @Transactional(readOnly = true)
    public PdfTemplateDto get(PdfTemplateKey key) {
        PdfTemplateRenderer.Resolved resolved = renderer.resolve(key);
        Instant updatedAt = repository.findById(key)
                .map(PdfTemplate::getUpdatedAt)
                .orElse(null);

        List<PdfTemplateDto.PdfFieldSchema> fieldSchemas = schemaRegistry.schemaFor(key).stream()
                .map(PdfTemplateService::toDto)
                .toList();

        return new PdfTemplateDto(
                key,
                PdfTemplateMetadata.displayName(key),
                PdfTemplateMetadata.description(key),
                resolved.customized(),
                updatedAt,
                fieldSchemas,
                resolved.values(),
                schemaRegistry.defaultValues(key),
                PdfTemplateMetadata.variables(key)
        );
    }

    @Transactional
    public PdfTemplateDto update(PdfTemplateKey key, PdfTemplateUpdateRequest request, UUID actorId) {
        Map<String, String> incoming = request.values() == null ? Map.of() : request.values();
        Map<String, String> normalized = validateOverrides(key, incoming);

        // Persist only fields the admin actually customized away from the default so
        // future changes to defaults flow through automatically.
        Map<String, String> defaults = schemaRegistry.defaultValues(key);
        Map<String, Object> toPersist = new LinkedHashMap<>();
        for (PdfTemplateField field : schemaRegistry.schemaFor(key)) {
            String value = normalized.get(field.id());
            if (value == null) continue;
            if (!Objects.equals(value, defaults.get(field.id()))) {
                toPersist.put(field.id(), value);
            }
        }

        if (toPersist.isEmpty()) {
            // Every field is at its default — treat as a reset.
            repository.deleteById(key);
        } else {
            PdfTemplate template = repository.findById(key).orElseGet(() -> {
                PdfTemplate t = new PdfTemplate();
                t.setKey(key);
                return t;
            });
            template.setFieldValues(toPersist);
            template.setUpdatedBy(actorId);
            template.setUpdatedAt(Instant.now());
            repository.save(template);
        }

        log.info("PDF template {} updated by {}", key, actorId);
        return get(key);
    }

    @Transactional
    public PdfTemplateDto reset(PdfTemplateKey key) {
        repository.deleteById(key);
        log.info("PDF template {} reset to default", key);
        return get(key);
    }

    /**
     * Renders the template with the supplied (in-flight) field values over
     * sample data, returning the PDF bytes. Nothing is persisted. Enforces the
     * same validation as {@link #update} so a preview can never render values a
     * save would have rejected.
     */
    @Transactional(readOnly = true)
    public byte[] preview(PdfTemplateKey key, Map<String, String> values) {
        Map<String, String> normalized = validateOverrides(key, values == null ? Map.of() : values);
        Map<String, String> merged = new LinkedHashMap<>(schemaRegistry.defaultValues(key));
        merged.putAll(normalized);

        Map<String, String> renderedFields = renderer.renderedFieldsWith(
                key, merged, PdfTemplateMetadata.sampleVariables(key));
        Context ctx = PdfTemplateMetadata.sampleContext(key);
        ctx.setVariable("fields", renderedFields);
        return pdfRenderer.renderTemplate(PdfTemplateMetadata.templateName(key), ctx);
    }

    /**
     * The single validation gate shared by {@link #update} and {@link #preview}:
     * every supplied field must exist in the schema, respect its max length, and
     * reference only its allowed {@code {{variables}}}.
     */
    private Map<String, String> validateOverrides(PdfTemplateKey key, Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();

        Map<String, PdfTemplateField> byId = new LinkedHashMap<>();
        for (PdfTemplateField field : schemaRegistry.schemaFor(key)) {
            byId.put(field.id(), field);
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            PdfTemplateField field = byId.get(entry.getKey());
            if (field == null) {
                errors.add("Unknown field: " + entry.getKey());
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.length() > field.maxLength()) {
                errors.add(field.label() + ": exceeds maximum length of " + field.maxLength());
            }
            checkVariables(field, value, errors);
            normalized.put(field.id(), value);
        }
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join("; ", errors));
        }
        return normalized;
    }

    private static void checkVariables(PdfTemplateField field, String value, List<String> errors) {
        Matcher matcher = VARIABLE_TOKEN.matcher(value);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!field.allowedVariables().contains(token)) {
                errors.add(field.label() + ": variable {{" + token + "}} is not allowed here");
            }
        }
    }

    private PdfTemplateSummaryDto summarize(PdfTemplateKey key) {
        Instant updatedAt = repository.findById(key)
                .map(PdfTemplate::getUpdatedAt)
                .orElse(null);
        return new PdfTemplateSummaryDto(
                key,
                PdfTemplateMetadata.displayName(key),
                PdfTemplateMetadata.description(key),
                updatedAt != null,
                updatedAt
        );
    }

    private static PdfTemplateDto.PdfFieldSchema toDto(PdfTemplateField field) {
        return new PdfTemplateDto.PdfFieldSchema(
                field.id(), field.label(), field.helpText(),
                field.maxLength(), field.allowedVariables(),
                field.defaultValue(), field.section()
        );
    }
}
