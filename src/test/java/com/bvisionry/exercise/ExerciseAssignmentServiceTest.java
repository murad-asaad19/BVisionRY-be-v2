package com.bvisionry.exercise;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.exercise.dto.CreateExerciseAssignmentRequest;
import com.bvisionry.exercise.entity.ExerciseAssignment;
import com.bvisionry.exercise.entity.ExerciseSubmission;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.repository.ExerciseAssignmentRepository;
import com.bvisionry.exercise.repository.ExerciseRowRepository;
import com.bvisionry.exercise.repository.ExerciseSubmissionRepository;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
import com.bvisionry.membertype.MemberTypeService;
import com.bvisionry.notification.push.PushNotificationService;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExerciseAssignmentServiceTest {

    @Mock private ExerciseAssignmentRepository assignmentRepository;
    @Mock private ExerciseSubmissionRepository submissionRepository;
    @Mock private ExerciseRowRepository rowRepository;
    @Mock private ExerciseTemplateRepository templateRepository;
    @Mock private ExerciseSubmissionService submissionService;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRepository userRepository;
    @Mock private MemberTypeService memberTypeService;
    @Mock private AuditService auditService;
    @Mock private PushNotificationService pushNotificationService;

    @InjectMocks private ExerciseAssignmentService service;

    private UUID orgId;
    private UUID templateId;
    private Organization organization;
    private ExerciseTemplate template;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        templateId = UUID.randomUUID();

        organization = new Organization();
        organization.setId(orgId);
        organization.setName("Test Org");

        template = new ExerciseTemplate();
        template.setId(templateId);
        template.setName("Test Exercise");
        template.setStatus(ExerciseTemplateStatus.PUBLISHED);

        User caller = new User();
        caller.setId(UUID.randomUUID());
        caller.setRole(UserRole.ORG_ADMIN);
        caller.setOrganization(organization);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null, List.of()));

        ExerciseAssignment provision = new ExerciseAssignment();
        provision.setOrganization(organization);
        provision.setTemplate(template);
        provision.setUser(null);
        lenient().when(assignmentRepository.findProvision(any(), any()))
                .thenReturn(Optional.of(provision));
        lenient().when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        lenient().when(templateRepository.findById(templateId)).thenReturn(Optional.of(template));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private User memberOf(Organization org) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setName("Member");
        user.setEmail("member@test.com");
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return user;
    }

    @Test
    void createAssignment_memberIdFromAnotherOrg_isNotFoundAndSavesNothing() {
        Organization otherOrg = new Organization();
        otherOrg.setId(UUID.randomUUID());
        User foreign = memberOf(otherOrg);
        when(userRepository.findAllById(List.of(foreign.getId()))).thenReturn(List.of(foreign));

        CreateExerciseAssignmentRequest request = new CreateExerciseAssignmentRequest(
                templateId, List.of(foreign.getId()), null, null, false);

        assertThatThrownBy(() -> service.createAssignment(orgId, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(assignmentRepository, never()).save(any(ExerciseAssignment.class));
        verify(submissionRepository, never()).save(any(ExerciseSubmission.class));
    }

    @Test
    void createAssignment_memberOfSameOrg_isAssigned() {
        User member = memberOf(organization);
        when(userRepository.findAllById(List.of(member.getId()))).thenReturn(List.of(member));
        when(assignmentRepository.findExistingAssignedUserIdsIn(eq(orgId), eq(templateId), any()))
                .thenReturn(List.of());
        when(assignmentRepository.save(any(ExerciseAssignment.class))).thenAnswer(inv -> {
            ExerciseAssignment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(submissionRepository.save(any(ExerciseSubmission.class))).thenAnswer(inv -> {
            ExerciseSubmission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        CreateExerciseAssignmentRequest request = new CreateExerciseAssignmentRequest(
                templateId, List.of(member.getId()), null, null, false);

        service.createAssignment(orgId, request);

        verify(assignmentRepository).save(argThat(a -> member.equals(a.getUser())));
    }
}
