package com.bvisionry.auth.sso;

import java.net.IDN;
import java.util.Locale;
import java.util.Optional;

/**
 * Domain normalisation and matching for enterprise SSO — the gate that decides
 * whether an asserted email is one the registration is allowed to speak for.
 *
 * <p>This is the whole cross-tenant boundary, so it is deliberately the dumbest
 * possible rule: normalise both sides to one canonical form, then compare with
 * {@code equals}.
 *
 * <p><strong>Never {@code endsWith}.</strong> A suffix test makes
 * {@code evil-orgb.com} match {@code orgb.com} and hands the attacker every
 * account in the victim tenant. Even {@code "." + domain} suffix matching would
 * silently admit every subdomain, which is a different trust decision than the
 * one the platform verified. Exact label equality means a subdomain is its own
 * registration — an operator who wants {@code eu.orgb.com} registers it.
 *
 * <p><strong>IDN/punycode first.</strong> {@code оrgb.com} with a Cyrillic
 * U+043E is a different domain from {@code orgb.com} but renders identically.
 * Encoding to ASCII via {@link IDN#toASCII} turns it into
 * {@code xn--rgb-red.com}, so a homoglyph domain fails the equality test instead
 * of passing a naive Unicode comparison. Registrations are stored already
 * normalised (see {@code SsoRegistrationService}), so both sides of the
 * comparison always went through this method.
 */
final class EmailDomains {

    private EmailDomains() {
    }

    /**
     * Canonical form of a domain: trimmed, a leading {@code @} or trailing dot
     * removed, punycode-encoded, lowercased under {@link Locale#ROOT}.
     *
     * <p>{@code Locale.ROOT} is load-bearing: default-locale lowercasing maps
     * {@code I} to a dotless {@code ı} under a Turkish locale, so a server's
     * locale would otherwise change which domains match.
     *
     * @return empty when the input is blank or not a domain {@link IDN} accepts
     */
    static Optional<String> normalizeDomain(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String candidate = raw.trim();
        if (candidate.startsWith("@")) {
            candidate = candidate.substring(1);
        }
        // A trailing dot is the (legal) absolute-root form of a FQDN; it names the
        // same domain, so it must not produce a second, non-matching registration.
        while (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isEmpty() || candidate.contains("..") || candidate.indexOf('.') < 0) {
            // No dot at all is never a registrable domain, and an empty label
            // ("a..b") is what IDN would otherwise accept and we would compare.
            return Optional.empty();
        }
        try {
            String ascii = IDN.toASCII(candidate, IDN.ALLOW_UNASSIGNED);
            return Optional.of(ascii.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException notADomain) {
            return Optional.empty();
        }
    }

    /**
     * The domain part of an email address, canonicalised by
     * {@link #normalizeDomain}.
     *
     * <p>Splits on the LAST {@code @}, because RFC 5321 permits a QUOTED local
     * part to contain one ({@code "weird@evil.com"@orgb.com} really is an address
     * at orgb.com) and splitting on the first would read the wrong domain.
     *
     * <p>An UNQUOTED local part containing {@code @} is rejected outright. Such an
     * address is not legal, and accepting it is display spoofing in a multi-tenant
     * product: org B's identity provider could assert
     * {@code ceo@orgc.com@orgb.com}, pass the domain gate on {@code orgb.com}, and
     * have that literal string stored and then rendered as a member of org B in
     * member lists, audit rows and coach views — another company's executive
     * address, apparently on their roster. It is not account takeover (addresses
     * are globally unique and matched exactly, so it can only ever create its own
     * account), which is precisely why it would survive review unnoticed.
     */
    static Optional<String> domainOf(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String trimmed = email.trim();
        int at = trimmed.lastIndexOf('@');
        if (at < 1) {
            return Optional.empty();
        }
        String localPart = trimmed.substring(0, at);
        if (localPart.indexOf('@') >= 0 && !isQuoted(localPart)) {
            return Optional.empty();
        }
        return normalizeDomain(trimmed.substring(at + 1));
    }

    /** RFC 5321 quoted-string local part: the whole part is wrapped in double quotes. */
    private static boolean isQuoted(String localPart) {
        return localPart.length() >= 2
                && localPart.charAt(0) == '"'
                && localPart.charAt(localPart.length() - 1) == '"';
    }

    /**
     * True iff {@code email}'s domain is exactly {@code registeredDomain}.
     *
     * @param registeredDomain the registration's already-normalised domain
     */
    static boolean matches(String registeredDomain, String email) {
        Optional<String> registered = normalizeDomain(registeredDomain);
        Optional<String> asserted = domainOf(email);
        return registered.isPresent() && registered.equals(asserted);
    }

    /** Lowercase an email for storage/lookup, matching {@code users.email}. */
    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
