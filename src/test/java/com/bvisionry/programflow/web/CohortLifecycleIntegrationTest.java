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
import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.CreateModuleRequest;
import com.bvisionry.programflow.dto.UpdateAudienceRequest;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
import com.bvisionry.programflow.dto.UpdateCohortRequest;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
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
 * The V167 lifecycle: legal transitions only, ARCHIVED read-only, and member
 * visibility per state (spec §8 — members see LAUNCHED + COMPLETED, COMPLETED
 * is read-only; DRAFT/ARCHIVED are invisible; admin board/pulse/matrix read
 * any state).
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

        cohortId = cohortService.create(org.getId(), new CreateCohortRequest("Cohort 1", false)).id();
        cohortService.setMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId())));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /* ---------------------------------------------------------- transitions */

    @Test
    void onlyLegalTransitionsPass() {
        // DRAFT: complete refused, archive allowed (tested separately), launch allowed.
        assertThatThrownBy(() -> cohortService.complete(org.getId(), cohortId))
                .isInstanceOf(IllegalOperationException.class);

        CohortDto launched = cohortService.launch(org.getId(), cohortId);
        assertThat(launched.status()).isEqualTo(CohortStatus.LAUNCHED);
        assertThat(launched.launchedAt()).isNotNull();

        // LAUNCHED: no relaunch, no direct archive.
        assertThatThrownBy(() -> cohortService.launch(org.getId(), cohortId))
                .isInstanceOf(IllegalOperationException.class);
        assertThatThrownBy(() -> cohortService.archive(org.getId(), cohortId))
                .isInstanceOf(IllegalOperationException.class);

        CohortDto completed = cohortService.complete(org.getId(), cohortId);
        assertThat(completed.status()).isEqualTo(CohortStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();

        // COMPLETED: no relaunch — archive is the only exit.
        assertThatThrownBy(() -> cohortService.launch(org.getId(), cohortId))
                .isInstanceOf(IllegalOperationException.class);
        CohortDto archived = cohortService.archive(org.getId(), cohortId);
        assertThat(archived.status()).isEqualTo(CohortStatus.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
    }

    @Test
    void aDraftMayBeArchivedDirectly() {
        assertThat(cohortService.archive(org.getId(), cohortId).status())
                .isEqualTo(CohortStatus.ARCHIVED);
    }

    /* ----------------------------------------------------- archived = frozen */

    @Test
    void archived_isReadOnlyForEveryMutation_butAdminReadsStillWork() {
        cohortService.archive(org.getId(), cohortId);

        assertThatThrownBy(() -> cohortService.update(org.getId(), cohortId,
                new UpdateCohortRequest("Renamed")))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("archived");
        assertThatThrownBy(() -> cohortService.setMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of())))
                .isInstanceOf(IllegalOperationException.class);
        assertThatThrownBy(() -> adminService.createModule(org.getId(), cohortId,
                new CreateModuleRequest("Week 1", null, null)))
                .isInstanceOf(IllegalOperationException.class);

        // Admin reads keep working in any state (spec: "Pulse/matrix work for any state").
        assertThatCode(() -> adminService.getBoard(org.getId(), cohortId)).doesNotThrowAnyException();
        assertThatCode(() -> adminService.getPulse(org.getId(), cohortId)).doesNotThrowAnyException();
        assertThatCode(() -> adminService.getMatrix(org.getId(), cohortId)).doesNotThrowAnyException();
    }

    @Test
    void membersAndCurriculumStayEditableWhileCompleted() {
        cohortService.launch(org.getId(), cohortId);
        cohortService.complete(org.getId(), cohortId);

        assertThatCode(() -> adminService.createModule(org.getId(), cohortId,
                new CreateModuleRequest("Retro module", null, "Mindset")))
                .doesNotThrowAnyException();
        assertThatCode(() -> cohortService.setMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId()))))
                .doesNotThrowAnyException();
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

        assertThat(adminService.createModule(org.getId(), cohortId,
                new CreateModuleRequest("Week 1", null, null)).audience().reached())
                .as("an ALL-audience module reaches the roster, not the org").isEqualTo(1);
        assertThat(adminService.getBoard(org.getId(), cohortId).stats().members())
                .as("board stats tile = enrolled founders").isEqualTo(1);
        assertThat(adminService.getPulse(org.getId(), cohortId).rows())
                .extracting(com.bvisionry.programflow.dto.PulseResponse.PulseRow::userId)
                .containsExactly(member.getId());
        assertThat(adminService.getMatrix(org.getId(), cohortId).rows())
                .extracting(com.bvisionry.programflow.dto.CohortMatrixResponse.FounderRow::userId)
                .containsExactly(member.getId());

        cohortService.setMembers(org.getId(), cohortId, new UpdateCohortMembersRequest(List.of()));
        assertThat(adminService.getBoard(org.getId(), cohortId).stats().members()).isZero();
        assertThat(adminService.getPulse(org.getId(), cohortId).rows())
                .as("nobody enrolled → nothing to score, not the org roster").isEmpty();
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
        UUID moduleId = adminService.createModule(org.getId(), cohortId,
                new CreateModuleRequest("Week 1", null, null)).id();
        // Narrow to nobody, then include the member — would notify if launched.
        adminService.updateAudience(org.getId(), cohortId, moduleId,
                new UpdateAudienceRequest(AudienceMode.MEMBERS, List.of(), List.of()));
        adminService.updateAudience(org.getId(), cohortId, moduleId,
                new UpdateAudienceRequest(AudienceMode.MEMBERS, List.of(), List.of(member.getId())));

        assertThat(applicationEvents.stream(ProgramFlowEvents.CohortEnrolled.class))
                .as("draft create + roster stay silent").isEmpty();
        assertThat(applicationEvents.stream(ProgramFlowEvents.ModuleAssigned.class))
                .as("draft audience change stays silent").isEmpty();

        cohortService.launch(org.getId(), cohortId);
        List<ProgramFlowEvents.CohortEnrolled> atLaunch = applicationEvents
                .stream(ProgramFlowEvents.CohortEnrolled.class).toList();
        assertThat(atLaunch).hasSize(1);
        assertThat(atLaunch.get(0).userIds()).containsExactly(member.getId());

        // Post-launch addition: only the newcomer hears.
        cohortService.setMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId(), member2.getId())));
        List<ProgramFlowEvents.CohortEnrolled> afterAdd = applicationEvents
                .stream(ProgramFlowEvents.CohortEnrolled.class).toList();
        assertThat(afterAdd).hasSize(2);
        assertThat(afterAdd.get(1).userIds()).containsExactly(member2.getId());

        // Post-launch audience change notifies too.
        adminService.updateAudience(org.getId(), cohortId, moduleId,
                new UpdateAudienceRequest(AudienceMode.MEMBERS, List.of(), List.of()));
        adminService.updateAudience(org.getId(), cohortId, moduleId,
                new UpdateAudienceRequest(AudienceMode.MEMBERS, List.of(), List.of(member.getId())));
        assertThat(applicationEvents.stream(ProgramFlowEvents.ModuleAssigned.class)).hasSize(1);
    }

    /* -------------------------------------------- sessions write gate (§4) */

    /**
     * Review blocking #2: sessions + roll call reject an ARCHIVED cohort;
     * COMPLETED stays mutable (late roll-call tidy-up is legitimate).
     */
    @Test
    void sessionMutations_rejectArchived_butCompletedStaysMutable() {
        cohortService.launch(org.getId(), cohortId);
        cohortService.complete(org.getId(), cohortId);

        var req = new com.bvisionry.engagement.dto.SessionDtos.UpsertSessionRequest(
                com.bvisionry.engagement.domain.SessionType.WORKSHOP, "Retro",
                java.time.Instant.now(), List.of());
        UUID sessionId = sessionService
                .create(org.getId(), cohortId, req, admin.getId()).id();
        sessionService.setAttendance(org.getId(), cohortId, sessionId, member.getId(),
                true, admin.getId());

        cohortService.archive(org.getId(), cohortId);
        // The write gate reads the status by raw SQL; flush the JPA status
        // change so it is visible inside this test transaction (in production
        // the archive committed long before).
        entityManager.flush();
        assertThatThrownBy(() -> sessionService.create(org.getId(), cohortId, req, admin.getId()))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("archived");
        assertThatThrownBy(() -> sessionService.setAttendance(org.getId(), cohortId, sessionId,
                member.getId(), false, admin.getId()))
                .isInstanceOf(IllegalOperationException.class);
    }

    /* -------------------------------------------------- member visibility */

    @Test
    void memberVisibilityFollowsTheLifecycle() {
        TestAuthentication.authenticate(member);
        assertThat(myProgramService.myCohorts()).as("DRAFT is invisible").isEmpty();

        TestAuthentication.authenticate(admin);
        cohortService.launch(org.getId(), cohortId);
        TestAuthentication.authenticate(member);
        assertThat(myProgramService.myCohorts()).hasSize(1);
        assertThat(myProgramService.journey(cohortId).readOnly()).isFalse();

        TestAuthentication.authenticate(admin);
        cohortService.complete(org.getId(), cohortId);
        TestAuthentication.authenticate(member);
        assertThat(myProgramService.myCohorts())
                .as("COMPLETED stays visible (the closing screen)").hasSize(1);
        assertThat(myProgramService.journey(cohortId).readOnly())
                .as("COMPLETED reads as read-only (the closing screen)").isTrue();

        TestAuthentication.authenticate(admin);
        cohortService.archive(org.getId(), cohortId);
        TestAuthentication.authenticate(member);
        assertThat(myProgramService.myCohorts()).as("ARCHIVED is invisible").isEmpty();
    }
}
