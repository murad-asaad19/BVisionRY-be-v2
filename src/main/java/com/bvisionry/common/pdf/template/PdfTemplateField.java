package com.bvisionry.common.pdf.template;

import java.util.List;

/**
 * One editable text field of a PDF template. All PDF fields are plain text:
 * values are Mustache-substituted against the allowed variables and then
 * escaped by Thymeleaf ({@code th:text}) on their way into the document, so an
 * admin can never inject markup into the locked skeleton.
 *
 * @param id               field id — the key in {@code fields.*} the skeleton reads
 * @param label            human label for the admin editor
 * @param helpText         one-line hint shown next to the label
 * @param maxLength        maximum value length, enforced on save and preview
 * @param allowedVariables {@code {{variable}}} tokens permitted in this field
 * @param defaultValue     the shipped default
 * @param section          editor grouping label (e.g. "Masthead", "Footer")
 * @param required         a blank value is rejected on save; the skeleton
 *                         prints these unconditionally, so an empty one would
 *                         read as a rendering fault on paper
 */
public record PdfTemplateField(
        String id,
        String label,
        String helpText,
        int maxLength,
        List<String> allowedVariables,
        String defaultValue,
        String section,
        boolean required
) {}
