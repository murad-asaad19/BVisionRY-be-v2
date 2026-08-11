package com.bvisionry.engagement.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.engagement.domain.Session;
import com.bvisionry.engagement.domain.SessionAttendance;
import com.bvisionry.engagement.dto.SessionDtos.AttendanceMark;
import com.bvisionry.engagement.dto.SessionDtos.CohortSessionsResponse;
import com.bvisionry.engagement.dto.SessionDtos.RosterMember;
import com.bvisionry.engagement.dto.SessionDtos.SessionDto;
import com.bvisionry.engagement.dto.SessionDtos.UpsertSessionRequest;
import com.bvisionry.engagement.repository.EngagementReadRepository;
import com.bvisionry.engagement.repository.SessionAttendanceRepository;
import com.bvisionry.engagement.repository.SessionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Session CRUD + roll call for the Sessions tab (spec §4). Org-scoped since
 * §13.7: every roster read and every roll-call tick is bounded to the org's
 * OWN members, so one org can never see or mark another's founders. Coaches
 * read engagement through the founder profile. Every tick is §7b-stamped.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SessionService {

    private final SessionRepository sessions;
    private final SessionAttendanceRepository attendance;
    private final EngagementReadRepository reads;

    @Transactional(readOnly = true)
    public CohortSessionsResponse list(UUID cohortId, UUID orgId) {
        requireAssignedCohort(cohortId, orgId);
        List<RosterMember> roster = reads.roster(cohortId, orgId).stream()
                .map(r -> new RosterMember(r.id(), r.name(), r.email()))
                .toList();
        List<Session> all = sessions.findByCohortIdOrderBySessionDateDesc(cohortId);
        Map<UUID, List<SessionAttendance>> marks = attendance
                .findBySessionIdIn(all.stream().map(Session::getId).toList())
                .stream().collect(Collectors.groupingBy(SessionAttendance::getSessionId));
        Map<UUID, String> markerNames = markerNames(marks.values().stream()
                .flatMap(List::stream).toList());
        return new CohortSessionsResponse(roster, all.stream()
                .map(s -> toDto(s, marks.getOrDefault(s.getId(), List.of()), markerNames))
                .toList());
    }

    public SessionDto create(UUID cohortId, UUID orgId, UpsertSessionRequest req, UUID actorId) {
        requireEditableCohort(cohortId, orgId);
        Session s = new Session();
        s.setCohortId(cohortId);
        s.setCreatedBy(actorId);
        apply(s, cohortId, orgId, req);
        return toDto(sessions.save(s), List.of(), Map.of());
    }

    public SessionDto update(UUID cohortId, UUID orgId, UUID sessionId, UpsertSessionRequest req) {
        requireEditableCohort(cohortId, orgId);
        Session s = requireSession(cohortId, sessionId);
        apply(s, cohortId, orgId, req);
        return withAttendance(s);
    }

    /** Shared upsert body; expected attendees must be the ORG's own cohort members (§13.7). */
    private void apply(Session s, UUID cohortId, UUID orgId, UpsertSessionRequest req) {
        s.setType(req.type());
        s.setTitle(blankToNull(req.title()));
        s.setSessionDate(req.sessionDate().atOffset(ZoneOffset.UTC));
        List<UUID> expected = req.expectedMemberIds() == null ? List.of() : req.expectedMemberIds();
        if (!expected.isEmpty()) {
            Set<UUID> roster = reads.roster(cohortId, orgId).stream()
                    .map(EngagementReadRepository.RosterRow::id)
                    .collect(Collectors.toSet());
            if (!roster.containsAll(expected)) {
                throw new BadRequestException(
                        "One or more expected attendees are not members of this cohort");
            }
        }
        s.setExpectedMemberIds(new LinkedHashSet<>(expected));
    }

    public void delete(UUID cohortId, UUID orgId, UUID sessionId) {
        requireEditableCohort(cohortId, orgId);
        sessions.delete(requireSession(cohortId, sessionId));
    }

    /**
     * The roll-call tick/untick. Present = a §7b-stamped presence row;
     * re-ticking keeps the original stamp (idempotent); untick deletes the row.
     */
    public SessionDto setAttendance(UUID cohortId, UUID orgId, UUID sessionId, UUID memberId,
                                    boolean present, UUID actorId) {
        requireEditableCohort(cohortId, orgId);
        Session s = requireSession(cohortId, sessionId);
        // Own members only — the org path must never be a door to another
        // org's founders (§13.7).
        boolean mine = reads.roster(cohortId, orgId).stream()
                .anyMatch(r -> r.id().equals(memberId));
        if (!mine) {
            throw new BadRequestException("This member is not enrolled in the cohort");
        }
        SessionAttendance.Key key = new SessionAttendance.Key(sessionId, memberId);
        if (present) {
            if (attendance.findById(key).isEmpty()) {
                SessionAttendance mark = new SessionAttendance();
                mark.setSessionId(sessionId);
                mark.setMemberId(memberId);
                mark.setMarkedAt(OffsetDateTime.now());
                mark.setMarkedBy(actorId);
                attendance.save(mark);
            }
        } else {
            attendance.deleteById(key);
        }
        attendance.flush();
        return withAttendance(s);
    }

    /* ------------------------------------------------------------- plumbing */

    /**
     * The V167 write gate: sessions and roll call stay mutable while the
     * cohort is DRAFT/LAUNCHED/COMPLETED (a late roll-call tidy-up on a
     * completed cohort is legitimate); ARCHIVED refuses every mutation.
     */
    private void requireEditableCohort(UUID cohortId, UUID orgId) {
        requireAssignedCohort(cohortId, orgId);
        String status = reads.cohortStatus(cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
        if ("ARCHIVED".equals(status)) {
            throw new IllegalOperationException("This cohort is archived and read-only.");
        }
    }

    /** Tenant guard (§13.7): the cohort must exist AND be assigned to this org. */
    private void requireAssignedCohort(UUID cohortId, UUID orgId) {
        if (reads.cohort(cohortId).isEmpty() || !reads.assignedToOrg(cohortId, orgId)) {
            throw new ResourceNotFoundException("Cohort", cohortId.toString());
        }
    }

    /** The session, guarded to the cohort path — 404 otherwise. */
    Session requireSession(UUID cohortId, UUID sessionId) {
        return sessions.findById(sessionId)
                .filter(s -> s.getCohortId().equals(cohortId))
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId.toString()));
    }

    private SessionDto withAttendance(Session s) {
        List<SessionAttendance> marks = attendance.findBySessionIdIn(List.of(s.getId()));
        return toDto(s, marks, markerNames(marks));
    }

    private Map<UUID, String> markerNames(List<SessionAttendance> marks) {
        List<UUID> ids = marks.stream().map(SessionAttendance::getMarkedBy)
                .filter(id -> id != null).distinct().toList();
        return reads.markerNames(ids).stream()
                .collect(Collectors.toMap(EngagementReadRepository.MarkerName::markedBy,
                        m -> m.name() == null ? "" : m.name(), (a, b) -> a));
    }

    private static SessionDto toDto(Session s, List<SessionAttendance> marks,
                                    Map<UUID, String> markerNames) {
        List<AttendanceMark> att = marks.stream()
                .sorted(Comparator.comparing(SessionAttendance::getMarkedAt))
                .map(m -> new AttendanceMark(m.getMemberId(),
                        m.getMarkedAt() == null ? null : m.getMarkedAt().toInstant(),
                        m.getMarkedBy() == null ? null : markerNames.get(m.getMarkedBy())))
                .toList();
        List<UUID> expected = List.copyOf(s.getExpectedMemberIds());
        return new SessionDto(s.getId(), s.getType(), s.getTitle(),
                s.getSessionDate() == null ? null : s.getSessionDate().toInstant(),
                s.getCreatedAt() == null ? null : s.getCreatedAt().toInstant(),
                s.getUpdatedAt() == null ? null : s.getUpdatedAt().toInstant(),
                expected.isEmpty() ? null : expected.size(),
                expected,
                att);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
