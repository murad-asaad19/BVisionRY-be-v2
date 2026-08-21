package com.bvisionry.exercise;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

/**
 * Data-layer gate for COACH callers on the exercise review loop: a coach only
 * reaches submissions of founders inside their assignment union
 * ({@link CoachAccess}); anything else reads as absent (404), so foreign work
 * is never even acknowledged. Non-coach callers pass through — their scoping
 * is the org check in {@code requireAssignmentInOrg}.
 *
 * <p>One SQL round-trip: the shared union predicate is composed with a
 * subquery resolving the assignment's owner, so there is no separate
 * point-read of the submission. Constructor takes only shared-kernel types, so
 * this class introduces no cross-feature dependency edge.
 */
@Component
public class ExerciseCoachGate {

    /**
     * The shared union predicate with the member resolved from the (org-pinned)
     * exercise assignment itself. A provision row ({@code user_id IS NULL}) or
     * a foreign assignment resolves to no member → not visible.
     */
    private static final String COACH_SEES_ASSIGNMENT_OWNER =
            "SELECT " + CoachAccess.VISIBLE_MEMBER_PREDICATE.formatted("""
                    (SELECT ea.user_id FROM exercise_assignments ea
                     WHERE ea.id = :assignmentId AND ea.organization_id = :orgId)""");

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentUserAccessor currentUser;

    ExerciseCoachGate(NamedParameterJdbcTemplate jdbc, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    /**
     * When the caller is a COACH, require the assignment's owner to be inside
     * their assignment union — a uniform 404 otherwise.
     */
    void requireCoachMaySeeSubmission(UUID orgId, UUID assignmentId) {
        CurrentUser caller = this.currentUser.require();
        if (!UserRole.COACH.name().equals(caller.role())) {
            return;
        }
        Boolean visible = jdbc.queryForObject(COACH_SEES_ASSIGNMENT_OWNER,
                new MapSqlParameterSource("orgId", orgId)
                        .addValue("coachId", caller.userId())
                        .addValue("assignmentId", assignmentId),
                Boolean.class);
        if (!Boolean.TRUE.equals(visible)) {
            throw new ResourceNotFoundException("Submission", assignmentId.toString());
        }
    }
}
