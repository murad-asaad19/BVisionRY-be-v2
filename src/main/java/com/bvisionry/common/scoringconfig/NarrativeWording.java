package com.bvisionry.common.scoringconfig;

/**
 * The "Narrative fixed wording" config document (spec §7) + shipped defaults.
 *
 * <p>Lives in {@code common} for the same reason as {@link ScoringBands}: the
 * {@code platform} slice edits it and the {@code comparison} slice's narrative
 * job CONSUMES it (the not-enough-data sentence it emits instead of calling the
 * model, and the decline-close instruction it appends on a retry). The two must
 * agree on the shape and the defaults or an unedited install would show one
 * wording in the console and emit another.
 *
 * @param notEnoughDataSentence   emitted verbatim, with no model call, for a
 *                                pillar whose before-text is missing or under
 *                                the length floor (§6 guardrail a).
 * @param declineCloseInstruction appended to the system prompt on the single
 *                                retry when a decline pillar's narrative came
 *                                back without a forward-looking next step
 *                                (§6 guardrail b).
 * @param autoApprove             platform-level toggle (§6): OFF (default) =
 *                                approval required, every narrative lands
 *                                DRAFT. ON = generated narratives land
 *                                APPROVED, stamped by the system.
 *                                <p>Governs BOTH review-gated member-visible
 *                                artifacts — the per-pillar shift narratives
 *                                ({@code ShiftNarrativeService}) and the
 *                                member-level growth summary written from them
 *                                ({@code MemberGrowthSummaryService}). An edit
 *                                still returns either to draft. The cohort
 *                                summary is staff-only and has no gate to
 *                                govern.
 */
public record NarrativeWording(String notEnoughDataSentence, String declineCloseInstruction,
                               Boolean autoApprove) {

    /** platform_settings key for the narrative wording document. */
    public static final String NARRATIVE_KEY = "scoring.narrative_wording";

    public static String defaultNotEnoughDataSentence() {
        return "There isn't enough before-data to compare this pillar yet.";
    }

    public static String defaultDeclineCloseInstruction() {
        return "Every decline must end with a concrete next step — never a verdict.";
    }

    public static NarrativeWording defaults() {
        return new NarrativeWording(defaultNotEnoughDataSentence(),
                defaultDeclineCloseInstruction(), false);
    }

    /**
     * Per-field default fallback — a document stored before a field existed
     * parses with it null and must not blank the card (or, worse, feed a null
     * instruction into the retry prompt).
     */
    public NarrativeWording withDefaults() {
        return new NarrativeWording(
                blank(notEnoughDataSentence) ? defaultNotEnoughDataSentence() : notEnoughDataSentence,
                blank(declineCloseInstruction) ? defaultDeclineCloseInstruction() : declineCloseInstruction,
                autoApprove != null && autoApprove);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
