package com.bvisionry.common.pdf.template;

/**
 * The PDF documents whose text an admin can customize. Each key pairs a locked
 * Thymeleaf skeleton (the layout, never editable) with a schema of editable
 * text fields ({@link PdfTemplateSchemaRegistry}).
 *
 * <p>Lives in the shared kernel ({@code common.pdf}) so any feature that renders
 * a PDF can consume the admin's field values without creating a new
 * cross-feature dependency edge.
 */
public enum PdfTemplateKey {
    /** The respondent's own copy of a public exercise (questions + answers). */
    PUBLIC_EXERCISE_ANSWERS
}
