package com.bvisionry.organization;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrialExpiryJob {
    private final TrialService trialService;

    /** Runs every 15 minutes. */
    // Lock spans one instance's run: DB status updates + trial-ending emails finish
    // well under 10m; 30s floor absorbs clock skew so two replicas can't both fire.
    @Scheduled(fixedRate = 15 * 60 * 1000L)
    @SchedulerLock(name = "TrialExpiryJob_run", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        trialService.expireLapsed();
        trialService.notifyEndingTrials();
    }
}
