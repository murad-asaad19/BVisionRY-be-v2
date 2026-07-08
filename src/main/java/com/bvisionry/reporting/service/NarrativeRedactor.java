package com.bvisionry.reporting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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

    private static final NarrativeRedactor DISABLED = new NarrativeRedactor(false, null, null, null);

    /**
     * Lowercase English words that are also common personal / account names.
     * A term whose lowercase form is in this set is matched CASE-SENSITIVELY (the
     * name as-cased) rather than case-insensitively, so redacting a member called
     * "Will"/"Grace"/"Mark"/"May" scrubs the capitalised name but leaves the
     * ordinary lowercase word ("this will help", "with grace") untouched. Only
     * applied to single-token terms — a multi-word term like "Will Smith" is
     * unambiguous enough to stay case-insensitive.
     */
    private static final Set<String> COMMON_ENGLISH_WORDS = Set.of(
            "will", "may", "june", "april", "august", "mark", "bill", "art", "rich",
            "frank", "guy", "don", "earl", "victor", "dale", "gene", "ray", "reed",
            "lane", "miles", "chase", "dean", "drew", "hunter", "mason", "cash",
            "chance", "grace", "hope", "faith", "joy", "rose", "dawn", "iris", "ivy",
            "penny", "pearl", "ruby", "jade", "olive", "holly", "lily", "daisy",
            "sky", "summer", "autumn", "angel", "honey", "sage", "star", "storm",
            "melody", "harmony", "destiny", "sunny", "misty", "crystal", "king",
            "major", "prince", "queen", "precious", "young", "noble", "robin", "jay",
            "wren", "fox");

    private final boolean anonymized;
    // Whole-word patterns over the member's aliases. Names that collide with a
    // common English word go in the case-sensitive pattern (so lowercase prose
    // survives); everything else is case-insensitive. Either may be null.
    private final Pattern insensitivePattern;
    private final Pattern sensitivePattern;
    private final String quotedReplacement;

    private NarrativeRedactor(boolean anonymized, Pattern insensitivePattern,
                             Pattern sensitivePattern, String quotedReplacement) {
        this.anonymized = anonymized;
        this.insensitivePattern = insensitivePattern;
        this.sensitivePattern = sensitivePattern;
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
        if (cleaned.isEmpty()) return new NarrativeRedactor(true, null, null, null);

        // Split into the two matching regimes. Single-token names that are also
        // common English words match case-sensitively (see COMMON_ENGLISH_WORDS);
        // full names and ordinary names match case-insensitively.
        List<String> insensitiveTerms = new ArrayList<>();
        List<String> sensitiveTerms = new ArrayList<>();
        for (String term : cleaned) {
            if (isCommonWordName(term)) {
                sensitiveTerms.add(term);
            } else {
                insensitiveTerms.add(term);
            }
        }
        Pattern insensitive = compilePattern(insensitiveTerms, true);
        Pattern sensitive = compilePattern(sensitiveTerms, false);
        return new NarrativeRedactor(true, insensitive, sensitive, Matcher.quoteReplacement(label));
    }

    /**
     * A single-token alias that is also a common English word — those match
     * case-sensitively so the everyday lowercase word survives. Multi-word terms
     * are unambiguous and always match case-insensitively.
     */
    private static boolean isCommonWordName(String term) {
        return !containsWhitespace(term) && COMMON_ENGLISH_WORDS.contains(term.toLowerCase(Locale.ROOT));
    }

    private static boolean containsWhitespace(String term) {
        for (int i = 0; i < term.length(); i++) {
            if (Character.isWhitespace(term.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Compile a whole-word alternation over {@code terms}, or {@code null} when
     * there are none. \b would fail around names containing punctuation/spaces
     * (e.g. "Al-Madhoun"); lookarounds on "word-ish" characters instead match a
     * standalone name token regardless of its internal characters.
     */
    private static Pattern compilePattern(List<String> terms, boolean caseInsensitive) {
        if (terms.isEmpty()) return null;
        String alternation = terms.stream()
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElseThrow();
        int flags = caseInsensitive ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0;
        return Pattern.compile(
                "(?<![\\p{L}\\p{N}])(?:" + alternation + ")(?![\\p{L}\\p{N}])",
                flags);
    }

    /**
     * True when the report is anonymised (names hidden). Report builders use this
     * to drop whole personal-information sections — the member's general info is
     * exactly what anonymisation suppresses — rather than merely redacting cells.
     */
    public boolean isAnonymized() {
        return anonymized;
    }

    /**
     * True when this redactor actually scrubs text — it has at least one alias
     * pattern. A {@link #disabled()} redactor and an anonymised redactor built
     * with no usable names are both inactive (their {@link #redact} is a
     * pass-through), so DTO copy methods can return {@code this} unchanged.
     */
    public boolean isActive() {
        return insensitivePattern != null || sensitivePattern != null;
    }

    /** Redact all name occurrences in {@code text}. Null-safe pass-through. */
    public String redact(String text) {
        if (text == null) return null;
        String result = text;
        // Apply the case-insensitive pass first: it carries the full-name terms,
        // so a name collapses to one label before the case-sensitive common-word
        // pass runs on whatever tokens remain.
        if (insensitivePattern != null) {
            result = insensitivePattern.matcher(result).replaceAll(quotedReplacement);
        }
        if (sensitivePattern != null) {
            result = sensitivePattern.matcher(result).replaceAll(quotedReplacement);
        }
        return result;
    }

    /** Redact every string in {@code items}, preserving order. Null-safe. */
    public List<String> redact(List<String> items) {
        if (items == null || !isActive()) return items;
        return items.stream().map(this::redact).toList();
    }
}
