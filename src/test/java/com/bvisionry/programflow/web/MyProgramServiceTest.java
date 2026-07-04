package com.bvisionry.programflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;
import com.bvisionry.programflow.repository.TeamRepository;

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
    private TeamRepository teams;
    @Mock
    private CurrentUserAccessor currentUser;

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
        service = new MyProgramService(cohorts, modules, tasks, submissions, settings, teams, currentUser);

        cohort = new Cohort();
        cohort.setId(cohortId);
        cohort.setOrgId(orgId);
        cohort.setStatus(CohortStatus.ACTIVE);
        cohort.getMemberIds().add(userId);

        module = new ProgramModule();
        module.setId(UUID.randomUUID());
        module.setOrgId(orgId);
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
        org.mockito.Mockito.lenient().when(teams.findOrgMembers(orgId)).thenReturn(List.of(memberRow(userId, "Yousef Amin")));
        org.mockito.Mockito.lenient().when(modules.findByCohortIdOrderByPositionAsc(cohortId)).thenReturn(List.of(module));
        org.mockito.Mockito.lenient().when(submissions.findByUserId(userId)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(tasks.findWithModule(task.getId())).thenReturn(Optional.of(task));
        org.mockito.Mockito.lenient().when(settings.findById(cohortId)).thenReturn(Optional.empty());
    }

    private static OrgMemberRow memberRow(UUID id, String name) {
        return new OrgMemberRow() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getEmail() {
                return "user@example.com";
            }

            @Override
            public UUID getTeamId() {
                return null;
            }
        };
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
