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
        Set<UUID> rosterIds = rosterIds(cohortId, orgId);
        List<RosterMember> roster = reads.roster(cohortId, orgId).stream()
                .map(r -> new RosterMember(r.id(), r.name(), r.email()))
                .toList();
        // A 1:1 row is ABOUT one founder (sessions spec v2 §2). An org that
        // shares the cohort but not that founder has no business seeing it —
        // not even as an anonymous "0 of 1 attended" line. Cohort-wide rows
        // (member_id null) and plain sessions are shared by construction.
        List<Session> all = sessions.findByCohortOrderedByDate(cohortId).stream()
                .filter(s -> s.getMemberId() == null || rosterIds.contains(s.getMemberId()))
                .toList();
        Map<UUID, List<SessionAttendance>> marks = attendance
                .findBySessionIdIn(all.stream().map(Session::getId).toList())
                .stream().collect(Collectors.groupingBy(SessionAttendance::getSessionId));
        Map<UUID, String> markerNames = names(marks.values().stream()
                .flatMap(List::stream).toList(), all);
        return new CohortSessionsResponse(roster, all.stream()
                .map(s -> toDto(s, marks.getOrDefault(s.getId(), List.of()), markerNames, rosterIds))
                .toList());
    }

    public SessionDto create(UUID cohortId, UUID orgId, UpsertSessionRequest req, UUID actorId) {
        requireAssignedCohort(cohortId, orgId);
        Session s = new Session();
        s.setCohortId(cohortId);
        s.setCreatedBy(actorId);
        apply(s, cohortId, orgId, req);
        return toDto(sessions.save(s), List.of(), Map.of(), rosterIds(cohortId, orgId));
    }

    public SessionDto update(UUID cohortId, UUID orgId, UUID sessionId, UpsertSessionRequest req) {
        requireAssignedCohort(cohortId, orgId);
        Session s = requireSession(cohortId, sessionId);
        requireNotBooking(s);
        apply(s, cohortId, orgId, req);
        return withAttendance(s, rosterIds(cohortId, orgId));
    }

    /**
     * A coaching booking is the member's and the coach's (spec §2.2): the
     * Sessions tab reads it but never edits, deletes or marks it — roll call,
     * like cancelling, happens in the coach's console.
     */
    private static void requireNotBooking(Session s) {
        if (s.isBooking()) {
            throw new IllegalOperationException(
                    "This session was booked through the curriculum and is managed by the coach.");
        }
    }

    /** Shared upsert body; expected attendees must be the ORG's own cohort members (§13.7). */
    private void apply(Session s, UUID cohortId, UUID orgId, UpsertSessionRequest req) {
        s.setType(req.type());
        s.setTitle(blankToNull(req.title()));
        s.setSessionDate(req.sessionDate().atOffset(ZoneOffset.UTC));
        List<UUID> expected = req.expectedMemberIds() == null ? List.of() : req.expectedMemberIds();
        Set<UUID> roster = rosterIds(cohortId, orgId);
        if (!expected.isEmpty() && !roster.containsAll(expected)) {
            throw new BadRequestException(
                    "One or more expected attendees are not members of this cohort");
        }
        // §13.7: on a cohort shared by several orgs the session row is common,
        // but each org owns only its own founders' expected-attendee slice.
        // Replace only the caller-org's members; preserve every id that belongs
        // to another org's roster so an update can never wipe their set.
        Set<UUID> merged = s.getExpectedMemberIds().stream()
                .filter(id -> !roster.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        merged.addAll(expected);
        s.setExpectedMemberIds(merged);
        // Only a fully-mapped pair's distance pillar is taggable — the same
        // rule the board task save enforces, for the same reason: an unmapped
        // distance pillar feeds no narrative, so the tag would be dead weight
        // the picker no longer even shows.
        List<UUID> pillars = req.pillarIds() == null ? List.of() : req.pillarIds();
        if (!pillars.isEmpty()) {
            Set<UUID> mapped = Set.copyOf(reads.mappedDistancePillarIds(cohortId));
            if (!mapped.containsAll(pillars)) {
                throw new BadRequestException(
                        "One or more pillars are not mapped baseline↔distance pairs of this cohort");
            }
        }
        s.setPillarIds(new LinkedHashSet<>(pillars));
    }

    public void delete(UUID cohortId, UUID orgId, UUID sessionId) {
        requireAssignedCohort(cohortId, orgId);
        Session s = requireSession(cohortId, sessionId);
        requireNotBooking(s);
        // §13.7: a session on a shared cohort may carry another org's expected
        // attendees or attendance rows. Deleting it cascades their history away,
        // so refuse when any founder-data belongs outside the caller's roster —
        // no org unilaterally destroys another's roll call.
        Set<UUID> roster = rosterIds(cohortId, orgId);
        boolean hasForeignData = s.getExpectedMemberIds().stream().anyMatch(id -> !roster.contains(id))
                || attendance.findBySessionIdIn(List.of(sessionId)).stream()
                        .anyMatch(m -> !roster.contains(m.getMemberId()));
        if (hasForeignData) {
            throw new IllegalOperationException(
                    "This session is shared with another organization and cannot be deleted here");
        }
        // V212: attendance is RESTRICT now — this deliberate own-org delete
        // clears its roll call explicitly instead of cascading it.
        attendance.deleteBySessionId(sessionId);
        sessions.delete(s);
    }

    /**
     * The roll-call tick/untick. Present = a §7b-stamped presence row;
     * re-ticking keeps the original stamp (idempotent); untick deletes the row.
     */
    public SessionDto setAttendance(UUID cohortId, UUID orgId, UUID sessionId, UUID memberId,
                                    boolean present, UUID actorId) {
        requireAssignedCohort(cohortId, orgId);
        Session s = requireSession(cohortId, sessionId);
        requireNotBooking(s);
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
        return withAttendance(s, rosterIds(cohortId, orgId));
    }

    /* ------------------------------------------------------------- plumbing */

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

    /** The caller-org's own members in this cohort — the §13.7 visibility slice. */
    private Set<UUID> rosterIds(UUID cohortId, UUID orgId) {
        return reads.roster(cohortId, orgId).stream()
                .map(EngagementReadRepository.RosterRow::id)
                .collect(Collectors.toSet());
    }

    private SessionDto withAttendance(Session s, Set<UUID> rosterIds) {
        List<SessionAttendance> marks = attendance.findBySessionIdIn(List.of(s.getId()));
        return toDto(s, marks, names(marks, List.of(s)), rosterIds);
    }

    /** Display names for attendance markers AND booking coaches, one lookup. */
    private Map<UUID, String> names(List<SessionAttendance> marks, List<Session> sessions) {
        List<UUID> ids = java.util.stream.Stream.concat(
                        marks.stream().map(SessionAttendance::getMarkedBy),
                        sessions.stream().map(Session::getCoachId))
                .filter(id -> id != null).distinct().toList();
        return reads.markerNames(ids).stream()
                .collect(Collectors.toMap(EngagementReadRepository.MarkerName::markedBy,
                        m -> m.name() == null ? "" : m.name(), (a, b) -> a));
    }

    private static SessionDto toDto(Session s, List<SessionAttendance> marks,
                                    Map<UUID, String> markerNames, Set<UUID> rosterIds) {
        // §13.7: only ever surface the caller-org's own founders — never
        // another org's ids, attendance, or the name of who marked them.
        List<AttendanceMark> att = marks.stream()
                .filter(m -> rosterIds.contains(m.getMemberId()))
                .sorted(Comparator.comparing(SessionAttendance::getMarkedAt))
                .map(m -> new AttendanceMark(m.getMemberId(),
                        m.getMarkedAt() == null ? null : m.getMarkedAt().toInstant(),
                        m.getMarkedBy() == null ? null : markerNames.get(m.getMarkedBy())))
                .toList();
        List<UUID> expected = s.getExpectedMemberIds().stream()
                .filter(rosterIds::contains)
                .toList();
        return new SessionDto(s.getId(), s.getType(), s.getTitle(),
                s.getSessionDate() == null ? null : s.getSessionDate().toInstant(),
                s.getCreatedAt() == null ? null : s.getCreatedAt().toInstant(),
                s.getUpdatedAt() == null ? null : s.getUpdatedAt().toInstant(),
                expected.isEmpty() ? null : expected.size(),
                expected,
                List.copyOf(s.getPillarIds()),
                att,
                s.getBookingStatus(),
                s.getCoachId() == null ? null : markerNames.get(s.getCoachId()),
                // The booked founder, but only if they are the caller-org's (§13.7).
                s.getMemberId() != null && rosterIds.contains(s.getMemberId()) ? s.getMemberId() : null,
                s.getMeetingUrl(),
                s.getProgramTaskId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
