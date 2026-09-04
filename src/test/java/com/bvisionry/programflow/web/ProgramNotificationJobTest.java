package com.bvisionry.programflow.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskType;
import com.bvisionry.programflow.repository.CohortMemberRow;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;
import com.bvisionry.programflow.repository.TaskSpineRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The stamp-vs-defer rule: the send-once stamps ({@code unlock_notified_at} /
 * {@code due_reminder_sent_at}) are consumed only while the cohort is
 * member-visible (LAUNCHED). A cohort hidden by an unlaunch window is SKIPPED
 * — no event, stamp untouched — so the notification fires on a later run
 * instead of being swallowed. A zero-recipient LAUNCHED cohort, by contrast,
 * IS stamped: an empty audience is handled, not deferred.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProgramNotificationJobTest {

    @Mock private ProgramModuleRepository modules;
    @Mock private ProgramTaskRepository tasks;
    @Mock private TaskSpineRepository spine;
    @Mock private ProgramSettingsRepository settings;
    @Mock private CohortRepository cohorts;
    @Mock private CourseVisibilityAccess courseVisibility;
    @Mock private ApplicationEventPublisher events;
    @Mock private CohortMemberRow memberRow;

    private ProgramNotificationJob job;

    private final UUID cohortId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final Cohort cohort = new Cohort();
    private ProgramModule module;
    private ProgramTask task;

    @BeforeEach
    void setUp() {
        job = new ProgramNotificationJob(modules, tasks, spine, settings, cohorts,
                courseVisibility, events);

        cohort.setId(cohortId); // recipientRows keys the roster read on cohort.getId()

        module = new ProgramModule();
        module.setCohortId(cohortId);
        module.setName("Week 3");
        module.setLockMode(ModuleLockMode.SCHEDULED);
        module.setUnlockAt(OffsetDateTime.now().minusHours(1));

        task = new ProgramTask();
        task.setModule(module);
        task.setName("Pricing homework");
        task.setDueDate(LocalDate.now());

        when(cohorts.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(memberRow.getId()).thenReturn(memberId);
        when(memberRow.getOrgId()).thenReturn(UUID.randomUUID());
        when(cohorts.findRoster(cohortId)).thenReturn(List.of(memberRow));
        when(settings.findById(cohortId)).thenReturn(Optional.empty());
        when(spine.usersDoneWithTask(any())).thenReturn(List.of());
        when(modules.findByLockModeAndUnlockAtLessThanEqualAndUnlockNotifiedAtIsNull(
                any(), any())).thenReturn(List.of(module));
        when(tasks.findDueForReminder(any(), any(), any())).thenReturn(List.of(task));
    }

    @Test
    void aDraftCohortsModuleUnlockIsDeferred_notConsumed() {
        cohort.setStatus(CohortStatus.DRAFT);

        job.notifyModuleUnlocks();

        verify(events, never()).publishEvent(any());
        assertThat(module.getUnlockNotifiedAt())
                .as("stamp survives, so the relaunch run still sends").isNull();

        cohort.setStatus(CohortStatus.LAUNCHED);
        job.notifyModuleUnlocks();

        verify(events).publishEvent(any(ProgramFlowEvents.ModuleUnlocked.class));
        assertThat(module.getUnlockNotifiedAt()).isNotNull();
    }

    @Test
    void aDraftCohortsDueReminderIsDeferred_notConsumed() {
        cohort.setStatus(CohortStatus.DRAFT);

        job.notifyDueSoonTasks();

        verify(events, never()).publishEvent(any());
        assertThat(task.getDueReminderSentAt())
                .as("stamp survives, so the relaunch run still reminds").isNull();

        cohort.setStatus(CohortStatus.LAUNCHED);
        job.notifyDueSoonTasks();

        verify(events).publishEvent(any(ProgramFlowEvents.TaskDueSoon.class));
        assertThat(task.getDueReminderSentAt()).isNotNull();
    }

    /** The original "stamp first" intent survives: an empty audience is handled, not retried. */
    @Test
    void aZeroRecipientLaunchedCohortIsStillStamped() {
        cohort.setStatus(CohortStatus.LAUNCHED);
        when(cohorts.findRoster(cohortId)).thenReturn(List.of());

        job.notifyModuleUnlocks();
        job.notifyDueSoonTasks();

        verify(events, never()).publishEvent(any());
        assertThat(module.getUnlockNotifiedAt()).isNotNull();
        assertThat(task.getDueReminderSentAt()).isNotNull();
    }

    /**
     * A SESSION is completed by attendance, not by the member, so a due-soon
     * reminder would nag about work they cannot do. Stamped rather than
     * deferred: nothing will ever make it actionable.
     */
    @Test
    void aSessionTasksDueReminderIsSkipped_butStillStamped() {
        cohort.setStatus(CohortStatus.LAUNCHED);
        task.setTaskType(ProgramTaskType.SESSION);

        job.notifyDueSoonTasks();

        verify(events, never()).publishEvent(any());
        assertThat(task.getDueReminderSentAt()).isNotNull();
    }
}
