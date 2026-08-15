package com.bvisionry.coaching.web;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.coaching.domain.CoachNote;
import com.bvisionry.coaching.dto.CoachNoteRequest;
import com.bvisionry.coaching.dto.CoachNoteResponse;
import com.bvisionry.coaching.repository.CoachNoteRepository;
import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

/**
 * Coach-side note writes. Create is gated by the shared {@link CoachAccess}
 * assignment-union predicate (a founder outside the union is a 404); edit and
 * delete are gated by AUTHORSHIP — the lookup itself is
 * {@code findByIdAndCoachId}, so another coach's note is a 404, never a leak.
 * Reads happen on the founder-profile aggregation, not here.
 */
@Service
public class CoachNoteService {

    private final CoachNoteRepository notes;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;

    public CoachNoteService(CoachNoteRepository notes, CoachAccess coachAccess,
            CurrentUserAccessor currentUser) {
        this.notes = notes;
        this.coachAccess = coachAccess;
        this.currentUser = currentUser;
    }

    @Transactional
    public CoachNoteResponse create(UUID founderId, CoachNoteRequest request) {
        CoachAccess.ViewedFounder view = coachAccess.requireSees(founderId);
        CoachNote note = new CoachNote();
        note.setOrgId(view.orgId());
        note.setCoachId(view.viewerId());
        note.setMemberId(view.founderId());
        note.setBody(request.body().trim());
        return CoachNoteResponse.from(notes.saveAndFlush(note), currentUser.require().name());
    }

    @Transactional
    public CoachNoteResponse update(UUID noteId, CoachNoteRequest request) {
        CurrentUser coach = currentUser.require();
        CoachNote note = requireOwn(noteId, coach.userId());
        note.setBody(request.body().trim());
        return CoachNoteResponse.from(notes.saveAndFlush(note), coach.name());
    }

    @Transactional
    public void delete(UUID noteId) {
        notes.delete(requireOwn(noteId, currentUser.require().userId()));
        // Flush so the profile aggregation's raw-SQL read (same connection)
        // can never see a deleted note — mirrors the saveAndFlush stance above.
        notes.flush();
    }

    /**
     * Ownership AND a CURRENT view of the founder: a coach whose assignment
     * was revoked keeps nothing here — the note stays visible to org admins,
     * but its author can no longer edit or delete it. Same union predicate as
     * create, re-checked at mutation time.
     */
    private CoachNote requireOwn(UUID noteId, UUID coachId) {
        CoachNote note = notes.findByIdAndCoachId(noteId, coachId)
                .orElseThrow(() -> new ResourceNotFoundException("Note", noteId.toString()));
        if (!coachAccess.coachSees(note.getOrgId(), coachId, note.getMemberId())) {
            throw new ResourceNotFoundException("Note", noteId.toString());
        }
        return note;
    }
}
