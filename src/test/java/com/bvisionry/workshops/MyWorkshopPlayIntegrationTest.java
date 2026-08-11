package com.bvisionry.workshops;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import com.bvisionry.workshops.domain.*;
import com.bvisionry.workshops.dto.*;
import com.bvisionry.workshops.repository.*;
import com.bvisionry.workshops.web.MyWorkshopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.bvisionry.testsupport.TestAuthentication.authenticate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Pins {@link MyWorkshopService}'s access boundary and the load-bearing play
 * state transitions against a real schema:
 *
 * <ul>
 *   <li>Access: non-members, wrong-org users and DRAFT workshops all resolve
 *       to not-found — membership and org are checked before any play state
 *       is exposed.</li>
 *   <li>State machine: tasks must be started before they accept submissions;
 *       a member's tasks stay locked until the lead shares; completing the
 *       lead's LAST lead task (and only that) shares the run; SORT grading is
 *       server-side and merges retries; QUESTION answers freeze once
 *       completed; a member can never write to a LEAD task's shared
 *       submission; FINISHED workshops reject all mutations.</li>
 *   <li>Standalone: a workshop needs no cohort — an org with zero cohorts can
 *       own, assign and run one end to end ({@code workshopRunsWithoutAnyCohort}).</li>
 * </ul>
 *
 * <p>Fixture: one team (lead + member) playing one exercise with the standard
 * pipeline SORT(LEAD) → WEIGHT(LEAD) → TOP(LEAD) → QUESTION(MEMBER), two cards
 * (c1 correct-left, c2 correct-right).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional // each test rolls back, so fixture emails can't collide across methods
