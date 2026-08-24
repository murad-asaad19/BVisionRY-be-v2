package com.bvisionry.programflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;
import com.bvisionry.programflow.domain.FieldType;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramSubmission;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskField;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.SubmissionStatus;
import com.bvisionry.programflow.dto.GamificationDto;
import com.bvisionry.programflow.dto.SubmitResponse;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;

@ExtendWith(MockitoExtension.class)
class MyProgramServiceTest {

    @Mock
    private CohortRepository cohorts;
    @Mock
    private ProgramModuleRepository modules;
    @Mock
    private ProgramTaskRepository tasks;
    @Mock
    private ProgramSubmissionRepository submissions;
    @Mock
    private ProgramSettingsRepository settings;
    @Mock
    private com.bvisionry.programflow.repository.TaskSpineRepository spine;
    @Mock private com.bvisionry.common.coursevisibility.CourseVisibilityAccess courseVisibility;
    @Mock
    private CurrentUserAccessor currentUser;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private com.bvisionry.common.media.SubmissionUploadPort submissionUploads;
    @Mock
    private com.bvisionry.common.media.MediaUrlPort mediaUrls;
    @Mock
    private com.bvisionry.common.media.MediaQuotaPort mediaQuota;

    private MyProgramService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private Cohort cohort;
    private ProgramModule module;
    private ProgramTask task;
    private ProgramTaskField requiredShort;

