package com.bvisionry.coaching.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bvisionry.coaching.repository.CoachingBookingRepository;
import com.bvisionry.coaching.repository.CoachingBookingRepository.SessionMemberRow;
import com.bvisionry.coaching.repository.CoachingBookingRepository.SessionRow;
import com.bvisionry.common.event.CoachingEvents;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Closes sessions the clock has already ended (spec v2 §9).
 *
 * <p>WHY ATTENDANCE IS AUTOMATIC. v1 left every session SCHEDULED until the
 * coach pressed a button, which made the common case — the session happened,
 * everyone came — the one that needed work, and left the post-session survey
 * waiting on an administrative act nobody had a reason to perform. v2 inverts
 * it: when {@code ends_at} passes, the session is held and everyone expected was
 * there, and the coach CORRECTS that afterwards
 * ({@code PUT .../attendance/{memberId}}) rather than authoring it. Rows the
 * coach already marked are skipped by the SCHEDULED predicate, so taking the
 * roll call yourself still wins.
 *
 * <p>ONE TRANSACTION PER SESSION. A session whose attendance write or event
 * publishing blows up must not take the rest of the batch with it, so each is
 * wrapped in its own {@link TransactionTemplate} — started by hand rather than
 * by a {@code REQUIRES_NEW} annotation on a second bean, which would only be a
 * proxy hop to say the same thing and would put {@link #complete} out of a
 * caller's reach.
 */
@Component
@Slf4j
public class SessionAutoCompleteJob {

    private final CoachingBookingRepository sessions;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate perSession;

    public SessionAutoCompleteJob(CoachingBookingRepository sessions,
                                  ApplicationEventPublisher events,
                                  PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.events = events;
        this.perSession = new TransactionTemplate(transactionManager);
        this.perSession.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Every five minutes by default — the resolution the member's "Give
     * feedback" CTA appears at, which is as tight as it needs to be for
     * something that only ever runs late, never early.
     *
     * <p>{@code lockAtMostFor} is 10 minutes: twice the interval, so a replica
     * that dies mid-run releases the lock before the following tick, and far
     * short of anything that could let two replicas run at once.
     */
    @Scheduled(fixedDelayString = "${bvisionry.sessions.auto-complete.interval-ms:300000}")
    @SchedulerLock(name = "SessionAutoCompleteJob_completeEnded",
            lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void completeEnded() {
        List<UUID> ended = sessions.endedScheduledSessionIds(Instant.now());
        int completed = 0;
        for (UUID sessionId : ended) {
            try {
                completed += Boolean.TRUE.equals(
                        perSession.execute(status -> complete(sessionId))) ? 1 : 0;
            } catch (RuntimeException e) {
                log.error("SessionAutoCompleteJob: could not complete session {}", sessionId, e);
            }
        }
        if (completed > 0) {
            log.info("SessionAutoCompleteJob: marked {} of {} ended session(s) held",
                    completed, ended.size());
        }
    }

    /**
     * Mark one session held with everyone expected present.
     *
     * <p>The members are read BEFORE the marks are written, so
     * {@code feedbackSubmitted} is the state that decides who gets the survey
     * invitation — and {@code marked_by} stays NULL, which is how the console
     * tells "the system assumed this" from "the coach said so".
     *
     * <p>Public and transactional in its own right so the batch above is the
     * only thing the schedule adds: this is the whole unit of work, and it is
     * what a test drives.
     *
     * @return false when the row was no longer SCHEDULED (the coach got there first)
     */
    @Transactional
    public boolean complete(UUID sessionId) {
        SessionRow row = sessions.findById(sessionId).orElse(null);
        if (row == null || !row.isScheduled()) {
            return false;
        }
        List<SessionMemberRow> members = sessions.sessionMembers(sessionId);
        Instant now = Instant.now();
        if (!sessions.complete(sessionId, now)) {
            return false;
        }
        for (SessionMemberRow member : members) {
            sessions.markPresent(sessionId, member.memberId(), null, now);
            CoachingEvents.SessionCompleted invitation =
                    CoachScheduleService.completed(row, member);
            if (invitation != null) {
                events.publishEvent(invitation);
            }
        }
        return true;
    }
}
