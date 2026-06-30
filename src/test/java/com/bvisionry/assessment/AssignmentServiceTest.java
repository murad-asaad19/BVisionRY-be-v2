package com.bvisionry.assessment;

import com.bvisionry.assessment.dto.AssignmentResponse;
import com.bvisionry.assessment.dto.CreateAssignmentRequest;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.PipelineAutoAssignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.config.FrontendProperties;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.auth.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssignmentServiceTest {

    @Mock private AssignmentRepository assignmentRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private PipelineRepository pipelineRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private AuditService auditService;
    @Mock private com.bvisionry.membertype.MemberTypeService memberTypeService;
    @Mock private PipelineAutoAssignmentService pipelineAutoAssignmentService;

    @InjectMocks
    private AssignmentService assignmentService;

    private UUID orgId;
    private UUID pipelineId;
    private UUID assignedBy;
    private Organization organization;
    private Pipeline pipeline;
    private User member1;
    private User member2;

    @BeforeEach
    void setUp() {
        // AssignmentService builds /my/assessments links via FrontendUrls; inject a
        // real one so the email-send paths don't NPE (URL value isn't asserted).
        ReflectionTestUtils.setField(assignmentService, "frontendUrls",
                new FrontendUrls(new FrontendProperties()));
        orgId = UUID.randomUUID();
        pipelineId = UUID.randomUUID();
        assignedBy = UUID.randomUUID();

        organization = new Organization();
        organization.setId(orgId);
        organization.setName("Test Org");
        organization.setActive(true);

        pipeline = new Pipeline();
        pipeline.setId(pipelineId);
        pipeline.setName("Test Pipeline");
        pipeline.setStatus(PipelineStatus.PUBLISHED);

        member1 = new User();
        member1.setId(UUID.randomUUID());
        member1.setEmail("member1@test.com");
        member1.setName("Member One");
        member1.setRole(UserRole.MEMBER);
        member1.setStatus(UserStatus.ACTIVE);

        member2 = new User();
        member2.setId(UUID.randomUUID());
        member2.setEmail("member2@test.com");
        member2.setName("Member Two");
        member2.setRole(UserRole.MEMBER);
        member2.setStatus(UserStatus.ACTIVE);

        // Service derives the assigner from SecurityUtils now that the
        // assignedBy request field was dropped — seed the principal here.
        User caller = new User();
        caller.setId(assignedBy);
        caller.setRole(UserRole.ORG_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAssignment_assignAll_createsOneAssignmentPerActiveMember() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of(member1, member2));
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        CreateAssignmentRequest request = new CreateAssignmentRequest(pipelineId, null, null, null, false, null);
        List<AssignmentResponse> responses = assignmentService.createAssignment(orgId, request);

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(AssignmentResponse::userName)
                .containsExactlyInAnyOrder("Member One", "Member Two");

        verify(assignmentRepository, times(2)).save(any(Assignment.class));
        verify(submissionRepository, times(2)).save(any(Submission.class));
    }

    @Test
    void createAssignment_specificMember_createsOneAssignment() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findAllById(List.of(member1.getId()))).thenReturn(List.of(member1));
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        CreateAssignmentRequest request = new CreateAssignmentRequest(
                pipelineId, List.of(member1.getId()), null, null, false, null);
        List<AssignmentResponse> responses = assignmentService.createAssignment(orgId, request);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).userName()).isEqualTo("Member One");
        assertThat(responses.get(0).status()).isEqualTo(SubmissionStatus.IN_PROGRESS);
    }

    @Test
    void createAssignment_memberAlreadyAssigned_isSkipped() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of(member1, member2));
        when(assignmentRepository.findExistingAssignedUserIdsIn(eq(orgId), eq(pipelineId), any()))
                .thenReturn(List.of(member1.getId()));
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        CreateAssignmentRequest request = new CreateAssignmentRequest(pipelineId, null, null, null, false, null);
        List<AssignmentResponse> responses = assignmentService.createAssignment(orgId, request);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).userName()).isEqualTo("Member Two");
    }

    @Test
    void createAssignment_allTargetsAlreadyAssigned_throwsBadRequest() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of(member1));
        when(assignmentRepository.findExistingAssignedUserIdsIn(eq(orgId), eq(pipelineId), any()))
                .thenReturn(List.of(member1.getId()));

        CreateAssignmentRequest request = new CreateAssignmentRequest(pipelineId, null, null, null, false, null);

        assertThatThrownBy(() -> assignmentService.createAssignment(orgId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already have this pipeline assigned");
    }

    @Test
    void createAssignment_pipelineNotPublished_throwsBadRequest() {
        pipeline.setStatus(PipelineStatus.DRAFT);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));

        CreateAssignmentRequest request = new CreateAssignmentRequest(pipelineId, null, null, null, false, null);

        assertThatThrownBy(() -> assignmentService.createAssignment(orgId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("published");
    }

    @Test
    void createAssignment_orgNotFound_throwsNotFound() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        CreateAssignmentRequest request = new CreateAssignmentRequest(pipelineId, null, null, null, false, null);

        assertThatThrownBy(() -> assignmentService.createAssignment(orgId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createAssignment_autoAssignFlag_persistsRule() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of(member1));
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        CreateAssignmentRequest request = new CreateAssignmentRequest(
                pipelineId, null, null, null, true, null);
        assignmentService.createAssignment(orgId, request);

        verify(pipelineAutoAssignmentService).upsertRule(
                eq(organization), eq(pipeline), eq(null), eq(null), eq(assignedBy), eq(1));
    }

    @Test
    void createAssignment_autoAssignWithMemberIds_throwsBadRequest() {
        // Auto-assign is incoherent with explicit memberIds — reject up-front.
        // Service should NOT touch any other dependency before rejecting.
        CreateAssignmentRequest request = new CreateAssignmentRequest(
                pipelineId, List.of(member1.getId()), null, null, true, null);

        assertThatThrownBy(() -> assignmentService.createAssignment(orgId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("all members");

        verifyNoInteractions(pipelineAutoAssignmentService);
    }

    @Test
    void createAssignment_orgAdminRequestsCustomMaxCheckIns_throwsBadRequest() {
        // Caller seeded in setUp() is ORG_ADMIN — only SUPER_ADMIN may configure
        // maxCheckIns above the single-check-in default.
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of(member1));

        CreateAssignmentRequest request = new CreateAssignmentRequest(
                pipelineId, null, null, null, false, 3);

        assertThatThrownBy(() -> assignmentService.createAssignment(orgId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("super admin");
        verify(assignmentRepository, never()).save(any(Assignment.class));
    }

    @Test
    void createAssignment_superAdminRequestsCustomMaxCheckIns_isHonored() {
        User superAdminCaller = new User();
        superAdminCaller.setId(assignedBy);
        superAdminCaller.setRole(UserRole.SUPER_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(superAdminCaller, null, List.of()));

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of(member1));
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        CreateAssignmentRequest request = new CreateAssignmentRequest(
                pipelineId, null, null, null, false, 3);
        assignmentService.createAssignment(orgId, request);

        verify(assignmentRepository).save(argThat(a -> a.getMaxCheckIns() == 3));
    }

    @Test
    void createAssignment_autoAssignOnEmptyOrg_stillUpsertsRule() {
        // Org has no active members yet — the rule itself is still the
        // deliverable for future joiners, so the service must not throw
        // "No eligible members" and must persist the rule.
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of());

        CreateAssignmentRequest request = new CreateAssignmentRequest(
                pipelineId, null, null, null, true, null);
        List<AssignmentResponse> responses = assignmentService.createAssignment(orgId, request);

        assertThat(responses).isEmpty();
        verify(pipelineAutoAssignmentService).upsertRule(
                eq(organization), eq(pipeline), eq(null), eq(null), eq(assignedBy), eq(1));
        verify(assignmentRepository, never()).save(any(Assignment.class));
        // Empty member list must short-circuit the dedup query — passing an
        // empty IN-list to JPQL is a needless round-trip and historically a
        // Hibernate footgun.
        verify(assignmentRepository, never()).findExistingAssignedUserIdsIn(any(), any(), any());
    }

    @Test
    void createAssignment_emptyOrgWithoutAutoAssign_throwsBadRequest() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of());

        CreateAssignmentRequest request = new CreateAssignmentRequest(pipelineId, null, null, null, false, null);

        assertThatThrownBy(() -> assignmentService.createAssignment(orgId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No eligible members");
    }

    @Test
    void createAssignment_autoAssignAllAlreadyAssigned_stillUpsertsRule() {
        // The rule itself is the deliverable when no current members are
        // unassigned — the service should not throw "all already assigned".
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE))
                .thenReturn(List.of(member1));
        when(assignmentRepository.findExistingAssignedUserIdsIn(eq(orgId), eq(pipelineId), any()))
                .thenReturn(List.of(member1.getId()));

        CreateAssignmentRequest request = new CreateAssignmentRequest(
                pipelineId, null, null, null, true, null);
        List<AssignmentResponse> responses = assignmentService.createAssignment(orgId, request);

        assertThat(responses).isEmpty();
        verify(pipelineAutoAssignmentService).upsertRule(
                eq(organization), eq(pipeline), eq(null), eq(null), eq(assignedBy), eq(1));
    }

    @Test
    void cancelAssignment_noCompletedSubmissions_deletes() {
        UUID assignmentId = UUID.randomUUID();
        Assignment assignment = new Assignment();
        assignment.setId(assignmentId);
        assignment.setOrganization(organization);

        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.countCompletedSubmissions(assignmentId)).thenReturn(0L);

        assignmentService.cancelAssignment(orgId, assignmentId);

        verify(assignmentRepository).delete(assignment);
    }

    @Test
    void cancelAssignment_hasCompletedSubmissions_throwsBadRequest() {
        UUID assignmentId = UUID.randomUUID();
        Assignment assignment = new Assignment();
        assignment.setId(assignmentId);
        assignment.setOrganization(organization);

        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.countCompletedSubmissions(assignmentId)).thenReturn(3L);

        assertThatThrownBy(() -> assignmentService.cancelAssignment(orgId, assignmentId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("completed submissions");
    }

    // ---------- applyAutoAssignRule (listener path) ------------------------
    //
    // Public proxy boundary: the AFTER_COMMIT handler invokes this method via
    // a fresh REQUIRES_NEW transaction. These tests pin the guards that keep
    // it from cross-tenant or stale-state assignment creation.

    @Test
    void applyAutoAssignRule_alreadyAssigned_isNoOp() {
        UUID ruleId = UUID.randomUUID();
        PipelineAutoAssignment rule = ruleFor(pipeline, organization, /* userType */ null);
        rule.setId(ruleId);

        when(pipelineAutoAssignmentService.findByIdLoaded(ruleId)).thenReturn(Optional.of(rule));
        when(userRepository.findById(member1.getId())).thenReturn(Optional.of(memberInOrg(member1, organization)));
        when(assignmentRepository.existsByOrganizationIdAndPipelineIdAndUserId(
                orgId, pipelineId, member1.getId())).thenReturn(true);

        assignmentService.applyAutoAssignRule(ruleId, member1.getId(), orgId);

        verify(assignmentRepository, never()).save(any(Assignment.class));
    }

    @Test
    void applyAutoAssignRule_pipelineArchived_isNoOp() {
        UUID ruleId = UUID.randomUUID();
        Pipeline archived = new Pipeline();
        archived.setId(pipelineId);
        archived.setStatus(PipelineStatus.ARCHIVED);
        PipelineAutoAssignment rule = ruleFor(archived, organization, null);
        rule.setId(ruleId);

        when(pipelineAutoAssignmentService.findByIdLoaded(ruleId)).thenReturn(Optional.of(rule));

        assignmentService.applyAutoAssignRule(ruleId, member1.getId(), orgId);

        verify(userRepository, never()).findById(any());
        verify(assignmentRepository, never()).save(any(Assignment.class));
    }

    @Test
    void applyAutoAssignRule_inactiveUser_isNoOp() {
        UUID ruleId = UUID.randomUUID();
        PipelineAutoAssignment rule = ruleFor(pipeline, organization, null);
        rule.setId(ruleId);

        User suspended = memberInOrg(member1, organization);
        suspended.setStatus(UserStatus.SUSPENDED);

        when(pipelineAutoAssignmentService.findByIdLoaded(ruleId)).thenReturn(Optional.of(rule));
        when(userRepository.findById(member1.getId())).thenReturn(Optional.of(suspended));

        assignmentService.applyAutoAssignRule(ruleId, member1.getId(), orgId);

        verify(assignmentRepository, never()).save(any(Assignment.class));
    }

    @Test
    void applyAutoAssignRule_userMovedToDifferentOrg_isNoOp() {
        UUID ruleId = UUID.randomUUID();
        PipelineAutoAssignment rule = ruleFor(pipeline, organization, null);
        rule.setId(ruleId);

        Organization otherOrg = new Organization();
        otherOrg.setId(UUID.randomUUID());
        User movedAway = memberInOrg(member1, otherOrg);

        when(pipelineAutoAssignmentService.findByIdLoaded(ruleId)).thenReturn(Optional.of(rule));
        when(userRepository.findById(member1.getId())).thenReturn(Optional.of(movedAway));

        assignmentService.applyAutoAssignRule(ruleId, member1.getId(), orgId);

        verify(assignmentRepository, never()).save(any(Assignment.class));
    }

    @Test
    void applyAutoAssignRule_callerPassesWrongExpectedOrg_isNoOp() {
        // Defensive guard at the public proxy boundary: a caller passing a
        // ruleId from one org with the userId of a member of another org
        // must not cause a cross-tenant assignment.
        UUID ruleId = UUID.randomUUID();
        PipelineAutoAssignment rule = ruleFor(pipeline, organization, null);
        rule.setId(ruleId);

        when(pipelineAutoAssignmentService.findByIdLoaded(ruleId)).thenReturn(Optional.of(rule));

        assignmentService.applyAutoAssignRule(ruleId, member1.getId(), UUID.randomUUID());

        verify(userRepository, never()).findById(any());
        verify(assignmentRepository, never()).save(any(Assignment.class));
    }

    @Test
    void applyAutoAssignRule_happyPath_createsAssignment() {
        UUID ruleId = UUID.randomUUID();
        PipelineAutoAssignment rule = ruleFor(pipeline, organization, null);
        rule.setId(ruleId);
        rule.setCreatedBy(assignedBy);

        when(pipelineAutoAssignmentService.findByIdLoaded(ruleId)).thenReturn(Optional.of(rule));
        when(userRepository.findById(member1.getId())).thenReturn(Optional.of(memberInOrg(member1, organization)));
        when(assignmentRepository.existsByOrganizationIdAndPipelineIdAndUserId(
                orgId, pipelineId, member1.getId())).thenReturn(false);
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        assignmentService.applyAutoAssignRule(ruleId, member1.getId(), orgId);

        verify(assignmentRepository).save(any(Assignment.class));
        verify(submissionRepository).save(any(Submission.class));
    }

    private static PipelineAutoAssignment ruleFor(Pipeline pipeline, Organization org, String userType) {
        PipelineAutoAssignment rule = new PipelineAutoAssignment();
        rule.setOrganization(org);
        rule.setPipeline(pipeline);
        rule.setUserType(userType);
        return rule;
    }

    private static User memberInOrg(User template, Organization org) {
        User u = new User();
        u.setId(template.getId());
        u.setEmail(template.getEmail());
        u.setName(template.getName());
        u.setRole(template.getRole());
        u.setStatus(template.getStatus());
        u.setOrganization(org);
        return u;
    }

    @Test
    void listAssignments_returnsResponsesWithMemberInfo() {
        Assignment assignment = new Assignment();
        assignment.setId(UUID.randomUUID());
        assignment.setPipeline(pipeline);
        assignment.setOrganization(organization);
        assignment.setUser(member1);
        assignment.setAssignedBy(assignedBy);
        assignment.setCreatedAt(Instant.now());

        Submission submission = new Submission();
        submission.setId(UUID.randomUUID());
        submission.setStatus(SubmissionStatus.EVALUATED);

        // Wire submission to its assignment+user so the in-memory join in
        // listAssignments can re-pair them after the batched fetch.
        submission.setAssignment(assignment);
        submission.setUser(member1);

        when(assignmentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId))
                .thenReturn(List.of(assignment));
        when(submissionRepository.findByAssignmentIdIn(List.of(assignment.getId())))
                .thenReturn(List.of(submission));

        List<AssignmentResponse> result = assignmentService.listAssignments(orgId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userName()).isEqualTo("Member One");
        assertThat(result.get(0).status()).isEqualTo(SubmissionStatus.EVALUATED);
    }
}
