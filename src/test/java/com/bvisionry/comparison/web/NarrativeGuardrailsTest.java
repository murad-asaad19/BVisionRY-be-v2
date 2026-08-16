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

    private static ShiftNarrativeResult result(String kind, String text, String closing) {
        return new ShiftNarrativeResult(List.of(new ShiftNarrativeResult.Item(kind, text)), closing);
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

    /* ------------------------------------- (a') something to compare against */

    @Test
    void noLaterTextAndNoTaggedWork_isNothingToCompareAgainst() {
        assertThat(NarrativeGuardrails.hasComparableAfter(List.of(), List.of(), false)).isFalse();
        assertThat(NarrativeGuardrails.hasComparableAfter(null, null, false)).isFalse();
        assertThat(NarrativeGuardrails.hasComparableAfter(
                List.of("Fine."), List.of("Meh."), false)).isFalse();
    }

    @Test
    void taggedWorkAloneIsEnoughToCompareAgainst() {
        // §2's whole point: the programme work IS post-baseline evidence. A
        // blanket after-TEXT floor would suppress exactly the calls the activity
        // section exists to improve.
        assertThat(NarrativeGuardrails.hasComparableAfter(List.of(), List.of(), true)).isTrue();
    }

    @Test
    void laterTextAloneIsEnoughToCompareAgainst() {
        String forty = "x".repeat(NarrativeGuardrails.MIN_BEFORE_TEXT_CHARS);
        assertThat(NarrativeGuardrails.hasComparableAfter(List.of(forty), List.of(), false)).isTrue();
        assertThat(NarrativeGuardrails.hasAfterText(List.of(forty), List.of())).isTrue();
        assertThat(NarrativeGuardrails.hasAfterText(List.of("Fine."), List.of())).isFalse();
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

    /* --------------------------------------- (d) numbers are the prompt's job */

    @Test
    void figuresInMemberFacingProse_areNotRejected() {
        // §2's no-numbers rule is asked for in the prompt and NOT enforced here.
        // The rejecting guardrail was removed: the input is full of figures the
        // model is told to quote back ("scored at 100%", "rated Easy (4/5)"), so
        // it fired mostly on the founder's own words — and each false reject
        // burned the single corrective retry and surfaced the pillar as
        // ungeneratable to the reviewer. This test fails if it is reinstated.
        for (String withFigure : List.of(
                "Your focus score moved to 80%.",
                "You rated it Easy (4/5) this time.",
                "Restorative sleep now runs 6 out of 7 nights.",
                "Statement 3 is where the shift shows up.",
                "You now protect the habit 7 days a week.")) {
            assertThat(NarrativeGuardrails.validate(result("NEW", withFigure, ""), false))
                    .as(withFigure).isEmpty();
        }
    }

    @Test
    void aClosingActionCarryingAFigure_isAccepted() {
        assertThat(NarrativeGuardrails.validate(
                result("FADED", "The cadence slipped.", "Get back to 80% completion this month."),
                true))
                .isEmpty();
    }

    @Test
    void aBlankDeclineStillReportsTheMissingClose() {
        assertThat(NarrativeGuardrails.validate(result("FADED", "It slipped.", ""), true))
                .contains(Rejection.MISSING_CLOSING_ACTION);
    }

    /* ------------------------------------------------- (e) the breakdown */

    @Test
    void aBreakdownWithNoObservations_isRejected() {
        // §2 replaced one paragraph with a LIST, and an empty list is the new
        // way to return nothing at all — it must fail exactly like empty prose.
        assertThat(NarrativeGuardrails.validate(new ShiftNarrativeResult(List.of(), "Next."), false))
                .contains(Rejection.EMPTY_NARRATIVE);
        assertThat(NarrativeGuardrails.validate(new ShiftNarrativeResult(null, ""), false))
                .contains(Rejection.EMPTY_NARRATIVE);
    }

    @Test
    void everyItemIsChecked_notJustTheFirst() {
        assertThat(NarrativeGuardrails.validate(new ShiftNarrativeResult(List.of(
                new ShiftNarrativeResult.Item("RESOLVED", "The gap closed."),
                new ShiftNarrativeResult.Item("Improved", "And it kept going.")), ""), false))
                .contains(Rejection.BAD_KIND);
        assertThat(NarrativeGuardrails.validate(new ShiftNarrativeResult(List.of(
                new ShiftNarrativeResult.Item("RESOLVED", "The gap closed."),
                new ShiftNarrativeResult.Item("NEW", "   ")), ""), false))
                .contains(Rejection.EMPTY_NARRATIVE);
    }

    @Test
    void aMultiItemBreakdown_passesEveryCheck() {
        assertThat(NarrativeGuardrails.validate(new ShiftNarrativeResult(List.of(
                new ShiftNarrativeResult.Item("PERSISTED", "The edge is still there."),
                new ShiftNarrativeResult.Item("carried forward", "And the strength deepened."),
                new ShiftNarrativeResult.Item("NEW", "A cadence appeared.")),
                "Keep the cadence."), true))
                .isEqualTo(Optional.empty());
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

    @Test
    void emptyCorrection_namesTheItemsContract() {
        // The re-ask has to describe the shape the model actually broke — the
        // pre-§2 wording ("2-4 sentences") would send it back to one paragraph.
        assertThat(NarrativeGuardrails.correction(Rejection.EMPTY_NARRATIVE, "ignored"))
                .contains("items").contains("text");
    }
}
