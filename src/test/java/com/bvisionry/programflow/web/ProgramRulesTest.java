package com.bvisionry.programflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.bvisionry.programflow.domain.AudienceMode;
import com.bvisionry.programflow.domain.FieldType;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskField;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.dto.JourneyResponse.LockState;

class ProgramRulesTest {

    private static ProgramTaskField field(FieldType type, boolean required, Map<String, Object> config) {
        ProgramTaskField f = new ProgramTaskField();
        f.setId(UUID.randomUUID());
        f.setFieldType(type);
        f.setRequired(required);
        f.setConfig(new java.util.LinkedHashMap<>(config));
        return f;
    }

    private static ProgramModule module(ModuleLockMode lock, ProgramTask... tasks) {
        ProgramModule m = new ProgramModule();
        m.setId(UUID.randomUUID());
        m.setLockMode(lock);
        for (ProgramTask t : tasks) {
            m.getTasks().add(t);
        }
        return m;
    }

    private static ProgramTask liveTask() {
        ProgramTask t = new ProgramTask();
        t.setId(UUID.randomUUID());
        t.setStatus(ProgramTaskStatus.LIVE);
        return t;
    }

    @Nested
    class Audience {

        @Test
        void allModeIncludesEveryone() {
            ProgramModule m = module(ModuleLockMode.UNLOCKED);
            m.setAssignMode(AudienceMode.ALL);
            assertThat(ProgramRules.includes(m, UUID.randomUUID(), null)).isTrue();
        }

        @Test
        void teamsModeRequiresMembershipOfASelectedTeam() {
            ProgramModule m = module(ModuleLockMode.UNLOCKED);
            m.setAssignMode(AudienceMode.TEAMS);
            UUID teamId = UUID.randomUUID();
            m.getTeamIds().add(teamId);
            assertThat(ProgramRules.includes(m, UUID.randomUUID(), teamId)).isTrue();
            assertThat(ProgramRules.includes(m, UUID.randomUUID(), UUID.randomUUID())).isFalse();
            assertThat(ProgramRules.includes(m, UUID.randomUUID(), null)).isFalse();
        }

        @Test
        void membersModeRequiresExplicitSelection() {
            ProgramModule m = module(ModuleLockMode.UNLOCKED);
            m.setAssignMode(AudienceMode.MEMBERS);
            UUID userId = UUID.randomUUID();
            m.getMemberIds().add(userId);
            assertThat(ProgramRules.includes(m, userId, null)).isTrue();
            assertThat(ProgramRules.includes(m, UUID.randomUUID(), null)).isFalse();
        }
    }

    @Nested
    class DripLocking {

        @Test
        void firstModuleIsNeverSequentialLocked() {
            List<ProgramModule> mods = List.of(module(ModuleLockMode.SEQUENTIAL));
            assertThat(ProgramRules.lockState(mods, 0, true, Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void sequentialUnlocksOnlyWhenPreviousLiveTasksAreAllSubmitted() {
            ProgramTask t1 = liveTask();
            List<ProgramModule> mods = List.of(
                    module(ModuleLockMode.UNLOCKED, t1),
                    module(ModuleLockMode.SEQUENTIAL));
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.LOCKED_SEQUENTIAL);
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(t1.getId()), OffsetDateTime.now()))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void draftTasksDoNotBlockSequentialUnlock() {
            ProgramTask draft = liveTask();
            draft.setStatus(ProgramTaskStatus.DRAFT);
            List<ProgramModule> mods = List.of(
                    module(ModuleLockMode.UNLOCKED, draft),
                    module(ModuleLockMode.SEQUENTIAL));
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void scheduledLocksUntilItsInstant() {
            ProgramModule scheduled = module(ModuleLockMode.SCHEDULED);
            scheduled.setUnlockAt(OffsetDateTime.now().plusDays(2));
            List<ProgramModule> mods = List.of(module(ModuleLockMode.UNLOCKED), scheduled);
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.LOCKED_SCHEDULED);
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), OffsetDateTime.now().plusDays(3)))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void dripDisabledUnlocksEverything() {
            ProgramModule scheduled = module(ModuleLockMode.SCHEDULED);
            scheduled.setUnlockAt(OffsetDateTime.now().plusDays(2));
            assertThat(ProgramRules.lockState(List.of(scheduled), 0, false, Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.UNLOCKED);
        }
    }

    @Nested
    class Answers {

        @Test
        void textAnswersMustBeNonBlank() {
            ProgramTaskField f = field(FieldType.SHORT, true, Map.of());
            assertThat(ProgramRules.isAnswered(f, null)).isFalse();
            assertThat(ProgramRules.isAnswered(f, "  ")).isFalse();
            assertThat(ProgramRules.isAnswered(f, "a statement")).isTrue();
        }

        @Test
        void multiMcqNeedsAtLeastOneSelection() {
            ProgramTaskField f = field(FieldType.MCQ, true, Map.of("multi", true, "options", List.of("a", "b")));
            assertThat(ProgramRules.isAnswered(f, List.of())).isFalse();
            assertThat(ProgramRules.isAnswered(f, List.of(1))).isTrue();
        }

        @Test
        void singleMcqAnyPresentIndexCounts() {
            ProgramTaskField f = field(FieldType.MCQ, true, Map.of("multi", false, "options", List.of("a", "b")));
            assertThat(ProgramRules.isAnswered(f, 0)).isTrue();
            assertThat(ProgramRules.isAnswered(f, null)).isFalse();
        }

        @Test
        void checklistRequiresEveryItemConfirmed() {
            ProgramTaskField f = field(FieldType.CHECKLIST, true, Map.of("items", List.of("x", "y", "z")));
            assertThat(ProgramRules.isAnswered(f, List.of(0, 1))).isFalse();
            assertThat(ProgramRules.isAnswered(f, List.of(0, 1, 2))).isTrue();
        }

        @Test
        void ratingMustBePositive() {
            ProgramTaskField f = field(FieldType.RATING, true, Map.of("scale", 5));
            assertThat(ProgramRules.isAnswered(f, 0)).isFalse();
            assertThat(ProgramRules.isAnswered(f, 4)).isTrue();
        }

        @Test
        void missingRequiredSkipsOptionalAndContentFields() {
            ProgramTaskField instructions = field(FieldType.INSTRUCTIONS, true, Map.of("text", "read me"));
            ProgramTaskField requiredShort = field(FieldType.SHORT, true, Map.of());
            ProgramTaskField optionalLong = field(FieldType.LONG, false, Map.of());
            List<UUID> missing = ProgramRules.missingRequired(
                    List.of(instructions, requiredShort, optionalLong), Map.of());
            assertThat(missing).containsExactly(requiredShort.getId());
        }
    }

    @Nested
    class Streaks {

        @Test
        void countsConsecutiveDaysEndingTodayOrYesterday() {
            LocalDate today = LocalDate.of(2026, 7, 4);
            OffsetDateTime base = today.atTime(10, 0).atOffset(java.time.ZoneOffset.UTC);
            assertThat(ProgramRules.streak(List.of(base, base.minusDays(1), base.minusDays(2)), today))
                    .isEqualTo(3);
            // gap two days ago breaks the run
            assertThat(ProgramRules.streak(List.of(base, base.minusDays(2)), today)).isEqualTo(1);
            // nothing today: yesterday's streak survives
            assertThat(ProgramRules.streak(List.of(base.minusDays(1), base.minusDays(2)), today)).isEqualTo(2);
            assertThat(ProgramRules.streak(List.of(), today)).isZero();
        }
    }
}
