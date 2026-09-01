package com.bvisionry.common.pdf.template;

import com.samskivert.mustache.Mustache;
import lombok.RequiredArgsConstructor;
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

    private final PdfTemplateRepository repository;
    private final PdfTemplateSchemaRegistry schemaRegistry;

    private final Mustache.Compiler compiler = Mustache.compiler()
            // Missing variables render empty rather than throwing.
            .defaultValue("")
            // Output is plain text; Thymeleaf escapes it at render time, so
            // escaping here would double-encode.
            .escapeHTML(false);

    /** Effective persisted state (or defaults) for the admin editor. */
    public Resolved resolve(PdfTemplateKey key) {
        boolean customized = repository.findById(key).isPresent();
        return new Resolved(resolveFieldValues(key), customized);
    }

    /** Defaults merged with whatever the admin customized. */
    public Map<String, String> resolveFieldValues(PdfTemplateKey key) {
        Map<String, String> merged = new LinkedHashMap<>(schemaRegistry.defaultValues(key));
        repository.findById(key)
                .map(PdfTemplate::getFieldValues)
                .ifPresent(saved -> saved.forEach((id, value) ->
                        merged.put(id, value == null ? "" : value.toString())));
        return merged;
    }

    /** The persisted (or default) field values with system variables substituted. */
    public Map<String, String> renderedFields(PdfTemplateKey key,
                                              Map<String, Object> systemVariables) {
        return renderedFieldsWith(key, resolveFieldValues(key), systemVariables);
    }

    /**
     * Used by the admin preview. Substitutes system variables into the supplied
     * (in-flight) field values, without persisting anything.
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

    /** Effective field values plus whether they came from an admin override. */
    public record Resolved(Map<String, String> values, boolean customized) {}
}
