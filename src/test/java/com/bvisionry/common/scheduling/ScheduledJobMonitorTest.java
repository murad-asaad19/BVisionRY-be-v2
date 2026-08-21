package com.bvisionry.common.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Iterator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staleness verdict, driven by a clock instead of by waiting.
 *
 * <p>What is under test is deliberately narrow. {@link ScheduledJobMonitor} owns
 * no state — Spring records each task's last outcome and the monitor only adds
 * one judgement: is that outcome recent enough for this task's own interval? So
 * the judgement is what is worth pinning, and the plumbing around it is a loop.
 *
 * <p>A monitor that never fires is worth less than no monitor, because it is also
 * a claim.
 */
class ScheduledJobMonitorTest {

    private static final Duration EVERY_MINUTE = Duration.ofMinutes(1);
    private static final Duration NO_WARMUP = Duration.ZERO;

    /** Advanceable clock. {@code Clock.fixed} cannot move, and the point is to move it. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    /** No registered tasks: the verdict itself needs none, and `health()` has its own case. */
    private static ObjectProvider<ScheduledTaskHolder> noHolders() {
        return new ObjectProvider<>() {
            @Override public ScheduledTaskHolder getObject() {
                throw new UnsupportedOperationException();
            }

            @Override public ScheduledTaskHolder getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override public ScheduledTaskHolder getIfAvailable() {
                return null;
            }

            @Override public ScheduledTaskHolder getIfUnique() {
                return null;
            }

            @Override public Iterator<ScheduledTaskHolder> iterator() {
                return Collections.emptyIterator();
            }

            @Override public Stream<ScheduledTaskHolder> stream() {
                return Stream.empty();
            }
        };
    }

    @Test
    void aJobThatHasNeverRunIsHealthyUntilTwoIntervalsHavePassed() {
        MovableClock clock = new MovableClock();
        ScheduledJobMonitor monitor = new ScheduledJobMonitor(noHolders(), clock);

        clock.advance(Duration.ofMinutes(2));
        assertThat(monitor.isOverdue(null, EVERY_MINUTE, NO_WARMUP))
                .as("two intervals in, still inside the grace window — one missed tick is noise")
                .isFalse();

        clock.advance(Duration.ofMinutes(2));
        assertThat(monitor.isOverdue(null, EVERY_MINUTE, NO_WARMUP))
                .as("four intervals with no completion is a dead job, not a slow one")
                .isTrue();
    }

    @Test
    void anInitialDelayPostponesTheCountdownRatherThanAlertingDuringWarmUp() {
        MovableClock clock = new MovableClock();
        ScheduledJobMonitor monitor = new ScheduledJobMonitor(noHolders(), clock);

        clock.advance(Duration.ofMinutes(4));
        assertThat(monitor.isOverdue(null, EVERY_MINUTE, Duration.ofMinutes(5)))
                .as("a job configured not to start for five minutes cannot be late at four")
                .isFalse();
    }

    @Test
    void aRecentSuccessResetsTheCountdown() {
        MovableClock clock = new MovableClock();
        ScheduledJobMonitor monitor = new ScheduledJobMonitor(noHolders(), clock);

        clock.advance(Duration.ofHours(3));
        assertThat(monitor.isOverdue(null, EVERY_MINUTE, NO_WARMUP)).isTrue();
        assertThat(monitor.isOverdue(clock.instant(), EVERY_MINUTE, NO_WARMUP))
                .as("the job reported in — the countdown restarts from that success")
                .isFalse();
    }

    @Test
    void aLongIntervalGetsAProportionallyLongerDeadline() {
        // A daily retention job must not be called stale after an hour, and a
        // 60-second sweep must not be given a day. The deadline scales with the
        // task's own interval for exactly that reason.
        MovableClock clock = new MovableClock();
        ScheduledJobMonitor monitor = new ScheduledJobMonitor(noHolders(), clock);
        Duration daily = Duration.ofHours(24);

        clock.advance(Duration.ofHours(30));
        assertThat(monitor.isOverdue(null, daily, NO_WARMUP)).isFalse();

        clock.advance(Duration.ofHours(20));
        assertThat(monitor.isOverdue(null, daily, NO_WARMUP)).isTrue();
    }

    @Test
    void noRegisteredTasksIsDegradedRatherThanVacuouslyUp() {
        // An empty task set means scheduling is not running at all. Reporting UP
        // on it is precisely the silence this class exists to end.
        ScheduledJobMonitor monitor = new ScheduledJobMonitor(noHolders(), new MovableClock());
        assertThat(monitor.health().getStatus()).isEqualTo(ScheduledJobMonitor.DEGRADED);
    }

    @Test
    void aQuietJobNeverReportsDownAndThereforeNeverFailsALivenessProbe() {
        // THE LOAD-BEARING ASSERTION. /actuator/health is read by the compose
        // healthcheck and by the platform's liveness probe. If a stale reaper
        // could report DOWN, this indicator would get a healthy, request-serving
        // container restarted — turning a monitoring signal into an outage, which
        // is a far worse bug than the silence it was added to report.
        ScheduledJobMonitor monitor = new ScheduledJobMonitor(noHolders(), new MovableClock());
        assertThat(monitor.health().getStatus().getCode())
                .as("must aggregate as UP; see management.endpoint.health.status.order")
                .isNotEqualTo("DOWN");
    }
}
