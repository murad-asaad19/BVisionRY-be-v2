package com.bvisionry.reporting.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NarrativeRedactorTest {

    @Test
    void disabledRedactorIsPassThrough() {
        NarrativeRedactor redactor = NarrativeRedactor.disabled();
        assertThat(redactor.isAnonymized()).isFalse();
        assertThat(redactor.redact("Ashraf, your score is 71%")).isEqualTo("Ashraf, your score is 71%");
        assertThat(redactor.redact((String) null)).isNull();
    }

    @Test
    void redactorWithNoUsableNamesStaysAnonymizedButPassesTextThrough() {
        NarrativeRedactor redactor = NarrativeRedactor.forNames("Member 1", Arrays.asList(null, "", "  "));
        // Still anonymised — personal-info sections must stay suppressed even
        // when there is no name to scrub from the prose.
        assertThat(redactor.isAnonymized()).isTrue();
        assertThat(redactor.redact("Ashraf, your score is 71%")).isEqualTo("Ashraf, your score is 71%");
    }

    @Test
    void forMemberRedactsEveryAliasIncludingFullName() {
        NarrativeRedactor redactor = NarrativeRedactor.forMember(
                "Member 1", "Ashraf", "Al-Madhoun", "ash.madhoun", "Ashraf A.");
        assertThat(redactor.isAnonymized()).isTrue();
        assertThat(redactor.redact("Ashraf Al-Madhoun (ash.madhoun) — Ashraf leads."))
                .isEqualTo("Member 1 (Member 1) — Member 1 leads.");
    }

    @Test
    void forMemberWithAllNullAliasesStaysAnonymized() {
        NarrativeRedactor redactor = NarrativeRedactor.forMember("Member 1", null, null, null, null);
        assertThat(redactor.isAnonymized()).isTrue();
        assertThat(redactor.redact("No names here")).isEqualTo("No names here");
    }

    @Test
    void replacesLeadingVocativeWithLabel() {
        NarrativeRedactor redactor = NarrativeRedactor.forNames("Member 1", List.of("Ashraf"));
        assertThat(redactor.redact("Ashraf, your overall Founder Mindset score of 71% places you"))
                .isEqualTo("Member 1, your overall Founder Mindset score of 71% places you");
    }

    @Test
    void replacesInlineOccurrencesCaseInsensitively() {
        NarrativeRedactor redactor = NarrativeRedactor.forNames("Member", List.of("Murad"));
        assertThat(redactor.redact("Today MURAD showed up. murad, your body is on Redline."))
                .isEqualTo("Today Member showed up. Member, your body is on Redline.");
    }

    @Test
    void doesNotClobberNamesEmbeddedInLongerWords() {
        // "Sam" must not turn "same"/"assessment" into a redaction.
        NarrativeRedactor redactor = NarrativeRedactor.forNames("Member", List.of("Sam"));
        assertThat(redactor.redact("Sam, the same assessment shows Sam is consistent."))
                .isEqualTo("Member, the same assessment shows Member is consistent.");
    }

    @Test
    void collapsesFullNameToSingleLabelWhenLongestMatchesFirst() {
        NarrativeRedactor redactor = NarrativeRedactor.forNames(
                "Member 1", List.of("Ashraf", "Al-Madhoun", "Ashraf Al-Madhoun"));
        assertThat(redactor.redact("Ashraf Al-Madhoun founded the company; Ashraf leads it."))
                .isEqualTo("Member 1 founded the company; Member 1 leads it.");
    }

    @Test
    void redactsNameWithInternalPunctuation() {
        NarrativeRedactor redactor = NarrativeRedactor.forNames("Member", List.of("Al-Madhoun"));
        assertThat(redactor.redact("The pattern Al-Madhoun shows is fragmentation."))
                .isEqualTo("The pattern Member shows is fragmentation.");
    }

    @Test
    void redactsListOfNarrativesNullSafe() {
        NarrativeRedactor redactor = NarrativeRedactor.forNames("Member", List.of("Murad"));
        List<String> out = redactor.redact(List.of("Murad grows fast", "No name here"));
        assertThat(out).containsExactly("Member grows fast", "No name here");
        assertThat(redactor.redact((List<String>) null)).isNull();
    }
}
