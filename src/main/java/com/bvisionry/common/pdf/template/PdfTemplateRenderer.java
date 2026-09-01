package com.bvisionry.common.pdf.template;

import com.samskivert.mustache.Mustache;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the effective field values of a PDF template (admin overrides
 * merged over the shipped defaults) and Mustache-substitutes the allowed
 * system variables into them.
 *
 * <p>The output is a map of plain strings that PDF services put into their
 * Thymeleaf context as {@code fields} — the skeleton reads them with
 * {@code th:text}, which HTML-escapes, so neither admin copy nor variable
 * values can inject markup into the document.
 */
@Component
@RequiredArgsConstructor
public class PdfTemplateRenderer {

    /**
     * Cache region for resolved field values (see {@code CacheConfig}); the
     * hot public download path resolves once per render, and the values only
     * change when an admin saves. {@link PdfTemplateService} evicts on
     * update/reset; the region TTL is the safety net.
     */
    public static final String FIELD_VALUES_CACHE = "pdfTemplateFieldValues";

    private final PdfTemplateRepository repository;
    private final PdfTemplateSchemaRegistry schemaRegistry;

    private final Mustache.Compiler compiler = Mustache.compiler()
            // Missing variables render empty rather than throwing.
            .defaultValue("")
            // Output is plain text; Thymeleaf escapes it at render time, so
            // escaping here would double-encode.
            .escapeHTML(false);

    /** Defaults merged with whatever the admin customized, cached per key. */
    @Cacheable(value = FIELD_VALUES_CACHE, key = "#key.name()")
    public Map<String, String> resolveFieldValues(PdfTemplateKey key) {
        return effectiveValues(key, repository.findById(key)
                .map(PdfTemplate::getFieldValues)
                .orElse(null));
    }

    /**
     * Defaults overlaid with the supplied persisted overrides. Keys no longer
     * in the schema are dropped rather than surfaced: the admin editor
     * round-trips the full map it is given, so a stale id left behind by a
     * renamed field (or a hand-edited row) must not make every subsequent
     * save fail the unknown-field validation.
     */
    public Map<String, String> effectiveValues(PdfTemplateKey key, Map<String, Object> saved) {
        Map<String, String> merged = new LinkedHashMap<>(schemaRegistry.defaultValues(key));
        if (saved != null) {
            saved.forEach((id, value) -> {
                if (merged.containsKey(id)) {
                    merged.put(id, value == null ? "" : value.toString());
                }
            });
        }
        return merged;
    }

    /**
     * Substitutes system variables into the supplied field values — the
     * persisted ones on the real render path, or in-flight ones from the
     * admin preview. Persists nothing.
     */
    public Map<String, String> renderedFieldsWith(PdfTemplateKey key,
                                                  Map<String, String> fieldValues,
                                                  Map<String, Object> systemVariables) {
        List<PdfTemplateField> schema = schemaRegistry.schemaFor(key);
        Map<String, String> rendered = new LinkedHashMap<>();
        for (PdfTemplateField field : schema) {
            String source = fieldValues.getOrDefault(field.id(), "");
            rendered.put(field.id(),
                    compiler.compile(source == null ? "" : source).execute(systemVariables));
        }
        return rendered;
    }

    /**
     * The template error for a field source, or {@code null} when it renders
     * cleanly. Compiles AND executes (against an empty scope, where missing
     * variables default to empty) because jmustache reports some constructs —
     * a {@code {{>partial}}} with no loader, for one — only at execution.
     * Save/preview validation rejects what render could not execute, so a
     * malformed value can never persist and then 500 the public download.
     */
    public String syntaxError(String source) {
        try {
            compiler.compile(source == null ? "" : source).execute(Map.of());
            return null;
        } catch (RuntimeException e) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? "invalid template syntax" : message;
        }
    }
}
