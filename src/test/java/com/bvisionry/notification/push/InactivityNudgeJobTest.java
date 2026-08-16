package com.bvisionry.notification.push;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The scheduled trigger for roadmap §7 item 7.
 *
 * <p>The inactivity PREDICATE and the send-once guard are SQL (see
 * {@code UserNotificationRepository.findStalledLearners}) and are not asserted
 * here — a mock cannot evaluate them. What is asserted is everything the job
 * itself owns and could get wrong: the sweep is per-org, delivery goes through
 * the ordinary preference-respecting dispatch under the member-visible type,
 * the deep link resumes the right course, and one org's failure does not
 * silently end the sweep for the orgs after it.
 */
@ExtendWith(MockitoExtension.class)
class InactivityNudgeJobTest {

    @Mock private UserNotificationRepository repository;
    @Mock private PushNotificationService pushNotificationService;

    /** The job under test, switched ON — the off case builds its own. */
    private InactivityNudgeJob job;

    private final UUID orgA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final UUID orgB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private final UUID founder = UUID.randomUUID();

    @BeforeEach
    void enableTheJob() {
        job = new InactivityNudgeJob(repository, pushNotificationService, true);
    }

    private static StalledLearnerRow row(UUID userId, String title, String slug, int days) {
        return new StalledLearnerRow() {
            @Override public UUID getUserId() { return userId; }
            @Override public String getCourseTitle() { return title; }
            @Override public String getCourseSlug() { return slug; }
            @Override public int getStalledDays() { return days; }
        };
    }

    /**
     * The kill switch, and the reason it is default-OFF: V149 gives EVERY
     * existing org a 14-day window at migration time, so a job that ran on
     * deploy would nudge the whole platform toward {@code /app/courses/**},
     * which renders Coming Soon while NEXT_PUBLIC_COURSES_ENABLED is false.
     * Disabled must mean "touches nothing" — not even the org sweep.
     */
    @Test
    void disabledByDefaultItDoesNotEvenLookForOrgsToSweep() {
        InactivityNudgeJob off = new InactivityNudgeJob(repository, pushNotificationService, false);

        off.nudgeStalledFounders();

        verifyNoInteractions(repository);
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void nudgesThroughTheOrdinaryDispatchWithADeepLinkToTheStalledCourse() {
        when(repository.findOrgIdsWithNudgesEnabled()).thenReturn(List.of(orgA));
        when(repository.findStalledLearners(orgA))
                .thenReturn(List.of(row(founder, "Fundraising Basics", "fundraising-basics", 21)));

        job.nudgeStalledFounders();

        // The `/learn` tail is asserted deliberately: there is no page at
        // /app/courses/[slug], so the bare slug is a chrome-less 404 and every
        // nudge would land on it. A test pinning that URL would have been
        // worse than no test.
        //
        // The type is member-visible, so the existing opt-out filter in
        // PushNotificationService.dispatch is what decides delivery — the job
        // never inspects preferences itself.
        verify(pushNotificationService).notifyUser(founder, NotificationType.INACTIVITY_NUDGE,
                "Pick up Fundraising Basics again",
                "No progress on Fundraising Basics for 21 days. Open it to carry on where you stopped.",
                "/app/courses/fundraising-basics/learn");
    }

    @Test
    void aSingleStalledDayIsNotPluralised() {
        when(repository.findOrgIdsWithNudgesEnabled()).thenReturn(List.of(orgA));
        when(repository.findStalledLearners(orgA))
                .thenReturn(List.of(row(founder, "Pitching", "pitching", 1)));

        job.nudgeStalledFounders();

        verify(pushNotificationService).notifyUser(ArgumentMatchers.eq(founder),
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("No progress on Pitching for 1 day. "
                        + "Open it to carry on where you stopped."),
                ArgumentMatchers.any());
    }

    @Test
    void asksOneOrgAtATimeAndNeverSweepsOrgsWithNudgingSwitchedOff() {
        // The org list is the ONLY source of orgs the job touches: an org with
        // inactivity_nudge_days = 0 (or suspended) is absent from it, so it is
        // never queried at all rather than queried and filtered afterwards.
        when(repository.findOrgIdsWithNudgesEnabled()).thenReturn(List.of(orgA, orgB));
        when(repository.findStalledLearners(orgA)).thenReturn(List.of());
        when(repository.findStalledLearners(orgB)).thenReturn(List.of());

        job.nudgeStalledFounders();

        verify(repository).findOrgIdsWithNudgesEnabled();
        verify(repository).findStalledLearners(orgA);
        verify(repository).findStalledLearners(orgB);
        verifyNoMoreInteractions(repository);
        verify(pushNotificationService, never()).notifyUser(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void oneOrgFailingDoesNotStopTheOrgsAfterIt() {
        when(repository.findOrgIdsWithNudgesEnabled()).thenReturn(List.of(orgA, orgB));
        when(repository.findStalledLearners(orgA)).thenThrow(new IllegalStateException("boom"));
        when(repository.findStalledLearners(orgB))
                .thenReturn(List.of(row(founder, "Unit Economics", "unit-economics", 30)));

        job.nudgeStalledFounders();

        verify(pushNotificationService).notifyUser(ArgumentMatchers.eq(founder),
                ArgumentMatchers.eq(NotificationType.INACTIVITY_NUDGE),
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("/app/courses/unit-economics/learn"));
    }

    @Test
    void anAuthoredCourseTitleCannotOverflowTheNotificationTitleColumn() {
        when(repository.findOrgIdsWithNudgesEnabled()).thenReturn(List.of(orgA));
        when(repository.findStalledLearners(orgA))
                .thenReturn(List.of(row(founder, "T".repeat(200), "long", 9)));

        job.nudgeStalledFounders();

        verify(pushNotificationService).notifyUser(ArgumentMatchers.eq(founder),
                ArgumentMatchers.any(),
                ArgumentMatchers.argThat(title -> title.length() == 200),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }
}
