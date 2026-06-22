package com.bvisionry.notification;

import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.dto.EmailTemplateDto;
import com.bvisionry.notification.dto.EmailTemplatePreviewResponse;
import com.bvisionry.notification.dto.EmailTemplateSummaryDto;
import com.bvisionry.notification.dto.EmailTemplateUpdateRequest;
import com.bvisionry.notification.entity.EmailTemplate;
import com.bvisionry.notification.entity.EmailTemplateKey;
import com.bvisionry.notification.schema.EmailTemplateField;
import com.bvisionry.notification.schema.EmailTemplateSchemaRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Admin-facing operations on editable email templates.
 * Rendering and sending live in {@link EmailTemplateRenderer} / {@link EmailService} respectively.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailTemplateService {

    // Finds {{var}} tokens that are plain variable references (no #, /, ^, &, !, >).
    private static final Pattern VARIABLE_TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)\\s*}}");

    private final EmailTemplateRepository repository;
    private final EmailTemplateRenderer renderer;
    private final EmailTemplateSchemaRegistry schemaRegistry;
    private final EmailService emailService;
    private final FrontendUrls frontendUrls;

    @Transactional(readOnly = true)
    public List<EmailTemplateSummaryDto> listAll() {
        return Arrays.stream(EmailTemplateKey.values())
                .map(this::summarize)
                .toList();
    }

    @Transactional(readOnly = true)
    public EmailTemplateDto get(EmailTemplateKey key) {
        EmailTemplateRenderer.Resolved resolved = renderer.resolve(key);
        Instant updatedAt = repository.findById(key)
                .map(EmailTemplate::getUpdatedAt)
                .orElse(null);

        List<EmailTemplateField> schema = schemaRegistry.schemaFor(key);
        List<EmailTemplateDto.FieldSchema> fieldSchemas = schema.stream()
                .map(EmailTemplateService::toDto)
                .toList();

        return new EmailTemplateDto(
                key,
                EmailTemplateMetadata.displayName(key),
                EmailTemplateMetadata.description(key),
                resolved.customized(),
                updatedAt,
                fieldSchemas,
                resolved.values(),
                schemaRegistry.defaultValues(key),
                EmailTemplateMetadata.variables(key)
        );
    }

    @Transactional
    public EmailTemplateDto update(EmailTemplateKey key, EmailTemplateUpdateRequest request, UUID actorId) {
        Map<String, Object> incoming = request.values() == null ? Map.of() : request.values();
        Map<String, Object> normalized = validateOverrides(key, incoming);

        // Persist only fields the admin actually customized away from the default so
        // future changes to defaults flow through automatically.
        List<EmailTemplateField> schema = schemaRegistry.schemaFor(key);
        Map<String, Object> defaults = schemaRegistry.defaultValues(key);
        Map<String, Object> toPersist = new LinkedHashMap<>();
        for (EmailTemplateField f : schema) {
            Object v = normalized.get(f.id());
            if (v == null) continue;
            if (!Objects.equals(v, defaults.get(f.id()))) {
                toPersist.put(f.id(), v);
            }
        }

        if (toPersist.isEmpty()) {
            // Every field is at its default — treat as a reset.
            repository.deleteById(key);
        } else {
            EmailTemplate template = repository.findById(key).orElseGet(() -> {
                EmailTemplate t = new EmailTemplate();
                t.setKey(key);
                return t;
            });
            template.setFieldValues(toPersist);
            template.setUpdatedBy(actorId);
            template.setUpdatedAt(Instant.now());
            repository.save(template);
        }

        log.info("Email template {} updated by {}", key, actorId);
        return get(key);
    }

    @Transactional
    public EmailTemplateDto reset(EmailTemplateKey key) {
        repository.deleteById(key);
        log.info("Email template {} reset to default", key);
        return get(key);
    }

    public EmailTemplatePreviewResponse preview(EmailTemplateKey key, Map<String, Object> values) {
        try {
            // Enforce the same variable-allowlist / length validation as update():
            // preview must not render admin-supplied values that inject disallowed
            // {{variables}} (e.g. leaking unrelated context into the message).
            validateOverrides(key, values);
            Map<String, Object> merged = mergeWithDefaults(key, values);
            EmailTemplateRenderer.Rendered rendered = renderer.renderWith(
                    key, merged, EmailTemplateMetadata.sampleValues(key, frontendUrls));
            return EmailTemplatePreviewResponse.ok(rendered.subject(), rendered.body());
        } catch (Exception e) {
            return EmailTemplatePreviewResponse.error(e.getMessage());
        }
    }

    /** Renders with the supplied values + sample variables and delivers to the supplied address. */
    public void sendTest(EmailTemplateKey key, Map<String, Object> values, String toEmail) {
        // Same allowlist enforcement as update()/preview() — a test send must not
        // bypass the validation an actual save would have rejected.
        validateOverrides(key, values);
        Map<String, Object> merged = mergeWithDefaults(key, values);
        EmailTemplateRenderer.Rendered rendered = renderer.renderWith(
                key, merged, EmailTemplateMetadata.sampleValues(key, frontendUrls));
        emailService.sendRaw(toEmail, "[TEST] " + rendered.subject(), rendered.body());
    }

    /**
     * Normalizes the supplied overrides and runs schema validation, returning the
     * normalized values. The single validation gate shared by update(), preview()
     * and sendTest() so a test send / preview can never bypass what a save rejects.
     */
    private Map<String, Object> validateOverrides(EmailTemplateKey key, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        List<EmailTemplateField> schema = schemaRegistry.schemaFor(key);
        Map<String, Object> normalized = normalize(values, schema);
        validate(normalized, schema);
        return normalized;
    }

    /**
     * Canonicalize incoming values to the type each field expects (trims strings,
     * drops blank items from lists, normalizes nulls). JSON delivers lists as
     * {@code List<?>} and strings as {@code String}, but accept either for LIST
     * fields so the admin UI can send a newline-separated string if it wants.
     */
    private Map<String, Object> normalize(Map<String, Object> incoming, List<EmailTemplateField> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (EmailTemplateField f : schema) {
            if (!incoming.containsKey(f.id())) continue;
            Object raw = incoming.get(f.id());
            if (f.kind() == EmailTemplateField.Kind.LIST) {
                out.put(f.id(), normalizeList(raw));
            } else {
                out.put(f.id(), raw == null ? "" : raw.toString());
            }
        }
        return out;
    }

    private List<String> normalizeList(Object raw) {
        List<String> source = EmailTemplateRenderer.asItemList(raw);
        List<String> out = new ArrayList<>(source.size());
        for (String item : source) {
            if (item == null) continue;
            String trimmed = item.strip();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private void validate(Map<String, Object> values, List<EmailTemplateField> schema) {
        Map<String, EmailTemplateField> byId = new LinkedHashMap<>();
        for (EmailTemplateField f : schema) byId.put(f.id(), f);

        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            EmailTemplateField field = byId.get(e.getKey());
            if (field == null) {
                errors.add("Unknown field: " + e.getKey());
                continue;
            }
            if (field.kind() == EmailTemplateField.Kind.LIST) {
                validateList(field, EmailTemplateRenderer.asItemList(e.getValue()), errors);
            } else {
                validateText(field, EmailTemplateRenderer.textValue(e.getValue(), ""), errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private void validateText(EmailTemplateField field, String value, List<String> errors) {
        if (value.length() > field.maxLength()) {
            errors.add(field.label() + ": exceeds maximum length of " + field.maxLength());
        }
        checkVariables(field, value, errors, null);
    }

    private void validateList(EmailTemplateField field, List<String> items, List<String> errors) {
        if (items.size() > field.maxItems()) {
            errors.add(field.label() + ": exceeds maximum of " + field.maxItems() + " items");
        }
        int idx = 0;
        for (String item : items) {
            idx++;
            if (item.length() > field.itemMaxLength()) {
                errors.add(field.label() + " item " + idx
                        + ": exceeds maximum length of " + field.itemMaxLength());
            }
            checkVariables(field, item, errors, idx);
        }
    }

    private void checkVariables(EmailTemplateField field, String value, List<String> errors, Integer itemIndex) {
        Matcher m = VARIABLE_TOKEN.matcher(value);
        while (m.find()) {
            String token = m.group(1);
            if (!field.allowedVariables().contains(token)) {
                String where = itemIndex == null ? field.label() : field.label() + " item " + itemIndex;
                errors.add(where + ": variable {{" + token + "}} is not allowed here");
            }
        }
    }

    private Map<String, Object> mergeWithDefaults(EmailTemplateKey key, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>(schemaRegistry.defaultValues(key));
        if (overrides != null) {
            List<EmailTemplateField> schema = schemaRegistry.schemaFor(key);
            Map<String, Object> normalized = normalize(overrides, schema);
            for (Map.Entry<String, Object> e : normalized.entrySet()) {
                if (merged.containsKey(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        return merged;
    }

    private EmailTemplateSummaryDto summarize(EmailTemplateKey key) {
        Instant updatedAt = repository.findById(key)
                .map(EmailTemplate::getUpdatedAt)
                .orElse(null);
        return new EmailTemplateSummaryDto(
                key,
                EmailTemplateMetadata.displayName(key),
                EmailTemplateMetadata.description(key),
                updatedAt != null,
                updatedAt
        );
    }

    private static EmailTemplateDto.FieldSchema toDto(EmailTemplateField f) {
        return new EmailTemplateDto.FieldSchema(
                f.id(), f.label(), f.helpText(),
                f.kind().name(),
                f.maxLength(), f.allowedVariables(),
                f.defaultValue(),
                f.step(), f.stepLabel(),
                f.conditional(), f.sectionKey(),
                f.itemMaxLength(), f.maxItems(),
                f.defaultItems()
        );
    }
}
