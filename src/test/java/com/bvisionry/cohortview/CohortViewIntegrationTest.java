package com.bvisionry.cohortview;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dedicated cohort view (redesign spec §13.7) and the V176 org-wide coach
 * grain.
 *
 * <p>The load-bearing fixture is a cohort assigned to BOTH orgs with a founder
 * in each: every number the overview and the roster quote must be org A's slice
 * of that roster, never the cohort's total. Org B's founder submits the same
 * milestone task on purpose — if any predicate here drops the org join, his
 * work shows up in org A's completion count and the assertions fail.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class CohortViewIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CoachAccess coachAccess;

    private Organization orgA;
    private Organization orgB;
    private User admin;          // ORG_ADMIN org A
    private User coachCohort;    // COACH org A, whole-cohort grant on the shared cohort
    private User coachHouse;     // COACH org A, ORG-WIDE grant (V176) — no cohort, no member
    private User founderA1;      // MEMBER org A, in the cohort, did the baseline
    private User founderA2;      // MEMBER org A, in the cohort, did nothing
    private User founderB1;      // MEMBER org B, in the SAME cohort, did the baseline
    private UUID cohort;         // assigned to org A and org B
    private UUID otherCohort;    // assigned to org B only
    private UUID emptyCohort;    // assigned to org A, no modules at all
    private UUID baselineTask;
    private UUID moduleOne;
    private UUID moduleTwo;
    private UUID courseId;
    private UUID lessonOne;

    @BeforeEach
    void seed() {
        orgA = saveOrg("Cohort View Org A");
        orgB = saveOrg("Cohort View Org B");
        admin = saveUser("admin.a@test.invalid", UserRole.ORG_ADMIN, orgA);
        coachCohort = saveUser("coach.cohort@test.invalid", UserRole.COACH, orgA);
        coachHouse = saveUser("coach.house@test.invalid", UserRole.COACH, orgA);
        founderA1 = saveUser("founder.a1@test.invalid", UserRole.MEMBER, orgA);
        founderA2 = saveUser("founder.a2@test.invalid", UserRole.MEMBER, orgA);
        founderB1 = saveUser("founder.b1@test.invalid", UserRole.MEMBER, orgB);

        cohort = insertCohort("Growth Cohort", "Scaling past the first ten customers");
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", cohort,
                orgA.getId());
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", cohort,
                orgB.getId());
        enrol(cohort, founderA1, founderA2, founderB1);
        jdbc.update("INSERT INTO program_settings (cohort_id, end_at) VALUES (?, now())", cohort);

        otherCohort = insertCohort("Org B Only", null);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", otherCohort,
                orgB.getId());

        // A cohort org A genuinely runs but has never had a module built on it.
        emptyCohort = insertCohort("Curriculum Pending", null);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", emptyCohort,
                orgA.getId());

        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, NULL)
                """, orgA.getId(), coachCohort.getId(), cohort);
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, NULL, NULL)
                """, orgA.getId(), coachHouse.getId());

        seedProgram();
        seedSession();
        seedScores();
        seedCourse();
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* -------------------------------------------------------------- overview */

    @Nested
    class Overview {

        @Test
        void headerCoachesMilestonesAndActivityAreTheOrgsSliceOnly() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(overview(orgA, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", is("Growth Cohort")))
                    .andExpect(jsonPath("$.description",
                            is("Scaling past the first ten customers")))
                    .andExpect(jsonPath("$.status", is("LAUNCHED")))
                    .andExpect(jsonPath("$.startAt", is("2026-01-05")))
                    .andExpect(jsonPath("$.endAt", org.hamcrest.Matchers.notNullValue()))
                    // No per-cohort seat count exists in the schema — always null.
                    .andExpect(jsonPath("$.seatsTotal", nullValue()))
                    // No distance pair designated in this fixture — both null,
                    // never an empty string standing in for "not set".
                    .andExpect(jsonPath("$.baselinePipelineName", nullValue()))
                    .andExpect(jsonPath("$.distancePipelineName", nullValue()))
                    // both grains, and only the house coach reads as org-wide
                    .andExpect(jsonPath("$.coaches", hasSize(2)))
                    .andExpect(jsonPath("$.coaches[0].name", is("coach.cohort")))
                    .andExpect(jsonPath("$.coaches[0].orgWide", is(false)))
                    .andExpect(jsonPath("$.coaches[1].name", is("coach.house")))
                    .andExpect(jsonPath("$.coaches[1].orgWide", is(true)))
                    // milestones in board order; org B's founder is not in either fraction
                    .andExpect(jsonPath("$.milestones", hasSize(2)))
                    .andExpect(jsonPath("$.milestones[0].name", is("Baseline Check")))
                    .andExpect(jsonPath("$.milestones[0].role", is("BASELINE")))
                    .andExpect(jsonPath("$.milestones[0].totalMembers", is(2)))
                    .andExpect(jsonPath("$.milestones[0].doneCount", is(1)))
                    // MEMBERS-targeted module: audience-excluded members deflate nothing
                    .andExpect(jsonPath("$.milestones[1].name", is("Distance Check")))
                    .andExpect(jsonPath("$.milestones[1].totalMembers", is(1)))
                    .andExpect(jsonPath("$.milestones[1].doneCount", is(0)))
                    // activity: this org's founders only
                    .andExpect(jsonPath("$.activity[*].type", hasItem("TASK_SUBMITTED")))
                    .andExpect(jsonPath("$.activity[*].type", hasItem("SESSION_ATTENDED")))
                    .andExpect(jsonPath("$.activity[*].memberName",
                            everyItem(is("founder.a1"))));
        }

        @Test
        void aCohortThisOrgDoesNotRunIsAbsent() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(overview(orgA, otherCohort))).andExpect(status().isNotFound());
            mockMvc.perform(get(overview(orgA, UUID.randomUUID())))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(founderA1);
            mockMvc.perform(get(overview(orgA, cohort))).andExpect(status().isForbidden());
        }

        /**
         * The header's measurement-pair chip (spec §5 gap A): once an admin
         * designates BOTH sides on the Curriculum tab's Distance card, the
         * overview names both instruments by their pipeline names, not their ids.
         */
        @Test
        void measurementPairReportsTheDesignatedPipelineNames() throws Exception {
            UUID baselinePipeline = UUID.randomUUID();
            UUID distancePipeline = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO pipelines (id, name, status)
                    VALUES (?, 'Founder Mindset Assessment', 'PUBLISHED')
                    """, baselinePipeline);
            jdbc.update("""
                    INSERT INTO pipelines (id, name, status)
                    VALUES (?, 'Founder Mindset Assessment — Distance', 'PUBLISHED')
                    """, distancePipeline);
            jdbc.update("""
                    UPDATE program_settings
                       SET baseline_pipeline_id = ?, distance_pipeline_id = ?
                     WHERE cohort_id = ?
                    """, baselinePipeline, distancePipeline, cohort);

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(overview(orgA, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.baselinePipelineName", is("Founder Mindset Assessment")))
                    .andExpect(jsonPath("$.distancePipelineName",
                            is("Founder Mindset Assessment — Distance")));
        }

        /**
         * A grant asserts nothing about the COACH row it names (see
         * {@code CoachAccess.VISIBLE_COACH_PREDICATE}'s javadoc), so the header
         * query pins org + role + status itself. Suspend one coach, demote the
         * other out of the role, and hand-write a grant naming another tenant's
         * coach: all three chips go with them. Drop those predicates and this
         * goes red.
         */
        @Test
        void aSuspendedDemotedOrForeignCoachIsNotChippedOntoTheHeader() throws Exception {
            jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", coachCohort.getId());
            jdbc.update("UPDATE users SET role = 'MEMBER' WHERE id = ?", coachHouse.getId());
            User foreignCoach = saveUser("coach.b@test.invalid", UserRole.COACH, orgB);
            jdbc.update("""
                    INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                    VALUES (?, ?, ?, NULL)
                    """, orgA.getId(), foreignCoach.getId(), cohort);

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(overview(orgA, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.coaches", hasSize(0)));
        }
    }

    /* --------------------------------------------------------------- outline */

    /**
     * The read twin of the builder's board (spec §13.7): the curriculum an org
     * admin running the cohort may see, and the org slice's progress through it.
     * Same tenancy story as the overview — org B's founder is on this very
     * cohort and has done the baseline, and no fraction here may include him.
     */
    @Nested
    class Outline {

        @Test
        void modulesInBoardOrderWithTheirLiveTasksAndTheOrgsOwnCounts() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(outline(orgA, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohortId", is(cohort.toString())))
                    .andExpect(jsonPath("$.name", is("Growth Cohort")))
                    .andExpect(jsonPath("$.status", is("LAUNCHED")))
                    // 3 LIVE tasks across both modules — the DRAFT is not one of them.
                    .andExpect(jsonPath("$.taskCount", is(3)))
                    // Board order is program_modules.position, NOT insertion order
                    // (Module Two's row was written first on purpose).
                    .andExpect(jsonPath("$.modules", hasSize(2)))
                    .andExpect(jsonPath("$.modules[0].name", is("Module One")))
                    .andExpect(jsonPath("$.modules[0].position", is(0)))
                    .andExpect(jsonPath("$.modules[1].name", is("Module Two")))
                    .andExpect(jsonPath("$.modules[1].position", is(1)))
                    // Module fields carry through as authored.
                    .andExpect(jsonPath("$.modules[0].moduleId", is(moduleOne.toString())))
                    .andExpect(jsonPath("$.modules[0].summary",
                            is("Find the first ten customers")))
                    .andExpect(jsonPath("$.modules[0].pillarLabel", is("Traction")))
                    .andExpect(jsonPath("$.modules[0].lockMode", is("UNLOCKED")))
                    .andExpect(jsonPath("$.modules[0].unlockAt", nullValue()))
                    .andExpect(jsonPath("$.modules[0].audienceMode", is("ALL")))
                    // tasks in t.position within the module
                    .andExpect(jsonPath("$.modules[0].tasks", hasSize(2)))
                    .andExpect(jsonPath("$.modules[0].tasks[0].name", is("Baseline Check")))
                    .andExpect(jsonPath("$.modules[0].tasks[0].position", is(0)))
                    .andExpect(jsonPath("$.modules[0].tasks[0].taskType", is("LESSON")))
                    .andExpect(jsonPath("$.modules[0].tasks[0].milestoneRole", is("BASELINE")))
                    .andExpect(jsonPath("$.modules[0].tasks[0].taskId",
                            is(baselineTask.toString())))
                    .andExpect(jsonPath("$.modules[0].tasks[1].name", is("Ordinary Task")))
                    .andExpect(jsonPath("$.modules[0].tasks[1].position", is(1)))
                    .andExpect(jsonPath("$.modules[0].tasks[1].dueDate", notNullValue()))
                    .andExpect(jsonPath("$.modules[0].tasks[1].milestoneRole", nullValue()));
        }

        /**
         * The highest-value assertion in the outline (§13.7): a cohort spans
         * orgs since V171, and org B's founder submitted the SAME baseline task.
         * Drop the org join from either side of the fraction and 1/2 becomes 2/3.
         */
        @Test
        void doneAndTotalAreThisOrgsSliceOfARosterTheCohortShares() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(outline(orgA, cohort)))
                    .andExpect(status().isOk())
                    // org A has exactly two members on this cohort…
                    .andExpect(jsonPath("$.modules[0].audienceCount", is(2)))
                    .andExpect(jsonPath("$.modules[0].tasks[0].totalMembers", is(2)))
                    // …and only ONE of them did the baseline. founder.b1 did it
                    // too and contributes to neither number.
                    .andExpect(jsonPath("$.modules[0].tasks[0].doneCount", is(1)))
                    .andExpect(jsonPath("$.modules[0].tasks[1].totalMembers", is(2)))
                    .andExpect(jsonPath("$.modules[0].tasks[1].doneCount", is(0)));

            // Read as org B and the same cohort quotes B's slice: one member,
            // one done. Proof the numbers are the org's and not the cohort's.
            TestAuthentication.authenticate(saveUser("admin.b@test.invalid", UserRole.ORG_ADMIN,
                    orgB));
            mockMvc.perform(get(outline(orgB, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modules[0].audienceCount", is(1)))
                    .andExpect(jsonPath("$.modules[0].tasks[0].totalMembers", is(1)))
                    .andExpect(jsonPath("$.modules[0].tasks[0].doneCount", is(1)));
        }

        /**
         * A MEMBERS module reports the reach it actually has in THIS org, and
         * its tasks' denominators agree with it: the excluded member (a2) is
         * dropped from BOTH sides, so narrowing an audience can never deflate
         * the module's own percentage.
         */
        @Test
        void aNarrowedModuleCountsOnlyTheMembersItReaches() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(outline(orgA, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modules[1].audienceMode", is("MEMBERS")))
                    .andExpect(jsonPath("$.modules[1].lockMode", is("SCHEDULED")))
                    .andExpect(jsonPath("$.modules[1].unlockAt", notNullValue()))
                    // a1 is named on the module; a2 is not; b1 is another org's.
                    .andExpect(jsonPath("$.modules[1].audienceCount", is(1)))
                    .andExpect(jsonPath("$.modules[1].tasks", hasSize(1)))
                    .andExpect(jsonPath("$.modules[1].tasks[0].name", is("Distance Check")))
                    .andExpect(jsonPath("$.modules[1].tasks[0].totalMembers", is(1)))
                    .andExpect(jsonPath("$.modules[1].tasks[0].doneCount", is(0)));
        }

        @Test
        void aDraftTaskIsAbsentAndUncounted() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(outline(orgA, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modules[*].tasks[*].name",
                            not(hasItem("Draft Task"))))
                    .andExpect(jsonPath("$.modules[0].tasks", hasSize(2)))
                    .andExpect(jsonPath("$.taskCount", is(3)));
        }

        @Test
        void aCohortWithNoModulesIsAnEmptyOutline_notA404() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(outline(orgA, emptyCohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", is("Curriculum Pending")))
                    .andExpect(jsonPath("$.modules", hasSize(0)))
                    .andExpect(jsonPath("$.taskCount", is(0)));
        }

        @Test
        void aCohortThisOrgDoesNotRunIsAbsent() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(outline(orgA, otherCohort))).andExpect(status().isNotFound());
            mockMvc.perform(get(outline(orgA, UUID.randomUUID())))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(founderA1);
            mockMvc.perform(get(outline(orgA, cohort))).andExpect(status().isForbidden());
        }
    }

    /* ---------------------------------------------------------------- roster */

    @Nested
    class Roster {

        @Test
        void onlyThisOrgsMembersWithTheirOwnAudienceCounts() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(roster(orgA, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members", hasSize(2)))
                    .andExpect(jsonPath("$.members[*].name", not(hasItem("founder.b1"))))
                    // a1: 3 audience tasks (both modules), baseline done, 1 overdue
                    .andExpect(jsonPath("$.members[0].name", is("founder.a1")))
                    .andExpect(jsonPath("$.members[0].memberType", is("LEADER")))
                    .andExpect(jsonPath("$.members[0].progressTotal", is(3)))
                    .andExpect(jsonPath("$.members[0].progressDone", is(1)))
                    .andExpect(jsonPath("$.members[0].overdueCount", is(1)))
                    .andExpect(jsonPath("$.members[0].friLatest", is(66.50)))
                    .andExpect(jsonPath("$.members[0].friDelta", is(12.25)))
                    .andExpect(jsonPath("$.members[0].lastActivityAt",
                            org.hamcrest.Matchers.notNullValue()))
                    // a2: outside the targeted module, so only 2 tasks — and no scores
                    .andExpect(jsonPath("$.members[1].name", is("founder.a2")))
                    .andExpect(jsonPath("$.members[1].progressTotal", is(2)))
                    .andExpect(jsonPath("$.members[1].progressDone", is(0)))
                    .andExpect(jsonPath("$.members[1].friLatest", nullValue()))
                    .andExpect(jsonPath("$.members[1].friDelta", nullValue()));
        }

        @Test
        void aCohortThisOrgDoesNotRunIsAbsent() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(roster(orgA, otherCohort))).andExpect(status().isNotFound());
        }

        /**
         * The third needs-attention reason (spec §13.7 gap B): a SUBMITTED
         * exercise on one of this cohort's LIVE tasks counts as awaiting review;
         * a member with no such submission reads zero, not null or absent.
         */
        @Test
        void awaitingReviewCountsSubmittedExercisesOnThisCohortsLiveTasks() throws Exception {
            UUID exerciseTask = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_tasks (id, module_id, name, status, position, task_type)
                    VALUES (?, ?, 'Pitch Deck Exercise', 'LIVE', 2, 'EXERCISE')
                    """, exerciseTask, moduleOne);

            UUID template = UUID.randomUUID();
            jdbc.update("INSERT INTO exercise_templates (id, name, created_by) VALUES (?, 'Pitch Deck', ?)",
                    template, admin.getId());

            UUID assignment = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO exercise_assignments (id, template_id, organization_id, user_id,
                                                      assigned_by, program_task_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, assignment, template, orgA.getId(), founderA1.getId(), admin.getId(),
                    exerciseTask);
            jdbc.update("""
                    INSERT INTO exercise_submissions (assignment_id, user_id, status, submitted_at)
                    VALUES (?, ?, 'SUBMITTED', now())
                    """, assignment, founderA1.getId());

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(roster(orgA, cohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members[0].name", is("founder.a1")))
                    .andExpect(jsonPath("$.members[0].awaitingReview", is(1)))
                    .andExpect(jsonPath("$.members[1].name", is("founder.a2")))
                    .andExpect(jsonPath("$.members[1].awaitingReview", is(0)));
        }
    }

    /* ------------------------------------------------------- course progress */

    @Nested
    class CourseProgress {

        @Test
        void enrolledMemberGetsLiveProgressAndPerLessonCompletion() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(progress(orgA, founderA1, courseId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title", is("Pricing Foundations")))
                    .andExpect(jsonPath("$.status", is("ACTIVE")))
                    // one of two lessons ticked — live, not the cached column
                    .andExpect(jsonPath("$.progressPct", is(50)))
                    .andExpect(jsonPath("$.certificateIssued", is(false)))
                    .andExpect(jsonPath("$.sections", hasSize(1)))
                    .andExpect(jsonPath("$.sections[0].title", is("Getting Started")))
                    .andExpect(jsonPath("$.sections[0].lessons", hasSize(2)))
                    .andExpect(jsonPath("$.sections[0].lessons[0].title", is("Why price")))
                    .andExpect(jsonPath("$.sections[0].lessons[0].completed", is(true)))
                    .andExpect(jsonPath("$.sections[0].lessons[0].completedAt",
                            org.hamcrest.Matchers.notNullValue()))
                    .andExpect(jsonPath("$.sections[0].lessons[1].completed", is(false)));
        }

        @Test
        void unenrolledMemberStillGetsTheSkeleton() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(progress(orgA, founderA2, courseId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", nullValue()))
                    .andExpect(jsonPath("$.progressPct", nullValue()))
                    .andExpect(jsonPath("$.enrolledAt", nullValue()))
                    .andExpect(jsonPath("$.sections[0].lessons", hasSize(2)))
                    .andExpect(jsonPath("$.sections[0].lessons[0].completed", is(false)));
        }

        @Test
        void foreignMemberAndUnknownCourseAreAbsent() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(progress(orgA, founderB1, courseId)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(progress(orgA, founderA1, UUID.randomUUID())))
                    .andExpect(status().isNotFound());
        }
    }

    /* ------------------------------------------------- V176 org-wide grain */

    @Nested
    class OrgWideCoachGrant {

        @Test
        void theHouseCoachSeesEveryMemberOfTheOrgAndNobodyElses() {
            // both-null grant: every member of org A, including the one no
            // cohort grant covers…
            assertThat(coachAccess.coachSees(orgA.getId(), coachHouse.getId(), founderA1.getId()))
                    .isTrue();
            assertThat(coachAccess.coachSees(orgA.getId(), coachHouse.getId(), founderA2.getId()))
                    .isTrue();
            // …and NOT another org's member, even one in the very same cohort.
            assertThat(coachAccess.coachSees(orgA.getId(), coachHouse.getId(), founderB1.getId()))
                    .isFalse();
            assertThat(coachAccess.coachSees(orgB.getId(), coachHouse.getId(), founderB1.getId()))
                    .isFalse();
        }

        @Test
        void aSuspendedMemberDropsOutDespiteTheWidestGrant() {
            jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", founderA1.getId());
            assertThat(coachAccess.coachSees(orgA.getId(), coachHouse.getId(), founderA1.getId()))
                    .isFalse();
        }

        /**
         * {@code bool_and(ca.cohort_id IS NULL)}: the overview's "house coach"
         * chip is about THIS cohort. Give the org-wide coach a cohort grant on
         * it as well and they stop being the house coach here — they are this
         * cohort's coach, named on it, and the chip must say so. Swap the
         * aggregate for {@code bool_or} and this goes red.
         */
        @Test
        void holdingACohortGrantAsWellStopsReadingAsTheHouseCoach() throws Exception {
            jdbc.update("""
                    INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                    VALUES (?, ?, ?, NULL)
                    """, orgA.getId(), coachHouse.getId(), cohort);

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(overview(orgA, cohort)))
                    .andExpect(status().isOk())
                    // Still listed once — the grants are grouped per coach…
                    .andExpect(jsonPath("$.coaches", hasSize(2)))
                    .andExpect(jsonPath("$.coaches[1].name", is("coach.house")))
                    // …and no longer org-wide AS FAR AS THIS COHORT GOES.
                    .andExpect(jsonPath("$.coaches[1].orgWide", is(false)));

            // A cohort they hold no grant on still reads them as the house coach:
            // the flag is per cohort, not a property of the coach.
            mockMvc.perform(get(overview(orgA, emptyCohort)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.coaches", hasSize(1)))
                    .andExpect(jsonPath("$.coaches[0].name", is("coach.house")))
                    .andExpect(jsonPath("$.coaches[0].orgWide", is(true)));
        }

        @Test
        void theProfileReportsTheGrainRatherThanGuessingItFromANullCohort() throws Exception {
            TestAuthentication.authenticate(admin);
            // founderA2 is covered by the cohort grant AND the org-wide grant;
            // neither is a DIRECT grant, which is what a null cohort_id used to
            // be taken to mean.
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/members/"
                            + founderA2.getId() + "/founder-profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.coaches", hasSize(2)))
                    .andExpect(jsonPath("$.header.coaches[0].name", is("coach.cohort")))
                    .andExpect(jsonPath("$.header.coaches[0].direct", is(false)))
                    .andExpect(jsonPath("$.header.coaches[0].orgWide", is(false)))
                    .andExpect(jsonPath("$.header.coaches[0].cohortIds", hasSize(1)))
                    .andExpect(jsonPath("$.header.coaches[1].name", is("coach.house")))
                    .andExpect(jsonPath("$.header.coaches[1].direct", is(false)))
                    .andExpect(jsonPath("$.header.coaches[1].orgWide", is(true)))
                    .andExpect(jsonPath("$.header.coaches[1].cohortIds", hasSize(0)));
        }
    }

    /* ------------------------------------------------------------- seeding */

    private String overview(Organization org, UUID cohortId) {
        return "/api/organizations/" + org.getId() + "/cohorts/" + cohortId + "/overview";
    }

    private String outline(Organization org, UUID cohortId) {
        return "/api/organizations/" + org.getId() + "/cohorts/" + cohortId + "/outline";
    }

    private String roster(Organization org, UUID cohortId) {
        return "/api/organizations/" + org.getId() + "/cohorts/" + cohortId + "/roster";
    }

    private String progress(Organization org, User member, UUID course) {
        return "/api/organizations/" + org.getId() + "/members/" + member.getId() + "/courses/"
                + course + "/progress";
    }

    private Organization saveOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        return organizationRepository.saveAndFlush(org);
    }

    private User saveUser(String email, UserRole role, Organization org) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return userRepository.saveAndFlush(user);
    }

    private UUID insertCohort(String name, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cohorts (id, name, status, description, launched_at)
                VALUES (?, ?, 'LAUNCHED', ?, timestamptz '2026-01-05 09:00:00+00')
                """, id, name, description);
        return id;
    }

    private void enrol(UUID cohortId, User... members) {
        for (User member : members) {
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    cohortId, member.getId());
        }
    }

    /**
     * Module One (ALL, with a summary + pillar chip): the BASELINE milestone —
     * a1 and b1 both submit it — plus an overdue ordinary task and a DRAFT the
     * outline must never publish. Module Two (MEMBERS → a1 only, SCHEDULED):
     * the DISTANCE milestone, nobody done.
     *
     * <p>The board is deliberately seeded OUT of position order (Module Two's
     * row is written first) so "board order" cannot pass on insertion order.
     */
    private void seedProgram() {
        moduleTwo = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, position, assign_mode, lock_mode,
                                             unlock_at)
                VALUES (?, ?, 'Module Two', 1, 'MEMBERS', 'SCHEDULED',
                        timestamptz '2026-02-01 09:00:00+00')
                """, moduleTwo, cohort);
        jdbc.update("INSERT INTO program_module_members (module_id, user_id) VALUES (?, ?)",
                moduleTwo, founderA1.getId());
        jdbc.update("""
                INSERT INTO program_tasks (module_id, name, status, position, milestone_role)
                VALUES (?, 'Distance Check', 'LIVE', 0, 'DISTANCE')
                """, moduleTwo);

        moduleOne = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, summary, pillar_label, position,
                                             assign_mode, lock_mode)
                VALUES (?, ?, 'Module One', 'Find the first ten customers', 'Traction', 0,
                        'ALL', 'UNLOCKED')
                """, moduleOne, cohort);
        baselineTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, milestone_role)
                VALUES (?, ?, 'Baseline Check', 'LIVE', 0, 'BASELINE')
                """, baselineTask, moduleOne);
        jdbc.update("""
                INSERT INTO program_tasks (module_id, name, status, position, due_date)
                VALUES (?, 'Ordinary Task', 'LIVE', 1, CURRENT_DATE - 3)
                """, moduleOne);
        // The builder's business, never the org admin's: a DRAFT task must not
        // appear in the outline nor be counted by it.
        jdbc.update("""
                INSERT INTO program_tasks (module_id, name, status, position)
                VALUES (?, 'Draft Task', 'DRAFT', 2)
                """, moduleOne);

        for (User member : new User[] {founderA1, founderB1}) {
            jdbc.update("""
                    INSERT INTO program_submissions (task_id, user_id, status, submitted_at)
                    VALUES (?, ?, 'SUBMITTED', now())
                    """, baselineTask, member.getId());
        }
    }

    /** One held session; a1 attended, b1 did not. */
    private void seedSession() {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sessions (id, cohort_id, type, title, session_date)
                VALUES (?, ?, 'WORKSHOP', 'Pricing Workshop', now() - interval '1 day')
                """, sessionId, cohort);
        jdbc.update("INSERT INTO session_attendance (session_id, member_id) VALUES (?, ?)",
                sessionId, founderA1.getId());
    }

    /** a1's evaluated overall (66.50) and this cohort's stored comparison (+12.25). */
    private void seedScores() {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status) VALUES (?, 'Founder Mindset', 'PUBLISHED')",
                pipelineId);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, orgA.getId(), founderA1.getId(), admin.getId());

        UUID baseline = UUID.randomUUID();
        UUID distance = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now() - interval '30 days', now() - interval '30 days')
                """, baseline, assignmentId, founderA1.getId());
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now(), now())
                """, distance, assignmentId, founderA1.getId());
        jdbc.update("INSERT INTO overall_summaries (submission_id, overall_score_percentage) VALUES (?, 54.25)",
                baseline);
        jdbc.update("INSERT INTO overall_summaries (submission_id, overall_score_percentage) VALUES (?, 66.50)",
                distance);
        jdbc.update("""
                INSERT INTO founder_comparisons (cohort_id, org_id, user_id, baseline_submission_id,
                                                 distance_submission_id, overall_delta, computed_at)
                VALUES (?, ?, ?, ?, ?, 12.25, now())
                """, cohort, orgA.getId(), founderA1.getId(), baseline, distance);
    }

    /** One two-lesson course; a1 enrolled with the first lesson ticked, a2 not enrolled. */
    private void seedCourse() {
        courseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO course (id, org_id, slug, title, state)
                VALUES (?, ?, ?, 'Pricing Foundations', 'PUBLISHED')
                """, courseId, orgA.getId(), "pricing-" + courseId);
        UUID sectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO section (id, org_id, course_id, title, sequence)
                VALUES (?, ?, ?, 'Getting Started', 0)
                """, sectionId, orgA.getId(), courseId);
        lessonOne = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO content (id, org_id, section_id, title, sequence)
                VALUES (?, ?, ?, 'Why price', 0)
                """, lessonOne, orgA.getId(), sectionId);
        jdbc.update("""
                INSERT INTO content (id, org_id, section_id, title, sequence)
                VALUES (?, ?, ?, 'How to price', 1)
                """, UUID.randomUUID(), orgA.getId(), sectionId);

        UUID enrollmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO enrollment (id, user_id, course_id, status, progress_pct)
                VALUES (?, ?, ?, 'ACTIVE', 0)
                """, enrollmentId, founderA1.getId(), courseId);
        jdbc.update("""
                INSERT INTO content_progress (enrollment_id, content_id, completed, completed_at)
                VALUES (?, ?, TRUE, now())
                """, enrollmentId, lessonOne);
    }
}
