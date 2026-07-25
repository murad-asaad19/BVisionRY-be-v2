package com.bvisionry.exercise;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.exercise.dto.ExerciseRowPayload;
import com.bvisionry.exercise.dto.SaveExerciseRowsRequest;
import com.bvisionry.exercise.entity.ExerciseAssignment;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseColumnType;
import com.bvisionry.exercise.entity.ExerciseRow;
import com.bvisionry.exercise.entity.ExerciseSubmission;
import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExerciseCommentRepository;
import com.bvisionry.exercise.repository.ExerciseRowRepository;
import com.bvisionry.exercise.repository.ExerciseSubmissionRepository;
import com.bvisionry.notification.push.PushNotificationService;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ExerciseSubmissionServiceTest {

    @Mock private ExerciseSubmissionRepository submissionRepository;
    @Mock private ExerciseRowRepository rowRepository;
    @Mock private ExerciseColumnRepository columnRepository;
    @Mock private ExerciseCommentRepository commentRepository;
    @Mock private AuditService auditService;
    @Mock private PushNotificationService pushNotificationService;

    @InjectMocks private ExerciseSubmissionService service;

    private UUID submissionId;
    private UUID userId;
    private UUID columnId;
    private ExerciseSubmission submission;
    private ExerciseRow row;

    @BeforeEach
    void setUp() {
        submissionId = UUID.randomUUID();
        userId = UUID.randomUUID();
        columnId = UUID.randomUUID();

        ExerciseTemplate template = new ExerciseTemplate();
        template.setId(UUID.randomUUID());
        template.setName("Exercise");
        template.setAllowAddRows(true);

        ExerciseColumn column = new ExerciseColumn();
        column.setId(columnId);
        column.setName("Answer");
        column.setType(ExerciseColumnType.TEXT);
        column.setTemplate(template);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        User member = new User();
        member.setId(userId);
        member.setName("Member");
        member.setRole(UserRole.MEMBER);

        ExerciseAssignment assignment = new ExerciseAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setTemplate(template);
        assignment.setOrganization(org);
        assignment.setUser(member);

        submission = new ExerciseSubmission();
        submission.setId(submissionId);
        submission.setAssignment(assignment);
        submission.setUser(member);
        submission.setStatus(ExerciseSubmissionStatus.REVIEWED);
        submission.setReviewedAt(Instant.now());

        row = new ExerciseRow();
        row.setId(UUID.randomUUID());
        row.setSubmission(submission);
        row.setDisplayOrder(0);
        row.setCells(new HashMap<>(Map.of(columnId.toString(), "old value")));

        lenient().when(submissionRepository.findById(submissionId))
                .thenReturn(java.util.Optional.of(submission));
        lenient().when(columnRepository.findByTemplateIdOrderByDisplayOrder(template.getId()))
                .thenReturn(List.of(column));
        lenient().when(rowRepository.findBySubmissionId(submissionId)).thenReturn(List.of(row));
        lenient().when(commentRepository.findCommentedRowIds(submissionId)).thenReturn(List.of());
        lenient().when(commentRepository.findBySubmissionIdOrderByCreatedAt(submissionId))
                .thenReturn(List.of());
    }

    @Test
    void saveRows_changeOnReviewedSubmission_reopensAsSubmitted() {
        SaveExerciseRowsRequest request = new SaveExerciseRowsRequest(List.of(
                new ExerciseRowPayload(row.getId(), Map.of(columnId.toString(), "new value"))));

        service.saveRows(submissionId, userId, request);

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.SUBMITTED);
        assertThat(submission.getReviewedAt()).isNull();
    }

    @Test
    void saveRows_identicalSaveOnReviewedSubmission_staysReviewed() {
        SaveExerciseRowsRequest request = new SaveExerciseRowsRequest(List.of(
                new ExerciseRowPayload(row.getId(), Map.of(columnId.toString(), "old value"))));

        service.saveRows(submissionId, userId, request);

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.REVIEWED);
        assertThat(submission.getReviewedAt()).isNotNull();
    }
}
