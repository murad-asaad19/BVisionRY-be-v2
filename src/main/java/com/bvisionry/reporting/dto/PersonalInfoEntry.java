package com.bvisionry.reporting.dto;

/**
 * One field of "general information" the member filled in on the assessment —
 * a Personal-pillar question prompt paired with the formatted answer.
 *
 * <p>Used by the assessment side drawer, the per-member PDF/Excel exports,
 * and the team insights PDF. Renders as a label/value row in every surface.
 */
public record PersonalInfoEntry(
        String label,
        String value
) {}
