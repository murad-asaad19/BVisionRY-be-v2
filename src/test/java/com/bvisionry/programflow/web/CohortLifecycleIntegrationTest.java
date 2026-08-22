package com.bvisionry.programflow.web;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.engagement.web.SessionService;
import com.bvisionry.programflow.domain.AudienceMode;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.domain.CohortStatus;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.dto.BoardResponse;
import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.SaveBoardRequest;
import com.bvisionry.programflow.dto.SaveBoardRequest.ModuleUpsert;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
import com.bvisionry.programflow.dto.UpdateCohortRequest;
import com.bvisionry.programflow.dto.UpdateOrgAssignmentRequest;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.BoardPayloads;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two-state lifecycle (V183): DRAFT ⇄ LAUNCHED, legal transitions only,
 * and member visibility per state (spec §8 — members see LAUNCHED; DRAFT is
 * invisible; admin board/pulse/matrix read any state).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@EnabledIfDockerAvailable
class CohortLifecycleIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private CohortService cohortService;
    @Autowired private ProgramAdminService adminService;
    @Autowired private MyProgramService myProgramService;
    @Autowired private SessionService sessionService;
    @Autowired private OrganizationRepository orgs;
    @Autowired private UserRepository users;
    @Autowired private ApplicationEvents applicationEvents;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    private Organization org;
    private User admin;
    private User member;
    private UUID cohortId;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setName("Lifecycle Org");
        org.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS); // quota out of the way
        org.setActive(true);
        org = orgs.saveAndFlush(org);
        member = TestAuthentication.authenticateAsMember(users, org);
        admin = TestAuthentication.authenticateAsOrgAdmin(users, org);

        cohortId = cohortService.create(new CreateCohortRequest("Cohort 1")).id();
        cohortService.assignOrg(cohortId, new com.bvisionry.programflow.dto.AssignOrgRequest(
                org.getId(), false, List.of(), false));
        cohortService.setOrgMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId())));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /* ---------------------------------------------------------- transitions */

    @Test
    void onlyLegalTransitionsPass() {
        // DRAFT: unlaunch refused, launch allowed.
        assertThatThrownBy(() -> cohortService.unlaunch(cohortId))
                .isInstanceOf(IllegalOperationException.class);

        CohortDto launched = cohortService.launch(cohortId);
        assertThat(launched.status()).isEqualTo(CohortStatus.LAUNCHED);
        assertThat(launched.launchedAt()).isNotNull();

        // LAUNCHED: no relaunch on top.
        assertThatThrownBy(() -> cohortService.launch(cohortId))
                .isInstanceOf(IllegalOperationException.class);

        // Unlaunch pulls it back to draft but KEEPS the first-launch stamp —
        // the milestone-adoption floor (ADOPTABLE_SITTINGS) must survive.
        CohortDto unlaunched = cohortService.unlaunch(cohortId);
        assertThat(unlaunched.status()).isEqualTo(CohortStatus.DRAFT);
        assertThat(unlaunched.launchedAt()).isEqualTo(launched.launchedAt());

        // Relaunch works and returns the SAME instant, not a fresh stamp.
        CohortDto relaunched = cohortService.launch(cohortId);
        assertThat(relaunched.status()).isEqualTo(CohortStatus.LAUNCHED);
        assertThat(relaunched.launchedAt()).isEqualTo(launched.launchedAt());
    }

    /**
     * Relaunch is silent: the "you were enrolled" blast fires on the FIRST
     * launch only, and members added while LAUNCHED are notified individually
     * by the roster path — never the whole roster again.
     */
    @Test
    void relaunch_doesNotRenotifyTheRoster() {
        User member2 = newOrgUser("relaunch.member@lifecycle.invalid", "Relaunch Member",
                com.bvisionry.common.enums.UserRole.MEMBER);
        TestAuthentication.authenticate(admin);
        cohortService.launch(cohortId);
        assertThat(applicationEvents.stream(ProgramFlowEvents.CohortEnrolled.class))
                .as("first launch blasts the roster once").hasSize(1);

        cohortService.unlaunch(cohortId);
        cohortService.launch(cohortId);
        assertThat(applicationEvents.stream(ProgramFlowEvents.CohortEnrolled.class))
                .as("relaunch stays silent").hasSize(1);

        cohortService.setOrgMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId(), member2.getId())));
        List<ProgramFlowEvents.CohortEnrolled> events = applicationEvents
                .stream(ProgramFlowEvents.CohortEnrolled.class).toList();
        assertThat(events).as("a newcomer while LAUNCHED still hears").hasSize(2);
        assertThat(events.get(1).userIds()).containsExactly(member2.getId());
    }

    @Test
    void everythingStaysEditableWhileLaunched_andAdminReadsWorkInAnyState() {
        cohortService.launch(cohortId);

        assertThatCode(() -> cohortService.update(cohortId, new UpdateCohortRequest("Renamed")))
                .doesNotThrowAnyException();
        assertThatCode(() -> addModule("Retro module", "Mindset"))
                .doesNotThrowAnyException();
        assertThatCode(() -> cohortService.setOrgMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId()))))
                .doesNotThrowAnyException();

        cohortService.unlaunch(cohortId);
        assertThatCode(() -> adminService.getBoard(cohortId)).doesNotThrowAnyException();
        assertThatCode(() -> adminService.getPulse(cohortId, null)).doesNotThrowAnyException();
        assertThatCode(() -> adminService.getMatrix(cohortId, null)).doesNotThrowAnyException();
    }

    /* ------------------------------------------------- board writes (§13.10) */

    /**
     * The console's "add a module": since §13.10 the builder holds the board
     * locally and the ONLY write is the whole-board Save, so every curriculum
     * change here is expressed as one.
     */
    private UUID addModule(String name, String pillarLabel) {
        BoardResponse board = adminService.getBoard(cohortId);
        UUID id = UUID.randomUUID();
        adminService.saveBoard(cohortId, BoardPayloads.of(board,
                java.util.stream.Stream.concat(BoardPayloads.modulesOf(board).stream(),
                        java.util.stream.Stream.of(new ModuleUpsert(id, name, null, pillarLabel, true,
                                ModuleLockMode.SEQUENTIAL, null, AudienceMode.ALL,
                                List.of(), List.of())))
                        .toList()));
        return id;
    }

    /** Re-saves the board with one module's audience narrowed to {@code memberIds}. */
    private void setAudience(UUID moduleId, List<UUID> memberIds) {
        BoardResponse board = adminService.getBoard(cohortId);
        adminService.saveBoard(cohortId, BoardPayloads.of(board, board.modules().stream()
                .map(m -> m.id().equals(moduleId)
                        ? new ModuleUpsert(m.id(), m.name(), m.summary(), m.pillarLabel(), m.paced(),
                                m.lockMode(), m.unlockAt(), AudienceMode.MEMBERS, memberIds,
                                m.tasks().stream().map(BoardPayloads::asUpsert).toList())
                        : BoardPayloads.asUpsert(m,
                                m.tasks().stream().map(BoardPayloads::asUpsert).toList()))
                .toList()));
    }

    /* ------------------------------------------------------- roster scoping */

    /**
     * Every cohort-board number counts the cohort's ROSTER, never the org: an
     * active org member who was never enrolled is absent from the board stats
     * tile, from a module's "reached", and from Pulse — so he cannot be
     * averaged into the Pulse tiles or flagged "at risk" on someone else's
     * cohort. An empty roster yields no rows at all, not the org's.
     */
    @Test
    void boardStatsModuleReachAndPulse_countTheRosterNotTheOrg() {
        User outsider = new User();
        outsider.setEmail("never.enrolled@lifecycle.invalid");
        outsider.setName("Never Enrolled");
        outsider.setRole(com.bvisionry.common.enums.UserRole.MEMBER);
        outsider.setStatus(com.bvisionry.common.enums.UserStatus.ACTIVE);
        outsider.setOrganization(org);
        users.saveAndFlush(outsider);
        TestAuthentication.authenticate(admin);

        addModule("Week 1", null);
        assertThat(adminService.getBoard(cohortId).modules()).singleElement()
                .extracting(m -> m.audience().reached())
                .as("an ALL-audience module reaches the roster, not the org").isEqualTo(1);
        assertThat(adminService.getBoard(cohortId).stats().members())
                .as("board stats tile = enrolled founders").isEqualTo(1);
        assertThat(adminService.getPulse(cohortId, null).rows())
                .extracting(com.bvisionry.programflow.dto.PulseResponse.PulseRow::userId)
                .containsExactly(member.getId());
        assertThat(adminService.getMatrix(cohortId, null).rows())
                .extracting(com.bvisionry.programflow.dto.CohortMatrixResponse.FounderRow::userId)
                .containsExactly(member.getId());

        cohortService.setOrgMembers(org.getId(), cohortId, new UpdateCohortMembersRequest(List.of()));
        assertThat(adminService.getBoard(cohortId).stats().members()).isZero();
        assertThat(adminService.getPulse(cohortId, null).rows())
                .as("nobody enrolled → nothing to score, not the org roster").isEmpty();
    }

    /* --------------------------------------------- cohort-card progress bar */

    /**
     * Spec §8 cohort-card progress bar: the org list read carries the real
     * module count and stage label — zero/"Week" before any curriculum or
     * settings exist, the true count and label once they do.
     */
    @Test
    void listAssigned_carriesModuleCountAndStageLabelForTheCards() {
        TestAuthentication.authenticate(admin);
        assertThat(cohortService.listAssigned(org.getId())).singleElement().satisfies(c -> {
            assertThat(c.moduleCount()).as("no modules yet").isZero();
            assertThat(c.stageLabel()).as("no settings row yet, default label").isEqualTo("Week");
        });

        addModule("Week 1", null);
        addModule("Week 2", null);
        adminService.updateSettings(cohortId,
                new ProgramSettingsDto("Sprint", true, 3, null, null, null, null));

        assertThat(cohortService.listAssigned(org.getId())).singleElement().satisfies(c -> {
            assertThat(c.moduleCount()).isEqualTo(2);
            assertThat(c.stageLabel()).isEqualTo("Sprint");
        });
    }

    /**
     * Spec §13.7: the org console's progress views are the org's OWN slice of
     * a cross-org roster — never another org's people — and a cohort the org
     * is not assigned to is a 404, not an empty matrix.
     */
    @Test
    void orgScopedMatrixAndPulse_showOnlyThatOrgsMembers() {
        Organization other = new Organization();
        other.setName("Lifecycle Org B");
        other.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS);
        other.setActive(true);
        other = orgs.saveAndFlush(other);
        User theirs = new User();
        theirs.setEmail("member.b@lifecycle.invalid");
        theirs.setName("Other Org Member");
        theirs.setRole(com.bvisionry.common.enums.UserRole.MEMBER);
        theirs.setStatus(com.bvisionry.common.enums.UserStatus.ACTIVE);
        theirs.setOrganization(other);
        users.saveAndFlush(theirs);
        TestAuthentication.authenticate(admin);

        cohortService.assignOrg(cohortId, new com.bvisionry.programflow.dto.AssignOrgRequest(
                other.getId(), true, List.of(), false));

        assertThat(adminService.getMatrix(cohortId, null).rows())
                .as("platform view spans the whole roster").hasSize(2);
        assertThat(adminService.getMatrix(cohortId, org.getId()).rows())
                .extracting(com.bvisionry.programflow.dto.CohortMatrixResponse.FounderRow::userId)
                .containsExactly(member.getId());
        assertThat(adminService.getPulse(cohortId, other.getId()).rows())
                .extracting(com.bvisionry.programflow.dto.PulseResponse.PulseRow::userId)
                .containsExactly(theirs.getId());

        Organization stranger = new Organization();
        stranger.setName("Not Assigned");
        stranger.setActive(true);
        UUID strangerId = orgs.saveAndFlush(stranger).getId();
        assertThatThrownBy(() -> adminService.getMatrix(cohortId, strangerId))
                .isInstanceOf(com.bvisionry.common.exception.ResourceNotFoundException.class);
    }

    /* -------------------------------------------------- auto-enroll (§13.3) */

    /**
     * Auto-enroll is learners-only, exactly like the two manual paths: a coach
     * who accepts an invitation into an auto-enroll org is STAFFING the
     * program, not joining it. Enrolling him would push him a "you were
     * enrolled" notification and leave a memberIds row no roster surface ever
     * shows (findRoster is role-filtered), so the raw count would disagree
     * with the roster forever. An ORG_ADMIN, by contrast, IS a learner
     * ({@code Learner.ROLE_IN}): an org-scoped admin takes the program like a
     * founder does, matching the assessment slice's targetRoles.
     */
    @Test
    void autoEnroll_takesLearnersOnly_neverStaff() {
        cohortService.launch(cohortId);
        cohortService.updateOrgAssignment(cohortId, org.getId(),
                new UpdateOrgAssignmentRequest(true));
        User coach = newOrgUser("coach@lifecycle.invalid", "Program Coach",
                com.bvisionry.common.enums.UserRole.COACH);
        User joiner = newOrgUser("late.joiner@lifecycle.invalid", "Late Joiner",
                com.bvisionry.common.enums.UserRole.MEMBER);
        User orgAdmin = newOrgUser("orgadmin@lifecycle.invalid", "Org Admin",
                com.bvisionry.common.enums.UserRole.ORG_ADMIN);
        TestAuthentication.authenticate(admin);

        cohortService.autoEnroll(org.getId(), coach.getId());
        cohortService.autoEnroll(org.getId(), joiner.getId());
        cohortService.autoEnroll(org.getId(), orgAdmin.getId());

        assertThat(rawMemberIds())
                .as("the coach staffs the cohort; learners — member and org admin — enroll")
                .containsExactlyInAnyOrder(member.getId(), joiner.getId(), orgAdmin.getId());
        assertThat(applicationEvents.stream(ProgramFlowEvents.CohortEnrolled.class)
                .flatMap(e -> e.userIds().stream()))
                .as("no 'you were enrolled' push for someone who is staffing")
                .containsExactlyInAnyOrder(member.getId(), joiner.getId(), orgAdmin.getId());
    }

    /** The cohort's RAW roster — the platform view, before any role filtering. */
    private List<UUID> rawMemberIds() {
        return cohortService.listAll().stream()
                .filter(c -> c.id().equals(cohortId))
                .findFirst().orElseThrow()
                .memberIds();
    }

    private User newOrgUser(String email, String name, com.bvisionry.common.enums.UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setRole(role);
        user.setStatus(com.bvisionry.common.enums.UserStatus.ACTIVE);
        user.setOrganization(org);
        return users.saveAndFlush(user);
    }

    /* ------------------------------------------------ notification timing */

    /**
     * Review blocking #1: members are never pinged about cohorts they can't
     * see. Draft-time enrolment and audience changes stay silent; launch
     * notifies the full roster once; post-launch roster additions notify the
     * newcomers only.
     */
    @Test
    void enrolmentAndAudienceNotifications_fireOnlyForLaunchedCohorts() {
        // A second, distinct member (the fixed-email helper would upsert
        // straight onto `member`).
        User member2 = new User();
        member2.setEmail("second.member@lifecycle.invalid");
        member2.setName("Second Member");
        member2.setRole(com.bvisionry.common.enums.UserRole.MEMBER);
        member2.setStatus(com.bvisionry.common.enums.UserStatus.ACTIVE);
        member2.setOrganization(org);
        member2 = users.saveAndFlush(member2);
        TestAuthentication.authenticate(admin);
        UUID moduleId = addModule("Week 1", null);
        // Narrow to nobody, then include the member — would notify if launched.
        setAudience(moduleId, List.of());
        setAudience(moduleId, List.of(member.getId()));

        assertThat(applicationEvents.stream(ProgramFlowEvents.CohortEnrolled.class))
                .as("draft create + roster stay silent").isEmpty();
        assertThat(applicationEvents.stream(ProgramFlowEvents.ModuleAssigned.class))
                .as("draft audience change stays silent").isEmpty();

        cohortService.launch(cohortId);
        List<ProgramFlowEvents.CohortEnrolled> atLaunch = applicationEvents
                .stream(ProgramFlowEvents.CohortEnrolled.class).toList();
        assertThat(atLaunch).hasSize(1);
        assertThat(atLaunch.get(0).userIds()).containsExactly(member.getId());

        // Post-launch addition: only the newcomer hears.
        cohortService.setOrgMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId(), member2.getId())));
        List<ProgramFlowEvents.CohortEnrolled> afterAdd = applicationEvents
                .stream(ProgramFlowEvents.CohortEnrolled.class).toList();
        assertThat(afterAdd).hasSize(2);
        assertThat(afterAdd.get(1).userIds()).containsExactly(member2.getId());

        // Post-launch audience change notifies too.
        setAudience(moduleId, List.of());
        setAudience(moduleId, List.of(member.getId()));
        assertThat(applicationEvents.stream(ProgramFlowEvents.ModuleAssigned.class)).hasSize(1);
    }

    /* -------------------------------------------- sessions write gate (§4) */

    /** Sessions + roll call stay mutable in both states — the gate is tenancy only. */
    @Test
    void sessionMutations_workInEitherState() {
        cohortService.launch(cohortId);

        var req = new com.bvisionry.engagement.dto.SessionDtos.UpsertSessionRequest(
                com.bvisionry.engagement.domain.SessionType.WORKSHOP, "Retro",
                java.time.Instant.now(), List.of(), List.of());
        UUID sessionId = sessionService
                .create(cohortId, org.getId(), req, admin.getId()).id();
        sessionService.setAttendance(cohortId, org.getId(), sessionId, member.getId(),
                true, admin.getId());

        cohortService.unlaunch(cohortId);
        entityManager.flush();
        assertThatCode(() -> sessionService.setAttendance(cohortId, org.getId(),
                sessionId, member.getId(), false, admin.getId()))
                .doesNotThrowAnyException();
    }

    /* -------------------------------------------------- member visibility */

    @Test
    void memberVisibilityFollowsTheLifecycle() {
        TestAuthentication.authenticate(member);
        assertThat(myProgramService.myCohorts()).as("DRAFT is invisible").isEmpty();

        TestAuthentication.authenticate(admin);
        cohortService.launch(cohortId);
        TestAuthentication.authenticate(member);
        assertThat(myProgramService.myCohorts()).hasSize(1);

        TestAuthentication.authenticate(admin);
        cohortService.unlaunch(cohortId);
        TestAuthentication.authenticate(member);
        assertThat(myProgramService.myCohorts())
                .as("unlaunched → back to invisible").isEmpty();
    }
}
