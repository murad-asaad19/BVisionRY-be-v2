package com.bvisionry.organization;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrialExpiryJob {
    private final TrialService trialService;

    /** Runs every 15 minutes. */
    @Scheduled(fixedRate = 15 * 60 * 1000L)
    public void run() {
        trialService.expireLapsed();
        trialService.notifyEndingTrials();
    }
}
