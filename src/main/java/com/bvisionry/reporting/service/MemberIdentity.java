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
 */
public record MemberIdentity(Map<UUID, String> names, Map<UUID, String> emails) {

    public static MemberIdentity of(List<Submission> submissions, boolean showNames) {
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, String> emails = new LinkedHashMap<>();
        for (int i = 0; i < submissions.size(); i++) {
            Submission sub = submissions.get(i);
            names.put(sub.getId(), showNames ? sub.getUser().getName() : "Member " + (i + 1));
            emails.put(sub.getId(), showNames ? sub.getUser().getEmail() : "");
        }
        return new MemberIdentity(names, emails);
    }

    public String name(Submission sub) {
        return names.getOrDefault(sub.getId(), "");
    }

    public String email(Submission sub) {
        return emails.getOrDefault(sub.getId(), "");
    }
}
