package com.bvisionry.programflow.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.SubmissionStatus;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;
import com.bvisionry.programflow.repository.TeamRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Time-driven program-flow notifications, published as {@code common} events
 * for the notification slice's push handler:
 *
 * <ul>
 *   <li><b>Module unlocked</b> — a SCHEDULED module's {@code unlockAt} passed.</li>
 *   <li><b>Task due soon</b> — a LIVE task enters its cohort's due-soon window
 *       and the learner hasn't submitted.</li>
 * </ul>
 *
 * Both are send-once: the row is stamped ({@code unlock_notified_at} /
 * {@code due_reminder_sent_at}) in the same transaction that publishes, so
 * restarts and overlapping schedules can't double-send. Recipients are always
 * the module's cohort enrolment ∩ its audience; FINISHED cohorts stay silent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgramNotificationJob {

    /** Widest allowed due-soon window (ck_program_settings_due_soon caps at 10). */
    private static final int MAX_DUE_SOON_DAYS = 10;

    private final ProgramModuleRepository modules;
    private final ProgramTaskRepository tasks;
    private final ProgramSubmissionRepository submissions;
    private final ProgramSettingsRepository settings;
    private final CohortRepository cohorts;
    private final TeamRepository teams;
    private final ApplicationEventPublisher events;

    @Transactional
    @Scheduled(fixedDelayString = "${bvisionry.program.unlock-notify.interval-ms:300000}",
            initialDelayString = "${bvisionry.program.unlock-notify.initial-delay-ms:60000}")
    @SchedulerLock(name = "ProgramNotificationJob_moduleUnlocks",
            lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void notifyModuleUnlocks() {
        OffsetDateTime now = OffsetDateTime.now();
        for (ProgramModule module : modules
                .findByLockModeAndUnlockAtLessThanEqualAndUnlockNotifiedAtIsNull(
                        ModuleLockMode.SCHEDULED, now)) {
            // Stamp first — even with zero recipients this unlock is now handled.
            module.setUnlockNotifiedAt(now);
            List<UUID> recipients = recipients(module);
            if (!recipients.isEmpty()) {
                events.publishEvent(new ProgramFlowEvents.ModuleUnlocked(module.getName(), recipients));
                log.info("Program module '{}' unlocked — notifying {} learner(s)",
                        module.getName(), recipients.size());
            }
        }
    }

    @Transactional
    @Scheduled(fixedDelayString = "${bvisionry.program.due-reminder.interval-ms:3600000}",
            initialDelayString = "${bvisionry.program.due-reminder.initial-delay-ms:120000}")
    @SchedulerLock(name = "ProgramNotificationJob_dueReminders",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void notifyDueSoonTasks() {
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();
        for (ProgramTask task : tasks.findDueForReminder(
                ProgramTaskStatus.LIVE, today, today.plusDays(MAX_DUE_SOON_DAYS))) {
            ProgramModule module = task.getModule();
            int dueSoonDays = ProgramMapper
                    .toDto(settings.findById(module.getCohortId()).orElse(null))
                    .dueSoonDays();
            if (task.getDueDate().isAfter(today.plusDays(dueSoonDays))) {
                continue; // not inside this cohort's own window yet — retried next run
            }
            task.setDueReminderSentAt(now);
            Set<UUID> alreadySubmitted = submissions.findByTaskIdIn(List.of(task.getId())).stream()
                    .filter(s -> s.getStatus() == SubmissionStatus.SUBMITTED)
                    .map(s -> s.getUserId())
                    .collect(Collectors.toSet());
            List<UUID> recipients = recipients(module).stream()
                    .filter(id -> !alreadySubmitted.contains(id))
                    .toList();
            if (!recipients.isEmpty()) {
                events.publishEvent(new ProgramFlowEvents.TaskDueSoon(
                        task.getId(), task.getName(), task.getDueDate(), recipients));
                log.info("Program task '{}' due {} — reminding {} learner(s)",
                        task.getName(), task.getDueDate(), recipients.size());
            }
        }
    }

    /** Enrolled learners of the module's cohort ∩ the module's audience (ACTIVE cohorts only). */
    // ponytail: no per-learner drip-lock check — a due-soon task inside a module a
    // learner hasn't unlocked yet is an admin scheduling quirk, not worth the
    // per-learner lockState pass here.
    private List<UUID> recipients(ProgramModule module) {
        Cohort cohort = cohorts.findById(module.getCohortId()).orElse(null);
        if (cohort == null || cohort.getStatus() == CohortStatus.FINISHED) {
            return List.of();
        }
        Set<UUID> enrolled = cohort.getMemberIds();
        return teams.findOrgMembers(module.getOrgId()).stream()
                .filter(member -> enrolled.contains(member.getId()))
                .filter(member -> ProgramRules.includes(module, member.getId(), member.getTeamId()))
                .map(OrgMemberRow::getId)
                .toList();
    }
}
