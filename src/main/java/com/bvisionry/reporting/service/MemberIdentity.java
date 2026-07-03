package com.bvisionry.reporting.service;

import com.bvisionry.assessment.entity.Submission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-submission display name + email lookup that honours the showNames flag.
 * When names are hidden, name → "Member N" (deterministic by row order) and email → "".
 *
 * <p>Shared between Team Insights Excel and PDF exports so identity masking is
 * applied identically — callers should never touch {@code submission.getUser()}
 * directly to render a label.
 *
 * <p>Also exposes a per-submission {@link NarrativeRedactor}: the AI addresses
 * members by name inside the narrative prose, so masking the identity columns is
 * not enough — the name has to be scrubbed out of the summary/pillar text too.
 * When names are shown every redactor is a pass-through.
 *
 * <p>Built via {@link MemberIdentityFactory}, which loads the first/last-name
 * answers the redactors need.
 */
public record MemberIdentity(
        Map<UUID, String> names,
        Map<UUID, String> emails,
        Map<UUID, NarrativeRedactor> redactors) {

    /**
     * @param submissions ordered members ("Member N" numbering follows this order)
     * @param showNames   when false, labels become "Member N", email blanks, and
     *                    the redactor scrubs the member's name(s) from narratives
     * @param firstNames  submissionId → FIRST_NAME answer (used for redaction)
     * @param lastNames   submissionId → LAST_NAME answer (used for redaction)
     */
    static MemberIdentity of(List<Submission> submissions,
                             boolean showNames,
                             Map<UUID, String> firstNames,
                             Map<UUID, String> lastNames) {
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, String> emails = new LinkedHashMap<>();
        Map<UUID, NarrativeRedactor> redactors = new LinkedHashMap<>();
        for (int i = 0; i < submissions.size(); i++) {
            Submission sub = submissions.get(i);
            String label = "Member " + (i + 1);
            names.put(sub.getId(), showNames ? sub.getUser().getName() : label);
            emails.put(sub.getId(), showNames ? sub.getUser().getEmail() : "");
            redactors.put(sub.getId(), showNames
                    ? NarrativeRedactor.disabled()
                    : NarrativeRedactor.forMember(
                            label,
                            firstNames.get(sub.getId()),
                            lastNames.get(sub.getId()),
                            sub.getUser() != null ? sub.getUser().getName() : null,
                            sub.getRespondentName()));
        }
        return new MemberIdentity(names, emails, redactors);
    }

    public String name(Submission sub) {
        return names.getOrDefault(sub.getId(), "");
    }

    public String email(Submission sub) {
        return emails.getOrDefault(sub.getId(), "");
    }

    /** Redactor for this submission — pass-through when names are shown. */
    public NarrativeRedactor redactor(Submission sub) {
        return redactors.getOrDefault(sub.getId(), NarrativeRedactor.disabled());
    }
}
