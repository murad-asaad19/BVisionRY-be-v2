package com.bvisionry.coaching.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/admin/sessions/{id}/scheduling} (spec v2 §6.2): everything
 * the super admin's scheduling dialog needs before it can ask for slots.
 *
 * @param coaches spec §5 — the coaches assigned to the session's cohort (by
 *                cohort or org-wide) who have published availability. Empty
 *                means a coach has to be assigned, or none has ever saved a
 *                calendar — not an error
 */
public record CohortSessionSchedulingResponse(String sessionType, Integer durationMinutes,
                                              String bookingStatus,
                                              List<SchedulingCoachDto> coaches) {

    /**
     * A coach card for the picker. Its own record rather than the member
     * screen's {@code BookingCoachDto} because this one carries the coach's
     * ZONE: the admin scheduling for someone else's calendar has to see which
     * clock the offered slots are in, while a founder picking their own coach
     * reads the zone off the slots response.
     *
     * @param photoUrl ALREADY resolved through {@code MediaUrlPort} — a raw
     *                 {@code minio://} marker is not loadable by a browser
     */
    public record SchedulingCoachDto(UUID id, String name, String headline, String photoUrl,
                                     String timeZone) {}
}
