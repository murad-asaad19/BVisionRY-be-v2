package com.bvisionry.founderprofile.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The shared founder profile (redesign spec §2.4) — one DTO shape for BOTH the
 * org admin and the coach; capability differences are purely client-side
 * (Manage menu, note editing). Growth-tab data is NOT here — it comes from the
 * Phase A comparison endpoints; this payload carries the header, the unified
 * work list, the latest pillar snapshot (for the comparison "none"/"pending"
 * states), coach notes and announcements received.
 *
 * <p><strong>Privacy invariant (§2.4):</strong> nothing in this payload is
 * derived from {@code answers.response_text} — scores, labels, maturity and
 * timestamps only. No query in the profile read repository touches the
 * {@code answers} table.
 *
 * <p>§7b: every item carries its action timestamps so no surface renders an
 * undated action.
 */
public record FounderProfileResponse(
        FounderProfileHeader header,
        List<FounderWorkItem> work,
        List<FounderPillarScore> pillarScores,
        List<FounderProfileNote> notes,
        List<FounderAnnouncement> announcements) {

    /** A cohort the member belongs to — id carried so the Journey tab can switch. */
    public record FounderCohortRef(UUID id, String name) {}

    public record FounderProfileHeader(
            UUID userId,
            String name,
            String email,
            String role,
            String status,
            String memberType,
            List<FounderCohortRef> cohorts,
            /** Latest evaluated overall score (the FRI), null when never evaluated. */
            BigDecimal friLatest,
            /** Latest minus earliest evaluated overall — the Δ-so-far. Null with fewer than two. */
            BigDecimal friDelta,
            Instant friEvaluatedAt,
            /** Most recent of: program save/submit, exercise save/submit, assessment activity, course enrolment/completion, login. */
            Instant lastActivityAt,
            Instant lastLoginAt) {}

    /**
     * One row of the unified Work list. {@code type} discriminates which of
     * the nullable fields apply: PROGRAM (task), EXERCISE (assignment),
     * COURSE (enrollment) or ASSESSMENT (assignment × submission — one row
     * per submission, or a bare TODO row when none exists yet).
     */
    public record FounderWorkItem(
            String type,
            /** Program task id / exercise assignment id / course id / assessment assignment id. */
            UUID refId,
            /** Assessment submission id (results link); null elsewhere. */
            UUID submissionId,
            String title,
            /** "Cohort · Module" for program tasks; suggesting pillar for AI courses. */
            String context,
            /** Raw per-type status; null = not started. */
            String status,
            /**
             * Course enrolment source chip (spec §3, V168): ORG_RULE | DIRECT |
             * AI_SUGGESTED | SELF — the STRONGEST source that put this course on
             * the member's shelf. Null for non-course rows.
             */
            String courseSource,
            Integer progressPct,
            /** Assessment overall score, once evaluated. */
            BigDecimal score,
            Instant dueAt,
            /** First activity: assessment started / exercise last save / program save / course enrolled. */
            Instant startedAt,
            Instant submittedAt,
            Instant reviewedAt,
            Instant evaluatedAt,
            Instant completedAt,
            /** Course removed via enrolment override (kept visible, flagged — never silently dropped). */
            boolean removed,
            /** COURSE only (spec §3): required work gates journey progress. */
            boolean required,
            /**
             * EXERCISE only (spec §4): the reviewer's quality-tag label snapshot,
             * null when untagged. Metadata for staff — this profile is only ever
             * served to an org admin or the founder's own coach.
             */
            String qualityTagLabel) {}

    /** Latest evaluated assessment, one row per pillar — feeds the Growth tab's plain/pending states. */
    public record FounderPillarScore(
            String pillarName,
            BigDecimal scorePercentage,
            String maturityLabel,
            Instant evaluatedAt) {}

    /** A coach note, labeled by author. Mutations go through the coach-notes endpoints. */
    public record FounderProfileNote(
            UUID id,
            UUID coachId,
            String coachName,
            String body,
            Instant createdAt,
            Instant updatedAt) {}

    /** An announcement the founder received (cohort-scoped). */
    public record FounderAnnouncement(
            UUID id,
            String cohortName,
            String authorName,
            String body,
            Instant createdAt) {}
}
