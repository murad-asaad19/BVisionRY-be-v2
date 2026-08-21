package com.bvisionry.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.util.Locale;

public class CalComBookingUrlValidator implements ConstraintValidator<CalComBookingUrl, String> {

    /** The one provider the policy record closes on ({@code INTEGRATE_CAL_COM}). */
    static final String BOOKING_HOST = "cal.com";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return isCalComBookingUrl(value);
    }

    /**
     * True when {@code value} is an https URL whose host is {@code cal.com} or a
     * subdomain of it, and which also clears every {@link ValidExternalUrl} rule.
     *
     * <p>Composed, never restated: {@link ValidExternalUrlValidator#isSafePublicUrl}
     * still owns the scheme/host/SSRF semantics, and this adds the two rules on
     * top.
     *
     * <p><strong>What the composition actually buys, stated honestly.</strong>
     * Its SSRF half is unreachable here — a private-address rule can only fire
     * on a name that resolves privately, and the host pin below already admits
     * nothing but {@code cal.com} and its subdomains, whose DNS is not
     * attacker-controlled. What the call DOES do is guarantee this method's own
     * preconditions: the string parses as a URI and has a non-null host, so the
     * two lines after it need neither a try/catch nor a null check. Removing it
     * would make this method longer, not shorter. There is deliberately no test
     * asserting "localhost is refused" — the dot-boundary check refuses it
     * anyway, so such a test passes with the composition deleted and is
     * therefore false evidence.
     *
     * <p><strong>Parsed, not pattern-matched, and matched on a DOT BOUNDARY.</strong>
     * Both halves of that are load-bearing:
     * <ul>
     *   <li>{@code URI.getHost()} rather than a regex or {@code contains}, because
     *       {@code https://cal.com@evil.com/x} reads as "cal.com" to a substring
     *       check and resolves to {@code evil.com} in a browser — the userinfo
     *       trick is the classic way past a naive URL allowlist;</li>
     *   <li>{@code endsWith(".cal.com")} rather than {@code endsWith("cal.com")}
     *       or {@code contains("cal.com")}, because the latter admit
     *       {@code evilcal.com} and {@code cal.com.phish.example}.</li>
     * </ul>
     * A null host cannot reach the {@code endsWith} — {@code isSafePublicUrl}
     * already rejected it.
     */
    public static boolean isCalComBookingUrl(String value) {
        if (!ValidExternalUrlValidator.isSafePublicUrl(value)) {
            return false;
        }
        // Parses by construction: isSafePublicUrl above already ran the same
        // parser on the same string and returned false if it threw.
        URI uri = URI.create(value);
        // http is fine for a generic external link; it is not fine for the page
        // we send a founder to identify themselves on.
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals(BOOKING_HOST) || host.endsWith("." + BOOKING_HOST);
    }
}
