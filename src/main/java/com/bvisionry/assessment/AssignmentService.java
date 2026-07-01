package com.bvisionry.assessment;

import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.assessment.dto.AssessmentSummaryResponse;
import com.bvisionry.assessment.dto.AssignmentDetailResponse;
import com.bvisionry.assessment.dto.AssignmentResponse;
import com.bvisionry.assessment.dto.CreateAssignmentRequest;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.PipelineAutoAssignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.evaluation.EvaluationService;
import com.bvisionry.membertype.MemberTypeService;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.reporting.dto.PersonalInfoEntry;
import com.bvisionry.reporting.service.PersonalInfoResolver;
import com.bvisionry.survey.dto.SurveySummary;
import com.bvisionry.survey.entity.SurveyResponse;
import com.bvisionry.survey.repository.SurveyResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentService {

    /** Filters the org assignment list (see {@link #listAssignments}). */
    public enum AssignmentListScope {
        /** Rows with {@code user_id IS NULL} — pipeline provisioned to the org only. */
        PROVISIONS,
        /** Rows with a member — per-user assignments. */
        MEMBERS,
        /** Every assignment row for the org. */
        ALL
    }

    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final AnswerRepository answerRepository;
    private final PipelineRepository pipelineRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final AuditService auditService;
    private final EvaluationService evaluationService;
    private final AssessmentService assessmentService;
    private final SurveyResponseRepository surveyResponseRepository;
    private final MemberTypeService memberTypeService;
    private final PersonalInfoResolver personalInfoResolver;
    private final PipelineAutoAssignmentService pipelineAutoAssignmentService;
    private final FrontendUrls frontendUrls;

    /**
     * Create one Assignment (+ one Submission) per target member. The caller's
     * request may still say "all members" or pass a list of IDs — we resolve those
     * into concrete users here and fan out.
     */
    @Transactional
    public List<AssignmentResponse> createAssignment(UUID orgId, CreateAssignmentRequest request) {
        if (request.assignToOrganization()) {
            return List.of(createOrganizationProvision(orgId, request));
        }

        // Auto-assign is fundamentally an "all members" operation — picking a
        // specific subset and then asking the system to extend that to future
        // joiners has no coherent meaning. Reject early with a clear message
        // rather than silently ignoring one of the two intents.
        if (request.autoAssignFutureMembers() && !request.isAssignAll()) {
            throw new BadRequestException(
                    "Auto-assign requires the 'all members' mode (optionally with a userType filter); "
                            + "remove the explicit memberIds list to enable it.");
        }

        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));

        Pipeline pipeline = pipelineRepository.findById(request.pipelineId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", request.pipelineId().toString()));

        if (pipeline.getStatus() != PipelineStatus.PUBLISHED) {
            throw new BadRequestException("Pipeline must be published before it can be assigned");
        }

        // An org provision (user == null) is the platform's grant of this
        // pipeline to the org. Org admins may only distribute against an existing
        // provision; super admins may assign directly — and we create the missing
        // provision below so the org keeps access afterwards.
        Assignment provision = assignmentRepository
                .findProvision(orgId, pipeline.getId())
                .orElse(null);
        if (!SecurityUtils.isSuperAdmin() && provision == null) {
            throw new BadRequestException(
                    "This pipeline has not been provisioned to your organization. "
                            + "Contact your platform administrator.");
        }

        List<User> members = resolveTargetMembers(orgId, request);

        // An empty member set is fine when auto-assign is on — the rule itself
        // is the deliverable (e.g. registering a rule on an org with zero
        // current members so future joiners are auto-assigned).
        if (members.isEmpty() && !request.autoAssignFutureMembers()) {
            throw new BadRequestException("No eligible members to assign this pipeline to.");
        }

        // Per-member dedup: skip anyone who already has this pipeline assigned in
        // this org so we don't violate the unique (org, pipeline, user) constraint.
        // Single batched query replaces the previous per-member exists check.
        List<UUID> memberIds = members.stream().map(User::getId).toList();
        Set<UUID> alreadyAssigned = memberIds.isEmpty()
                ? Set.of()
                : Set.copyOf(assignmentRepository.findExistingAssignedUserIdsIn(
                        orgId, pipeline.getId(), memberIds));
        List<User> newMembers = members.stream()
                .filter(m -> !alreadyAssigned.contains(m.getId()))
                .toList();

        // When auto-assign is on, an empty newMembers list is fine — the rule
        // itself is the deliverable, e.g. an org with zero current matching
        // members can still register the rule for future joiners.
        if (newMembers.isEmpty() && !request.autoAssignFutureMembers()) {
            throw new BadRequestException(
                    "All selected members already have this pipeline assigned in this organization.");
        }

        // Derive the assigner from the authenticated principal — never trust a
        // client-supplied {@code assignedBy} field for audit attribution.
        UUID assignerId = SecurityUtils.getCurrentUserId();

        // Members inherit the provision's defaults: an org admin's explicit
        // deadline still wins, but when they leave it blank the super admin's
        // provisioned "Default deadline" applies. Org admins can't set check-ins,
        // so they always take the provisioned count (see resolveMaxCheckIns).
        int maxCheckIns = resolveMaxCheckIns(request, provision);
        Instant deadline = request.deadline() != null
                ? request.deadline()
                : (provision != null ? provision.getDeadline() : null);

        // Maintain the invariant V112 backfilled — every member row implies an
        // org provision. A super admin assigning members to a not-yet-provisioned
        // pipeline auto-creates the provision (seeded with these same defaults)
        // so org admins can keep distributing it afterwards.
        if (provision == null) {
            provision = provisionPipeline(org, pipeline, assignerId, deadline, maxCheckIns);
        }

        List<AssignmentResponse> responses = new ArrayList<>();
        for (User member : newMembers) {
            AssignmentCreated created = createAssignmentForMember(
                    org, pipeline, member, assignerId, deadline, maxCheckIns);
            responses.add(toAssignmentResponse(created.assignment(), created.submission(), 1));
        }

        if (request.autoAssignFutureMembers()) {
            pipelineAutoAssignmentService.upsertRule(
                    org, pipeline, request.userType(), deadline, assignerId, maxCheckIns);
        }

        return responses;
    }

    /**
     * Super-admin flow: assign a published pipeline to an organization without
     * picking members. Org admins see the provision and distribute it to members.
     */
    private AssignmentResponse createOrganizationProvision(UUID orgId, CreateAssignmentRequest request) {
        if (!SecurityUtils.isSuperAdmin()) {
            throw new BadRequestException("Only super admins can provision pipelines to organizations.");
        }
        if (request.autoAssignFutureMembers()) {
            throw new BadRequestException(
                    "Organization provisioning cannot be combined with auto-assign rules.");
        }
        if (!request.isAssignAll()) {
            throw new BadRequestException(
                    "Organization provisioning cannot target specific members.");
        }

        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));

        Pipeline pipeline = pipelineRepository.findById(request.pipelineId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", request.pipelineId().toString()));

        if (pipeline.getStatus() != PipelineStatus.PUBLISHED) {
            throw new BadRequestException("Pipeline must be published before it can be assigned");
        }

        if (assignmentRepository.existsByOrganizationIdAndPipelineIdAndUserIsNull(orgId, pipeline.getId())) {
            throw new BadRequestException(
                    "This pipeline is already provisioned to this organization.");
        }

        UUID assignerId = SecurityUtils.getCurrentUserId();
        int maxCheckIns = resolveMaxCheckIns(request, null);

        Assignment saved = provisionPipeline(org, pipeline, assignerId, request.deadline(), maxCheckIns);

        log.info("Provisioned pipeline {} to org {}", pipeline.getId(), orgId);
        return toAssignmentResponse(saved, null, 0);
    }

    /**
     * Create, persist, and audit the org-level provision row (user == null). The
     * {@code uq_assignments_org_pipeline_provision} partial index allows at most
     * one per (org, pipeline); callers ensure one doesn't already exist. Shared by
     * explicit super-admin provisioning and the auto-provision that backs a super
     * admin's direct member assignment, so "member rows imply a provision" holds
     * on every write path.
     */
    private Assignment provisionPipeline(Organization org, Pipeline pipeline, UUID assignerId,
                                         Instant deadline, int maxCheckIns) {
        Assignment provision = new Assignment();
        provision.setPipeline(pipeline);
        provision.setOrganization(org);
        provision.setUser(null);
        provision.setAssignedBy(assignerId);
        provision.setDeadline(deadline);
        provision.setMaxCheckIns(maxCheckIns);
        Assignment saved = assignmentRepository.save(provision);

        auditService.log(assignerId, org.getId(), OrgAuditActions.ASSESSMENT_PROVISIONED,
                OrgAuditActions.ENTITY_ORGANIZATION, org.getId(),
                Map.of("pipelineName", pipeline.getName(),
                       "pipelineId", pipeline.getId().toString()));
        return saved;
    }

    /**
     * Creates one Assignment + one Submission for a single member, logs the
     * audit entry, and queues the assignment email. Has no {@code @Transactional}
     * of its own so it always runs inside the caller's transaction (the bulk
     * {@link #createAssignment} or the {@code REQUIRES_NEW} {@link #applyAutoAssignRule}).
     */
    public AssignmentCreated createAssignmentForMember(Organization org, Pipeline pipeline, User member,
                                                       UUID assignerId, Instant deadline, int maxCheckIns) {
        Assignment assignment = new Assignment();
        assignment.setPipeline(pipeline);
        assignment.setOrganization(org);
        assignment.setUser(member);
        assignment.setAssignedBy(assignerId);
        assignment.setDeadline(deadline);
        assignment.setMaxCheckIns(maxCheckIns);
        Assignment savedAssignment = assignmentRepository.save(assignment);

        Submission sub = new Submission();
        sub.setAssignment(savedAssignment);
        sub.setUser(member);
        sub.setStatus(SubmissionStatus.IN_PROGRESS);
        sub.setStartedAt(Instant.now());
        Submission savedSubmission = submissionRepository.save(sub);

        // Surface the assignment in the org Activity feed. Actor = the admin
        // who assigned it (already an org member, so the feed query picks it
        // up), entity = the new submission so future per-submission audits
        // (submit, evaluate, deadline-extend) thread together.
        auditService.log(assignerId, org.getId(), OrgAuditActions.ASSESSMENT_ASSIGNED,
                OrgAuditActions.ENTITY_SUBMISSION, savedSubmission.getId(),
                Map.of("pipelineName", pipeline.getName(),
                       "memberName", member.getName()));

        // Fire-and-forget email — don't block the response if SMTP is slow.
        sendAssignmentEmailAsync(member, pipeline, savedAssignment, savedSubmission);

        return new AssignmentCreated(savedAssignment, savedSubmission);
    }

    public record AssignmentCreated(Assignment assignment, Submission submission) {}

    /**
     * Materialise an assignment for a single user under an auto-assign rule.
     * Takes IDs (not entities) so the rule + user are reloaded fresh inside
     * this REQUIRES_NEW transaction — the caller in
     * {@link AutoAssignmentEventHandler} runs after the joining transaction
     * has already committed, so any entity it loaded is detached and any
     * lazy proxy on it would throw on access here.
     *
     * <p>REQUIRES_NEW also keeps a single rule failure (e.g. pipeline archived
     * mid-flight) from aborting the rest of the listener's batch.
     *
     * <p>{@code expectedOrgId} is a defensive guard for the public proxy
     * boundary: if a future caller passes a {@code ruleId} from a different
     * org than the joining user belongs to, the rule is silently skipped
     * rather than producing a cross-tenant assignment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyAutoAssignRule(UUID ruleId, UUID userId, UUID expectedOrgId) {
        PipelineAutoAssignment rule = pipelineAutoAssignmentService.findByIdLoaded(ruleId).orElse(null);
        if (rule == null) {
            // Rule was deleted between the listener fetch and now — nothing to do.
            return;
        }
        if (!rule.getOrganization().getId().equals(expectedOrgId)) {
            // Defensive: caller passed a rule from another org.
            return;
        }
        // Skip if the pipeline is no longer publishable (admin archived or
        // reverted it after the rule was created). The cleanup hook in
        // PipelineService deletes rules in that case, but this guard covers
        // the race window between transition and rule deletion.
        if (rule.getPipeline().getStatus() != PipelineStatus.PUBLISHED) {
            return;
        }

        User member = userRepository.findById(userId).orElse(null);
        if (member == null) {
            return;
        }
        // Status + membership re-check: the user may have been deactivated or
        // moved between the originating membership transaction committing and
        // this AFTER_COMMIT listener firing.
        if (member.getStatus() != UserStatus.ACTIVE) {
            return;
        }
        if (member.getOrganization() == null
                || !member.getOrganization().getId().equals(rule.getOrganization().getId())) {
            return;
        }

        if (assignmentRepository.existsByOrganizationIdAndPipelineIdAndUserId(
                rule.getOrganization().getId(), rule.getPipeline().getId(), userId)) {
            return;
        }
        // Attribution: the rule's creator is recorded as the assigner. The
        // newly-joined user is not part of the security context here (event
        // fires after commit, no authenticated principal), so SecurityUtils
        // is not applicable.
        createAssignmentForMember(rule.getOrganization(), rule.getPipeline(), member,
                rule.getCreatedBy(), rule.getDeadline(), rule.getMaxCheckIns());
        log.info("Auto-assigned pipeline {} to user {} in org {} via rule {}",
                rule.getPipeline().getId(), userId, rule.getOrganization().getId(), rule.getId());
    }

    /**
     * The check-in count to stamp on the assignments being created. Super admins
     * set it explicitly; org admins can't, so they inherit the provision's count
     * (the value the super admin chose when provisioning, defaulting to 1 when
     * there is no provision yet).
     */
    private int resolveMaxCheckIns(CreateAssignmentRequest request, Assignment provision) {
        if (SecurityUtils.isSuperAdmin()) {
            return request.maxCheckInsOrDefault();
        }
        if (request.maxCheckIns() != null && request.maxCheckIns() != 1) {
            throw new BadRequestException("Only super admins can configure max check-ins.");
        }
        return provision != null ? provision.getMaxCheckIns() : 1;
    }

    private List<User> resolveTargetMembers(UUID orgId, CreateAssignmentRequest request) {
        String userType = request.userType();
        // Reject unknown codes up-front so the caller gets a clear error instead
        // of silently seeing "No eligible members" from a typo.
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

    private void sendAssignmentEmailAsync(User member, Pipeline pipeline,
                                           Assignment assignment, Submission submission) {
        emailService.sendAssessmentAssignedAsync(
                member.getEmail(),
                member.getName(),
                pipeline.getName(),
                assignment.getDeadline(),
                frontendUrls.path("/my/assessments/" + submission.getId()));
    }

    @Transactional(readOnly = true)
    public List<AssignmentResponse> listAssignments(UUID orgId, AssignmentListScope scope) {
        AssignmentListScope effectiveScope = scope != null
                ? scope
                : (SecurityUtils.isSuperAdmin() ? AssignmentListScope.ALL : AssignmentListScope.PROVISIONS);

        List<Assignment> assignments = switch (effectiveScope) {
            case PROVISIONS -> assignmentRepository.findProvisionsByOrganizationIdOrderByCreatedAtDesc(orgId);
            case MEMBERS -> assignmentRepository.findMemberAssignmentsByOrganizationIdOrderByCreatedAtDesc(orgId);
            case ALL -> assignmentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        };
        if (assignments.isEmpty()) {
            return List.of();
        }

        // Batch-load submissions for all these assignments in one round-trip
        // (replaces the previous per-assignment findByAssignmentIdAndUserId loop).
        // We re-apply the (assignmentId, userId) scoping in memory to preserve
        // the ownership match the per-row call used to enforce.
        // For multi-check-in pipelines a single (assignment, user) pair can
        // have N rows — keep only the most recent by createdAt so the admin
        // list shows the latest check-in's status.
        List<UUID> assignmentIds = assignments.stream().map(Assignment::getId).toList();
        Map<String, Submission> submissionsByKey = new HashMap<>();
        Map<String, Integer> checkInCountByKey = new HashMap<>();
        for (Submission s : submissionRepository.findByAssignmentIdIn(assignmentIds)) {
            UUID userId = s.getUser() != null ? s.getUser().getId() : null;
            String key = submissionKey(s.getAssignment().getId(), userId);
            submissionsByKey.merge(key, s, (existing, incoming) ->
                    incoming.getCreatedAt().isAfter(existing.getCreatedAt()) ? incoming : existing);
            checkInCountByKey.merge(key, 1, Integer::sum);
        }

        return assignments.stream()
                .map(a -> {
                    UUID userId = a.getUser() != null ? a.getUser().getId() : null;
                    String key = submissionKey(a.getId(), userId);
                    return toAssignmentResponse(a, submissionsByKey.get(key),
                            checkInCountByKey.getOrDefault(key, 0));
                })
                .toList();
    }

    private static String submissionKey(UUID assignmentId, UUID userId) {
        return assignmentId + ":" + (userId == null ? "" : userId);
    }

    @Transactional
    public void cancelAssignment(UUID orgId, UUID assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));

        if (!assignment.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }

        // Removing an org-level provision (user == null) is a super-admin act:
        // the provision is the platform's grant to the org. Org admins may only
        // cancel their own member assignments, not undo the provision itself.
        if (assignment.getUser() == null && !SecurityUtils.isSuperAdmin()) {
            throw new BadRequestException(
                    "Only super admins can remove an organization provision.");
        }

        long completedCount = assignmentRepository.countCompletedSubmissions(assignmentId);
        if (completedCount > 0) {
            throw new BadRequestException(
                    "Cannot cancel assignment with " + completedCount + " completed submissions");
        }

        assignmentRepository.delete(assignment);
        log.info("Cancelled assignment {} for org {}", assignmentId, orgId);
    }

    private AssignmentResponse toAssignmentResponse(Assignment a, Submission submission, int checkInCount) {
        User user = a.getUser();
        return new AssignmentResponse(
                a.getId(),
                a.getPipeline().getId(),
                a.getPipeline().getName(),
                a.getPipeline().getStatus(),
                a.getOrganization().getId(),
                user != null ? user.getId() : null,
                user != null ? user.getName() : null,
                user != null ? user.getEmail() : null,
                a.getAssignedBy(),
                a.getDeadline(),
                submission != null ? submission.getId() : null,
                submission != null ? submission.getStatus() : null,
                a.getCreatedAt(),
                a.getMaxCheckIns(),
                checkInCount
        );
    }

    @Transactional(readOnly = true)
    public AssignmentDetailResponse getAssignmentDetail(UUID orgId, UUID assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));
        if (!assignment.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }
        Submission submission = null;
        if (assignment.getUser() != null) {
            submission = submissionRepository
                    .findTopByAssignmentIdAndUserIdOrderByCreatedAtDesc(
                            assignment.getId(), assignment.getUser().getId())
                    .orElse(null);
        }

        Pipeline pipeline = assignment.getPipeline();
        int totalQuestions = pipeline.getPillars() != null
                ? pipeline.getPillars().stream().mapToInt(p -> p.getQuestions().size()).sum()
                : 0;
        int answered = submission != null
                ? (int) answerRepository.countBySubmissionId(submission.getId())
                : 0;

        User user = assignment.getUser();

        // Survey response visibility is restricted to the platform Super Admin
        // (Conductor). Org Admins and members must see no signal that a survey
        // exists or has been submitted, so we skip the lookup entirely for
        // non-super-admin viewers and let `surveySummary` stay null.
        SurveySummary surveySummary = null;
        if (SecurityUtils.isSuperAdmin() && pipeline.getPostCompletionSurveyId() != null) {
            SurveyResponse surveyResponse = submission != null
                    ? surveyResponseRepository
                            .findFirstBySubmissionIdOrderBySubmittedAtDesc(submission.getId())
                            .orElse(null)
                    : null;
            surveySummary = new SurveySummary(
                    pipeline.getPostCompletionSurveyId(),
                    surveyResponse != null ? surveyResponse.getId() : null,
                    surveyResponse != null ? surveyResponse.getSubmittedAt() : null);
        }

        List<PersonalInfoEntry> personalInfo = submission != null
                ? personalInfoResolver.resolve(submission.getId())
                : List.of();

        return new AssignmentDetailResponse(
                assignment.getId(),
                pipeline.getId(),
                pipeline.getName(),
                pipeline.getVersion(),
                assignment.getOrganization().getId(),
                user != null ? user.getId() : null,
                user != null ? user.getName() : null,
                user != null ? user.getEmail() : null,
                assignment.getAssignedBy(),
                assignment.getCreatedAt(),
                assignment.getDeadline(),
                submission != null ? submission.getEffectiveDeadline() : assignment.getDeadline(),
                submission != null ? submission.getId() : null,
                submission != null ? submission.getStatus() : null,
                submission != null ? submission.getStartedAt() : null,
                submission != null ? submission.getSubmittedAt() : null,
                submission != null ? submission.getEvaluatedAt() : null,
                totalQuestions,
                answered,
                submission != null ? submission.getFailureReason() : null,
                surveySummary,
                personalInfo,
                assignment.getMaxCheckIns(),
                (int) submissionRepository.countByAssignmentId(assignment.getId())
        );
    }

    /**
     * Admin view of the member's raw assessment answers for an assignment.
     * Enforces org-scoping then defers to {@link AssessmentService#getAssessmentForAdmin}
     * which bypasses the member-ownership check.
     */
    @Transactional(readOnly = true)
    public AssessmentDetailResponse getAssignmentAnswers(UUID orgId, UUID assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));
        if (!assignment.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }
        if (assignment.getUser() == null) {
            throw new BadRequestException("This provision has not been assigned to a member yet.");
        }
        Submission submission = submissionRepository.requireLatestForAssignment(
                assignment, "No submission exists for this assignment yet.");
        return assessmentService.getAssessmentForAdmin(submission.getId());
    }

    /**
     * Re-sends the "you have an assessment to complete" email for a submission
     * that's still IN_PROGRESS. Rejects completed/failed submissions because a
     * reminder no longer applies to those states.
     */
    @Transactional(readOnly = true)
    public void sendReminder(UUID orgId, UUID assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));
        if (!assignment.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }
        if (assignment.getUser() == null) {
            throw new BadRequestException("Cannot send a reminder for an org-level provision.");
        }
        Submission submission = submissionRepository.requireLatestForAssignment(
                assignment, "Cannot send reminder — no submission exists for this assignment yet.");
        if (submission.getStatus() != SubmissionStatus.IN_PROGRESS) {
            throw new BadRequestException(
                    "Reminders only apply to in-progress assignments (status was "
                            + submission.getStatus() + ").");
        }
        User member = assignment.getUser();
        emailService.sendAssessmentReminder(
                member.getEmail(),
                member.getName(),
                assignment.getPipeline().getName(),
                submission.getEffectiveDeadline(),
                frontendUrls.path("/my/assessments/" + submission.getId()));
        // Log user UUID instead of email to keep PII out of application logs
        // — the user can still be looked up via the UUID for support cases.
        log.info("Reminder sent for assignment {} to user {}", assignmentId, member.getId());
    }

    /**
     * Retries evaluation for a FAILED submission. Delegates to
     * {@link EvaluationService#retryFailedSubmission} which re-queues the async job.
     */
    @Transactional
    public void retryEvaluation(UUID orgId, UUID assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));
        if (!assignment.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }
        if (assignment.getUser() == null) {
            throw new BadRequestException("Cannot retry evaluation for an org-level provision.");
        }
        Submission submission = submissionRepository.requireLatestForAssignment(
                assignment, "No submission exists for this assignment.");
        evaluationService.retryFailedSubmission(submission.getId());
        log.info("Retry-evaluation triggered for assignment {} (submission {})",
                assignmentId, submission.getId());
    }

    /**
     * Member-initiated start of a fresh check-in on a pipeline configured for
     * multiple completions. Creates a new Submission row tied to the same
     * Assignment — previous check-ins remain intact as read-only history.
     *
     * <p>Preconditions:
     * <ul>
     *   <li>Assignment exists and is owned by the calling user.</li>
     *   <li>Assignment has {@code maxCheckIns > 1}.</li>
     *   <li>The latest existing submission is in {@code EVALUATED} state —
     *       members can't open a parallel check-in while one is in progress,
     *       submitted, failed, or pending re-edit.</li>
     * </ul>
     */
    @Transactional
    public AssessmentSummaryResponse startNewCheckIn(UUID assignmentId, UUID userId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));

        // Member can only start a check-in on their own assignment. Treat a
        // mismatch as "not found" so we don't leak the existence of someone
        // else's assignment to an unauthorised caller.
        if (assignment.getUser() == null || !assignment.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }

        if (assignment.getMaxCheckIns() <= 1) {
            throw new BadRequestException("This assignment does not allow multiple check-ins.");
        }

        Submission latest = submissionRepository.requireLatestForAssignment(
                assignment, "No existing submission found for this assignment.");

        if (latest.getStatus() != SubmissionStatus.EVALUATED) {
            throw new BadRequestException(
                    "Finish the current check-in before starting a new one (status was "
                            + latest.getStatus() + ").");
        }

        // Cap on the assignment's configured maxCheckIns. Counting all existing
        // submissions (rather than only EVALUATED ones) ensures a member who
        // burned a check-in via a FAILED evaluation can't side-step the limit
        // by spawning a fresh one before the failed one would have counted.
        long existingCheckIns = submissionRepository.countByAssignmentId(assignmentId);
        if (existingCheckIns >= assignment.getMaxCheckIns()) {
            throw new BadRequestException(
                    "You have used all " + assignment.getMaxCheckIns() + " check-ins for this assignment.");
        }

        Submission checkIn = new Submission();
        checkIn.setAssignment(assignment);
        checkIn.setUser(assignment.getUser());
        checkIn.setStatus(SubmissionStatus.IN_PROGRESS);
        checkIn.setStartedAt(Instant.now());
        Submission saved = submissionRepository.save(checkIn);

        auditService.log(userId, assignment.getOrganization().getId(),
                OrgAuditActions.ASSESSMENT_CHECK_IN_STARTED, OrgAuditActions.ENTITY_SUBMISSION, saved.getId(),
                Map.of("pipelineName", assignment.getPipeline().getName(),
                       "assignmentId", assignmentId.toString()));

        // The new row is by definition the latest check-in for this assignment,
        // and its 1-indexed position is existingCheckIns + 1.
        return assessmentService.toSummary(saved, 0L, (int) existingCheckIns + 1, true);
    }

    @Transactional
    public void extendDeadline(UUID orgId, UUID submissionId, Instant newDeadline) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));

        if (!submission.getAssignment().getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Submission", submissionId.toString());
        }

        if (newDeadline.isBefore(Instant.now())) {
            throw new BadRequestException("New deadline must be in the future");
        }

        Instant previousDeadline = submission.getEffectiveDeadline();
        submission.setDeadlineOverride(newDeadline);
        submissionRepository.save(submission);

        auditService.log(null, orgId, "DEADLINE_EXTENDED", "Submission", submissionId,
                Map.of("previousDeadline", previousDeadline != null ? previousDeadline.toString() : "none",
                       "newDeadline", newDeadline.toString()));
    }
}
