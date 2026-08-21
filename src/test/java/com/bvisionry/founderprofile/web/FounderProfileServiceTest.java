package com.bvisionry.founderprofile.web;

import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.AssessmentRow;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.ProgramTaskRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Work tab's per-type cohort-task status and milestone adoption.
 *
 * <p>The header's Δ-so-far math used to be tested here. It is gone: a global
 * latest-minus-earliest across every instrument contradicted the cohort's real
 * comparison on the same screen, so the header now reads
 * {@code founder_comparisons.overall_delta} instead of deriving a second answer.
 */
class FounderProfileServiceTest {

    private static ProgramTaskRow task(String type, boolean done, String submissionStatus) {
        return new ProgramTaskRow(null, "T", null, "Cohort", "Module", null, type, null, null,
                null, done, submissionStatus, null, null, null, null);
    }

    /** lessonStatus only maps LESSON's done flag to SUBMITTED; unfinished tasks keep their raw status. */
    @Test
    void doneLessonReportsSubmitted() {
        assertThat(FounderProfileService.lessonStatus(task("LESSON", true, "SUBMITTED")))
                .isEqualTo("SUBMITTED");
        assertThat(FounderProfileService.lessonStatus(task("LESSON", true, null)))
                .isEqualTo("SUBMITTED");
    }

    @Test
    void unfinishedTasksKeepTheirSubmissionStatus() {
        assertThat(FounderProfileService.lessonStatus(task("LESSON", false, "DRAFT")))
                .isEqualTo("DRAFT");
        assertThat(FounderProfileService.lessonStatus(task("LESSON", false, null))).isNull();
    }

    /* ------------------------------------------- milestone adoption (Work tab) */

    private static final UUID COHORT = UUID.randomUUID();
    private static final UUID READINESS = UUID.randomUUID();
    private static final UUID DISTANCE = UUID.randomUUID();
    private static final Instant LAUNCH = Instant.parse("2026-06-01T00:00:00Z");

    private static ProgramTaskRow milestone(String role, UUID pipeline) {
        return new ProgramTaskRow(UUID.randomUUID(), "T", COHORT, "Cohort", "Module", null,
                "ASSESSMENT", pipeline, role, LAUNCH, false, null, null, null, null, null);
    }

    private static AssessmentRow sitting(UUID pipeline, String evaluatedAt) {
        return new AssessmentRow(UUID.randomUUID(), UUID.randomUUID(), null, pipeline, "P",
                null, "EVALUATED", null, null, Instant.parse(evaluatedAt), null, null);
    }

    /**
     * Own-instrument DISTANCE (readiness → distance): the floor is the
     * member's baseline reading, not the launch — a pre-launch sitting after
     * the baseline adopts; one before the baseline never does.
     */
    @Test
    void distanceOnItsOwnInstrumentAdoptsTheSittingAfterTheBaselineReading() {
        ProgramTaskRow baseline = milestone("BASELINE", READINESS);
        ProgramTaskRow distance = milestone("DISTANCE", DISTANCE);
        AssessmentRow baselineSitting = sitting(READINESS, "2026-05-04T00:00:00Z");
        AssessmentRow beforeBaseline = sitting(DISTANCE, "2026-03-01T00:00:00Z");
        AssessmentRow afterBaseline = sitting(DISTANCE, "2026-05-10T00:00:00Z");

        assertThat(FounderProfileService.adoptableSitting(distance,
                List.of(baseline, distance),
                List.of(baselineSitting, beforeBaseline, afterBaseline)))
                .isEqualTo(afterBaseline);
        assertThat(FounderProfileService.adoptableSitting(distance,
                List.of(baseline, distance), List.of(baselineSitting, beforeBaseline)))
                .isNull();
    }

    /**
     * Shared-instrument DISTANCE keeps the strict rule: launch floor, and
     * never the pipeline's earliest sitting (BASELINE's claim).
     */
    @Test
    void distanceOnASharedInstrumentSkipsTheBaselinesSitting() {
        ProgramTaskRow baseline = milestone("BASELINE", READINESS);
        ProgramTaskRow distance = milestone("DISTANCE", READINESS);
        AssessmentRow first = sitting(READINESS, "2026-06-10T00:00:00Z");
        AssessmentRow second = sitting(READINESS, "2026-07-01T00:00:00Z");

        assertThat(FounderProfileService.adoptableSitting(distance,
                List.of(baseline, distance), List.of(first, second))).isEqualTo(second);
        assertThat(FounderProfileService.adoptableSitting(distance,
                List.of(baseline, distance), List.of(first))).isNull();
    }

    /** CHECKIN adopts nothing — an outside sitting is not a point on the curve. */
    @Test
    void checkinAdoptsNothing() {
        ProgramTaskRow checkin = milestone("CHECKIN", READINESS);
        assertThat(FounderProfileService.adoptableSitting(checkin,
                List.of(checkin), List.of(sitting(READINESS, "2026-06-10T00:00:00Z"))))
                .isNull();
    }
}
