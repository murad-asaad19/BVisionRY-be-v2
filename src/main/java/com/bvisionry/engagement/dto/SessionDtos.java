package com.bvisionry.engagement.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bvisionry.engagement.domain.SessionType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The cohort board Sessions tab contract (spec §4). Attendance is a list of
 * PRESENCE marks — a member absent from it is unticked, so the roll call
 * starts empty by construction. §7b: every mark carries who ticked it and
 * when.
 */
public final class SessionDtos {

    private SessionDtos() {
    }

    /**
     * One create/update body — a session is small enough for full replace.
     * {@code expectedMemberIds} null/empty = the whole cohort is expected;
     * ids narrow the session (a 1:1 names its one founder). Every id must be
     * a cohort member (400 otherwise).
     */
    public record UpsertSessionRequest(
            @NotNull SessionType type,
            @Size(max = 200) String title,
            @NotNull Instant sessionDate,
            List<UUID> expectedMemberIds,
            /** Distance pillar ids this session grows (V207); null/empty = untagged. */
            List<UUID> pillarIds) {
    }

    public record AttendanceRequest(boolean present) {
    }

    /** A §7b-stamped presence mark. */
    public record AttendanceMark(UUID memberId, Instant markedAt, String markedByName) {
    }

    public record SessionDto(
            UUID id,
            SessionType type,
            String title,
            Instant sessionDate,
            Instant createdAt,
            Instant updatedAt,
            /** Null = the whole cohort is expected. */
            Integer expectedCount,
            /** Empty = the whole cohort; ids drive the edit path + roll call. */
            List<UUID> expectedMemberIds,
            /** Distance pillar ids this session grows (V207). */
            List<UUID> pillarIds,
            List<AttendanceMark> attendance) {
    }

    /** An expected attendee — the cohort roster at read time. */
    public record RosterMember(UUID id, String name, String email) {
    }

    /** The whole Sessions tab in one GET: roster + sessions with marks. */
    public record CohortSessionsResponse(
            List<RosterMember> members,
            List<SessionDto> sessions) {
    }
}
