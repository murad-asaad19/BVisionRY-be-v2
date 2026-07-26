package com.bvisionry.communication.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plain-text boundary, pinned as a PLAIN JUnit test — no Spring context,
 * no Docker gate — so it runs on every build, including machines where the
 * Testcontainers suite skips. This is the rule the whole feature's
 * "stored body is plain text" invariant rests on.
 *
 * <p>{@code AnnouncementService.post} accepts a body only when it IS its own
 * canonical form. The tests below are therefore fixpoint questions, not "what
 * does the sanitiser output" questions — the sanitiser exposes no output to ask
 * about, because storing one is unsound: canonicalising is not idempotent on
 * encoded input ({@code &lt;script&gt;} canonicalises to a literal
 * {@code <script>}, which canonicalises again to something else), so one pass
 * would put markup under a column every reader believes holds text.
 */
class AnnouncementSanitizerTest {

    /** Exactly {@code post}'s rule, so this table IS the endpoint's boundary. */
    private static boolean accepted(String body) {
        String trimmed = body == null ? "" : body.trim();
        return !trimmed.isBlank() && AnnouncementService.isCanonicalPlainText(trimmed);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Demo day moves to Friday 10:00.",
            // Ampersands and comparison signs are ordinary punctuation in a
            // sentence and must NOT cost an author their announcement.
            "Demo day & drinks < 5pm, bring your deck",
            "Two links: https://bvisionry.test/x and http://bvisionry.test/y",
            "Multi\nline\nbodies survive",
            "Unicode is text too — café, 🚀, ünïcode",
    })
    void ordinaryTextIsItsOwnCanonicalForm(String body) {
        assertThat(accepted(body)).as("accepted: %s", body).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "Read <b>this</b> now",
            "<img src=x onerror=alert(1)>",
            "<a href=\"https://evil.test\">click</a>",
            // Entity-encoded: the sanitiser DECODES these, so a stripped-and-
            // stored pipeline would have written live markup to the column.
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            "&#60;script&#62;alert(1)&#60;/script&#62;",
            // Double-encoded: one decode still leaves an encoded payload.
            "&amp;lt;script&amp;gt;alert(1)&amp;lt;/script&amp;gt;",
            // Encoded markup hidden inside otherwise ordinary copy.
            "Demo day &lt;script&gt;alert(1)&lt;/script&gt; on Friday",
    })
    void markupAndEncodedMarkupAreRefused(String body) {
        assertThat(accepted(body)).as("accepted: %s", body).isFalse();
    }

    @Test
    void anEmptyOrWhitespaceBodyIsRefused() {
        assertThat(accepted("")).isFalse();
        assertThat(accepted("   \n\t ")).isFalse();
        assertThat(accepted(null)).isFalse();
    }
}
