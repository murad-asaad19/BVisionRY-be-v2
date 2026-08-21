package com.bvisionry.common.scheduling;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.config.TaskExecutionOutcome;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes a dead scheduled job visible.
 *
 * <p>THE GAP THIS CLOSES. Twelve {@code @Scheduled} jobs run this application's
 * reapers, retention purges and founder nudges. Until now the only evidence any
 * of them was alive was a {@code log.error} on the unhappy path — so a scheduler
 * that stopped firing, a ShedLock row stuck held by a dead replica, or a job
 * throwing on every single tick all looked exactly like a healthy quiet system.
 * Nothing polled and nothing alerted, and the failure mode is silent by
 * construction: nobody notices reminders stopped going out until a customer says
 * so.
 *
 * <p>THIS CLASS OWNS NO STATE. Spring already records, per registered task, when
 * it last ran and how that went ({@link Task#getLastExecutionOutcome()}, added in
 * Framework 6.2). The first version of this file did not know that and kept its
 * own timestamps, fed by an {@code ObservationHandler} — which meant correlating
 * two sides by a synthetic key, being constructed during observation-registry
 * setup long before the scheduler existed, and getting the answer wrong in a way
 * that reported every job dead. Reading the framework's own record is smaller,
 * has nothing to keep in step, and cannot drift.
 *
 * <p>All this adds is the JUDGEMENT the framework does not make: is that last
 * outcome recent enough for this task's own interval?
 *
 * <p>WHY THIS NEVER REPORTS DOWN. {@code /actuator/health} is a LIVENESS probe —
 * the compose healthcheck reads it, and a container platform restarts what it
 * finds unhealthy. A stale reaper is not a reason to restart a server that is
 * happily serving requests; doing so would convert a monitoring signal into an
 * outage, and a self-inflicted restart loop is a far worse bug than the silence
 * it was meant to report. So a stale job reports {@link #DEGRADED}, which
 * aggregates as UP (see {@code management.endpoint.health.status.order}) and
 * surfaces on its own {@code /actuator/health/scheduling} group for anything that
 * wants to alert on it.
 *
 * <p>WHAT THIS DOES <b>NOT</b> SEE, stated plainly so nobody reads more into a
 * green check than it carries:
 * <ul>
 *   <li><b>A job that catches its own exception and logs it.</b>
 *       {@code InactivityNudgeJob} does exactly this per organisation, and from
 *       out here that tick is indistinguishable from a clean one. Catching a
 *       per-item error to keep processing the rest is correct; the monitoring
 *       consequence is that "healthy" here means "the tick completed", not
 *       "every unit of work inside it succeeded".</li>
 *   <li><b>Whether the work happened on THIS replica.</b> A
 *       {@code @SchedulerLock} tick that loses the lock returns normally and
 *       counts as a success — which is right, because the question being asked is
 *       whether this instance's scheduler is still firing. Cluster-wide "did
 *       anyone do the work" is a different question and would need the shedlock
 *       table, not this.</li>
 *   <li><b>Cron tasks.</b> Every job is fixed-delay or fixed-rate today, so the
 *       deadline is arithmetic. A cron task would report
 *       {@code UNMONITORED_NO_INTERVAL} rather than being silently skipped — an
 *       unmonitored job must announce itself, or this indicator quietly shrinks
 *       to cover less than it appears to.</li>
 * </ul>
 */
@Component
public class ScheduledJobMonitor implements HealthIndicator {

    /**
     * Not UP, and deliberately not DOWN. Ranked after UP in
     * {@code management.endpoint.health.status.order} so it cannot drag the
     * aggregate — and therefore the liveness probe — down with it.
     */
    public static final Status DEGRADED = new Status("DEGRADED", "A scheduled job has gone quiet");

    /**
     * Slack on top of two whole intervals. One missed cycle is noise — a slow
     * tick, a GC pause, a deploy; two consecutive ones are a fault. The flat
     * minute keeps the fastest job (a 60s cache sweep) from alerting on a
     * 20-second hiccup.
     */
    private static final Duration GRACE = Duration.ofMinutes(1);

    private final ObjectProvider<ScheduledTaskHolder> taskHolders;
    private final Clock clock;
    private final Instant startedAt;

    /**
     * {@code @Autowired} is load-bearing, not decoration: this class declares a
     * second (test) constructor, and with more than one candidate Spring stops
     * inferring and looks for a no-arg constructor it will not find.
     */
    @Autowired
    public ScheduledJobMonitor(ObjectProvider<ScheduledTaskHolder> taskHolders) {
        this(taskHolders, Clock.systemUTC());
    }

    /** Test seam — a movable clock is the only way to assert a deadline without sleeping through it. */
    ScheduledJobMonitor(ObjectProvider<ScheduledTaskHolder> taskHolders, Clock clock) {
        this.taskHolders = taskHolders;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean stale = false;

        for (ScheduledTaskHolder holder : taskHolders) {
            for (ScheduledTask scheduled : holder.getScheduledTasks()) {
                Task task = scheduled.getTask();
                String name = task.toString();

                if (!(task instanceof IntervalTask interval)) {
                    details.put(name, "UNMONITORED_NO_INTERVAL");
                    continue;
                }

                Instant lastSuccess = lastSuccessOf(task);
                boolean overdue = isOverdue(lastSuccess,
                        interval.getIntervalDuration(), interval.getInitialDelayDuration());
                stale |= overdue;

                details.put(name, (overdue ? "STALE" : "OK")
                        + ", interval=" + interval.getIntervalDuration()
                        + ", lastSuccess=" + (lastSuccess == null ? "never" : lastSuccess)
                        + failureSuffix(task));
            }
        }

        if (details.isEmpty()) {
            // Scheduling itself is not running, or no task is registered. Saying
            // UP on an empty set would be the exact silence this class exists to
            // end — but it is still not a reason to restart a serving container.
            return Health.status(DEGRADED).withDetail("scheduledTasks", "none registered").build();
        }
        return (stale ? Health.status(DEGRADED) : Health.up()).withDetails(details).build();
    }

    /**
     * When the task last finished WITHOUT throwing, or null if it never has.
     *
     * <p>A failed run deliberately does not count. Were failures to refresh the
     * timestamp, a job throwing on every tick — the likeliest real fault — would
     * report healthy for ever, and a monitor that is green over a broken job is
     * worse than no monitor, because it is also a claim.
     */
    private static Instant lastSuccessOf(Task task) {
        TaskExecutionOutcome outcome = task.getLastExecutionOutcome();
        return outcome != null && outcome.status() == TaskExecutionOutcome.Status.SUCCESS
                ? outcome.executionTime()
                : null;
    }

    /** The last throwable, if the most recent run ended in one — so DEGRADED says why. */
    private static String failureSuffix(Task task) {
        TaskExecutionOutcome outcome = task.getLastExecutionOutcome();
        if (outcome == null || outcome.status() != TaskExecutionOutcome.Status.ERROR) {
            return "";
        }
        Throwable error = outcome.throwable();
        return ", lastError=" + (error == null ? "unknown" : error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage()));
    }

    /**
     * The whole decision, isolated from the task-registry plumbing so it can be
     * tested against a movable clock instead of by sleeping.
     *
     * <p>Before a job's first run the countdown starts at boot PLUS its configured
     * initial delay, so a job with a five-minute warm-up is not called stale
     * during those five minutes — the commonest false positive, and the one that
     * would train an operator to ignore this indicator during every deploy.
     */
    boolean isOverdue(Instant lastSuccess, Duration interval, Duration initialDelay) {
        Instant since = lastSuccess != null ? lastSuccess : startedAt.plus(initialDelay);
        return clock.instant().isAfter(since.plus(interval.multipliedBy(2)).plus(GRACE));
    }
}
