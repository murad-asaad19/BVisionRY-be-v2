package com.bvisionry.coaching.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The coach's own console — {@code GET /api/v1/coach/sessions} (spec v2 §6.1).
 * Three lists rather than one, because the buckets get different orders and
 * different actions: {@code unscheduled} is a to-do (schedule a group session,
 * or wait on a member's 1:1), {@code upcoming} ascending (join / reschedule /
 * cancel), {@code past} newest-first (the roll call).
 */
public record CoachSessionsResponse(List<CoachSessionDto> unscheduled,
                                    List<CoachSessionDto> upcoming,
                                    List<CoachSessionDto> past) {

    /**
     * @param sessionType        COACHING_1ON1 / COACHING_GROUP / WORKSHOP — a
     *                           1:1 row names its member, the other two do not
     * @param durationMinutes    the task's configured length. Carried even
     *                           though a dated row's {@code endsAt} implies it,
     *                           because an UNSCHEDULED row has neither end and
     *                           the slot picker needs the length to ask for slots
     * @param canSchedule        this coach may date (or re-date) the row: true
     *                           only for a cohort-wide session that is not held
     * @param attendees          the roll call: the one member of a 1:1, or the
     *                           cohort's CURRENT roster, each with their mark
     * @param feedbackSubmitted  whether the member has already answered the
     *                           task's post-session survey — the coach's only
     *                           view of it; the answers themselves are the
     *                           survey slice's, not theirs. Always false on a
     *                           cohort-wide row, which has no single respondent
     */
    public record CoachSessionDto(UUID id, String sessionType,
                                  UUID memberId, String memberName,
                                  UUID cohortId, String cohortName,
                                  UUID taskId, String taskName,
                                  Instant startsAt, Instant endsAt, int durationMinutes,
                                  String bookingStatus, Instant completedAt,
                                  String meetingUrl, boolean canSchedule,
                                  List<SessionAttendeeDto> attendees,
                                  boolean feedbackSubmitted) {}

    /** One expected member and whether they were marked present. */
    public record SessionAttendeeDto(UUID memberId, String name, boolean present) {}
}
