package com.bvisionry.founderprofile.web;

import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.FriPoint;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.ProgramTaskRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header's Δ-so-far math (pure): latest minus earliest, null under two
 * points — plus the Work tab's per-type cohort-task status.
 */
class FounderProfileServiceTest {

    private static FriPoint point(String score) {
        return new FriPoint(score == null ? null : new BigDecimal(score), Instant.now());
    }

    @Test
    void latestMinusEarliest() {
        assertThat(FounderProfileService.friDelta(
                List.of(point("50.00"), point("64.00"), point("71.25"))))
                .isEqualByComparingTo("21.25");
    }

    @Test
    void declineIsNegative() {
        assertThat(FounderProfileService.friDelta(List.of(point("60.00"), point("52.50"))))
                .isEqualByComparingTo("-7.50");
    }

    @Test
    void underTwoPointsIsNull() {
        assertThat(FounderProfileService.friDelta(List.of())).isNull();
        assertThat(FounderProfileService.friDelta(List.of(point("58.00")))).isNull();
    }

    @Test
    void nullScoresYieldNullNotAnException() {
        assertThat(FounderProfileService.friDelta(List.of(point(null), point("58.00")))).isNull();
    }

    private static ProgramTaskRow task(String type, boolean done, String submissionStatus) {
        return new ProgramTaskRow(null, "T", null, "Cohort", "Module", null, type, null, null,
                done, submissionStatus, null, null, null, null);
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
}
