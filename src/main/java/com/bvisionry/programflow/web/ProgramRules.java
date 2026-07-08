package com.bvisionry.programflow.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bvisionry.programflow.domain.FieldType;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramTaskField;
import com.bvisionry.programflow.dto.JourneyResponse.LockState;

/** Pure domain rules shared by the admin and learner services. */
final class ProgramRules {

    private ProgramRules() {
    }

    /** Does this module's audience include the given member? */
    static boolean includes(ProgramModule m, UUID userId, UUID teamId) {
        return switch (m.getAssignMode()) {
            case ALL -> true;
            case TEAMS -> teamId != null && m.getTeamIds().contains(teamId);
            case MEMBERS -> m.getMemberIds().contains(userId);
        };
    }

    /**
     * Drip state of {@code modules.get(index)} for one learner. The first
     * visible module is never sequential-locked; a previous module with no
     * live tasks counts as done (there is nothing to complete in it).
     *
     * @param modules       the learner's visible modules, in board order
     * @param submittedTaskIds live task ids the learner has submitted
     */
    static LockState lockState(List<ProgramModule> modules, int index, boolean dripEnabled,
            Set<UUID> submittedTaskIds, OffsetDateTime now) {
        ProgramModule m = modules.get(index);
        if (!dripEnabled || m.getLockMode() == ModuleLockMode.UNLOCKED) {
            return LockState.UNLOCKED;
        }
        if (m.getLockMode() == ModuleLockMode.SCHEDULED) {
            return m.getUnlockAt() == null || !now.isBefore(m.getUnlockAt())
                    ? LockState.UNLOCKED
                    : LockState.LOCKED_SCHEDULED;
        }
        // SEQUENTIAL
        if (index == 0) {
            return LockState.UNLOCKED;
        }
        boolean previousDone = liveTasks(modules.get(index - 1)).stream()
                .allMatch(t -> submittedTaskIds.contains(t.getId()));
        return previousDone ? LockState.UNLOCKED : LockState.LOCKED_SEQUENTIAL;
    }

    static List<com.bvisionry.programflow.domain.ProgramTask> liveTasks(ProgramModule m) {
        return m.getTasks().stream()
                .filter(t -> t.getStatus() == com.bvisionry.programflow.domain.ProgramTaskStatus.LIVE)
                .toList();
    }

    /** Mirrors the player's per-type "has the learner actually answered" rule. */
    @SuppressWarnings("unchecked")
    static boolean isAnswered(ProgramTaskField f, Object v) {
        if (v == null) {
            return false;
        }
        return switch (f.getFieldType()) {
            case MCQ -> {
                boolean multi = Boolean.TRUE.equals(f.getConfig().get("multi"));
                yield !multi || (v instanceof Collection<?> c && !c.isEmpty());
            }
            case CHECKLIST -> {
                Object items = f.getConfig().get("items");
                int total = items instanceof Collection<?> c ? c.size() : 0;
                yield v instanceof Collection<?> c && total > 0 && c.size() == total;
            }
            case RATING -> v instanceof Number n && n.intValue() > 0;
            case FILE -> !(v instanceof Map<?, ?> m2 && m2.isEmpty()) && !String.valueOf(v).isBlank();
            case SHORT, LONG -> !String.valueOf(v).trim().isEmpty();
            case INSTRUCTIONS, VIDEO -> true;
        };
    }

    /** Required answerable fields with no valid answer. */
    static List<UUID> missingRequired(List<ProgramTaskField> fields, Map<String, Object> answers) {
        return fields.stream()
                .filter(f -> f.getFieldType().answerable() && f.isRequired())
                .filter(f -> !isAnswered(f, answers.get(f.getId().toString())))
                .map(ProgramTaskField::getId)
                .toList();
    }

    /**
     * Consecutive-day submission streak ending today (or yesterday, so the
     * streak survives until the end of the current day).
     */
    static int streak(Collection<OffsetDateTime> submissionTimes, LocalDate today) {
        Set<LocalDate> days = new HashSet<>();
        for (OffsetDateTime t : submissionTimes) {
            if (t != null) {
                // Bucket by the same zone `today` is computed in (the JVM zone the
                // rest of the program uses via LocalDate.now()/OffsetDateTime.now()),
                // so a submission's calendar day lines up with the streak cursor.
                days.add(t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate());
            }
        }
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
