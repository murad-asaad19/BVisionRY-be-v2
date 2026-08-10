package com.bvisionry.courseaccess.domain;

import com.bvisionry.common.enums.EnrollmentSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The heart of spec §3: merge a member's four course paths into one deduped
 * list, strongest source shown.
 *
 * <p>Deliberately a PURE function over three plain lists rather than one clever
 * {@code DISTINCT ON} query. The precedence rule is the part of this phase most
 * likely to be got wrong and most expensive to get wrong (an org-wide rule
 * hiding a by-name assignment reads as a bug to an admin), so it is written
 * where a plain JUnit test can drive every collision without a database. The
 * three reads it consumes are each a single indexed scan over one member's rows.
 */
public final class EffectiveCourses {

    private EffectiveCourses() {}

    /** A real {@code enrollment} row. */
    public record EnrolmentRow(UUID courseId, EnrollmentSource source, String enrollmentStatus,
                               int progressPct, boolean required, Instant deadline,
                               Instant enrolledAt, Instant completedAt, String assignedByName) {}

    /** An {@code org_course_rules} row covering this member (exclusions already removed). */
    public record RuleRow(UUID courseId, boolean required, Instant deadline,
                          Instant createdAt, String createdByName) {}

    /** An open {@code auto_enrolments} SUGGESTED row (exclusions already removed). */
    public record SuggestionRow(UUID courseId, Instant createdAt, String pillarName) {}

    /** Course titles, resolved once for whatever ids the three lists mention. */
    public record CourseMeta(UUID courseId, String title, String slug) {}

    /**
     * Merge, dedup by course, one row per course carrying the strongest source.
     *
     * <p>Precedence comes from {@link EnrollmentSource#precedence()}. The
     * strongest source decides the LABEL; the enrollment row, when there is one,
     * still decides the PROGRESS — a self-enrolment later covered by an org rule
     * reads "Org rule" but keeps the 38% the member earned.
     *
     * <p>{@code required} and {@code deadline} are unions, not the winner's
     * values: an optional direct assignment cannot cancel a required org rule,
     * and the earliest deadline is the one that binds.
     *
     * <p>Courses with no {@link CourseMeta} are dropped — a deleted course must
     * not render as a blank row.
     */
    public static List<EffectiveCourse> merge(List<EnrolmentRow> enrolments,
                                              List<RuleRow> rules,
                                              List<SuggestionRow> suggestions,
                                              Map<UUID, CourseMeta> meta) {
        Map<UUID, EnrolmentRow> byCourseEnrolment = enrolments.stream()
                .collect(Collectors.toMap(EnrolmentRow::courseId, Function.identity(), (a, b) -> a));
        Map<UUID, RuleRow> byCourseRule = rules.stream()
                .collect(Collectors.toMap(RuleRow::courseId, Function.identity(), (a, b) -> a));
        Map<UUID, SuggestionRow> byCourseSuggestion = suggestions.stream()
                .collect(Collectors.toMap(SuggestionRow::courseId, Function.identity(), (a, b) -> a));

        Set<UUID> courseIds = new LinkedHashSet<>();
        enrolments.forEach(r -> courseIds.add(r.courseId()));
        rules.forEach(r -> courseIds.add(r.courseId()));
        suggestions.forEach(r -> courseIds.add(r.courseId()));

        List<EffectiveCourse> merged = new ArrayList<>();
        for (UUID courseId : courseIds) {
            CourseMeta course = meta.get(courseId);
            if (course == null) {
                continue;
            }
            EnrolmentRow enrolment = byCourseEnrolment.get(courseId);
            RuleRow rule = byCourseRule.get(courseId);
            SuggestionRow suggestion = byCourseSuggestion.get(courseId);

            EnrollmentSource source = strongest(enrolment, rule, suggestion);
            boolean required = (enrolment != null && enrolment.required())
                    || (rule != null && rule.required());
            Instant deadline = earliest(enrolment == null ? null : enrolment.deadline(),
                    rule == null ? null : rule.deadline());
            Instant assignedAt = firstNonNull(
                    enrolment == null ? null : enrolment.enrolledAt(),
                    rule == null ? null : rule.createdAt(),
                    suggestion == null ? null : suggestion.createdAt());

            merged.add(new EffectiveCourse(
                    courseId, course.title(), course.slug(),
                    source,
                    statusOf(enrolment, rule),
                    required,
                    deadline,
                    enrolment == null ? 0 : enrolment.progressPct(),
                    assignedAt,
                    enrolment == null ? null : enrolment.completedAt(),
                    assignedByName(source, enrolment, rule),
                    suggestion == null ? null : suggestion.pillarName(),
                    enrolment != null));
        }

        merged.sort(Comparator
                .comparingInt((EffectiveCourse c) -> c.source().precedence())
                .thenComparing(EffectiveCourse::assignedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(EffectiveCourse::courseTitle,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(merged);
    }

    private static EnrollmentSource strongest(EnrolmentRow enrolment, RuleRow rule, SuggestionRow suggestion) {
        EnrollmentSource strongest = null;
        if (enrolment != null) {
            strongest = enrolment.source();
        }
        if (rule != null && (strongest == null
                || EnrollmentSource.ORG_RULE.precedence() < strongest.precedence())) {
            strongest = EnrollmentSource.ORG_RULE;
        }
        if (suggestion != null && (strongest == null
                || EnrollmentSource.AI_SUGGESTED.precedence() < strongest.precedence())) {
            strongest = EnrollmentSource.AI_SUGGESTED;
        }
        return strongest == null ? EnrollmentSource.SELF : strongest;
    }

    /**
     * No enrollment row and no rule means the only claim is an open suggestion.
     * A rule with no row yet is ASSIGNED, not IN_PROGRESS — the member has it,
     * the row is written when they open it.
     */
    private static EffectiveCourseStatus statusOf(EnrolmentRow enrolment, RuleRow rule) {
        if (enrolment == null) {
            return rule == null ? EffectiveCourseStatus.SUGGESTED : EffectiveCourseStatus.ASSIGNED;
        }
        if ("COMPLETED".equals(enrolment.enrollmentStatus())) {
            return EffectiveCourseStatus.COMPLETED;
        }
        return enrolment.progressPct() > 0
                ? EffectiveCourseStatus.IN_PROGRESS
                : EffectiveCourseStatus.ASSIGNED;
    }

    /** "Assigned by Test Org Admin" — whoever the WINNING source says did it. */
    private static String assignedByName(EnrollmentSource source, EnrolmentRow enrolment, RuleRow rule) {
        if (source == EnrollmentSource.ORG_RULE && rule != null) {
            return rule.createdByName();
        }
        return enrolment == null ? null : enrolment.assignedByName();
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static Instant firstNonNull(Instant... values) {
        for (Instant value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
