package com.bvisionry.notification.push;

import com.bvisionry.common.event.ProgramFlowEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Guards the one thing in this handler that is not a straight pass-through: the
 * admin deep links. The learner-facing pushes are a literal URL constant with no
 * branch to break, and the fan-out itself belongs to
 * {@link PushNotificationServiceTest}.
 *
 * <p>Also guards the coach half added this pass: every submission-family event
 * (program task, assessment, exercise submit, exercise reply) must reach
 * {@link CoachReviewNotifier#notifyCoachesOf} with the SUBMITTING learner's id —
 * who among the coaches it actually notifies is {@link CoachReviewNotifier}'s
 * own concern, covered by {@code CoachReviewNotifierIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProgramFlowPushHandlerTest {

    @Mock private PushNotificationService pushNotificationService;
    @Mock private CoachReviewNotifier coachReviewNotifier;
    @InjectMocks private ProgramFlowPushHandler handler;

    @Test
    void taskSubmittedSendsEachRoleSomewhereItMayActuallyOpen() {
        UUID orgId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();

        handler.onTaskSubmitted(new ProgramFlowEvents.TaskSubmitted(orgId, learnerId, "Ada", "Pitch deck"));

        ArgumentCaptor<String> orgAdminUrl = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> superAdminUrl = ArgumentCaptor.forClass(String.class);
        verify(pushNotificationService).notifyOrgAdmins(eq(orgId),
                eq(NotificationType.PROGRAM_TASK_SUBMITTED), any(), any(),
                orgAdminUrl.capture(), superAdminUrl.capture());

        // The two slots must NOT collapse onto the platform console: it is
        // requireSuperAdmin, so an ORG_ADMIN following it gets a 404.
        assertThat(orgAdminUrl.getValue())
                .isEqualTo("/app/admin/organizations/" + orgId + "/cohorts");
        assertThat(superAdminUrl.getValue()).isEqualTo("/app/admin/program-flow");
        assertThat(orgAdminUrl.getValue()).isNotEqualTo(superAdminUrl.getValue());

        verify(coachReviewNotifier).notifyCoachesOf(
                eq(orgId), eq(learnerId), eq(NotificationType.PROGRAM_TASK_SUBMITTED), any(), any());
    }

    @Test
    void assessmentSubmittedNotifiesOrgAdminsAndTheLearnersCoaches() {
        UUID orgId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();

        handler.onAssessmentSubmitted(
                new ProgramFlowEvents.AssessmentSubmitted(orgId, learnerId, "Ada", "Founder Fit"));

        verify(pushNotificationService).notifyOrgAdmins(eq(orgId),
                eq(NotificationType.MEMBER_SUBMITTED), any(), any(), any(), any());
        verify(coachReviewNotifier).notifyCoachesOf(
                eq(orgId), eq(learnerId), eq(NotificationType.MEMBER_SUBMITTED), any(), any());
    }

    @Test
    void exerciseSubmittedNotifiesOrgAdminsAndTheLearnersCoaches() {
        UUID orgId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();

        handler.onExerciseSubmitted(
                new ProgramFlowEvents.ExerciseSubmitted(orgId, learnerId, "Ada", "Pitch deck"));

        verify(pushNotificationService).notifyOrgAdmins(eq(orgId),
                eq(NotificationType.EXERCISE_ACTIVITY), any(), any(), any(), any());
        verify(coachReviewNotifier).notifyCoachesOf(
                eq(orgId), eq(learnerId), eq(NotificationType.EXERCISE_ACTIVITY), any(), any());
    }

    @Test
    void exerciseFeedbackRepliedNotifiesOrgAdminsAndTheLearnersCoaches() {
        UUID orgId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();

        handler.onExerciseFeedbackReplied(
                new ProgramFlowEvents.ExerciseFeedbackReplied(orgId, learnerId, "Ada", "Pitch deck"));

        verify(pushNotificationService).notifyOrgAdmins(eq(orgId),
                eq(NotificationType.EXERCISE_ACTIVITY), any(), any(), any(), any());
        verify(coachReviewNotifier).notifyCoachesOf(
                eq(orgId), eq(learnerId), eq(NotificationType.EXERCISE_ACTIVITY), any(), any());
    }
}
