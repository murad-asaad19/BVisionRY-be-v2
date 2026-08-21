package com.bvisionry.common.scoringconfig;

import java.util.List;

/**
 * The participation-formula config document shape + shipped defaults
 * (spec §4/§7): N weighted categories, the protected always-computed
 * {@code assignments} category, and the participation bands.
 *
 * <p>Lives in {@code common} for the same reason as {@link ScoringBands}: the
 * {@code platform} slice edits/validates the document and the
 * {@code engagement} slice computes participation scores with it — the two
 * must agree on the shipped defaults or an unedited install would show one
 * formula in the console and score with another. Pure types + pure functions.
 */
public final class ParticipationFormula {

    /** platform_settings key for the categories document. */
    public static final String FORMULA_KEY = "scoring.participation_formula";

    /** platform_settings key for the participation bands document. */
    public static final String PARTICIPATION_BANDS_KEY = "scoring.participation_bands";

    /** The protected, always-computed category (spec §4/§7). */
    public static final String ASSIGNMENTS_KEY = "assignments";

    private ParticipationFormula() {
    }

    /** Mirrors the platform DTO's category shape — key is identity, label presentation. */
    public record Category(String key, String label, int weight, boolean computed) {
    }

    /** Shipped defaults (spec §4): Assignments 50 / Workshops 25 / 1:1 15 / group 10. */
    public static List<Category> defaultCategories() {
        return List.of(
                new Category(ASSIGNMENTS_KEY, "Assignments", 50, true),
                new Category("workshops", "Workshops", 25, false),
                new Category("coaching_1on1", "Coaching 1:1", 15, false),
                new Category("coaching_group", "Group coaching", 10, false));
    }

    /** Shipped defaults: 80–100 High · 50–79 Partial · 0–49 Low. */
    public static List<ScoringBands.Band> defaultParticipationBands() {
        return List.of(
                new ScoringBands.Band("band_1", "High", 80, 100),
                new ScoringBands.Band("band_2", "Partial", 50, 79),
                new ScoringBands.Band("band_3", "Low", 0, 49));
    }
}
