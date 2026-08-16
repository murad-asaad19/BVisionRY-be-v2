package com.bvisionry.common.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host allowlist, tested as an ATTACK surface rather than a happy path.
 *
 * <p>This link is published by a coach to the founders who trust them and is
 * opened in a new tab by a founder who is about to type their own details into
 * it. Every rejection case below is a real way past a naive check, and each one
 * is the reason a specific line of {@link CalComBookingUrlValidator} is written
 * the way it is:
 * {@code endsWith(".cal.com")} not {@code contains}/{@code endsWith("cal.com")},
 * {@code URI.getHost()} not a regex, {@code https} not http.
 */
class CalComBookingUrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://cal.com/jane",
            "https://cal.com/jane/intro-call?duration=30",
            "https://app.cal.com/jane",
            "https://acme.team.cal.com/jane",
            // Host comparison is case-insensitive; the path is not touched.
            "https://CAL.COM/Jane",
            // The host can be terminated by ? or # as well as / — the pair of
            // these and their hostile twins below is what makes the
            // parse-vs-split choice mutation-detectable.
            "https://cal.com?user=jane",
            "https://cal.com#jane",
    })
    void acceptsCalComBookingPages(String url) {
        assertThat(CalComBookingUrlValidator.isCalComBookingUrl(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // The dot-boundary cases. A `contains`/`endsWith("cal.com")` check
            // admits every one of these, and each resolves to an attacker host.
            "https://evilcal.com/jane",
            "https://notcal.com/jane",
            "https://cal.com.phish.example/jane",
            "https://phish.example/cal.com/jane",
            "https://phish.example/?u=https://cal.com/jane",
            // The host ends at the FIRST of / ? #. A rewrite that split the raw
            // string on "/" — the plausible "simplify this" — reads the host of
            // these two as ending in ".cal.com" and fails OPEN.
            "https://evil.example?x=.cal.com",
            "https://evil.example#.cal.com",
            // Userinfo: reads as "cal.com" to a substring or regex check,
            // resolves to evil.example in a browser.
            "https://cal.com@evil.example/jane",
            // Plaintext to the page a founder identifies themselves on.
            "http://cal.com/jane",
            // Not a web URL at all.
            "javascript:alert(1)//cal.com",
            "ftp://cal.com/jane",
            "cal.com/jane",
            "not a url",
    })
    void rejectsEverythingThatIsNotACalComBookingPage(String url) {
        assertThat(CalComBookingUrlValidator.isCalComBookingUrl(url)).isFalse();
    }

    @Test
    void nullAndBlankPassSoAConstraintDoesNotForceALink() {
        CalComBookingUrlValidator validator = new CalComBookingUrlValidator();
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("   ", null)).isTrue();
        assertThat(validator.isValid("https://evilcal.com/x", null)).isFalse();
    }
}