    @BeforeEach
    void setUp() {
        service = new MyProgramService(cohorts, modules, tasks, submissions, settings,
                spine, courseVisibility, currentUser, eventPublisher, submissionUploads, mediaUrls,
                mediaQuota);

        cohort = new Cohort();
        cohort.setId(cohortId);
        cohort.setStatus(CohortStatus.LAUNCHED);
        cohort.getMemberIds().add(userId);

        module = new ProgramModule();
        module.setId(UUID.randomUUID());
        module.setCohortId(cohortId);
        module.setLockMode(ModuleLockMode.UNLOCKED);

        task = new ProgramTask();
        task.setId(UUID.randomUUID());
        task.setModule(module);
        task.setName("Problem Statement v1");
        task.setStatus(ProgramTaskStatus.LIVE);
        task.setDueDate(LocalDate.now().plusDays(3));

        requiredShort = new ProgramTaskField();
        requiredShort.setId(UUID.randomUUID());
        requiredShort.setTask(task);
        requiredShort.setFieldType(FieldType.SHORT);
        requiredShort.setRequired(true);
        task.getFields().add(requiredShort);
        module.getTasks().add(task);

        when(currentUser.require()).thenReturn(new CurrentUser(userId, orgId, "Yousef Amin", "MEMBER"));
        // A DRAFT-task path bails before resolving the cohort/board, so keep these
        // shared stubs lenient — not every test exercises them.
        org.mockito.Mockito.lenient().when(cohorts.findEnrolled(userId)).thenReturn(List.of(cohort));
        org.mockito.Mockito.lenient().when(modules.findByCohortIdOrderByPositionAsc(cohortId)).thenReturn(List.of(module));
        org.mockito.Mockito.lenient().when(submissions.findByUserId(userId)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(tasks.findWithModule(task.getId())).thenReturn(Optional.of(task));
        org.mockito.Mockito.lenient().when(settings.findById(cohortId)).thenReturn(Optional.empty());
    }

    @Test
    void submitRejectsWhenARequiredAnswerIsMissing() {
        assertThatThrownBy(() -> service.submit(task.getId(), Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("required answer");
    }

    @Test
    void firstOnTimeSubmitAwardsBasePlusBonus() {
        when(submissions.findByTaskIdAndUserId(task.getId(), userId)).thenReturn(Optional.empty());
        when(submissions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubmitResponse response = service.submit(task.getId(),
                Map.of(requiredShort.getId().toString(), "We discovered that…"));

        assertThat(response.pointsEarned())
                .isEqualTo(GamificationDto.POINTS_PER_SUBMIT + GamificationDto.ON_TIME_BONUS);
        assertThat(response.submittedAt()).isNotNull();
        assertThat(response.answered()).isEqualTo(1);
        assertThat(response.answerable()).isEqualTo(1);

        // First submit publishes TaskSubmitted carrying the SUBMITTING learner's
        // id — ProgramFlowPushHandler needs it to find their coaches.
        org.mockito.ArgumentCaptor<ProgramFlowEvents.TaskSubmitted> captor =
                org.mockito.ArgumentCaptor.forClass(ProgramFlowEvents.TaskSubmitted.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orgId()).isEqualTo(orgId);
        assertThat(captor.getValue().learnerId()).isEqualTo(userId);
    }

    @Test
    void lateSubmitAwardsBaseOnly() {
        task.setDueDate(LocalDate.now().minusDays(1));
        when(submissions.findByTaskIdAndUserId(task.getId(), userId)).thenReturn(Optional.empty());
        when(submissions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubmitResponse response = service.submit(task.getId(),
                Map.of(requiredShort.getId().toString(), "late but honest"));

        assertThat(response.pointsEarned()).isEqualTo(GamificationDto.POINTS_PER_SUBMIT);
    }

    @Test
    void resubmitBeforeDeadlineNeverReAwardsPoints() {
        ProgramSubmission existing = new ProgramSubmission();
        existing.setTaskId(task.getId());
        existing.setUserId(userId);
        existing.setStatus(SubmissionStatus.SUBMITTED);
        existing.setSubmittedAt(java.time.OffsetDateTime.now().minusHours(2));
        existing.setPointsAwarded(55);
        when(submissions.findByTaskIdAndUserId(task.getId(), userId)).thenReturn(Optional.of(existing));
        when(submissions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubmitResponse response = service.submit(task.getId(),
                Map.of(requiredShort.getId().toString(), "revised answer"));

        assertThat(response.pointsEarned()).isZero();
        assertThat(existing.getPointsAwarded()).isEqualTo(55);
    }

    @Test
    void fileAnswersRejectMarkersOutsideTheMembersOwnPrefix() {
        ProgramTaskField file = new ProgramTaskField();
        file.setId(UUID.randomUUID());
        file.setTask(task);
        file.setFieldType(FieldType.FILE);
        task.getFields().add(file);

        // Another org's prefix, and another member's prefix under the own org —
        // both would presign-resolve someone else's object if accepted.
        String foreignOrg = "minio://bvisionry-media/org/" + UUID.randomUUID()
                + "/submissions/" + userId + "/deck.pdf";
        String foreignUser = "minio://bvisionry-media/org/" + orgId
                + "/submissions/" + UUID.randomUUID() + "/deck.pdf";
        for (String marker : List.of(foreignOrg, foreignUser, "minio://bvisionry-media/pdf/other.pdf")) {
            assertThatThrownBy(() -> service.saveAnswers(task.getId(),
                    Map.of(file.getId().toString(), Map.of("name", "deck.pdf", "url", marker))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("file attachment");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fileAnswersKeepOwnMarkerAndStripTransientPreviewUrl() {
        ProgramTaskField file = new ProgramTaskField();
        file.setId(UUID.randomUUID());
        file.setTask(task);
        file.setFieldType(FieldType.FILE);
        task.getFields().add(file);
        when(submissions.findByTaskIdAndUserId(task.getId(), userId)).thenReturn(Optional.empty());
        when(submissions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String own = "minio://bvisionry-media/org/" + orgId + "/submissions/" + userId + "/deck.pdf";
        service.saveAnswers(task.getId(), Map.of(file.getId().toString(),
                Map.of("name", "deck.pdf", "url", own, "previewUrl", "https://minio.example/presigned")));

        org.mockito.ArgumentCaptor<ProgramSubmission> captor =
                org.mockito.ArgumentCaptor.forClass(ProgramSubmission.class);
        verify(submissions).save(captor.capture());
        Map<String, Object> saved =
                (Map<String, Object>) captor.getValue().getAnswers().get(file.getId().toString());
        assertThat(saved).containsOnlyKeys("name", "url");
        assertThat(saved.get("url")).isEqualTo(own);
    }

    /**
     * The read side is the dangerous half: it turns a stored marker into a
     * presigned GET for whatever key that marker names. It must therefore
     * re-check BOTH ids the write side pinned, not just the trailing user id.
     */
    @Test
    @SuppressWarnings("unchecked")
    void playerMintsAPreviewUrlOnlyForTheOwnersOwnOrgAndUserPrefix() {
        ProgramTaskField file = new ProgramTaskField();
        file.setId(UUID.randomUUID());
        file.setTask(task);
        file.setFieldType(FieldType.FILE);
        task.getFields().add(file);
        requiredShort.setRequired(false);

        // Right user, WRONG org — the shape a pre-sanitizer row or a moved
        // member leaves behind. Resolving it would hand out someone else's file.
        String foreignOrg = "minio://bvisionry-media/org/" + UUID.randomUUID()
                + "/submissions/" + userId + "/deck.pdf";
        ProgramSubmission sub = new ProgramSubmission();
        sub.setTaskId(task.getId());
        sub.setUserId(userId);
        sub.setAnswers(new java.util.LinkedHashMap<>(Map.of(
                file.getId().toString(), new java.util.LinkedHashMap<>(Map.of(
                        "name", "deck.pdf", "url", foreignOrg)))));
        when(submissions.findByTaskIdAndUserId(task.getId(), userId)).thenReturn(Optional.of(sub));

        Map<String, Object> answer = (Map<String, Object>) service.player(task.getId())
                .answers().get(file.getId().toString());
        assertThat(answer).doesNotContainKey("previewUrl");
        org.mockito.Mockito.verify(mediaUrls, org.mockito.Mockito.never()).resolveUrl(any());
    }

    @Test
    void presignIsRefusedOnATaskWithNoFileField() {
        // task carries only `requiredShort` — nothing to attach to, so the
        // presign must never reach the media side (and never bill the quota).
        assertThatThrownBy(() -> service.presignAttachment(task.getId(),
                new com.bvisionry.programflow.dto.PresignAttachmentRequest(
                        "deck.pdf", "application/pdf", 1024L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no file field");
        org.mockito.Mockito.verifyNoInteractions(submissionUploads);
    }

    @Test
    void nonLessonTasksRejectThePlayerAndSubmitPaths() {
        task.setTaskType(com.bvisionry.programflow.domain.ProgramTaskType.COURSE);
        task.setRefId(UUID.randomUUID());
        task.getFields().clear();

        assertThatThrownBy(() -> service.player(task.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("open it instead");
        assertThatThrownBy(() -> service.submit(task.getId(), Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("open it instead");
        assertThatThrownBy(() -> service.saveAnswers(task.getId(), Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("open it instead");
    }

    @Test
    void draftTasksAreInvisibleToLearners() {
        task.setStatus(ProgramTaskStatus.DRAFT);
        assertThatThrownBy(() -> service.player(task.getId()))
                .isInstanceOf(com.bvisionry.common.exception.ResourceNotFoundException.class);
    }

    @Test
    void journeyCountsOnlyLiveTasksAndMySubmissions() {
        ProgramSubmission mine = new ProgramSubmission();
        mine.setTaskId(task.getId());
        mine.setUserId(userId);
        mine.setStatus(SubmissionStatus.SUBMITTED);
        mine.setSubmittedAt(java.time.OffsetDateTime.now());
        mine.setPointsAwarded(55);
        when(submissions.findByUserId(userId)).thenReturn(List.of(mine));

        var journey = service.journey(null);

        assertThat(journey.progress().done()).isEqualTo(1);
        assertThat(journey.progress().total()).isEqualTo(1);
        assertThat(journey.gamification().points()).isEqualTo(55);
        assertThat(journey.gamification().level()).isEqualTo(1);
        assertThat(journey.modules()).hasSize(1);
        assertThat(journey.modules().get(0).tasks().get(0).myStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }
}
