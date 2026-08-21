package com.bvisionry.auth.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant 1 in isolation: which asserted emails a registration may speak for.
 *
 * <p>Every case below is chosen so that a specific WRONG implementation passes
 * the happy path and fails here — a suffix test, a naive Unicode comparison, a
 * default-locale lowercase. A green run of this class is the evidence that none
 * of those is what shipped.
 */
class EmailDomainsTest {

    private static final String REGISTERED = "orgb.com";

    @Test
    void anExactInDomainAddressMatches() {
        assertThat(EmailDomains.matches(REGISTERED, "founder@orgb.com")).isTrue();
    }

    @Test
    void caseAndSurroundingWhitespaceAreIrrelevant() {
        assertThat(EmailDomains.matches(REGISTERED, "  Founder@ORGB.CoM ")).isTrue();
    }

    @Test
    void aTrailingRootDotNamesTheSameDomain() {
        // "orgb.com." is the absolute form of the same FQDN; treating it as a
        // different domain would let it be registered twice.
        assertThat(EmailDomains.matches(REGISTERED, "founder@orgb.com.")).isTrue();
    }

    /**
     * The suffix-test killer. {@code "evil-orgb.com".endsWith("orgb.com")} is true,
     * so an implementation that reached for endsWith hands the attacker every
     * account in the victim tenant. This is the single most important assertion in
     * the feature.
     */
    @Test
    void aDomainThatMerelyEndsWithTheRegisteredOneIsRefused() {
        assertThat("evil-orgb.com".endsWith(REGISTERED))
                .as("precondition: this is exactly the string a suffix test would accept")
                .isTrue();
        assertThat(EmailDomains.matches(REGISTERED, "attacker@evil-orgb.com")).isFalse();
    }

    @Test
    void aSubdomainIsItsOwnDomainAndIsRefused() {
        // Exact label match, not "in the zone": the platform verified orgb.com, and
        // whoever runs eu.orgb.com may not be the same party. It registers separately.
        assertThat(EmailDomains.matches(REGISTERED, "founder@eu.orgb.com")).isFalse();
    }

    @Test
    void aParentDomainIsRefusedToo() {
        assertThat(EmailDomains.matches("eu.orgb.com", "founder@orgb.com")).isFalse();
    }

    /**
     * A homoglyph domain: the first "o" is Cyrillic U+043E, so this renders
     * identically to orgb.com in a browser and in a log line. Punycode encoding
     * turns it into xn--rgb-red.com, which is simply a different string.
     */
    @Test
    void aHomoglyphDomainIsRefused() {
        String cyrillic = "оrgb.com";
        assertThat(cyrillic).as("precondition: renders like the real thing").isNotEqualTo(REGISTERED);
        assertThat(EmailDomains.matches(REGISTERED, "attacker@" + cyrillic)).isFalse();
        assertThat(EmailDomains.normalizeDomain(cyrillic)).contains("xn--rgb-red.com");
    }

    @Test
    void aUnicodeDomainMatchesItsOwnPunycodeRegistration() {
        // The other direction: an IDN customer registers "bücher.de", stored as
        // punycode, and their users assert the Unicode form.
        String stored = EmailDomains.normalizeDomain("bücher.de").orElseThrow();
        assertThat(stored).isEqualTo("xn--bcher-kva.de");
        assertThat(EmailDomains.matches(stored, "leser@bücher.de")).isTrue();
        assertThat(EmailDomains.matches(stored, "leser@xn--bcher-kva.de")).isTrue();
    }

    @Test
    void theLastAtSignDecidesTheDomainForAQuotedLocalPart() {
        // A quoted local part may legally contain '@'. Splitting on the FIRST one
        // would read "evil.com" as the domain of an address that is actually at
        // orgb.com — and, worse, the reverse for a crafted address.
        assertThat(EmailDomains.matches(REGISTERED, "\"weird@evil.com\"@orgb.com")).isTrue();
        assertThat(EmailDomains.matches("evil.com", "\"weird@evil.com\"@orgb.com")).isFalse();
    }

    /**
     * The other half of "split on the last @": an UNQUOTED local part may not
     * contain one at all. Accepting {@code ceo@orgc.com@orgb.com} passes the domain
     * gate on orgb.com and then stores that literal string, so org C's chief
     * executive appears on org B's member list, in their audit rows and in their
     * coach views. It is not account takeover — addresses are globally unique and
     * matched exactly — which is precisely why it reads as harmless and is not.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "ceo@orgc.com@orgb.com",
            "a@b@orgb.com",
            "\"quoted@x\"trailing@orgb.com",
            "unquoted@\"weird\"@orgb.com",
    })
    void anUnquotedLocalPartContainingAnAtSignIsRefused(String spoofed) {
        assertThat(EmailDomains.matches(REGISTERED, spoofed)).isFalse();
        assertThat(EmailDomains.domainOf(spoofed)).isEmpty();
    }

    /**
     * {@code Locale.ROOT} is documented as load-bearing in {@link EmailDomains}, so
     * it gets an input that can actually catch its absence. Under a Turkish locale
     * {@code "IBM.COM".toLowerCase()} yields {@code "ıbm.com"} with a DOTLESS i,
     * which equals nothing — every user at an uppercase-I domain would be refused,
     * and only on servers whose default locale happened to be tr. Run the class with
     * {@code -Duser.language=tr -Duser.country=TR} and this is the test that fails
     * if the explicit locale is ever dropped.
     */
    @Test
    void lowercasingIsLocaleIndependent() {
        assertThat(EmailDomains.matches("ibm.com", "someone@IBM.com")).isTrue();
        assertThat(EmailDomains.matches("ibm.com", "SOMEONE@IBM.COM")).isTrue();
        assertThat(EmailDomains.normalizeDomain("IBM.COM")).contains("ibm.com");
        assertThat(EmailDomains.normalizeEmail("İsmail@IBM.COM"))
                .as("the domain half must lowercase to the ASCII form regardless of default locale")
                .endsWith("@ibm.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "orgb.com", "@orgb.com", "founder@", "founder@localhost",
            "founder@orgb..com", "founder@ ", "@"})
    void anAddressWithNoUsableDomainNeverMatches(String malformed) {
        assertThat(EmailDomains.matches(REGISTERED, malformed)).isFalse();
    }

    @Test
    void aNullAddressNeverMatches() {
        assertThat(EmailDomains.matches(REGISTERED, null)).isFalse();
    }

    @Test
    void anUnnormalisableRegistrationMatchesNothing() {
        // Fail closed: a registration whose domain cannot be canonicalised must not
        // become a wildcard that matches every address that also fails to parse.
        assertThat(EmailDomains.matches("localhost", "founder@localhost")).isFalse();
        assertThat(EmailDomains.matches(null, "founder@orgb.com")).isFalse();
    }

    @Test
    void normalizeDomainAcceptsALeadingAtAndLowercases() {
        assertThat(EmailDomains.normalizeDomain("@ORGB.com")).contains(REGISTERED);
    }

    @Test
    void normalizeEmailLowercasesAndTrims() {
        assertThat(EmailDomains.normalizeEmail("  Founder@ORGB.com ")).isEqualTo("founder@orgb.com");
        assertThat(EmailDomains.normalizeEmail(null)).isNull();
    }
}
