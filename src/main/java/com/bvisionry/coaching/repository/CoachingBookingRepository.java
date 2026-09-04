package com.bvisionry.coaching.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.calendar.TimeRange;
import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.programaccess.CohortVisibility;
import com.bvisionry.common.programaccess.Learner;
import com.bvisionry.common.programaccess.ProgramAudience;

import lombok.RequiredArgsConstructor;

/**
 * Session rows for the coaching slice, read and written as raw SQL.
 *
 * <p><strong>The v2 row model (spec §2/§3).</strong> A SESSION task's row is
 * MATERIALISED before anyone books it ({@link SessionMaterializer}): status
 * UNSCHEDULED, no coach and no dates. Booking a 1:1 or scheduling a
 * cohort-wide session UPDATES that row to SCHEDULED; cancelling reverts it.
 * Nothing is deleted, so participation, the Sessions tab and the founder
 * profile see one stable row per (task, member) — or one per task for a
 * cohort-wide session, where {@code member_id} is NULL and the expected-attendee
 * table is deliberately empty (empty = the whole cohort, V163 semantics).
 *
 * <p><strong>Why raw SQL.</strong> A session spans four other slices —
 * {@code sessions}/{@code session_attendance} ({@code engagement}),
 * {@code program_tasks}/{@code program_modules}/{@code cohorts}
 * ({@code programflow}), {@code survey_responses} ({@code survey}) and
 * {@code users} ({@code auth}) — and the ArchUnit ratchet forbids new
 * feature to feature Java imports. Same stance as
 * {@link CoachingReadRepository}: depend on the schema, never on another
 * slice's types.
 *
 * <p><strong>Every read carries its scope in the SQL.</strong> The member side
 * composes the shared enrollment fragments ({@link CohortVisibility},
 * {@link ProgramAudience}) exactly as {@code ProgramTaskSurveyRepository} does;
 * the coach side is keyed on {@code coach_id = caller} or the cohort-grant
 * relation. Nothing is post-filtered in Java.
 */
