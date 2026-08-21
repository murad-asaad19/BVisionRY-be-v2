package com.bvisionry.exercise;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.exercise.entity.ExerciseAssignment;
import com.bvisionry.exercise.entity.ExerciseSubmission;
import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExerciseCommentRepository;
import com.bvisionry.exercise.repository.ExerciseRowRepository;
import com.bvisionry.exercise.repository.ExerciseSubmissionRepository;
import com.bvisionry.notification.push.PushNotificationService;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExerciseReviewServiceTest {

    @Mock private CurrentUserAccessor currentUser;
    @Mock private ExerciseAssignmentService assignmentService;
    @Mock private ExerciseSubmissionService submissionService;
    @Mock private ExerciseSubmissionRepository submissionRepository;
    @Mock private ExerciseRowRepository rowRepository;
    @Mock private ExerciseColumnRepository columnRepository;
    @Mock private ExerciseCommentRepository commentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private ExerciseCoachGate coachGate;

    @InjectMocks private ExerciseReviewService service;

    private UUID orgId;
    private UUID assignmentId;
    private ExerciseSubmission submission;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
        service.setCoachGate(coachGate);

        ExerciseTemplate template = new ExerciseTemplate();
        template.setId(UUID.randomUUID());
        template.setName("Exercise");

        Organization org = new Organization();
        org.setId(orgId);

        User member = new User();
        member.setId(UUID.randomUUID());
        member.setName("Member");

        ExerciseAssignment assignment = new ExerciseAssignment();
        assignment.setId(assignmentId);
        assignment.setTemplate(template);
        assignment.setOrganization(org);
        assignment.setUser(member);

        submission = new ExerciseSubmission();
        submission.setId(UUID.randomUUID());
        submission.setAssignment(assignment);
        submission.setUser(member);
        submission.setStatus(ExerciseSubmissionStatus.IN_PROGRESS);

        lenient().when(assignmentService.requireAssignmentInOrg(orgId, assignmentId))
                .thenReturn(assignment);
        lenient().when(submissionRepository.findByAssignmentId(assignmentId))
                .thenReturn(Optional.of(submission));
        lenient().when(currentUser.require())
                .thenReturn(new CurrentUser(UUID.randomUUID(), orgId, "Admin", "SUPER_ADMIN"));
    }

    @Test
    void overrideStatus_inProgressToReviewed_stampsTimestampsAndAudits() {
        service.overrideStatus(orgId, assignmentId, ExerciseSubmissionStatus.REVIEWED);

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.REVIEWED);
        assertThat(submission.getSubmittedAt()).isNotNull();
        assertThat(submission.getReviewedAt()).isNotNull();
        verify(auditService).log(any(), eq(orgId),
                eq(OrgAuditActions.EXERCISE_STATUS_OVERRIDDEN),
                eq(OrgAuditActions.ENTITY_EXERCISE_SUBMISSION), eq(submission.getId()),
                anyMap());
    }

    @Test
    void overrideStatus_reviewedBackToInProgress_clearsReviewedAt() {
        submission.setStatus(ExerciseSubmissionStatus.REVIEWED);
        submission.setSubmittedAt(Instant.now());
        submission.setReviewedAt(Instant.now());

        service.overrideStatus(orgId, assignmentId, ExerciseSubmissionStatus.IN_PROGRESS);

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.IN_PROGRESS);
        assertThat(submission.getReviewedAt()).isNull();
    }
}
