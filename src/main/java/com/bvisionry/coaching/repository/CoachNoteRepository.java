package com.bvisionry.coaching.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.coaching.domain.CoachNote;

public interface CoachNoteRepository extends JpaRepository<CoachNote, UUID> {

    /**
     * The one guarded load: a note is editable/deletable ONLY by the coach who
     * wrote it, so ownership is part of the lookup — someone else's note id is
     * indistinguishable from a missing one (404, no existence leak).
     */
    Optional<CoachNote> findByIdAndCoachId(UUID id, UUID coachId);
}
