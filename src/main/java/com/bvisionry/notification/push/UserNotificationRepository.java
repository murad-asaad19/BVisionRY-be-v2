package com.bvisionry.notification.push;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {

    /**
     * The caller's own history, newest first — ordering comes from the
     * {@link Pageable} rather than the method name so both this and the
     * unread-only variant below share one definition of "newest first".
     */
    Page<UserNotification> findByUserId(UUID userId, Pageable pageable);

    /** Same page, restricted to what the caller has not opened yet. */
    Page<UserNotification> findByUserIdAndReadAtIsNull(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /** Owner-scoped lookup so one user cannot mark another's notification read. */
    Optional<UserNotification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE UserNotification n SET n.readAt = :now, n.updatedAt = :now "
            + "WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Retention purge (see {@link NotificationRetentionJob}). */
    @Transactional
    long deleteByCreatedAtBefore(Instant cutoff);

    // ---------------------------------------------------------------------
    // Inactivity nudges (roadmap §7 items 7 + 18), driven by
    // {@link InactivityNudgeJob}.
    //
    // Both are NATIVE and both live here rather than in a repository of their
    // own, because the load-bearing half of the second one is THIS table: the
    // send-once guarantee is "has this founder already got an
    // INACTIVITY_NUDGE row inside the current window", so the notification
    // history IS the idempotency record and no new column or marker table
    // exists to add. Native also keeps the notification package free of any
    // Java dependency on enrollment/catalog/auth/organization entities, which
    // would each be a new ArchitectureRulesTest rule-1 violation.
    // ---------------------------------------------------------------------

    /**
     * Orgs that are switched on for nudging, in id order. The job iterates
     * these and scopes every subsequent read to one of them, so no query in
     * this feature ever returns a list with two tenants in it.
     */
    @Query(value = """
            SELECT o.id
            FROM organizations o
            WHERE o.is_active
              AND o.inactivity_nudge_days > 0
            ORDER BY o.id
            """, nativeQuery = true)
    List<UUID> findOrgIdsWithNudgesEnabled();

    /**
     * The founders of ONE org who are due an inactivity nudge, at most one row
     * each — the enrolment they have been stalled on longest.
     *
     * <p>The activity signal is what the schema actually records:
     * {@code max(content_progress.completed_at)} for the enrolment, falling
     * back to {@code enrollment.enrolled_at} for someone who has completed
     * nothing at all (otherwise a founder who never started would be invisible
     * to a job whose entire job is to find founders who never started).
     *
     * <p>ponytail: playback position is NOT an activity signal here, because
     * {@code PlaybackService.updatePosition} writes {@code watched_pct} /
     * {@code last_position_seconds} with no timestamp — so someone grinding
     * through a long video without crossing the auto-complete threshold reads
     * as stalled. Upgrade path when that bites: a {@code last_activity_at} on
     * {@code content_progress} stamped by that service, then COALESCE it in
     * ahead of {@code completed_at}. It needs the enrollment slice, which this
     * ticket does not own.
     *
     * <p>Tenancy: {@code u.organization_id = :orgId} is the roster predicate —
     * membership of the org is decided by the USER row, never by the
     * enrolment, so a cross-org enrolment ghost (a row pointing at a founder
     * who has since moved orgs) cannot pull another tenant's member into this
     * org's sweep. ACTIVE users only, role MEMBER only: suspended accounts and
     * staff are not founders with a stalled journey.
     *
     * <p><b>Which enrolment is picked matters as much as who.</b> Stalest-wins
     * alone silently defeats the whole feature: the shared "Bvisionry Academy"
     * catalog (V77) is a DESIGNED cross-org surface every founder can enrol on,
     * and those enrolments are systematically older than the org's own course,
     * so the pick landed on a foreign course and the send-once guard then
     * suppressed that founder for N days — the org's own course never nudged.
     * The ordering therefore prefers the founder's OWN org's courses first and
     * only then falls back to stalest. It is an ORDER BY, not a WHERE: the
     * Academy catalog is legitimate content, and a founder whose only stalled
     * enrolment is an Academy course still deserves the nudge.
     * ({@code AuthoringService.orgIdOf} stamps a course with its author's own
     * org, and members live in sub-orgs, so {@code c.org_id = u.organization_id}
     * is exact for org-authored courses and false for the Academy's.)
     *
     * <p>Two exclusions keep the job from nudging about something unreachable:
     * a course that is not PUBLISHED cannot be resumed, and a course with no
     * lesson rows can never record progress — so without that filter its
     * enrolees would be nudged every N days forever with no possible escape.
     * Lessons are counted from {@code content}, not the denormalised
     * {@code course.lessons_count}, which nothing recomputes (AuthoringService
     * itself counts rows live for the admin DTO).
     *
     * <p>The opt-out check is a PRE-filter, not the authority — dispatch still
     * applies it. It is here so the job's "nudged N founders" log counts what
     * was actually sent rather than what was selected, and so a muted founder
     * (who never gets a history row, and so would otherwise be re-selected on
     * every single run forever) drops out of the sweep entirely.
     */
    @Query(value = """
            SELECT DISTINCT ON (u.id)
                   u.id    AS "userId",
                   c.title AS "courseTitle",
                   c.slug  AS "courseSlug",
                   (EXTRACT(EPOCH FROM (now() - COALESCE(p.last_progress_at, e.enrolled_at)))
                        / 86400)::int AS "stalledDays"
            FROM enrollment e
            JOIN users u         ON u.id = e.user_id
            JOIN organizations o ON o.id = u.organization_id
            JOIN course c        ON c.id = e.course_id
            LEFT JOIN LATERAL (
                SELECT max(cp.completed_at) AS last_progress_at
                FROM content_progress cp
                WHERE cp.enrollment_id = e.id
            ) p ON true
            WHERE u.organization_id = :orgId
              AND u.status = 'ACTIVE'
              AND u.role = 'MEMBER'
              AND e.status = 'ACTIVE'
              AND o.inactivity_nudge_days > 0
              AND c.state = 'PUBLISHED'
              AND EXISTS (
                    SELECT 1 FROM content ct
                    JOIN section s ON s.id = ct.section_id
                    WHERE s.course_id = c.id)
              AND COALESCE(p.last_progress_at, e.enrolled_at)
                    < now() - make_interval(days => o.inactivity_nudge_days)
              AND NOT EXISTS (
                    SELECT 1 FROM notifications n
                    WHERE n.user_id = u.id
                      AND n.notification_type = 'INACTIVITY_NUDGE'
                      AND n.created_at >= now() - make_interval(days => o.inactivity_nudge_days))
              AND NOT EXISTS (
                    SELECT 1 FROM notification_optouts x
                    WHERE x.user_id = u.id
                      AND x.notification_type = 'INACTIVITY_NUDGE')
            ORDER BY u.id,
                     (c.org_id <> u.organization_id),
                     COALESCE(p.last_progress_at, e.enrolled_at)
            """, nativeQuery = true)
    List<StalledLearnerRow> findStalledLearners(@Param("orgId") UUID orgId);
}
