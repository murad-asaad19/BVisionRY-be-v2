package com.bvisionry.coaching.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.bvisionry.coaching.domain.CoachNote;

/**
 * One coach note, labeled with its author (spec §2.2). Timestamps are part of
 * the contract (§7b): every note surfaces created/updated visibly.
 */
public record CoachNoteResponse(
        UUID id,
        UUID memberId,
        UUID coachId,
        String coachName,
        String body,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CoachNoteResponse from(CoachNote note, String coachName) {
        return new CoachNoteResponse(note.getId(), note.getMemberId(), note.getCoachId(),
                coachName, note.getBody(), note.getCreatedAt(), note.getUpdatedAt());
    }
}
