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
 *
 * <p>Also handles the assessment and exercise SUBMISSION events declared
 * alongside {@link ProgramFlowEvents.TaskSubmitted} in {@link ProgramFlowEvents}.
 * They are not program-flow, but redesign spec §2.2 (and {@link NotificationType}'s
 * javadoc) treats all three submission types as one review-work concept with the
 * identical org-admin + coach fan-out shape, so they share this handler rather
 * than each spinning up its own near-identical class.
 */
@Component
@RequiredArgsConstructor
public class ProgramFlowPushHandler {

    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("MMM d");
    private static final String JOURNEY_URL = "/app/program";
    private static final String ADMIN_URL = "/app/admin/program-flow";

    private final PushNotificationService pushNotificationService;
    private final CoachReviewNotifier coachReviewNotifier;

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
        UUID orgId = event.orgId();
        // The org-admin slot used to be ADMIN_URL as well, but
        // /app/admin/program-flow is requireSuperAdmin — every org admin clicking
        // this bell item hit a 404, the same bug MemberJoinedPushHandler carried.
        // Their own org's Cohorts tab is org-scoped and one click from the work.
        // No deeper link exists here: the event names only the SUBMITTING
        // member's org (a cohort spans orgs), so there is no cohort id to aim at.
        String body = event.learnerName() + " submitted “" + event.taskName() + "”.";
        pushNotificationService.notifyOrgAdmins(orgId,
                NotificationType.PROGRAM_TASK_SUBMITTED,
                "Program task submitted",
                body,
                "/app/admin/organizations/" + orgId + "/cohorts",
                ADMIN_URL);
        coachReviewNotifier.notifyCoachesOf(orgId, event.learnerId(),
                NotificationType.PROGRAM_TASK_SUBMITTED,
                "Program task submitted",
                body);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssessmentSubmitted(ProgramFlowEvents.AssessmentSubmitted event) {
        UUID orgId = event.orgId();
        String body = event.learnerName() + " completed “" + event.pipelineName() + "”.";
        String url = "/app/admin/organizations/" + orgId + "/assignments";
        pushNotificationService.notifyOrgAdmins(orgId, NotificationType.MEMBER_SUBMITTED,
                "Assessment completed", body, url, url);
        coachReviewNotifier.notifyCoachesOf(orgId, event.learnerId(),
                NotificationType.MEMBER_SUBMITTED, "Assessment completed", body);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExerciseSubmitted(ProgramFlowEvents.ExerciseSubmitted event) {
        UUID orgId = event.orgId();
        String body = event.learnerName() + " submitted “" + event.exerciseName() + "”.";
        pushNotificationService.notifyOrgAdmins(orgId, NotificationType.EXERCISE_ACTIVITY,
                "Exercise submitted", body, "/app/admin/exercises", "/app/admin/exercises");
        coachReviewNotifier.notifyCoachesOf(orgId, event.learnerId(),
                NotificationType.EXERCISE_ACTIVITY, "Exercise submitted", body);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExerciseFeedbackReplied(ProgramFlowEvents.ExerciseFeedbackReplied event) {
        UUID orgId = event.orgId();
        String body = event.learnerName() + " replied on “" + event.exerciseName() + "”.";
        pushNotificationService.notifyOrgAdmins(orgId, NotificationType.EXERCISE_ACTIVITY,
                "Exercise feedback reply", body, "/app/admin/exercises", "/app/admin/exercises");
        coachReviewNotifier.notifyCoachesOf(orgId, event.learnerId(),
                NotificationType.EXERCISE_ACTIVITY, "Exercise feedback reply", body);
    }
}
