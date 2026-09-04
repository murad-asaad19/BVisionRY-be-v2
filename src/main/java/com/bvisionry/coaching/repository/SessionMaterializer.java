package com.bvisionry.coaching.repository;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.programaccess.Learner;
import com.bvisionry.common.programaccess.ProgramAudience;

import lombok.RequiredArgsConstructor;

/**
 * Brings a cohort's {@code sessions} rows into agreement with its board and its
 * roster (spec §4).
 *
 * <p>WHY ROWS EXIST BEFORE THEY ARE DATED. v1 created the row when the member
 * booked, which made "not booked yet" the ABSENCE of a row — and an absence
 * cannot carry a status, a coach's to-do list, or a group session nobody has
 * scheduled. v2 materialises the row UNSCHEDULED the moment the cohort reaches
 * the task, so booking and scheduling are the same UPDATE and cancelling is its
 * inverse; the journey row, the coach console and the org Sessions tab all read
 * one stable row.
 *
 * <p>IDEMPOTENT BY CONSTRUCTION. Every insert is {@code ON CONFLICT DO NOTHING}
 * against the two unique indexes ({@code ux_sessions_task_member} for 1:1 rows,
 * {@code ux_sessions_task_cohortwide} for the one row a group/workshop task
 * gets), so a re-run is a no-op and two concurrent runs cannot duplicate. No
 * conflict target is named on purpose: which of the two indexes applies depends
 * on whether {@code member_id} is null, and naming one would make the other a
 * hard error.
 *
 * <p>WHAT IT WILL NOT TOUCH. Cleanup deletes UNSCHEDULED rows only. A SCHEDULED
 * or COMPLETED session is a real appointment with a real calendar event and,
 * once held, a roll call ({@code session_attendance} is RESTRICT since V212);
 * un-scheduling it is the coach's decision, not a side effect of saving the
 * board or of a member leaving the cohort (spec §12 leaves that case to the
 * coach on purpose).
 *
 * <p>Raw SQL, like everything else in this package: the rows belong to
 * {@code engagement} and the tasks to {@code programflow}, and the ArchUnit
 * ratchet forbids importing either.
 */
@Component
@RequiredArgsConstructor
public class SessionMaterializer {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Cleanup first, then the two inserts. The order is what lets a member who
     * left and rejoined get a fresh row in ONE pass rather than needing a
     * second event, and it never deletes a row this same call just wrote —
     * both halves read the same predicates.
     */
    @Transactional
    public void sync(UUID cohortId) {
        MapSqlParameterSource params = new MapSqlParameterSource("cohortId", cohortId);
        deleteStaleUnscheduled(params);
        insertOneToOneRows(params);
        insertCohortWideRows(params);
        insertExpectedAttendees(params);
    }

    /**
     * An UNSCHEDULED row whose reason to exist is gone: the task is no longer a
     * LIVE SESSION task of this cohort, its subtype changed under the row
     * (1:1 ⇄ cohort-wide, or workshop → group), or the member it belongs to is
     * no longer enrolled — or no longer inside the module's audience, which is
     * the same predicate {@link #insertOneToOneRows} creates the row under, so
     * the two halves cannot disagree about which rows should exist.
     */
    private void deleteStaleUnscheduled(MapSqlParameterSource params) {
        jdbc.update("""
                DELETE FROM sessions s
                WHERE s.cohort_id = :cohortId
                  AND s.program_task_id IS NOT NULL
                  AND s.booking_status = 'UNSCHEDULED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM program_tasks t
                      JOIN program_modules m ON m.id = t.module_id
                      WHERE t.id = s.program_task_id
                        AND m.cohort_id = s.cohort_id
                        AND t.task_type = 'SESSION' AND t.status = 'LIVE'
                        AND t.session_type = s.type
                        AND (t.session_type = 'COACHING_1ON1') = (s.member_id IS NOT NULL)
                        AND (s.member_id IS NULL
                             OR EXISTS (SELECT 1 FROM cohort_members cm
                                        JOIN users u ON u.id = cm.user_id
                                                    AND u.status = 'ACTIVE'
                                                    AND %1$s
                                        WHERE cm.cohort_id = s.cohort_id
                                          AND cm.user_id = s.member_id
                                          AND %2$s)))
                """.formatted(Learner.ROLE_PREDICATE.formatted("u"),
                ProgramAudience.INCLUDES_USER.formatted("s.member_id")), params);
    }

    /** One row per (1:1 task, enrolled member). */
    private void insertOneToOneRows(MapSqlParameterSource params) {
        jdbc.update("""
                INSERT INTO sessions (cohort_id, type, title, program_task_id, member_id, booking_status)
                SELECT :cohortId, t.session_type, left(t.name, 200), t.id, cm.user_id, 'UNSCHEDULED'
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id
                JOIN cohort_members cm ON cm.cohort_id = m.cohort_id
                -- Learners only: a coach or admin sitting in cohort_members is
                -- not someone who books a 1:1 (same rule as the org roster).
                JOIN users u ON u.id = cm.user_id AND u.status = 'ACTIVE' AND %1$s
                WHERE m.cohort_id = :cohortId AND t.task_type = 'SESSION' AND t.status = 'LIVE'
                  AND t.session_type = 'COACHING_1ON1'
                  -- A MEMBERS-scoped module never reached these founders, so a
                  -- 1:1 row for them would be a task they cannot see and a
                  -- to-do the coach can never clear.
                  AND %2$s
                ON CONFLICT DO NOTHING
                """.formatted(Learner.ROLE_PREDICATE.formatted("u"),
                ProgramAudience.INCLUDES_USER.formatted("cm.user_id")), params);
    }

    /** One row per group / workshop task, member-less: the whole cohort is expected. */
    private void insertCohortWideRows(MapSqlParameterSource params) {
        jdbc.update("""
                INSERT INTO sessions (cohort_id, type, title, program_task_id, member_id, booking_status)
                SELECT :cohortId, t.session_type, left(t.name, 200), t.id, NULL, 'UNSCHEDULED'
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id
                WHERE m.cohort_id = :cohortId AND t.task_type = 'SESSION' AND t.status = 'LIVE'
                  AND t.session_type IN ('COACHING_GROUP', 'WORKSHOP')
                ON CONFLICT DO NOTHING
                """, params);
    }

    /**
     * A 1:1 row's single expected attendee is its member — that is what keeps
     * every OTHER member's participation denominator out of it. Cohort-wide
     * rows get none, which V163 already reads as "the whole cohort".
     */
    private void insertExpectedAttendees(MapSqlParameterSource params) {
        jdbc.update("""
                INSERT INTO session_expected_attendees (session_id, member_id)
                SELECT s.id, s.member_id FROM sessions s
                WHERE s.cohort_id = :cohortId
                  AND s.program_task_id IS NOT NULL
                  AND s.member_id IS NOT NULL
                ON CONFLICT DO NOTHING
                """, params);
    }
}
