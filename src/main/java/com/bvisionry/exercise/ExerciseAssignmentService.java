package com.bvisionry.exercise;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.exercise.dto.CreateExerciseAssignmentRequest;
import com.bvisionry.exercise.dto.ExerciseAssignmentResponse;
import com.bvisionry.exercise.entity.ExerciseAssignment;
import com.bvisionry.exercise.entity.ExerciseRow;
import com.bvisionry.exercise.entity.ExerciseSubmission;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.repository.ExerciseAssignmentRepository;
import com.bvisionry.exercise.repository.ExerciseRowRepository;
import com.bvisionry.exercise.repository.ExerciseSubmissionRepository;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
import com.bvisionry.membertype.MemberTypeService;
import com.bvisionry.notification.push.NotificationType;
import com.bvisionry.notification.push.PushNotificationService;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Distribution of exercise templates — the exercise mirror of
 * {@link com.bvisionry.assessment.AssignmentService}: super admins provision a
 * published template to an org, org admins distribute it to members (all / by
 * member type / selected), each member assignment materializing one
 * {@link ExerciseSubmission}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseAssignmentService {

    /** Filters the org assignment list. */
    public enum ExerciseAssignmentListScope {
        PROVISIONS,
        MEMBERS,
        ALL
    }

    private final ExerciseAssignmentRepository assignmentRepository;
    private final ExerciseSubmissionRepository submissionRepository;
    private final ExerciseRowRepository rowRepository;
    private final ExerciseTemplateRepository templateRepository;
    private final ExerciseSubmissionService submissionService;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final MemberTypeService memberTypeService;
    private final AuditService auditService;
    private final PushNotificationService pushNotificationService;

    @Transactional
    public List<ExerciseAssignmentResponse> createAssignment(UUID orgId,
                                                             CreateExerciseAssignmentRequest request) {
        if (request.assignToOrganization()) {
            return List.of(createOrganizationProvision(orgId, request));
        }

        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));
        ExerciseTemplate template = requirePublishedTemplate(request.templateId());

        // Org admins may only distribute against an existing provision; a super
        // admin assigning directly auto-creates the missing provision so the
        // org keeps access afterwards (same invariant as pipeline assignments).
        ExerciseAssignment provision = assignmentRepository
                .findProvision(orgId, template.getId())
                .orElse(null);
        if (!SecurityUtils.isSuperAdmin() && provision == null) {
            throw new BadRequestException(
                    "This exercise has not been provisioned to your organization. "
                            + "Contact your platform administrator.");
        }

        List<User> members = resolveTargetMembers(orgId, request);
        if (members.isEmpty()) {
            throw new BadRequestException("No eligible members to assign this exercise to.");
        }

        List<UUID> memberIds = members.stream().map(User::getId).toList();
        Set<UUID> alreadyAssigned = Set.copyOf(assignmentRepository.findExistingAssignedUserIdsIn(
                orgId, template.getId(), memberIds));
        List<User> newMembers = members.stream()
                .filter(m -> !alreadyAssigned.contains(m.getId()))
                .toList();
        if (newMembers.isEmpty()) {
            throw new BadRequestException(
                    "All selected members already have this exercise assigned in this organization.");
        }

        UUID assignerId = SecurityUtils.getCurrentUserId();
        Instant deadline = request.deadline() != null
                ? request.deadline()
                : (provision != null ? provision.getDeadline() : null);

        if (provision == null) {
            provisionTemplate(org, template, assignerId, deadline);
        }

        List<ExerciseAssignmentResponse> responses = new ArrayList<>();
        for (User member : newMembers) {
            responses.add(createAssignmentForMember(org, template, member, assignerId, deadline));
        }
        return responses;
    }

    private ExerciseAssignmentResponse createOrganizationProvision(UUID orgId,
                                                                   CreateExerciseAssignmentRequest request) {
        if (!SecurityUtils.isSuperAdmin()) {
            throw new BadRequestException("Only super admins can provision exercises to organizations.");
        }
        if (!request.isAssignAll()) {
            throw new BadRequestException("Organization provisioning cannot target specific members.");
        }

        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));
        ExerciseTemplate template = requirePublishedTemplate(request.templateId());

        if (assignmentRepository.existsByOrganizationIdAndTemplateIdAndUserIsNull(orgId, template.getId())) {
            throw new BadRequestException("This exercise is already provisioned to this organization.");
        }

        ExerciseAssignment saved = provisionTemplate(
                org, template, SecurityUtils.getCurrentUserId(), request.deadline());
        log.info("Provisioned exercise template {} to org {}", template.getId(), orgId);
        return toResponse(saved, null, 0);
    }

    private ExerciseAssignment provisionTemplate(Organization org, ExerciseTemplate template,
                                                 UUID assignerId, Instant deadline) {
        ExerciseAssignment provision = new ExerciseAssignment();
        provision.setTemplate(template);
        provision.setOrganization(org);
        provision.setUser(null);
        provision.setAssignedBy(assignerId);
        provision.setDeadline(deadline);
        ExerciseAssignment saved = assignmentRepository.save(provision);

        auditService.log(assignerId, org.getId(), OrgAuditActions.EXERCISE_PROVISIONED,
                OrgAuditActions.ENTITY_ORGANIZATION, org.getId(),
                Map.of("exerciseName", template.getName(),
                       "templateId", template.getId().toString()));
        return saved;
    }

    private ExerciseAssignmentResponse createAssignmentForMember(Organization org,
                                                                 ExerciseTemplate template,
                                                                 User member, UUID assignerId,
                                                                 Instant deadline) {
        ExerciseAssignment assignment = new ExerciseAssignment();
        assignment.setTemplate(template);
        assignment.setOrganization(org);
        assignment.setUser(member);
        assignment.setAssignedBy(assignerId);
        assignment.setDeadline(deadline);
        ExerciseAssignment savedAssignment = assignmentRepository.save(assignment);

        ExerciseSubmission submission = new ExerciseSubmission();
        submission.setAssignment(savedAssignment);
        submission.setUser(member);
        ExerciseSubmission savedSubmission = submissionRepository.save(submission);
        seedStarterRows(savedSubmission, template);

        auditService.log(assignerId, org.getId(), OrgAuditActions.EXERCISE_ASSIGNED,
                OrgAuditActions.ENTITY_EXERCISE_SUBMISSION, savedSubmission.getId(),
                Map.of("exerciseName", template.getName(),
                       "memberName", member.getName()));
        pushNotificationService.notifyUser(member.getId(), NotificationType.EXERCISE_ASSIGNED,
                "New exercise assigned",
                "\"" + template.getName() + "\" is ready for you.",
                "/app/exercises/" + savedSubmission.getId());

        return toResponse(savedAssignment, savedSubmission, 0);
    }

    /** Materialize the template's starter rows into the fresh submission. */
    private void seedStarterRows(ExerciseSubmission submission, ExerciseTemplate template) {
        List<Map<String, Object>> starters = template.getStarterRows();
        if (starters == null) {
            return;
        }
        int order = 0;
        for (Map<String, Object> cells : starters) {
            ExerciseRow row = new ExerciseRow();
            row.setSubmission(submission);
            row.setDisplayOrder(order++);
            row.setCells(cells != null ? cells : Map.of());
            row.setStarter(true);
            rowRepository.save(row);
        }
    }

    @Transactional(readOnly = true)
    public List<ExerciseAssignmentResponse> listAssignments(UUID orgId,
                                                            ExerciseAssignmentListScope scope) {
        ExerciseAssignmentListScope effectiveScope =
                scope != null ? scope : ExerciseAssignmentListScope.ALL;

        List<ExerciseAssignment> assignments = switch (effectiveScope) {
            case PROVISIONS -> assignmentRepository.findProvisionsByOrganizationId(orgId);
            case MEMBERS -> assignmentRepository.findMemberAssignmentsByOrganizationId(orgId);
            case ALL -> assignmentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        };
        if (assignments.isEmpty()) {
            return List.of();
        }

        List<UUID> assignmentIds = assignments.stream().map(ExerciseAssignment::getId).toList();
        Map<UUID, ExerciseSubmission> submissionsByAssignment = new HashMap<>();
        for (ExerciseSubmission s : submissionRepository.findByAssignmentIdIn(assignmentIds)) {
            submissionsByAssignment.put(s.getAssignment().getId(), s);
        }
        Map<UUID, Long> openCounts = submissionService.openCommentCounts(
                submissionsByAssignment.values().stream().map(ExerciseSubmission::getId).toList());

        return assignments.stream()
                .map(a -> {
                    ExerciseSubmission submission = submissionsByAssignment.get(a.getId());
                    long openCount = submission != null
                            ? openCounts.getOrDefault(submission.getId(), 0L)
                            : 0;
                    return toResponse(a, submission, openCount);
                })
                .toList();
    }

    @Transactional
    public void cancelAssignment(UUID orgId, UUID assignmentId) {
        ExerciseAssignment assignment = requireAssignmentInOrg(orgId, assignmentId);

        if (assignment.getUser() == null && !SecurityUtils.isSuperAdmin()) {
            throw new BadRequestException("Only super admins can remove an organization provision.");
        }

        ExerciseSubmission submission = assignment.getUser() != null
                ? submissionRepository.findByAssignmentId(assignmentId).orElse(null)
                : null;
        if (submission != null && submission.getSubmittedAt() != null) {
            throw new BadRequestException(
                    "Cannot remove an exercise that has already been submitted.");
        }

        assignmentRepository.delete(assignment);
        log.info("Cancelled exercise assignment {} for org {}", assignmentId, orgId);
    }

    /**
     * Org-scope gate for every admin operation on an assignment: missing and
     * cross-org ids are both "not found" so ids can't be probed across tenants.
     */
    @Transactional(readOnly = true)
    public ExerciseAssignment requireAssignmentInOrg(UUID orgId, UUID assignmentId) {
        ExerciseAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));
        if (!assignment.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }
        return assignment;
    }

    private ExerciseTemplate requirePublishedTemplate(UUID templateId) {
        ExerciseTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Exercise", templateId.toString()));
        if (template.getStatus() != ExerciseTemplateStatus.PUBLISHED) {
            throw new BadRequestException("Exercise must be published before it can be assigned");
        }
        return template;
    }

    private List<User> resolveTargetMembers(UUID orgId, CreateExerciseAssignmentRequest request) {
        String userType = request.userType();
        memberTypeService.requireExists(userType);
        if (request.isAssignAll()) {
            if (userType != null) {
                return userRepository.findByOrganizationIdAndStatusAndUserType(
                        orgId, UserStatus.ACTIVE, userType);
            }
            return userRepository.findByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE);
        }
        List<User> byIds = userRepository.findAllById(request.memberIds());
        if (userType != null) {
            return byIds.stream()
                    .filter(m -> userType.equals(m.getUserType()))
                    .toList();
        }
        return byIds;
    }

    private ExerciseAssignmentResponse toResponse(ExerciseAssignment assignment,
                                                  ExerciseSubmission submission,
                                                  long openCommentCount) {
        User user = assignment.getUser();
        return new ExerciseAssignmentResponse(
                assignment.getId(),
                assignment.getTemplate().getId(),
                assignment.getTemplate().getName(),
                assignment.getTemplate().getStatus(),
                assignment.getOrganization().getId(),
                user != null ? user.getId() : null,
                user != null ? user.getName() : null,
                user != null ? user.getEmail() : null,
                assignment.getAssignedBy(),
                assignment.getDeadline(),
                submission != null ? submission.getId() : null,
                submission != null ? submission.getStatus() : null,
                submission != null ? submission.getSubmittedAt() : null,
                openCommentCount,
                assignment.getCreatedAt());
    }
}
