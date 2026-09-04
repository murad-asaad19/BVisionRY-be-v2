package com.bvisionry.coaching.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The member's session screen in one payload — {@code GET
 * /api/my/program/tasks/{taskId}/booking} (spec v2 §6.3). Task, the row that
 * represents it and the bookable coaches together, because the screen renders
 * every state off the same fetch.
 *
 * @param sessionType      COACHING_1ON1 / COACHING_GROUP / WORKSHOP. The first
 *                         is booked by the member; the other two are dated by
 *                         their coach and the screen is read-only
 * @param booking          the row from spec §3 — the member's own for a 1:1,
 *                         the task's single cohort-wide row otherwise. Null only
 *                         when materialisation has not reached this member yet
 * @param attendeeCount    active learners expected on a cohort-wide session
 *                         ("You and N others"); NULL for a 1:1, where the answer
 *                         is always two and the screen says so in words
 * @param coaches          spec §5: the coaches who may see this member AND have
 *                         published availability. EMPTY for a cohort-wide task,
 *                         where there is nothing for the member to pick
 * @param feedbackSurveyId the task's post-session survey, or null
 * @param feedbackPending  the session was held with them present, a survey is
 *                         set, and they have not answered it yet
 */
public record MemberBookingResponse(UUID taskId, String taskName, String sessionType,
                                    Integer durationMinutes,
                                    BookingDto booking, Integer attendeeCount,
                                    List<BookingCoachDto> coaches,
                                    UUID feedbackSurveyId, boolean feedbackPending) {

    /**
     * @param coachId    null until the session is dated (UNSCHEDULED)
     * @param meetingUrl the video link, once a calendar event exists (spec §7)
     * @param attended   null until the session is COMPLETED; then whether THIS
     *                   member has an attendance row — "Done" vs "Missed"
     */
    public record BookingDto(UUID id, UUID coachId, String coachName, Instant startsAt,
                             Instant endsAt, String bookingStatus, Instant completedAt,
                             String meetingUrl, Boolean attended) {}

    /**
     * @param photoUrl ALREADY resolved through {@code MediaUrlPort} — a raw
     *                 {@code minio://} marker is not loadable by a browser
     */
    public record BookingCoachDto(UUID id, String name, String headline, String photoUrl) {}
}
