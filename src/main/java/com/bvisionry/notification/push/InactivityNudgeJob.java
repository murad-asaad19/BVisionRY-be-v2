package com.bvisionry.notification.push;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bvisionry.common.util.TextTruncator;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * The missing trigger for roadmap §7 item 7: a founder with no progress on an
 * assigned course for N days gets nudged. N is the org's own
 * {@code inactivity_nudge_days} (V149, default 14, 0 = off).
 *
 * <p><b>Delivery is the existing pipeline, not a new channel.</b> Every nudge
 * goes through {@link PushNotificationService}, so the per-user opt-out
 * ({@link NotificationOptOut}) and the in-app bell history are honored by the
 * code that already honors them for every other type — policy
 * {@code nudge_channels: RESPECT_EXISTING_PREFERENCES}. {@code INACTIVITY_NUDGE}
 * is a member-visible type ({@code adminOnly=false}) exactly like
 * {@code ANNOUNCEMENT}, so it appears in the preference UI with no backfill:
 * absence of an opt-out row means enabled.
 *
 * <p><b>Send-once, not send-daily.</b> The quiet period IS the window: a
 * founder due a nudge is one whose last progress is older than N days AND who
 * has no {@code INACTIVITY_NUDGE} row in the notification history inside those
 * same N days. So the history row this job writes is what stops the next run
 * writing another — no marker column, no marker table, no "last nudged at"
 * anywhere. A daily schedule therefore nudges a permanently stalled founder
 * once every N days, not once a day. (Consequence worth knowing: a founder who
 * has MUTED the type gets no history row, so they are re-selected every run.
 * The dispatch drops them every time and nothing is sent — a wasted row in a
 * result set, not a wasted notification.)
 *
 * <p><b>Tenancy.</b> The sweep is per-org: the job resolves the enabled orgs
 * and then asks one org at a time, so no query here ever produces a list with
 * two tenants in it, and one org's bad data cannot abort another's nudges.
 *
 * <p>ponytail: this is a DETERMINISTIC nudge, which is the honest ceiling of
 * roadmap item 18 ("the AI coach initiates contact") against today's code. The
 * AI coach is a single-shot, stateless SSE hint bound to one program-task field
 * ({@code POST /api/my/program/tasks/{taskId}/coach}); there is no conversation
 * store, no AI-authored message a user can read later, and no per-org token
 * budget or rate limit on that endpoint. Generating a per-founder AI message
 * from a scheduled job would therefore mean inventing AI-initiated message
 * storage AND a cost ceiling inside a job that fans out per tenant — an unbounded
 * spend path with no owner. Upgrade path, in order: (1) a per-org AI budget
 * enforced in {@code RateLimitService}, (2) a stored AI message the bell can
 * deep-link to, (3) swap {@link #body} for the generated text and keep
 * everything else here unchanged. The score-drop half of item 18 lives in the
 * assessment slice and is not wired by this job.
 */
@Component
@Slf4j
public class InactivityNudgeJob {

    /**
     * Where a stalled enrolment actually resumes. The player lives at
     * {@code /app/courses/[slug]/learn} — there is NO page at
     * {@code /app/courses/[slug]}, only {@code learn/} and {@code certificate/},
     * and Next routes a segment only when it has a page, so the bare slug is a
     * chrome-less 404. Same shape {@code playerHref()} in the web app builds.
     */
    private static final String COURSE_URL_PREFIX = "/app/courses/";
    private static final String COURSE_URL_SUFFIX = "/learn";

    /** {@code notifications.title} is VARCHAR(200); a course title is authored. */
    private static final int TITLE_MAX = 200;

    private final UserNotificationRepository repository;
    private final PushNotificationService pushNotificationService;

    /**
     * Off by default, and deliberately not merely "off for new installs".
     * V149 gives every existing org a 14-day window at migration time, so
     * without this the first deploy would start nudging every founder on the
     * platform with no operator decision anywhere — and it would send them to
     * {@code /app/courses/**}, which renders Coming Soon while
     * {@code NEXT_PUBLIC_COURSES_ENABLED} is false (it is false in
     * {@code .env.example} and in every sandbox lane env).
     *
     * <p>This flag is tied to the COURSES LAUNCH DECISION: turn it on in the
     * same change that flips {@code NEXT_PUBLIC_COURSES_ENABLED} to true, not
     * before. The per-org column is the other half — it means "when the
     * feature is on, N = 14", not "nudge now".
     */
    private final boolean enabled;

    public InactivityNudgeJob(
            UserNotificationRepository repository,
            PushNotificationService pushNotificationService,
            @Value("${bvisionry.notifications.inactivity-nudge.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.pushNotificationService = pushNotificationService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${bvisionry.notifications.inactivity-nudge.interval-ms:86400000}",
            initialDelayString = "${bvisionry.notifications.inactivity-nudge.initial-delay-ms:300000}")
    @SchedulerLock(name = "InactivityNudgeJob_nudgeStalledFounders",
            lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void nudgeStalledFounders() {
        if (!enabled) {
            return;
        }
        for (UUID orgId : repository.findOrgIdsWithNudgesEnabled()) {
            try {
                nudgeOrg(orgId);
            } catch (RuntimeException e) {
                // Per-org, so one org's failure costs that org one cycle rather
                // than silently ending the sweep for every org after it.
                log.error("InactivityNudgeJob failed for org {}: {}", orgId, e.getMessage(), e);
            }
        }
    }

    private void nudgeOrg(UUID orgId) {
        List<StalledLearnerRow> stalled = repository.findStalledLearners(orgId);
        if (stalled.isEmpty()) {
            return;
        }
        // ponytail: one dispatch per recipient, not the notifyUsers batch — the
        // body names the founder's OWN stalled course, so there is no shared
        // payload to batch. Bounded by construction: a founder appears at most
        // once per run and at most once per N days, so this is "founders who
        // stalled this window", not the roster.
        for (StalledLearnerRow row : stalled) {
            pushNotificationService.notifyUser(row.getUserId(), NotificationType.INACTIVITY_NUDGE,
                    title(row), body(row),
                    COURSE_URL_PREFIX + row.getCourseSlug() + COURSE_URL_SUFFIX);
        }
        // Counts what was DISPATCHED, not what was selected: the query already
        // drops opted-out founders, so these two are the same number. Without
        // that pre-filter this line overstated delivery by exactly the muted
        // population, which is the population you would most want to see.
        log.info("InactivityNudgeJob: nudged {} founder(s) in org {}", stalled.size(), orgId);
    }

    private static String title(StalledLearnerRow row) {
        // Null suffix, as AnnouncementPushHandler does: TextTruncator APPENDS the
        // suffix after the cap, so any non-null one would push a long authored
        // title to 201 characters and the insert would fail on VARCHAR(200).
        return TextTruncator.truncate("Pick up " + row.getCourseTitle() + " again", TITLE_MAX, null);
    }

    private static String body(StalledLearnerRow row) {
        int days = row.getStalledDays();
        return "No progress on " + row.getCourseTitle() + " for " + days
                + (days == 1 ? " day" : " days") + ". Open it to carry on where you stopped.";
    }
}