@EnabledIfDockerAvailable
class MyWorkshopPlayIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MyWorkshopService service;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private WorkshopRepository workshops;
    @Autowired private WorkshopTeamRepository teams;
    @Autowired private WorkshopExerciseRepository exercises;
    @Autowired private WorkshopExerciseTaskRepository tasks;
    @Autowired private WorkshopExerciseRunRepository runs;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    // ------------------------------------------------------------ access

    @Test
    void play_userNotOnAnyTeam_isNotFound() {
        Fixture f = fixture(WorkshopStatus.ACTIVE);
        User outsider = newUser("outsider@play.invalid", f.org());
        authenticate(outsider);

        assertThatThrownBy(() -> service.play(f.workshop().getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void play_userFromAnotherOrg_isNotFound() {
        Fixture f = fixture(WorkshopStatus.ACTIVE);
        Organization other = newOrg("Other Org");
        User foreign = newUser("foreign@play.invalid", other);
        authenticate(foreign);

        assertThatThrownBy(() -> service.play(f.workshop().getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void play_draftWorkshop_isHiddenEvenFromItsOwnMembers() {
        Fixture f = fixture(WorkshopStatus.DRAFT);
        authenticate(f.lead());

        assertThatThrownBy(() -> service.play(f.workshop().getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------ state machine

    @Test
    void start_finishedWorkshop_isRejected() {
        Fixture f = fixture(WorkshopStatus.FINISHED);
        authenticate(f.lead());

        assertThatThrownBy(() -> service.start(f.workshop().getId(), f.sort().getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("finished");
    }

    @Test
    void memberTasks_stayLockedUntilLeadShares() {
        Fixture f = fixture(WorkshopStatus.ACTIVE);
        authenticate(f.member());

        assertThat(service.play(f.workshop().getId()).view()).isEqualTo("WAITING");
        assertThatThrownBy(() -> service.start(f.workshop().getId(), f.question().getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("hasn't shared");
    }

    @Test
    void submitSort_beforeStart_isRejected() {
        Fixture f = fixture(WorkshopStatus.ACTIVE);
        authenticate(f.lead());

        assertThatThrownBy(() -> service.submitSort(f.workshop().getId(), f.sort().getId(),
                new SubmitSortRequest(Map.of("c1", "left", "c2", "right"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Start the task first");
    }

    @Test
    void submitSort_gradesServerSide_andRetryMergesPreviousChoices() {
        Fixture f = fixture(WorkshopStatus.ACTIVE);
        authenticate(f.lead());
        service.start(f.workshop().getId(), f.sort().getId());

        // c1 belongs left — sorting it right leaves one wrong card.
        SortResultDto wrong = service.submitSort(f.workshop().getId(), f.sort().getId(),
                new SubmitSortRequest(Map.of("c1", "right", "c2", "right")));
        assertThat(wrong.allCorrect()).isFalse();
        assertThat(wrong.wrongCount()).isEqualTo(1);
        assertThat(wrong.attempts()).isEqualTo(1);

        // The retry only re-sorts the wrong card; the kept c2 choice merges in.
        SortResultDto fixed = service.submitSort(f.workshop().getId(), f.sort().getId(),
                new SubmitSortRequest(Map.of("c1", "left")));
        assertThat(fixed.allCorrect()).isTrue();
        assertThat(fixed.attempts()).isEqualTo(2);

        // Completing the sort is NOT the share trigger — WEIGHT and TOP lead
        // tasks are still open, so the member keeps waiting.
        assertThat(sharedAt(f)).isNull();
        authenticate(f.member());
        assertThat(service.play(f.workshop().getId()).view()).isEqualTo("WAITING");
    }

    @Test
    void lastLeadTask_sharesRun_thenMemberAnswersExactlyOnce() {
        Fixture f = fixture(WorkshopStatus.ACTIVE);
        authenticate(f.lead());
        service.start(f.workshop().getId(), f.sort().getId());
        service.submitSort(f.workshop().getId(), f.sort().getId(),
                new SubmitSortRequest(Map.of("c1", "left", "c2", "right")));
        service.start(f.workshop().getId(), f.weight().getId());
        service.submitWeights(f.workshop().getId(), f.weight().getId(),
                new SubmitWeightsRequest(Map.of("c1", 70)));
        assertThat(sharedAt(f)).as("not shared until the last LEAD task").isNull();

        service.start(f.workshop().getId(), f.top().getId());
        service.completeTop(f.workshop().getId(), f.top().getId());
        assertThat(sharedAt(f)).as("completing the last LEAD task shares the run").isNotNull();

        // The member's QUESTION task is now unlocked; it presents the shared
        // top cards (the sort's left pile: c1) and requires an answer for each.
        authenticate(f.member());
        PlayResponse play = service.play(f.workshop().getId());
        assertThat(play.view()).isEqualTo("TASK");
        assertThat(play.task().id()).isEqualTo(f.question().getId());
        assertThat(play.task().topRows()).extracting(PlayResponse.ScoredCard::id)
                .containsExactly("c1");

        service.start(f.workshop().getId(), f.question().getId());
        service.respond(f.workshop().getId(), f.question().getId(),
                new RespondRequest(List.of(new RespondRequest.Answer("c1", "Because"))));
        assertThat(service.play(f.workshop().getId()).view()).isEqualTo("MEMBER_DONE");

        // Completed answers are frozen.
        assertThatThrownBy(() -> service.respond(f.workshop().getId(), f.question().getId(),
                new RespondRequest(List.of(new RespondRequest.Answer("c1", "Changed my mind")))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("can't be changed");
    }

    @Test
    void member_cannotWriteToLeadTasksSubmission() {
        Fixture f = fixture(WorkshopStatus.ACTIVE);
        authenticate(f.member());

        assertThatThrownBy(() -> service.submitWeights(f.workshop().getId(), f.weight().getId(),
                new SubmitWeightsRequest(Map.of("c1", 100))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not your task");
    }

    // ------------------------------------------------------------ standalone

    /**
     * A workshop is a standalone product surface: an org with NO cohort at all
     * can own one, assign members to its teams and run it end to end. Cohort
     * curriculum may REFERENCE a workshop (a WORKSHOP program task), never the
     * reverse — so nothing on the participant path (list → play → complete) may
     * ever reach for a cohort. The zero-cohort assertion is the premise, not
     * decoration: without it this reads as one more play test and would stay
     * green if a cohort lookup crept in behind a fixture that seeds one.
     */
    @Test
    void workshopRunsWithoutAnyCohort() {
        Fixture f = fixture(WorkshopStatus.ACTIVE);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cohort_orgs WHERE org_id = ?",
                Integer.class, f.org().getId()))
                .as("premise: the org owning this workshop has no cohort")
                .isZero();

        // The assigned lead sees it in their list…
        authenticate(f.lead());
        assertThat(service.myWorkshops())
                .extracting(MyWorkshopDto::id, MyWorkshopDto::teamName, MyWorkshopDto::lead)
                .containsExactly(tuple(f.workshop().getId(), "Team 1", true));

        // …and plays the whole lead pipeline through to the share.
        assertThat(service.play(f.workshop().getId()).view()).isEqualTo("TASK");
        service.start(f.workshop().getId(), f.sort().getId());
        service.submitSort(f.workshop().getId(), f.sort().getId(),
                new SubmitSortRequest(Map.of("c1", "left", "c2", "right")));
        service.start(f.workshop().getId(), f.weight().getId());
        service.submitWeights(f.workshop().getId(), f.weight().getId(),
                new SubmitWeightsRequest(Map.of("c1", 70)));
        service.start(f.workshop().getId(), f.top().getId());
        service.completeTop(f.workshop().getId(), f.top().getId());
        assertThat(sharedAt(f)).as("the last LEAD task shared the run").isNotNull();

        // The assigned member finishes their side — still no cohort anywhere.
        authenticate(f.member());
        assertThat(service.myWorkshops()).extracting(MyWorkshopDto::id)
                .containsExactly(f.workshop().getId());
        service.start(f.workshop().getId(), f.question().getId());
        service.respond(f.workshop().getId(), f.question().getId(),
                new RespondRequest(List.of(new RespondRequest.Answer("c1", "Because"))));
        assertThat(service.play(f.workshop().getId()).view()).isEqualTo("MEMBER_DONE");
    }

    // ------------------------------------------------------------ fixtures

    private record Fixture(Organization org, Workshop workshop, WorkshopTeam team,
                           User lead, User member,
                           WorkshopExerciseTask sort, WorkshopExerciseTask weight,
                           WorkshopExerciseTask top, WorkshopExerciseTask question) {
    }

    private Fixture fixture(WorkshopStatus status) {
        Organization org = newOrg("Play Org");
        Workshop w = new Workshop();
        w.setOrgId(org.getId());
        w.setName("Control Flip");
        w.setStatus(status);
        w = workshops.saveAndFlush(w);

        WorkshopTeam team = new WorkshopTeam();
        team.setWorkshopId(w.getId());
        team.setName("Team 1");
        team = teams.saveAndFlush(team);

        User lead = newUser("lead@play.invalid", org);
        User member = newUser("member@play.invalid", org);
        teams.addMembership(w.getId(), lead.getId(), team.getId());
        teams.addMembership(w.getId(), member.getId(), team.getId());
        teams.setLead(team.getId(), lead.getId());

        WorkshopExercise ex = new WorkshopExercise();
        ex.setWorkshopId(w.getId());
        ex.setTitle("Exercise 1");
        ex = exercises.saveAndFlush(ex);

        WorkshopExerciseTask sort = task(ex, WorkshopTaskType.SORT, WorkshopTaskAssignee.LEAD, 0,
                Map.of("cards", List.of(
                                Map.of("id", "c1", "text", "Keep", "correct", "left"),
                                Map.of("id", "c2", "text", "Drop", "correct", "right")),
                        "retryAfter", 3));
        WorkshopExerciseTask weight = task(ex, WorkshopTaskType.WEIGHT, WorkshopTaskAssignee.LEAD, 1, Map.of());
        WorkshopExerciseTask top = task(ex, WorkshopTaskType.TOP, WorkshopTaskAssignee.LEAD, 2, Map.of("count", 5));
        WorkshopExerciseTask question = task(ex, WorkshopTaskType.QUESTION, WorkshopTaskAssignee.MEMBER, 3,
                Map.of("prompt", "Why?"));
        return new Fixture(org, w, team, lead, member, sort, weight, top, question);
    }

    private WorkshopExerciseTask task(WorkshopExercise exercise, WorkshopTaskType type,
                                      WorkshopTaskAssignee assignee, int position,
                                      Map<String, Object> config) {
        WorkshopExerciseTask t = new WorkshopExerciseTask();
        t.setExerciseId(exercise.getId());
        t.setTaskType(type);
        t.setAssignee(assignee);
        t.setTitle(type.name());
        t.setPosition(position);
        t.setConfig(config);
        return tasks.saveAndFlush(t);
    }

    private Organization newOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        return organizationRepository.save(org);
    }

    private User newUser(String email, Organization org) {
        User u = new User();
        u.setEmail(email);
        u.setName(email);
        u.setRole(UserRole.MEMBER);
        u.setStatus(UserStatus.ACTIVE);
        u.setOrganization(org);
        return userRepository.save(u);
    }

    private java.time.OffsetDateTime sharedAt(Fixture f) {
        return runs.findByTeamId(f.team().getId()).stream()
                .findFirst()
                .map(WorkshopExerciseRun::getSharedAt)
                .orElse(null);
    }
}
