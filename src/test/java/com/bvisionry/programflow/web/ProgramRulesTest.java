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
import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.ProgramTaskType;
import com.bvisionry.programflow.dto.JourneyResponse.LockState;
import com.bvisionry.programflow.dto.JourneyTaskState;

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
            assertThat(ProgramRules.includes(m, UUID.randomUUID())).isTrue();
        }

        @Test
        void membersModeRequiresExplicitSelection() {
            ProgramModule m = module(ModuleLockMode.UNLOCKED);
            m.setAssignMode(AudienceMode.MEMBERS);
            UUID userId = UUID.randomUUID();
            m.getMemberIds().add(userId);
            assertThat(ProgramRules.includes(m, userId)).isTrue();
            assertThat(ProgramRules.includes(m, UUID.randomUUID())).isFalse();
        }
    }

    @Nested
    class DripLocking {

        @Test
        void firstModuleIsNeverSequentialLocked() {
            List<ProgramModule> mods = List.of(module(ModuleLockMode.SEQUENTIAL));
            assertThat(ProgramRules.lockState(mods, 0, true, Set.of(), Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void sequentialUnlocksOnlyWhenPreviousLiveTasksAreAllSubmitted() {
            ProgramTask t1 = liveTask();
            List<ProgramModule> mods = List.of(
                    module(ModuleLockMode.UNLOCKED, t1),
                    module(ModuleLockMode.SEQUENTIAL));
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.LOCKED_SEQUENTIAL);
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(t1.getId()), Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void draftTasksDoNotBlockSequentialUnlock() {
            ProgramTask draft = liveTask();
            draft.setStatus(ProgramTaskStatus.DRAFT);
            List<ProgramModule> mods = List.of(
                    module(ModuleLockMode.UNLOCKED, draft),
                    module(ModuleLockMode.SEQUENTIAL));
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void scheduledLocksUntilItsInstant() {
            ProgramModule scheduled = module(ModuleLockMode.SCHEDULED);
            scheduled.setUnlockAt(OffsetDateTime.now().plusDays(2));
            List<ProgramModule> mods = List.of(module(ModuleLockMode.UNLOCKED), scheduled);
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.LOCKED_SCHEDULED);
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), Set.of(), OffsetDateTime.now().plusDays(3)))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void dripDisabledUnlocksEverything() {
            ProgramModule scheduled = module(ModuleLockMode.SCHEDULED);
            scheduled.setUnlockAt(OffsetDateTime.now().plusDays(2));
            assertThat(ProgramRules.lockState(List.of(scheduled), 0, false, Set.of(), Set.of(), OffsetDateTime.now()))
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
    class TypedTaskStates {

        @Test
        void perTypeStatusMapping() {
            // LESSON
            assertThat(ProgramRules.lessonState(null)).isEqualTo(JourneyTaskState.NOT_STARTED);
            assertThat(ProgramRules.lessonState(com.bvisionry.programflow.domain.SubmissionStatus.DRAFT))
                    .isEqualTo(JourneyTaskState.IN_PROGRESS);
            assertThat(ProgramRules.lessonState(com.bvisionry.programflow.domain.SubmissionStatus.SUBMITTED))
                    .isEqualTo(JourneyTaskState.DONE);
            // COURSE
            assertThat(ProgramRules.courseState(null)).isEqualTo(JourneyTaskState.NOT_STARTED);
            assertThat(ProgramRules.courseState("ACTIVE")).isEqualTo(JourneyTaskState.IN_PROGRESS);
            assertThat(ProgramRules.courseState("COMPLETED")).isEqualTo(JourneyTaskState.DONE);
            // EXERCISE
            assertThat(ProgramRules.exerciseState(null)).isEqualTo(JourneyTaskState.NOT_STARTED);
            assertThat(ProgramRules.exerciseState("IN_PROGRESS")).isEqualTo(JourneyTaskState.IN_PROGRESS);
            assertThat(ProgramRules.exerciseState("SUBMITTED")).isEqualTo(JourneyTaskState.SUBMITTED);
            assertThat(ProgramRules.exerciseState("CHANGES_REQUESTED"))
                    .isEqualTo(JourneyTaskState.CHANGES_REQUESTED);
            assertThat(ProgramRules.exerciseState("REVIEWED")).isEqualTo(JourneyTaskState.REVIEWED);
            // ASSESSMENT — evaluation-side statuses collapse to SUBMITTED
            assertThat(ProgramRules.assessmentState(null)).isEqualTo(JourneyTaskState.NOT_STARTED);
            assertThat(ProgramRules.assessmentState("IN_PROGRESS")).isEqualTo(JourneyTaskState.IN_PROGRESS);
            assertThat(ProgramRules.assessmentState("SUBMITTED")).isEqualTo(JourneyTaskState.SUBMITTED);
            assertThat(ProgramRules.assessmentState("NEEDS_REVIEW")).isEqualTo(JourneyTaskState.SUBMITTED);
            assertThat(ProgramRules.assessmentState("FAILED")).isEqualTo(JourneyTaskState.SUBMITTED);
            assertThat(ProgramRules.assessmentState("EVALUATED")).isEqualTo(JourneyTaskState.EVALUATED);
        }

        @Test
        void doneCountsTheMembersSideOfTheWork() {
            assertThat(ProgramRules.done(JourneyTaskState.DONE)).isTrue();
            assertThat(ProgramRules.done(JourneyTaskState.SUBMITTED)).isTrue();
            assertThat(ProgramRules.done(JourneyTaskState.REVIEWED)).isTrue();
            assertThat(ProgramRules.done(JourneyTaskState.EVALUATED)).isTrue();
            assertThat(ProgramRules.done(JourneyTaskState.NOT_STARTED)).isFalse();
            assertThat(ProgramRules.done(JourneyTaskState.IN_PROGRESS)).isFalse();
            // A returned exercise is back with the member — not done.
            assertThat(ProgramRules.done(JourneyTaskState.CHANGES_REQUESTED)).isFalse();
        }
    }

    @Nested
    class TaskTypeValidation {

        private final UUID ref = UUID.randomUUID();

        @Test
        void lessonKeepsFieldsButNeverARefOrMilestone() {
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.LESSON, null, null,
                    ProgramTaskStatus.LIVE, 3)).isEmpty();
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.LESSON, ref, null,
                    ProgramTaskStatus.DRAFT, 0)).containsKey("refId");
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.LESSON, null,
                    MilestoneRole.CHECKIN, ProgramTaskStatus.DRAFT, 0)).containsKey("milestoneRole");
        }

        @Test
        void nonLessonRejectsFormFields() {
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.COURSE, ref, null,
                    ProgramTaskStatus.DRAFT, 1)).containsKey("fields");
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.COURSE, ref, null,
                    ProgramTaskStatus.DRAFT, 0)).isEmpty();
        }

        @Test
        void liveNonLessonNeedsARef_draftDoesNot() {
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.WORKSHOP, null, null,
                    ProgramTaskStatus.LIVE, 0)).containsKey("refId");
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.WORKSHOP, null, null,
                    ProgramTaskStatus.DRAFT, 0)).isEmpty();
        }

        @Test
        void milestoneRoleIsRequiredForAssessmentsAndForbiddenElsewhere() {
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.ASSESSMENT, ref, null,
                    ProgramTaskStatus.DRAFT, 0)).containsKey("milestoneRole");
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.ASSESSMENT, ref,
                    MilestoneRole.DISTANCE, ProgramTaskStatus.LIVE, 0)).isEmpty();
            assertThat(ProgramRules.taskTypeFieldErrors(ProgramTaskType.SURVEY, ref,
                    MilestoneRole.CHECKIN, ProgramTaskStatus.DRAFT, 0)).containsKey("milestoneRole");
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

    @Nested
    class VisibilityBlockedCourses {

        private static ProgramTask courseTask(UUID ref) {
            ProgramTask t = liveTask();
            t.setTaskType(ProgramTaskType.COURSE);
            t.setRefId(ref);
            return t;
        }

        /**
         * Spec §3 downgrade policy: a course the org can no longer SEE cannot be
         * opened, so it must not hold the chain — narrowing a course's visibility
         * mid-cohort would otherwise deadlock every founder behind that module.
         */
        @Test
        void anInvisibleCourseTaskNeitherGatesNorLocksTheNextModule() {
            UUID ref = UUID.randomUUID();
            ProgramTask course = courseTask(ref);
            List<ProgramModule> mods = List.of(
                    module(ModuleLockMode.UNLOCKED, course),
                    module(ModuleLockMode.SEQUENTIAL));

            // Visible and unfinished: it gates, and the next module stays locked.
            assertThat(ProgramRules.gates(course, Set.of())).isTrue();
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), Set.of(), OffsetDateTime.now()))
                    .isEqualTo(LockState.LOCKED_SEQUENTIAL);

            // Blocked: it gates nothing and the chain moves on.
            assertThat(ProgramRules.gates(course, Set.of(ref))).isFalse();
            assertThat(ProgramRules.lockState(mods, 1, true, Set.of(), Set.of(ref),
                    OffsetDateTime.now()))
                    .isEqualTo(LockState.UNLOCKED);
        }

        @Test
        void blockingOneCourseDoesNotUnblockAnother() {
            assertThat(ProgramRules.gates(courseTask(UUID.randomUUID()), Set.of(UUID.randomUUID())))
                    .isTrue();
        }

        @Test
        void aNonCourseTaskIsNeverBlockedByCourseVisibility() {
            ProgramTask lesson = liveTask();
            lesson.setTaskType(ProgramTaskType.LESSON);
            assertThat(ProgramRules.gates(lesson, Set.of(UUID.randomUUID()))).isTrue();
        }
    }
}
