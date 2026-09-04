package com.bvisionry.survey.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.programaccess.CohortVisibility;
import com.bvisionry.common.programaccess.ProgramAudience;
import com.bvisionry.common.programaccess.TaskCompletion;

import lombok.RequiredArgsConstructor;

/**
 * Native read into the {@code programflow} slice for the SURVEY journey-task
 * flow (redesign spec §2.1, phase D2). Same soft-coupling trick as
 * {@link WorkshopIntroSurveyRepository}: raw SQL keeps the survey slice free of
 * a survey→programflow Java dependency (the architecture-freeze rule forbids
 * new cross-feature coupling) while still authorizing the caller by cohort
 * enrollment + module audience ({@link ProgramAudience} — the shared fragment
 * needs {@code String.format}, hence JdbcTemplate rather than a Spring Data
 * {@code @Query} constant).
 *
 * <p>ponytail: the drip lock is NOT re-checked here (computing SEQUENTIAL
 * unlock needs the whole done-set) — same leniency as the workshop task-survey
 * route. The journey UI never offers a locked task, and answering early is
 * harmless: the response simply marks the task done once it unlocks.
 */
@Repository
@RequiredArgsConstructor
public class ProgramTaskSurveyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * The survey paired to a SURVEY journey task the user may see: LIVE, in a
     * cohort they're enrolled in, in a module whose audience includes them.
     * Empty when the task doesn't exist, isn't a SURVEY, or the user isn't in
     * its audience — all 404 to the caller so neither existence nor membership
     * leaks. A NULL refId still returns a row ("no survey configured").
     */
    public Optional<ProgramTaskSurveyRow> findEnrolledTaskSurvey(UUID taskId, UUID userId) {
        // A SESSION task serves its post-session survey through this same route
        // (sessions spec v2 §3) — gated on the DONE predicate: the row that
        // applies to this member (their 1:1 row, or the cohort-wide one) was
        // held AND they have a presence row, so nobody rates a session they did
        // not attend. Same 404 shape as a missing SURVEY task.
        String sql = """
                SELECT CASE t.task_type WHEN 'SURVEY' THEN t.ref_id
                                        ELSE t.post_session_survey_id END AS survey_id,
                       c.status AS cohort_status
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id
                JOIN cohorts c ON c.id = m.cohort_id
                JOIN cohort_members cm ON cm.cohort_id = c.id AND cm.user_id = :userId
                WHERE t.id = :taskId AND t.status = 'LIVE'
                  AND (t.task_type = 'SURVEY' OR (t.task_type = 'SESSION' AND %s))
                  AND %s
                  AND %s
                """.formatted(TaskCompletion.DONE_FOR_USER.formatted(":userId"),
                CohortVisibility.MEMBER_VISIBLE.formatted("c"),
                ProgramAudience.INCLUDES_USER.formatted(":userId"));
        return jdbc.query(sql,
                        new MapSqlParameterSource("taskId", taskId).addValue("userId", userId),
                        (rs, i) -> new ProgramTaskSurveyRow(
                                rs.getObject("survey_id", UUID.class),
                                rs.getString("cohort_status")))
                .stream().findFirst();
    }

    /**
     * True once this member has answered THIS task — the same key the journey's
     * done-detection uses since V173 ({@code TaskSpineRepository
     * .surveyParticipation}), so the 409 gate and the DONE state agree. A
     * response to the same survey taken elsewhere (public link, another
     * cohort's task) does not count: that cohort's work is that cohort's.
     */
    public boolean hasResponse(UUID taskId, UUID userId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM survey_responses
                    WHERE program_task_id = :taskId AND respondent_user_id = :userId)
                """,
                new MapSqlParameterSource("taskId", taskId).addValue("userId", userId),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * The respondent's email + name for stamping onto the response row. Native
     * SQL rather than the auth slice's {@code UserRepository}: the ratchet
     * forbids both a new survey→auth Java dependency and a bare-ID
     * {@code findById} outside a {@code require*} guard.
     */
    public Optional<UserIdentityRow> findUserIdentity(UUID userId) {
        return jdbc.query("""
                        SELECT email, name FROM users WHERE id = :userId
                        """,
                        new MapSqlParameterSource("userId", userId),
                        (rs, i) -> new UserIdentityRow(
                                rs.getString("email"), rs.getString("name")))
                .stream().findFirst();
    }

    /** The task's paired survey (nullable) + its cohort's lifecycle status. */
    public record ProgramTaskSurveyRow(UUID surveyId, String cohortStatus) {}

    /** Respondent identity values for the response row. */
    public record UserIdentityRow(String email, String name) {}
}