@Repository
@RequiredArgsConstructor
public class CoachingBookingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /* ------------------------------------------------------------ the task */

    /**
     * The LIVE SESSION task the caller may actually see: enrolled in its
     * cohort, inside its module's audience. Empty when the task does not
     * exist, is not a session task, or is not theirs — all 404 to the caller,
     * so neither existence nor membership leaks.
     *
     * <p>{@code cohortStatus} comes back even though
     * {@link CohortVisibility#MEMBER_VISIBLE} already pins it to LAUNCHED: the
     * write paths state the read-only rule themselves (spec §6.3) rather than
     * inheriting it from a visibility fragment that could later widen.
     */
    public Optional<SessionTaskRow> findEnrolledSessionTask(UUID taskId, UUID userId) {
        String sql = """
                SELECT t.id, t.name, t.session_type, c.id AS cohort_id, c.name AS cohort_name,
                       c.status, t.duration_minutes, t.post_session_survey_id
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id
                JOIN cohorts c ON c.id = m.cohort_id
                JOIN cohort_members cm ON cm.cohort_id = c.id AND cm.user_id = :userId
                WHERE t.id = :taskId AND t.task_type = 'SESSION' AND t.status = 'LIVE'
                  AND %s
                  AND %s
                """.formatted(CohortVisibility.MEMBER_VISIBLE.formatted("c"),
                ProgramAudience.INCLUDES_USER.formatted(":userId"));
        return jdbc.query(sql,
                        new MapSqlParameterSource("taskId", taskId).addValue("userId", userId),
                        (rs, i) -> new SessionTaskRow(
                                rs.getObject("id", UUID.class), rs.getString("name"),
                                rs.getString("session_type"),
                                rs.getObject("cohort_id", UUID.class), rs.getString("cohort_name"),
                                rs.getString("status"),
                                (Integer) rs.getObject("duration_minutes"),
                                rs.getObject("post_session_survey_id", UUID.class)))
                .stream().findFirst();
    }

    /* -------------------------------------------------------- the sessions */

    /**
     * One projection for every session read. It carries both parties' name AND
     * email because every write publishes an event the notification slice turns
     * into mail (spec §10), and a second lookup per send would be one more place
     * for the two to disagree.
     *
     * <p>{@code %1$s} is the "did the reader attend" expression: the member's own
     * screen binds it to {@code :memberId}, every other caller passes
     * {@code false} and reads attendance through {@link #sessionMembers} instead.
     * {@code %2$s} is WHOSE feedback {@code feedback_submitted} reports, for the
     * same reason: a cohort-wide row has no {@code member_id} to answer for it,
     * so the member screen binds the reader and everyone else keeps the row's
     * own member (null on a cohort-wide row, i.e. false).
     * {@code users} is a LEFT join because a cohort-wide row has no member.
     */
    private static final String BOOKING_SELECT = """
            SELECT s.id, s.type AS session_type,
                   s.member_id, mu.name AS member_name, mu.email AS member_email,
                   s.coach_id, cu.name AS coach_name, cu.email AS coach_email,
                   cp.time_zone AS coach_time_zone,
                   s.session_date, s.ends_at, s.booking_status, s.completed_at, s.meeting_url,
                   s.program_task_id, t.name AS task_name, t.post_session_survey_id,
                   t.duration_minutes,
                   s.cohort_id, c.name AS cohort_name,
                   %1$s AS attended,
                   EXISTS (SELECT 1 FROM survey_responses sr
                           WHERE sr.program_task_id = s.program_task_id
                             AND sr.respondent_user_id = %2$s) AS feedback_submitted
            FROM sessions s
            LEFT JOIN users mu ON mu.id = s.member_id
            LEFT JOIN users cu ON cu.id = s.coach_id
            LEFT JOIN coach_profiles cp ON cp.coach_id = s.coach_id
            LEFT JOIN cohorts c ON c.id = s.cohort_id
            LEFT JOIN program_tasks t ON t.id = s.program_task_id
            WHERE s.booking_status IS NOT NULL
            """;

    private static final String SELECT_FOR_OTHERS =
            BOOKING_SELECT.formatted("false", "s.member_id");

    private static final String SELECT_FOR_MEMBER = BOOKING_SELECT.formatted("""
            EXISTS (SELECT 1 FROM session_attendance sa
                    WHERE sa.session_id = s.id AND sa.member_id = :memberId)""", ":memberId");

    /**
     * The row that represents this task for this member (spec §3): their own
     * 1:1 row, or the task's single cohort-wide row. {@code attended} is
     * theirs, which is what makes the same query serve both shapes.
     */
    public Optional<SessionRow> findMemberSession(UUID taskId, UUID memberId) {
        return jdbc.query(SELECT_FOR_MEMBER
                        + " AND s.program_task_id = :taskId"
                        + " AND (s.member_id = :memberId OR s.member_id IS NULL)",
                        new MapSqlParameterSource("taskId", taskId).addValue("memberId", memberId),
                        CoachingBookingRepository::sessionRow)
                .stream().findFirst();
    }

    /** One session by id, unscoped — callers assert authority on the row they get back. */
    public Optional<SessionRow> findById(UUID sessionId) {
        return jdbc.query(SELECT_FOR_OTHERS + " AND s.id = :id",
                        new MapSqlParameterSource("id", sessionId),
                        CoachingBookingRepository::sessionRow)
                .stream().findFirst();
    }

    /**
     * The coach's console list (spec §6.1). Scope: the sessions they hold, plus
     * the still-UNSCHEDULED rows of the cohorts they may schedule for — the
     * latter is where a group session they have not dated yet comes from, and a
     * 1:1 row nobody has booked ("awaiting the member").
     *
     * <p><strong>The cohort grant alone is not enough for a 1:1 row.</strong>
     * {@code COACH_OF_COHORT_PREDICATE} reaches a cohort through
     * {@code cohort_orgs}, so an ORG-WIDE grant on org A admits every undated
     * row of a platform cohort org A merely shares — including the NAMED 1:1
     * rows of org B's founders. A cohort-wide row carries no member and leaks
     * nobody, so it keeps the cohort grant; a row that names a member is gated
     * on {@link CoachAccess#VISIBLE_MEMBER_PREDICATE}, the same relation the
     * roster and the founder profile use, so the console cannot show a founder
     * those screens hide. (That fragment's {@code mu} alias shadows this
     * query's outer {@code mu} harmlessly: both are pinned to
     * {@code s.member_id}.)
     *
     * <p>Undated rows sort first; {@code ORDER BY} cannot separate the three
     * buckets on its own, so the service splits them.
     */
    public List<SessionRow> findCoachSessions(UUID orgId, UUID coachId) {
        return jdbc.query(SELECT_FOR_OTHERS + """
                         AND s.program_task_id IS NOT NULL
                         AND (s.coach_id = :coachId
                              OR (s.booking_status = 'UNSCHEDULED' AND %1$s
                                  AND (s.member_id IS NULL OR %2$s)))
                        ORDER BY s.session_date NULLS FIRST, s.id
                        """.formatted(
                        CoachingReadRepository.COACH_OF_COHORT_PREDICATE
                                .formatted("s.cohort_id", ":coachId"),
                        CoachAccess.VISIBLE_MEMBER_PREDICATE.formatted("s.member_id")),
                new MapSqlParameterSource("coachId", coachId).addValue("orgId", orgId),
                CoachingBookingRepository::sessionRow);
    }

    /**
     * Every SCHEDULED task-backed row whose end has passed — the auto-complete
     * job's work list (spec §9). Ids only: each one is completed in its own
     * transaction, so a row read here and completed by the coach meanwhile is
     * simply a zero-row UPDATE later.
     */
    public List<UUID> endedScheduledSessionIds(Instant now) {
        return jdbc.queryForList("""
                SELECT id FROM sessions
                WHERE booking_status = 'SCHEDULED' AND program_task_id IS NOT NULL
                  AND ends_at <= :now
                ORDER BY ends_at
                """, new MapSqlParameterSource("now", Timestamp.from(now)), UUID.class);
    }

    /**
     * The coach's SCHEDULED sessions intersecting {@code [from, to)} — the
     * INTERNAL half of what the slot engine subtracts (the external half is
     * {@code CalendarBusyPort}, spec §6). UNSCHEDULED rows hold no time and
     * COMPLETED ones are in the past.
     *
     * <p>{@code excludeSessionId} is the row the offer is FOR: a session cannot
     * make itself unbookable, or the reschedule picker would hide the slot the
     * member is trying to move away from and every slot overlapping it.
     */
    public List<TimeRange> busyRanges(UUID coachId, Instant from, Instant to,
                                     UUID excludeSessionId) {
        return jdbc.query("""
                        SELECT session_date, ends_at FROM sessions
                        WHERE coach_id = :coachId AND booking_status = 'SCHEDULED'
                          AND ends_at > :from AND session_date < :to
                          -- The cast is what lets Postgres type an unbound NULL here.
                          AND (CAST(:excludeId AS uuid) IS NULL OR id <> CAST(:excludeId AS uuid))
                        """,
                new MapSqlParameterSource("coachId", coachId)
                        .addValue("from", Timestamp.from(from))
                        .addValue("to", Timestamp.from(to))
                        .addValue("excludeId", excludeSessionId),
                (rs, i) -> new TimeRange(instant(rs, "session_date"), instant(rs, "ends_at")));
    }

    /**
     * The population a session's roll call is about, as ONE FROM/WHERE the list
     * and the count both read: the one member of a 1:1 row, or the CURRENT
     * cohort roster of a cohort-wide row (V163: an empty expected-attendee set
     * means the whole cohort, resolved at read time).
     *
     * <p>The cohort-wide arm is narrowed by the module's audience
     * ({@link ProgramAudience}) as well as by the roster: a MEMBERS-scoped
     * module never reached the members it excludes, so expecting them at its
     * session would invent invites, absences and a wrong attendee count. A row
     * with no task — or a task with no module — predates the board and has no
     * audience to be narrowed by.
     *
     * <p>{@code %1$s} is the SELECT list.
     */
    private static String sessionMembersSql(String selectList) {
        return """
                SELECT %1$s
                FROM sessions s
                LEFT JOIN program_tasks pt ON pt.id = s.program_task_id
                LEFT JOIN program_modules m ON m.id = pt.module_id
                JOIN users u ON (s.member_id IS NOT NULL AND u.id = s.member_id)
                             OR (s.member_id IS NULL
                                 AND u.status = 'ACTIVE' AND %2$s
                                 AND EXISTS (SELECT 1 FROM cohort_members cm
                                             WHERE cm.cohort_id = s.cohort_id
                                               AND cm.user_id = u.id)
                                 AND (m.id IS NULL OR %3$s))
                WHERE s.id IN (:ids)
                """.formatted(selectList, Learner.ROLE_PREDICATE.formatted("u"),
                ProgramAudience.INCLUDES_USER.formatted("u.id"));
    }

    /**
     * The members a session's roll call is about, with presence and whether
     * they have already answered the task's survey, so the attendance writes
     * and the DTO share one source.
     */
    public List<SessionMemberRow> sessionMembers(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(sessionMembersSql("""
                        s.id AS session_id, u.id AS member_id, u.name, u.email,
                               EXISTS (SELECT 1 FROM session_attendance sa
                                       WHERE sa.session_id = s.id AND sa.member_id = u.id) AS present,
                               EXISTS (SELECT 1 FROM survey_responses sr
                                       WHERE sr.program_task_id = s.program_task_id
                                         AND sr.respondent_user_id = u.id) AS feedback_submitted""")
                        + " ORDER BY u.name, u.id",
                new MapSqlParameterSource("ids", sessionIds),
                (rs, i) -> new SessionMemberRow(rs.getObject("session_id", UUID.class),
                        rs.getObject("member_id", UUID.class), rs.getString("name"),
                        rs.getString("email"), rs.getBoolean("present"),
                        rs.getBoolean("feedback_submitted")));
    }

    /** {@link #sessionMembers(Collection)} for one session. */
    public List<SessionMemberRow> sessionMembers(UUID sessionId) {
        return sessionMembers(List.of(sessionId));
    }

    /**
     * How many members that same roll call covers — the attendee count on the
     * member's screen, which wants the number and none of the names.
     */
    public int sessionMemberCount(UUID sessionId) {
        Integer count = jdbc.queryForObject(sessionMembersSql("count(*)"),
                new MapSqlParameterSource("ids", List.of(sessionId)), Integer.class);
        return count == null ? 0 : count;
    }

    /* ---------------------------------------------------------- the writes */

    /**
     * Materialise the caller's own 1:1 row on the spot, for the one case
     * {@link SessionMaterializer} cannot have covered: a member booking a task
     * whose sync has not run (a board saved by a path that forgot to publish,
     * or a race with the AFTER_COMMIT listener). Same shape and the same
     * {@code ON CONFLICT DO NOTHING} as the materialiser, so the two cannot
     * produce different rows.
     *
     * @return the row's id, whether this call created it or found it
     */
    public UUID materializeOwnSession(UUID cohortId, UUID taskId, String title,
                                      String sessionType, UUID memberId) {
        MapSqlParameterSource params = new MapSqlParameterSource("cohortId", cohortId)
                .addValue("taskId", taskId)
                .addValue("title", title == null ? null : title.substring(0, Math.min(title.length(), 200)))
                .addValue("sessionType", sessionType)
                .addValue("memberId", memberId);
        jdbc.update("""
                INSERT INTO sessions (cohort_id, type, title, program_task_id, member_id, booking_status)
                VALUES (:cohortId, :sessionType, :title, :taskId, :memberId, 'UNSCHEDULED')
                ON CONFLICT DO NOTHING
                """, params);
        jdbc.update("""
                INSERT INTO session_expected_attendees (session_id, member_id)
                SELECT s.id, s.member_id FROM sessions s
                WHERE s.program_task_id = :taskId AND s.member_id = :memberId
                ON CONFLICT DO NOTHING
                """, params);
        return jdbc.queryForObject(
                "SELECT id FROM sessions WHERE program_task_id = :taskId AND member_id = :memberId",
                params, UUID.class);
    }

    /**
     * Date an UNSCHEDULED row: the ONE write behind both booking a 1:1 and
     * scheduling a cohort-wide session (spec §2.3). Scoped to UNSCHEDULED in
     * the statement itself, so "someone got there first" is a zero-row answer
     * rather than a read-then-write window.
     *
     * <p>{@code ex_sessions_coach_no_overlap} can still refuse this UPDATE, and
     * that is the point: the caller turns the
     * {@code DataIntegrityViolationException} into the 409.
     *
     * @return true when this call dated the row
     */
    public boolean schedule(UUID sessionId, UUID coachId, Instant startsAt, Instant endsAt) {
        return jdbc.update("""
                UPDATE sessions
                SET coach_id = :coachId, session_date = :startsAt, ends_at = :endsAt,
                    booking_status = 'SCHEDULED', completed_at = NULL, updated_at = now()
                WHERE id = :id AND booking_status = 'UNSCHEDULED'
                """,
                new MapSqlParameterSource("id", sessionId)
                        .addValue("coachId", coachId)
                        .addValue("startsAt", Timestamp.from(startsAt))
                        .addValue("endsAt", Timestamp.from(endsAt))) == 1;
    }

    /**
     * Move a SCHEDULED row to a new coach/slot in ONE statement (spec §6.1/§6.3
     * reschedule). Deliberately NOT a cancel plus a schedule: the pair loses the
     * calendar event and tells everyone twice, and between the two writes the
     * old slot is free for someone else to take.
     *
     * <p>{@code ex_sessions_coach_no_overlap} compares the new row against OTHER
     * rows, never against the version it replaces, so a move that merely shifts
     * the same session cannot collide with itself. A real clash still raises the
     * {@code DataIntegrityViolationException} the caller turns into the 409.
     *
     * @return true when this call moved a still-SCHEDULED row
     */
    public boolean reschedule(UUID sessionId, UUID coachId, Instant startsAt, Instant endsAt) {
        return jdbc.update("""
                UPDATE sessions
                SET coach_id = :coachId, session_date = :startsAt, ends_at = :endsAt,
                    updated_at = now()
                WHERE id = :id AND booking_status = 'SCHEDULED'
                """,
                new MapSqlParameterSource("id", sessionId)
                        .addValue("coachId", coachId)
                        .addValue("startsAt", Timestamp.from(startsAt))
                        .addValue("endsAt", Timestamp.from(endsAt))) == 1;
    }

    /**
     * Cancel = revert to UNSCHEDULED (spec §2.3 — nothing is deleted, so the
     * journey row keeps its identity and the member can rebook).
     *
     * <p>{@code calendar_event_id} is deliberately LEFT ALONE. The calendar
     * slice deletes the real Google event AFTER_COMMIT and needs the id to do
     * it; nulling it here would strand the event for ever. V216's shape
     * constraint therefore says nothing about that column on an UNSCHEDULED row.
     * {@code meeting_url} IS cleared, because it is the link we show and it
     * stops working the moment the event goes.
     *
     * @return true when this call reverted a SCHEDULED row
     */
    public boolean revertToUnscheduled(UUID sessionId) {
        return jdbc.update("""
                UPDATE sessions
                SET booking_status = 'UNSCHEDULED', coach_id = NULL, session_date = NULL,
                    ends_at = NULL, completed_at = NULL, meeting_url = NULL, updated_at = now()
                WHERE id = :id AND booking_status = 'SCHEDULED'
                """, new MapSqlParameterSource("id", sessionId)) == 1;
    }

    /**
     * A COMPLETED 1:1 nobody attended goes back to UNSCHEDULED so the member
     * can rebook (spec §6.1). Guarded in SQL on all three facts — completed,
     * per-member, no roll call — so there is no state this can silently undo.
     *
     * @return true when this call reopened the row
     */
    public boolean reopen(UUID sessionId) {
        return jdbc.update("""
                UPDATE sessions
                SET booking_status = 'UNSCHEDULED', coach_id = NULL, session_date = NULL,
                    ends_at = NULL, completed_at = NULL, meeting_url = NULL, updated_at = now()
                WHERE id = :id AND booking_status = 'COMPLETED' AND member_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM session_attendance sa WHERE sa.session_id = :id)
                """, new MapSqlParameterSource("id", sessionId)) == 1;
    }

    /**
     * Mark a started session held. Deliberately accepts an ALREADY COMPLETED
     * row as well as a SCHEDULED one: {@code SessionAutoCompleteJob} may have
     * flipped it between the session ending and the coach pressing submit, and
     * the roll call the coach is submitting is the authoritative one — the
     * caller clears and re-marks attendance either way. UNSCHEDULED is still
     * refused by the statement, so "nothing to complete" needs no separate read.
     *
     * @return true when this call resolved the session
     */
    public boolean complete(UUID sessionId, Instant now) {
        return jdbc.update("""
                UPDATE sessions SET booking_status = 'COMPLETED', completed_at = :now,
                                    updated_at = now()
                WHERE id = :id AND booking_status IN ('SCHEDULED', 'COMPLETED')
                """, new MapSqlParameterSource("id", sessionId)
                .addValue("now", Timestamp.from(now))) == 1;
    }

    /** Add one presence mark. Idempotent — re-marking someone is not an error. */
    public void markPresent(UUID sessionId, UUID memberId, UUID markedBy, Instant now) {
        jdbc.update("""
                INSERT INTO session_attendance (session_id, member_id, marked_at, marked_by)
                VALUES (:id, :memberId, :now, :markedBy)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource("id", sessionId)
                .addValue("memberId", memberId)
                .addValue("markedBy", markedBy)
                .addValue("now", Timestamp.from(now)));
    }

    /** Remove one presence mark — held-but-absent is COMPLETED with no row (spec §1.4). */
    public void markAbsent(UUID sessionId, UUID memberId) {
        jdbc.update("DELETE FROM session_attendance WHERE session_id = :id AND member_id = :memberId",
                new MapSqlParameterSource("id", sessionId).addValue("memberId", memberId));
    }

    /** The whole roll call, for the coach's "exactly these were present" submit. */
    public void clearAttendance(UUID sessionId) {
        jdbc.update("DELETE FROM session_attendance WHERE session_id = :id",
                new MapSqlParameterSource("id", sessionId));
    }

    private static SessionRow sessionRow(ResultSet rs, int i) throws SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class), rs.getString("session_type"),
                rs.getObject("member_id", UUID.class), rs.getString("member_name"),
                rs.getString("member_email"),
                rs.getObject("coach_id", UUID.class), rs.getString("coach_name"),
                rs.getString("coach_email"), rs.getString("coach_time_zone"),
                instant(rs, "session_date"), instant(rs, "ends_at"),
                rs.getString("booking_status"), instant(rs, "completed_at"),
                rs.getString("meeting_url"),
                rs.getObject("program_task_id", UUID.class), rs.getString("task_name"),
                (Integer) rs.getObject("duration_minutes"),
                rs.getObject("post_session_survey_id", UUID.class),
                rs.getObject("cohort_id", UUID.class), rs.getString("cohort_name"),
                rs.getBoolean("attended"), rs.getBoolean("feedback_submitted"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /** The SESSION task itself, with the cohort it hangs in. */
    public record SessionTaskRow(UUID taskId, String taskName, String sessionType,
                                 UUID cohortId, String cohortName, String cohortStatus,
                                 Integer durationMinutes, UUID postSessionSurveyId) {

        /** A 1:1 is booked by the member; group and workshop are dated by the coach (spec §1.2). */
        public boolean isCohortWide() {
            return !"COACHING_1ON1".equals(sessionType);
        }
    }

    /** One session row, joined to everything the API and the emails need. */
    public record SessionRow(UUID sessionId, String sessionType,
                             UUID memberId, String memberName, String memberEmail,
                             UUID coachId, String coachName, String coachEmail, String coachTimeZone,
                             Instant startsAt, Instant endsAt, String bookingStatus,
                             Instant completedAt, String meetingUrl,
                             UUID taskId, String taskName, Integer durationMinutes,
                             UUID feedbackSurveyId, UUID cohortId, String cohortName,
                             boolean attended, boolean feedbackSubmitted) {

        public boolean isUnscheduled() {
            return "UNSCHEDULED".equals(bookingStatus);
        }

        public boolean isScheduled() {
            return "SCHEDULED".equals(bookingStatus);
        }

        public boolean isCompleted() {
            return "COMPLETED".equals(bookingStatus);
        }

        /** Cohort-wide rows carry no member: the whole cohort is expected. */
        public boolean isCohortWide() {
            return memberId == null;
        }
    }

    /** One member of a session's roll call, with their presence and survey state. */
    public record SessionMemberRow(UUID sessionId, UUID memberId, String name, String email,
                                   boolean present, boolean feedbackSubmitted) {}
}
