package com.bvisionry.notification.push;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bvisionry.common.event.ProgramFlowEvents;

import lombok.RequiredArgsConstructor;

/**
 * Pushes the program-flow notifications off the {@code common} events the
 * programflow slice publishes (enrolment, module assignment/unlock, due-soon
 * reminders, submissions). AFTER_COMMIT like {@link MemberJoinedPushHandler}:
 * a rolled-back roster edit or submit must not notify.
 */
@Component
@RequiredArgsConstructor
public class ProgramFlowPushHandler {

    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("MMM d");
    private static final String JOURNEY_URL = "/app/program";
    private static final String ADMIN_URL = "/app/admin/program-flow";

    private final PushNotificationService pushNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCohortEnrolled(ProgramFlowEvents.CohortEnrolled event) {
        for (UUID userId : event.userIds()) {
            pushNotificationService.notifyUser(userId,
                    NotificationType.COHORT_ENROLLED,
                    "You've been added to a cohort",
                    "You are now part of “" + event.cohortName() + "”. Your journey is ready.",
                    JOURNEY_URL);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModuleAssigned(ProgramFlowEvents.ModuleAssigned event) {
        for (UUID userId : event.userIds()) {
            pushNotificationService.notifyUser(userId,
                    NotificationType.PROGRAM_MODULE_ASSIGNED,
                    "New module assigned",
                    "“" + event.moduleName() + "” was assigned to you in "
                            + event.cohortName() + ".",
                    JOURNEY_URL);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModuleUnlocked(ProgramFlowEvents.ModuleUnlocked event) {
        for (UUID userId : event.userIds()) {
            pushNotificationService.notifyUser(userId,
                    NotificationType.PROGRAM_MODULE_UNLOCKED,
                    "Module unlocked",
                    "“" + event.moduleName() + "” is now open on your journey.",
                    JOURNEY_URL);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskDueSoon(ProgramFlowEvents.TaskDueSoon event) {
        for (UUID userId : event.userIds()) {
            pushNotificationService.notifyUser(userId,
                    NotificationType.PROGRAM_TASK_DUE,
                    "Task due soon",
                    "“" + event.taskName() + "” is due " + DUE_FORMAT.format(event.dueDate()) + ".",
                    JOURNEY_URL + "/tasks/" + event.taskId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskSubmitted(ProgramFlowEvents.TaskSubmitted event) {
        pushNotificationService.notifyOrgAdmins(event.orgId(),
                NotificationType.PROGRAM_TASK_SUBMITTED,
                "Program task submitted",
                event.learnerName() + " submitted “" + event.taskName() + "”.",
                ADMIN_URL,
                ADMIN_URL);
    }
}
