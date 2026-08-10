package com.bvisionry.courseaccess;

import com.bvisionry.common.enums.EnrollmentSource;
import com.bvisionry.courseaccess.domain.EffectiveCourse;
import com.bvisionry.courseaccess.domain.EffectiveCourseStatus;
import com.bvisionry.courseaccess.domain.EffectiveCourses;
import com.bvisionry.courseaccess.domain.EffectiveCourses.CourseMeta;
import com.bvisionry.courseaccess.domain.EffectiveCourses.EnrolmentRow;
import com.bvisionry.courseaccess.domain.EffectiveCourses.RuleRow;
import com.bvisionry.courseaccess.domain.EffectiveCourses.SuggestionRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec §3 dedup rule, with no database in the way.
 *
 * <p>Precedence is the part of course wiring that is cheapest to get wrong and
 * most expensive to have wrong — an org-wide rule hiding a by-name assignment
 * reads as a bug to the admin who made it — so every collision is driven here
 * rather than through a Testcontainers round trip.
 */
class EffectiveCoursesTest {

    private static final UUID COURSE = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Map<UUID, CourseMeta> META =
            Map.of(COURSE, new CourseMeta(COURSE, "Pricing Foundations", "pricing-foundations"));

    private static EnrolmentRow enrolment(EnrollmentSource source, boolean required, Instant deadline) {
        return new EnrolmentRow(COURSE, source, "ACTIVE", 38, required, deadline, T0, null, "Test Org Admin");
    }

    private static RuleRow rule(boolean required, Instant deadline) {
        return new RuleRow(COURSE, required, deadline, T0, "Rule Author");
    }

    private static SuggestionRow suggestion() {
        return new SuggestionRow(COURSE, T0, "Focus & Flow");
    }

    /* ------------------------------------------------------------ precedence */

