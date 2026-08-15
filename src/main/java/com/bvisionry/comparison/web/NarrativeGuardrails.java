package com.bvisionry.comparison.web;

import com.bvisionry.common.dto.ShiftNarrativeResult;
import com.bvisionry.comparison.domain.NarrativeKind;

import java.util.List;
import java.util.Optional;

/**
 * Spec §6's guardrails as CODE, not prompt-hope. Pure functions, no Spring, no
 * database — so every rule is falsifiable in a plain JUnit test rather than
 * only observable through a live model.
 *
 * <ol>
 *   <li><b>Not enough before-data.</b> A pillar whose baseline text blocks are
 *       missing or under {@link #MIN_BEFORE_TEXT_CHARS} never reaches the model
 *       at all — the configured standard sentence is surfaced instead.</li>
 *   <li><b>Decline close.</b> A pillar in the decline band must come back with a
 *       non-blank {@code closingAction}. The caller re-asks once with the
 *       configured decline-close instruction appended; a second failure is a
 *       generation failure, never a persisted decline without a way forward.</li>
 *   <li><b>Kind sanity.</b> The classification must be one of the five
 *       (see {@link NarrativeKind#parse}); garbage is re-asked once.</li>
 * </ol>
 */
public final class NarrativeGuardrails {

    /**
     * Length floor for the baseline text, summed across "what's working" +
     * "what can improve" (spec §6: "before-text missing or under a length
     * floor"). 40 characters is roughly one short clause — below it there is no
     * "before" to compare against, only a stub, and a narrative built on it
     * would be invention dressed as evidence. A constant, not config: it is a
     * property of the input, not a label anyone would want to tune per install.
     */
    public static final int MIN_BEFORE_TEXT_CHARS = 40;

    private NarrativeGuardrails() {
    }

    /** Total length of the text actually available, blanks and nulls ignored. */
    public static int textLength(List<String> whatsWorking, List<String> whatCanImprove) {
        return length(whatsWorking) + length(whatCanImprove);
    }

    /** Guardrail (a): is there enough of a "before" to compare against at all? */
    public static boolean hasEnoughBeforeText(List<String> whatsWorking, List<String> whatCanImprove) {
        return textLength(whatsWorking, whatCanImprove) >= MIN_BEFORE_TEXT_CHARS;
    }

    /**
     * Guardrail (b): a DECLINING pillar's narrative must close with a next step.
     *
     * <p>Takes the stamped boolean, not a band key. §7 lets a super admin rename
     * or delete any band, so {@code "decline".equals(bandKey)} is a guardrail
     * with an off switch in the config screen; the delta's sign is not.
     */
    public static boolean requiresClosingAction(boolean decline) {
        return decline;
    }

    /** Why a model response was rejected — drives the single corrective retry. */
    public enum Rejection {
        /** The classification was not one of the five kinds. */
        BAD_KIND,
        /** Empty prose — nothing to review, let alone approve. */
        EMPTY_NARRATIVE,
        /** A decline pillar came back with no forward-looking next step. */
        MISSING_CLOSING_ACTION
    }

    /**
     * Validates one model response. Empty = accepted.
     *
     * @param decline whether the pillar declined (delta &lt; 0)
     */
    public static Optional<Rejection> validate(ShiftNarrativeResult result, boolean decline) {
        if (result == null) {
            return Optional.of(Rejection.EMPTY_NARRATIVE);
        }
        if (NarrativeKind.parse(result.kind()).isEmpty()) {
            return Optional.of(Rejection.BAD_KIND);
        }
        if (result.narrative() == null || result.narrative().isBlank()) {
            return Optional.of(Rejection.EMPTY_NARRATIVE);
        }
        if (requiresClosingAction(decline)
                && (result.closingAction() == null || result.closingAction().isBlank())) {
            return Optional.of(Rejection.MISSING_CLOSING_ACTION);
        }
        return Optional.empty();
    }

    /**
     * The corrective instruction appended to the system prompt for the single
     * retry. A decline miss uses the CONFIGURED wording (§7 owns that sentence);
     * the other two restate the contract the model broke.
     */
    public static String correction(Rejection rejection, String declineCloseInstruction) {
        return switch (rejection) {
            case MISSING_CLOSING_ACTION -> declineCloseInstruction
                    + " This pillar declined: \"closingAction\" MUST be a non-empty, concrete next step.";
            case BAD_KIND -> "\"kind\" must be exactly one of RESOLVED, CARRIED_FORWARD, NEW, "
                    + "PERSISTED or FADED — no other value, no free text.";
            case EMPTY_NARRATIVE -> "\"narrative\" must be 2-4 non-empty sentences grounded in the "
                    + "before and after text.";
        };
    }

    /** Human-readable failure reason for the review UI — what went wrong, reader-facing. */
    public static String reasonLabel(Rejection rejection) {
        return switch (rejection) {
            case BAD_KIND -> "The AI could not classify the change into one of the five kinds.";
            case EMPTY_NARRATIVE -> "The AI returned an empty narrative.";
            case MISSING_CLOSING_ACTION ->
                    "The AI did not include the next step a declining pillar requires.";
        };
    }

    private static int length(List<String> values) {
        if (values == null) {
            return 0;
        }
        return values.stream().filter(v -> v != null).mapToInt(v -> v.trim().length()).sum();
    }
}
