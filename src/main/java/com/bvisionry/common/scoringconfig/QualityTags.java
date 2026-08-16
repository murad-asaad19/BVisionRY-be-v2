package com.bvisionry.common.scoringconfig;

import java.util.List;

/**
 * The exercise quality-tag config document shape + shipped defaults
 * (spec §4/§7): the reviewer's tag set, stable keys with editable labels.
 *
 * <p>Lives in {@code common} for the same reason as {@link ParticipationFormula}:
 * the {@code platform} slice edits/validates the document and the
 * {@code exercise} slice offers and validates tags with it — the two must agree
 * on the shipped defaults or an unedited install would show one set in the
 * console and accept another on review. Pure types + pure functions.
 */
public final class QualityTags {

    /** platform_settings key for the quality-tags document. */
    public static final String KEY = "scoring.quality_tags";

    private QualityTags() {
    }

    /** Mirrors the platform DTO's tag shape — key is identity, label presentation. */
    public record Tag(String key, String label) {
    }

    /** Shipped defaults (spec §4): thin / adequate / strong. */
    public static List<Tag> defaults() {
        return List.of(
                new Tag("thin", "Thin"),
                new Tag("adequate", "Adequate"),
                new Tag("strong", "Strong"));
    }
}