    @Test
    void allFourSourcesCollide_oneRowWins_andItIsDirect() {
        // A DIRECT enrollment plus an org rule plus an open AI suggestion: the
        // member holds ONE course, and the strongest claim is the label.
        List<EffectiveCourse> merged = EffectiveCourses.merge(
                List.of(enrolment(EnrollmentSource.DIRECT, false, null)),
                List.of(rule(false, null)),
                List.of(suggestion()),
                META);

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().source()).isEqualTo(EnrollmentSource.DIRECT);
    }

    @Test
    void removingTheDirectAssignment_revealsTheOrgRule() {
        // Same inputs minus the DIRECT enrollment: the rule was there all along
        // and now shows. The member never lost the course.
        List<EffectiveCourse> merged = EffectiveCourses.merge(
                List.of(), List.of(rule(false, null)), List.of(suggestion()), META);

        assertThat(merged).singleElement()
                .satisfies(c -> assertThat(c.source()).isEqualTo(EnrollmentSource.ORG_RULE));
    }

    @Test
    void orgRuleOutranksASelfEnrolment_butTheSelfEnrolmentKeepsTheProgress() {
        List<EffectiveCourse> merged = EffectiveCourses.merge(
                List.of(enrolment(EnrollmentSource.SELF, false, null)),
                List.of(rule(false, null)), List.of(), META);

        EffectiveCourse row = merged.getFirst();
        assertThat(row.source()).isEqualTo(EnrollmentSource.ORG_RULE);
        assertThat(row.progressPct()).isEqualTo(38);
        assertThat(row.status()).isEqualTo(EffectiveCourseStatus.IN_PROGRESS);
        assertThat(row.materialized()).isTrue();
    }

    @Test
    void aiSuggestionOutranksSelf_butNotARule() {
        assertThat(EffectiveCourses.merge(List.of(enrolment(EnrollmentSource.SELF, false, null)),
                        List.of(), List.of(suggestion()), META).getFirst().source())
                .isEqualTo(EnrollmentSource.AI_SUGGESTED);

        assertThat(EffectiveCourses.merge(List.of(), List.of(rule(false, null)),
                        List.of(suggestion()), META).getFirst().source())
                .isEqualTo(EnrollmentSource.ORG_RULE);
    }

    /* --------------------------------------------------- unions, not winners */

    @Test
    void requiredIsAUnion_anOptionalPathCannotCancelARequiredOne() {
        // DIRECT wins the label but arrives optional; the org rule says required.
        EffectiveCourse row = EffectiveCourses.merge(
                List.of(enrolment(EnrollmentSource.DIRECT, false, null)),
                List.of(rule(true, null)), List.of(), META).getFirst();

        assertThat(row.source()).isEqualTo(EnrollmentSource.DIRECT);
        assertThat(row.required()).isTrue();
    }

    @Test
    void theEarliestDeadlineBinds_andNoDeadlineNeverWins() {
        Instant early = T0.plus(7, ChronoUnit.DAYS);
        Instant late = T0.plus(30, ChronoUnit.DAYS);

        assertThat(EffectiveCourses.merge(List.of(enrolment(EnrollmentSource.DIRECT, false, late)),
                        List.of(rule(false, early)), List.of(), META).getFirst().deadline())
                .isEqualTo(early);

        assertThat(EffectiveCourses.merge(List.of(enrolment(EnrollmentSource.DIRECT, false, null)),
                        List.of(rule(false, early)), List.of(), META).getFirst().deadline())
                .isEqualTo(early);
    }

    /* ------------------------------------------------------------- statuses */

    @Test
    void aSuggestionAloneIsSuggested_andIsNotMaterialized() {
        EffectiveCourse row = EffectiveCourses.merge(List.of(), List.of(), List.of(suggestion()), META)
                .getFirst();

        assertThat(row.status()).isEqualTo(EffectiveCourseStatus.SUGGESTED);
        assertThat(row.materialized()).isFalse();
        assertThat(row.reason()).isEqualTo("Focus & Flow");
        assertThat(row.progressPct()).isZero();
    }

    @Test
    void aRuleWithNoEnrollmentRowIsAssigned_notInProgress() {
        EffectiveCourse row = EffectiveCourses.merge(List.of(), List.of(rule(true, null)), List.of(), META)
                .getFirst();

        assertThat(row.status()).isEqualTo(EffectiveCourseStatus.ASSIGNED);
        assertThat(row.materialized()).isFalse();
        assertThat(row.required()).isTrue();
        assertThat(row.assignedByName()).isEqualTo("Rule Author");
    }

    @Test
    void completedWins_overProgressPct() {
        EffectiveCourse row = EffectiveCourses.merge(
                List.of(new EnrolmentRow(COURSE, EnrollmentSource.SELF, "COMPLETED", 100, false, null,
                        T0, T0.plus(1, ChronoUnit.DAYS), null)),
                List.of(), List.of(), META).getFirst();

        assertThat(row.status()).isEqualTo(EffectiveCourseStatus.COMPLETED);
        assertThat(row.completedAt()).isNotNull();
    }

    @Test
    void zeroProgressIsAssigned_notInProgress() {
        EffectiveCourse row = EffectiveCourses.merge(
                List.of(new EnrolmentRow(COURSE, EnrollmentSource.DIRECT, "ACTIVE", 0, false, null,
                        T0, null, null)),
                List.of(), List.of(), META).getFirst();

        assertThat(row.status()).isEqualTo(EffectiveCourseStatus.ASSIGNED);
    }

    /* --------------------------------------------------------------- overdue */

    @Test
    void overdueIsDeadlinePassedAndNotFinished() {
        Instant past = T0.minus(1, ChronoUnit.DAYS);
        EffectiveCourse open = EffectiveCourses.merge(
                List.of(enrolment(EnrollmentSource.DIRECT, true, past)), List.of(), List.of(), META)
                .getFirst();
        assertThat(open.overdue(T0)).isTrue();

        EffectiveCourse done = EffectiveCourses.merge(
                List.of(new EnrolmentRow(COURSE, EnrollmentSource.DIRECT, "COMPLETED", 100, true, past,
                        T0, T0, null)),
                List.of(), List.of(), META).getFirst();
        assertThat(done.overdue(T0)).isFalse();
    }

    /* ----------------------------------------------------------- edge cases */

    @Test
    void aCourseWithNoMetadataIsDropped_neverRenderedBlank() {
        assertThat(EffectiveCourses.merge(List.of(enrolment(EnrollmentSource.DIRECT, false, null)),
                List.of(), List.of(), Map.of())).isEmpty();
    }

    @Test
    void theEnumOrderIsThePrecedence() {
        // Pinned: the merge reads EnrollmentSource.ordinal() as the ranking, so
        // reordering the enum silently reorders every source chip on the product.
        assertThat(List.of(EnrollmentSource.values()))
                .containsExactly(EnrollmentSource.DIRECT, EnrollmentSource.ORG_RULE,
                        EnrollmentSource.AI_SUGGESTED, EnrollmentSource.SELF);
    }

    @Test
    void unknownStoredSourceDegradesToSelf_theWeakestClaim() {
        assertThat(EnrollmentSource.of("MYSTERY")).isEqualTo(EnrollmentSource.SELF);
        assertThat(EnrollmentSource.of(null)).isEqualTo(EnrollmentSource.SELF);
    }
}
