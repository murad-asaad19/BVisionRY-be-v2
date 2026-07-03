package com.bvisionry.reporting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-member anonymisation for exported reports: scrubs the member's real name
 * out of AI-generated narrative text and tells callers whether the report is
 * anonymised at all.
 *
 * <p>The evaluation prompt addresses the assessed person by their first name, so
 * the stored narratives ("Ashraf, your overall score…", "Murad, your physical
 * hardware…") carry the name inside the prose — blanking the identity columns of
 * an export is not enough to anonymise it. When a report is exported with names
 * hidden, every narrative string is passed through {@link #redact} which replaces
 * each occurrence of the member's name(s) with a neutral label (e.g. "Member 1"),
 * and {@link #isAnonymized()} tells report builders to drop whole
 * personal-information sections.
 *
 * <p>Instances are built per member via {@link #forMember}. A {@link #disabled()}
 * redactor is a pass-through — used on the names-shown path so callers wire the
 * redactor unconditionally and never branch at each write point.
 */
public final class NarrativeRedactor {

    private static final NarrativeRedactor DISABLED = new NarrativeRedactor(false, null, null);

    private final boolean anonymized;
    private final Pattern namePattern;
    private final String quotedReplacement;

    private NarrativeRedactor(boolean anonymized, Pattern namePattern, String quotedReplacement) {
        this.anonymized = anonymized;
        this.namePattern = namePattern;
        this.quotedReplacement = quotedReplacement;
    }

    /** A no-op redactor — names are shown, nothing is scrubbed or suppressed. */
    public static NarrativeRedactor disabled() {
        return DISABLED;
    }

    /**
     * Build the anonymising redactor for one member from every alias the AI might
     * have used — first name, last name, "first last", account name, respondent
     * name (any of which may be null/blank). This owns the alias policy so the
     * single-member and team exports can never drift apart.
     */
    public static NarrativeRedactor forMember(String label, String firstName, String lastName,
                                              String accountName, String respondentName) {
        List<String> terms = new ArrayList<>();
        terms.add(firstName);
        terms.add(lastName);
        if (firstName != null && lastName != null) {
            terms.add(firstName.trim() + " " + lastName.trim());
        }
        terms.add(accountName);
        terms.add(respondentName);
        return forNames(label, terms);
    }

    /**
     * Build an anonymising redactor that replaces any of {@code names} with
     * {@code label}. Null/blank entries are ignored.
     *
     * <p>Matching is case-insensitive and whole-word (so "Sam" doesn't clobber
     * "same"). Longer names are matched first so a full name collapses to a single
     * label rather than leaving a dangling half. If no usable name is supplied the
     * redaction degrades to a pass-through, but the report still counts as
     * anonymised — personal-information sections stay suppressed.
     */
    public static NarrativeRedactor forNames(String label, List<String> names) {
        List<String> cleaned = names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(n -> !n.isBlank())
                // Longest first — "Ashraf Al-Madhoun" must match before "Ashraf",
                // otherwise the surname would survive as a fragment.
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .distinct()
                .toList();
        if (cleaned.isEmpty()) return new NarrativeRedactor(true, null, null);

        String alternation = cleaned.stream()
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElseThrow();
        // \b would fail around names containing punctuation/spaces (e.g.
        // "Al-Madhoun"); use lookarounds on "word-ish" characters instead so we
        // match a standalone name token regardless of its internal characters.
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}])(?:" + alternation + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new NarrativeRedactor(true, pattern, Matcher.quoteReplacement(label));
    }

    /**
     * True when the report is anonymised (names hidden). Report builders use this
     * to drop whole personal-information sections — the member's general info is
     * exactly what anonymisation suppresses — rather than merely redacting cells.
     */
    public boolean isAnonymized() {
        return anonymized;
    }

    /** Redact all name occurrences in {@code text}. Null-safe pass-through. */
    public String redact(String text) {
        if (text == null || namePattern == null) return text;
        return namePattern.matcher(text).replaceAll(quotedReplacement);
    }

    /** Redact every string in {@code items}, preserving order. Null-safe. */
    public List<String> redact(List<String> items) {
        if (items == null || namePattern == null) return items;
        return items.stream().map(this::redact).toList();
    }
}
