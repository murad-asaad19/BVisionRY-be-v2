package com.bvisionry.notification.push;

import com.bvisionry.common.coachaccess.CoachAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The coach half of a submission notification: redesign spec §2.2 makes the
 * Review Queue "the notification target for submissions", and until this class
 * existed a coach was told nothing — every submission event went to
 * {@link PushNotificationService#notifyOrgAdmins} and stopped there.
 *
 * <p><strong>Why a separate component instead of one more method on
 * {@link PushNotificationService}.</strong> That service's constructor is
 * pinned in the frozen ArchUnit violation store (it takes
 * {@code auth.UserRepository}, a cross-feature dependency), and the store may
 * only shrink — adding a sixth parameter there re-describes a frozen violation
 * as a new one and fails the build. This class depends on the schema and on
 * {@code common} only, so it adds no violation at all: the same reason
 * {@link CoachAccess} itself lives in {@code common} rather than in
 * {@code coaching}.
 *
 * <p>Fire-and-forget on the push executor like every entry point next to it, so
 * a coach lookup can never throw into — or slow down — the submit that
 * triggered it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoachReviewNotifier {

    /**
     * The coaches who may see this founder: {@link CoachAccess#VISIBLE_COACH_PREDICATE}
     * COMPOSED, never restated, so a coach's bell and a coach's console cannot
     * disagree about who they coach — cohort grant, direct grant and the V176
     * org-wide grain all arrive for free.
     *
     * <p>The three lines the shared fragment deliberately leaves to its
     * composer (see its javadoc: forwards the coach is the authenticated
     * caller, here the coach is DATA): {@code role = 'COACH'},
     * {@code status = 'ACTIVE'} and {@code organization_id = :orgId}. Without
     * them a suspended coach, one demoted out of the role while a stale grant
     * survives, or another tenant's coach named by a hand-written cross-org
     * grant would be pushed a founder's submission — the identical three lines
     * {@code coaching}'s {@code coachesOfMember} and {@code cohortview}'s
     * {@code coaches} add for the identical reason.
     */
    private static final String COACHES_OF_MEMBER = """
            SELECT cu.id
            FROM users cu
            WHERE cu.organization_id = :orgId
              AND cu.role = 'COACH'
              AND cu.status = 'ACTIVE'
              AND %s
            """.formatted(CoachAccess.VISIBLE_COACH_PREDICATE.formatted("cu.id"));

    private final NamedParameterJdbcTemplate jdbc;
    private final PushNotificationService pushNotificationService;

    /**
     * Notify every coach who actually coaches {@code memberId} that they have
     * something to review. {@code type} must be one of the coach-visible
     * submission types — fail closed, so a future caller cannot quietly push an
     * org-administration event to coaches who have no switch to mute it with.
     */
    @Async("pushExecutor")
    public void notifyCoachesOf(UUID orgId, UUID memberId, NotificationType type,
                                String title, String body) {
        dispatch(orgId, memberId, type, title, body, coachUrl(type, memberId));
    }

    /**
     * As {@link #notifyCoachesOf(UUID, UUID, NotificationType, String, String)} but
     * with an EXPLICIT deep link — for activity whose type's default target cannot
     * hold it. A feedback reply is EXERCISE_ACTIVITY, but the exercise queue lists
     * only SUBMITTED submissions and a replied-to one is REVIEWED/CHANGES_REQUESTED,
     * so the default queue URL would open a list that cannot contain it.
     */
    @Async("pushExecutor")
    public void notifyCoachesOf(UUID orgId, UUID memberId, NotificationType type,
                                String title, String body, String coachUrl) {
        dispatch(orgId, memberId, type, title, body, coachUrl);
    }

    private void dispatch(UUID orgId, UUID memberId, NotificationType type,
                          String title, String body, String coachUrl) {
        if (orgId == null || memberId == null || !type.isCoachVisible()) {
            return;
        }
        try {
            // notifyUsers, not a loop: one opt-out query, one history batch and
            // one task for the whole (small) coach set.
            pushNotificationService.notifyUsers(coachesOf(orgId, memberId), type, title, body,
                    coachUrl);
        } catch (RuntimeException e) {
            log.warn("Push dispatch {} to coaches of member {} failed: {}",
                    type, memberId, e.getMessage());
        }
    }

    /** The coach's founder-profile deep link — its Work tab holds any exercise activity. */
    static String coachFounderUrl(UUID memberId) {
        return "/app/team/founders/" + memberId;
    }

    /**
     * The recipient set on its own, so the grant rule is assertable without
     * chasing a fire-and-forget dispatch. Public rather than package-private
     * because this bean is CGLIB-proxied by {@code @Async} and a
     * package-visible method through a proxy is a subtlety not worth owning.
     */
    public List<UUID> coachesOf(UUID orgId, UUID memberId) {
        return jdbc.queryForList(COACHES_OF_MEMBER,
                new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId),
                UUID.class);
    }

    /**
     * A route a COACH can actually open. Both of these live under
     * {@code /app/team/**}, whose pages are {@code requireRole("COACH")}; an
     * {@code /app/admin/**} URL here would 404 for every recipient — the bug
     * {@link MemberJoinedPushHandler} and {@link ProgramFlowPushHandler} both
     * carry comments about.
     *
     * <p>Spec §2.2 names the Review Queue as the submission target and
     * {@code /app/team/queue} is exactly that — but it is EXERCISE-only
     * ({@code coaching}'s {@code reviewQueue} selects from
     * {@code exercise_submissions}), so an assessment or program-task
     * submission sent there opens a list that cannot contain it. Those land on
     * the founder profile instead, whose Work tab does; it is reachable for
     * precisely the founders we notify about, since both this URL and the
     * page's own 404 are the same assignment union.
     */
    static String coachUrl(NotificationType type, UUID memberId) {
        return type == NotificationType.EXERCISE_ACTIVITY
                ? "/app/team/queue"
                : coachFounderUrl(memberId);
    }
}
