package com.bvisionry.notification.push;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bvisionry.common.event.WorkshopEvents;

import lombok.RequiredArgsConstructor;

/**
 * Pushes the workshop notifications off the {@code common} events the
 * workshops slice publishes. AFTER_COMMIT like {@link ProgramFlowPushHandler}:
 * a rolled-back share must not notify.
 */
@Component
@RequiredArgsConstructor
public class WorkshopsPushHandler {

    private final PushNotificationService pushNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResultsShared(WorkshopEvents.ResultsShared event) {
        for (UUID userId : event.memberIds()) {
            pushNotificationService.notifyUser(userId,
                    NotificationType.WORKSHOP_RESULTS_SHARED,
                    "Your team lead shared results",
                    "“" + event.exerciseTitle() + "” results are in — your tasks in "
                            + event.workshopName() + " are unlocked.",
                    "/app/workshops/" + event.workshopId());
        }
    }
}
