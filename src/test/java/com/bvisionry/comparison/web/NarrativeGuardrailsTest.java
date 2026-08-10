package com.bvisionry.comparison.web;

import com.bvisionry.common.dto.ShiftNarrativeResult;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.web.NarrativeGuardrails.Rejection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §6's guardrails, tested where they live — as pure functions, with no
 * model and no database. Each test is the falsification of one rule that a
 * prompt alone could not be trusted with.
 */
class NarrativeGuardrailsTest {

    private static ShiftNarrativeResult result(String kind, String narrative, String closing) {
        return new ShiftNarrativeResult(kind, narrative, closing);
    }

    /* ------------------------------------------- (a) the before-text floor */

    @Test
    void beforeTextUnderTheFloor_isNotEnoughData() {
        // 24 chars total — a stub, not a "before".
        assertThat(NarrativeGuardrails.hasEnoughBeforeText(
                List.of("Clear vision."), List.of("More focus."))).isFalse();
    }

    @Test
    void beforeTextAtTheFloor_isEnough() {
        String forty = "x".repeat(NarrativeGuardrails.MIN_BEFORE_TEXT_CHARS);
        assertThat(NarrativeGuardrails.hasEnoughBeforeText(List.of(forty), List.of())).isTrue();
    }

    @Test
    void floorCountsBothBlocksTogether_andIgnoresBlanksAndNulls() {
        List<String> working = java.util.Arrays.asList("   ", null, "a".repeat(25));
        assertThat(NarrativeGuardrails.textLength(working, List.of("b".repeat(15)))).isEqualTo(40);
        assertThat(NarrativeGuardrails.hasEnoughBeforeText(working, List.of("b".repeat(15)))).isTrue();
        assertThat(NarrativeGuardrails.hasEnoughBeforeText(working, List.of("b".repeat(14)))).isFalse();
    }

    @Test
    void missingBeforeTextEntirely_isNotEnoughData() {
        assertThat(NarrativeGuardrails.hasEnoughBeforeText(null, null)).isFalse();
        assertThat(NarrativeGuardrails.hasEnoughBeforeText(List.of(), List.of())).isFalse();
    }

    /* ------------------------------------------------ (b) the decline close */

    @Test
    void onlyADecliningPillarDemandsAClosingAction() {
        // Keyed on the stamped structural fact (delta < 0), NOT on a band key:
        // §7 lets a super admin rename or delete the "decline" band, and a
        // guardrail with an off switch in the config screen is not a guardrail.
        assertThat(NarrativeGuardrails.requiresClosingAction(true)).isTrue();
        assertThat(NarrativeGuardrails.requiresClosingAction(false)).isFalse();
    }

    @Test
    void declineWithoutAClosingAction_isRejected() {
        assertThat(NarrativeGuardrails.validate(result("FADED", "The driver is gone.", ""), true))
                .contains(Rejection.MISSING_CLOSING_ACTION);
        assertThat(NarrativeGuardrails.validate(result("FADED", "The driver is gone.", "   "), true))
                .contains(Rejection.MISSING_CLOSING_ACTION);
    }

    @Test
    void declineWithAClosingAction_isAccepted() {
        assertThat(NarrativeGuardrails.validate(
                result("FADED", "The driver is gone.", "Reconnect with it this week."), true))
                .isEmpty();
    }

    @Test
    void nonDeclineWithoutAClosingAction_isAccepted() {
        // The forward-looking close is a DECLINE rule (§6), not a global one.
        assertThat(NarrativeGuardrails.validate(result("RESOLVED", "The gap closed.", ""), false))
                .isEmpty();
    }

    /* ---------------------------------------------------- (c) kind sanity */

    @Test
    void everyOneOfTheFiveKindsParses_andNothingElseDoes() {
        for (NarrativeKind kind : NarrativeKind.values()) {
            assertThat(NarrativeKind.parse(kind.name())).contains(kind);
        }
        assertThat(NarrativeKind.parse("carried forward")).contains(NarrativeKind.CARRIED_FORWARD);
        assertThat(NarrativeKind.parse("Carried-Forward")).contains(NarrativeKind.CARRIED_FORWARD);
        // "Decline" is a BAND, never a kind — the classic model mistake.
        assertThat(NarrativeKind.parse("Decline")).isEmpty();
        assertThat(NarrativeKind.parse("improved")).isEmpty();
        assertThat(NarrativeKind.parse("")).isEmpty();
        assertThat(NarrativeKind.parse(null)).isEmpty();
    }

    @Test
    void unknownKind_isRejectedBeforeAnythingElse() {
        assertThat(NarrativeGuardrails.validate(result("Decline", "Prose.", "Next."), false))
                .contains(Rejection.BAD_KIND);
    }

    @Test
    void emptyNarrative_isRejected() {
        assertThat(NarrativeGuardrails.validate(result("NEW", "  ", "Next."), false))
                .contains(Rejection.EMPTY_NARRATIVE);
        assertThat(NarrativeGuardrails.validate(null, false)).contains(Rejection.EMPTY_NARRATIVE);
    }

    @Test
    void goodResponse_passesEveryCheck() {
        assertThat(NarrativeGuardrails.validate(
                result("PERSISTED", "The edge is still there.", ""), false))
                .isEqualTo(Optional.empty());
    }

    /* ------------------------------------------------- corrective retries */

    @Test
    void declineCorrection_carriesTheConfiguredInstruction() {
        String configured = "Close every decline with a next step.";
        assertThat(NarrativeGuardrails.correction(Rejection.MISSING_CLOSING_ACTION, configured))
                .startsWith(configured)
                .contains("closingAction");
    }

    @Test
    void kindCorrection_restatesTheFiveKinds() {
        assertThat(NarrativeGuardrails.correction(Rejection.BAD_KIND, "ignored"))
                .contains("RESOLVED").contains("CARRIED_FORWARD").contains("NEW")
                .contains("PERSISTED").contains("FADED");
    }
}
