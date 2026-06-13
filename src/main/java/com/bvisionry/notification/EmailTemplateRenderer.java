package com.bvisionry.notification;

import com.bvisionry.notification.entity.EmailTemplate;
import com.bvisionry.notification.entity.EmailTemplateKey;
import com.bvisionry.notification.schema.EmailTemplateField;
import com.bvisionry.notification.schema.EmailTemplateSchemaRegistry;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import lombok.RequiredArgsConstructor;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders email subject and body from Mustache templates with admin-editable
 * field values merged into a locked HTML skeleton.
 *
 * <p>Field values are polymorphic: {@link String} for text-kind fields and
 * {@link List}{@code <String>} for LIST-kind fields. Each value is Mustache-rendered
 * against the system variables first, then injected into {@code fields.*} in the
 * skeleton's scope — so text fields become pre-substituted strings and LIST fields
 * become iterable {@link List}s that the skeleton can loop over with
 * {@code {{#fields.tips}}...{{/fields.tips}}}.
 */
@Component
@RequiredArgsConstructor
public class EmailTemplateRenderer {

    private final EmailTemplateRepository repository;
    private final EmailTemplateDefaults defaults;
    private final EmailTemplateSchemaRegistry schemaRegistry;

    private final Mustache.Compiler compiler = Mustache.compiler()
            // Don't throw when a variable is missing; render as empty instead.
            // Email authors often reference optional fields (postCompletionUrl, deadline).
            .defaultValue("");

    /**
     * Allowlist policy for RICH_TEXT email fields. Permits a small set of inline
     * formatting tags + safe links. Anything else (script/iframe/onclick/etc.) is
     * stripped before the value is mustache-substituted, so a compromised admin
     * cannot plant phishing markup that lands in recipient inboxes.
     */
    private static final PolicyFactory RICH_TEXT_POLICY = new HtmlPolicyBuilder()
            .allowElements("strong", "em", "b", "i", "u", "br", "span")
            .allowElements("a")
            .allowAttributes("href").onElements("a")
            .allowStandardUrlProtocols()
            .requireRelNofollowOnLinks()
            .toFactory();

    public Rendered render(EmailTemplateKey key, Map<String, Object> systemVariables) {
        Map<String, Object> effectiveFieldValues = resolveFieldValues(key);
        return renderWith(key, effectiveFieldValues, systemVariables);
    }

    /**
     * Used by the admin preview endpoint. Renders the skeleton with the supplied
     * (in-flight) field values, without persisting anything.
     */
    public Rendered renderWith(EmailTemplateKey key,
                               Map<String, Object> fieldValues,
                               Map<String, Object> systemVariables) {
        Map<String, Object> renderedFields = renderFieldValues(
                schemaRegistry.schemaFor(key), fieldValues, systemVariables);

        Map<String, Object> scope = new HashMap<>(systemVariables);
        scope.put("fields", renderedFields);
        computeSectionFlags(key, fieldValues, scope);

        String subjectSource = textValue(fieldValues.get("subject"),
                textValue(schemaRegistry.defaultValues(key).get("subject"), defaults.subject(key)));
        String subject = compile(subjectSource).execute(systemVariables);
        String body    = compile(defaults.body(key)).execute(scope);
        return new Rendered(subject, body);
    }

    /** Effective persisted state (or defaults) for the wizard UI. */
    public Resolved resolve(EmailTemplateKey key) {
        boolean customized = repository.findById(key).isPresent();
        Map<String, Object> values = resolveFieldValues(key);
        return new Resolved(values, customized);
    }

    /**
     * Merge defaults with admin overrides. Types are preserved field-by-field:
     * text field ids map to {@link String}, LIST field ids map to {@link List}{@code <String>}.
     */
    public Map<String, Object> resolveFieldValues(EmailTemplateKey key) {
        Map<String, Object> merged = new LinkedHashMap<>(schemaRegistry.defaultValues(key));
        repository.findById(key)
                .map(EmailTemplate::getFieldValues)
                .ifPresent(saved -> {
                    if (saved != null) merged.putAll(saved);
                });
        return merged;
    }

    private Map<String, Object> renderFieldValues(List<EmailTemplateField> schema,
                                                   Map<String, Object> fieldValues,
                                                   Map<String, Object> systemVariables) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (EmailTemplateField field : schema) {
            Object raw = fieldValues.get(field.id());
            if (field.kind() == EmailTemplateField.Kind.LIST) {
                List<String> items = asItemList(raw);
                List<String> renderedItems = new ArrayList<>(items.size());
                for (String item : items) {
                    if (item == null || item.isBlank()) continue;
                    renderedItems.add(renderField(item, field.kind(), systemVariables));
                }
                rendered.put(field.id(), renderedItems);
            } else {
                String val = textValue(raw, "");
                rendered.put(field.id(), renderField(val, field.kind(), systemVariables));
            }
        }
        return rendered;
    }

    // Render mustache FIRST so {{var}} placeholders inside admin copy resolve
    // against the system variables, then sanitise the result. The sanitiser
    // inserts an HTML comment between adjacent `{` characters to defuse
    // template-injection patterns — sanitising before mustache would split
    // every `{{var}}` and leave the literal placeholder in the inbox.
    //
    // The reverse order is still safe: mustache HTML-escapes system vars by
    // default ({{var}} not {{{var}}}), so a malicious system var arrives at
    // the sanitiser as already-escaped text, not active HTML.
    private String renderField(String source, EmailTemplateField.Kind kind,
                               Map<String, Object> systemVariables) {
        String rendered = compile(source == null ? "" : source).execute(systemVariables);
        String sanitised = sanitiseSource(rendered, kind);
        return htmlizeNewlines(sanitised);
    }

    private static String sanitiseSource(String source, EmailTemplateField.Kind kind) {
        if (source == null || source.isEmpty()) return source;
        return switch (kind) {
            case RICH_TEXT -> RICH_TEXT_POLICY.sanitize(source);
            case PLAIN_TEXT, CTA_LABEL, LIST -> HtmlUtils.htmlEscape(source);
        };
    }

    private static String htmlizeNewlines(String s) {
        if (s == null || s.indexOf('\n') < 0) return s;
        return s.replace("\r\n", "\n").replace("\n", "<br>");
    }

    /**
     * Sets mustache-section flags like {@code showPostCompletion} / {@code showTips}.
     *
     * <p>A section renders only when every conditional field it owns has content AND
     * its required system variables are populated. The field-content check lets admins
     * hide a section by clearing its copy; the system-variable check hides the section
     * on a per-send basis when the upstream data isn't configured (e.g. a pipeline
     * with no post-completion URL shouldn't show the follow-up headline at all).
     */
    private void computeSectionFlags(EmailTemplateKey key,
                                     Map<String, Object> fieldValues,
                                     Map<String, Object> scope) {
        List<EmailTemplateField> schema = schemaRegistry.schemaFor(key);
        Map<String, Boolean> sectionHasContent = new HashMap<>();
        for (EmailTemplateField f : schema) {
            if (!f.conditional() || f.sectionKey() == null) continue;
            boolean hasValue = fieldHasContent(f, fieldValues.get(f.id()));
            sectionHasContent.merge(f.sectionKey(), hasValue, Boolean::logicalOr);
        }
        if (sectionHasContent.containsKey("postCompletion")) {
            boolean hasUrl = !textValue(scope.get("postCompletionUrl"), "").isBlank();
            scope.put("showPostCompletion", sectionHasContent.get("postCompletion") && hasUrl);
        }
        if (sectionHasContent.containsKey("tips")) {
            scope.put("showTips", sectionHasContent.get("tips"));
        }
    }

    private static boolean fieldHasContent(EmailTemplateField field, Object value) {
        if (field.kind() == EmailTemplateField.Kind.LIST) {
            for (String item : asItemList(value)) {
                if (item != null && !item.isBlank()) return true;
            }
            return false;
        }
        String s = textValue(value, "");
        return !s.isBlank();
    }

    /** Best-effort cast of a JSON value (from the DB, the API, or a default) into a List<String>. */
    @SuppressWarnings("unchecked")
    static List<String> asItemList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(o == null ? "" : o.toString());
            return out;
        }
        // A stray string value for a LIST field is treated as a single-item list.
        return List.of(raw.toString());
    }

    static String textValue(Object raw, String fallback) {
        if (raw == null) return fallback;
        if (raw instanceof String s) return s;
        return raw.toString();
    }

    private Template compile(String source) {
        return compiler.compile(source);
    }

    public record Rendered(String subject, String body) {}

    /** Effective field values plus whether they came from an admin override. */
    public record Resolved(Map<String, Object> values, boolean customized) {}
}
