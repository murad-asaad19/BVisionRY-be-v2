package com.bvisionry.organization;

import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.RefreshTokenRepository;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.membertype.MemberTypeService;
import com.bvisionry.organization.dto.ChangeMemberRoleRequest;
import com.bvisionry.organization.dto.ChangeMemberStatusRequest;
import com.bvisionry.organization.dto.MemberResponse;
import com.bvisionry.organization.dto.RemoveMemberResponse;
import com.bvisionry.organization.dto.UpdateMemberProfileRequest;
import com.bvisionry.reporting.service.CacheInvalidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class MemberService {

    private final UserRepository userRepository;
    private final OrganizationService organizationService;
    private final AuditService auditService;
    private final MemberTypeService memberTypeService;
    private final SubmissionRepository submissionRepository;
    private final AssignmentRepository assignmentRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CacheInvalidationService cacheInvalidationService;

    /**
     * Domain placeholder used for the email of removed members. Anonymising
     * the email frees the original address for a fresh invite while keeping
     * the row's unique-email constraint satisfied. The reserved {@code .invalid}
     * TLD (RFC 2606) guarantees no real account can ever land on this domain.
     */
    private static final String ANONYMIZED_EMAIL_DOMAIN = "deleted.bvisionry.invalid";
    private static final String ANONYMIZED_NAME = "Deleted user";

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID orgId) {
        // Listing must work for suspended orgs too — super-admin needs to see
        // who's affected before reactivating, and the Switchboard's members
        // tab is the surface for that. Mutations remain gated by findActiveOrThrow.
        organizationService.findOrThrow(orgId);
        return userRepository.findByOrganizationId(orgId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MemberResponse getMember(UUID orgId, UUID memberId) {
        organizationService.findOrThrow(orgId);
        User user = findMemberInOrg(orgId, memberId);
        return MemberResponse.from(user);
    }

    @Transactional
    public MemberResponse changeStatus(UUID orgId, UUID memberId, ChangeMemberStatusRequest request, UUID actorId) {
        organizationService.findActiveOrThrow(orgId);
        User user = findMemberInOrg(orgId, memberId);
        UserStatus oldStatus = user.getStatus();
        user.setStatus(request.status());
        User saved = userRepository.save(user);
        // Defense-in-depth: setting a member non-ACTIVE must also kill live
        // sessions. The auth layer rejects new access tokens once status is
        // SUSPENDED/DEACTIVATED, but an outstanding refresh token would keep
        // minting fresh access tokens until expiry. Revoke the same way
        // removeMember does so the suspension takes effect immediately.
        if (request.status() != UserStatus.ACTIVE) {
            refreshTokenRepository.revokeAllForUser(memberId, Instant.now());
        }
        auditService.log(actorId, orgId, OrgAuditActions.MEMBER_STATUS_CHANGED,
                OrgAuditActions.ENTITY_ORGANIZATION, orgId,
                Map.of("memberId", memberId.toString(),
                        "oldStatus", oldStatus.name(),
                        "newStatus", request.status().name()));
        return MemberResponse.from(saved);
    }

    /**
     * Update lightweight profile fields (name, member type) for a member of
     * the given org. PATCH semantics: only non-null request fields are applied.
     *
     * Exists so ORG_ADMINs can edit their own org's members from the member
     * list — the SUPER_ADMIN-only PUT /api/users/{id} returned 403 for them.
     * The class-level @PreAuthorize on MemberController already enforces that
     * the actor is super-admin or an admin of this org, so the only extra
     * check needed here is that the target user actually belongs to {@code orgId}
     * (handled by {@link #findMemberInOrg}).
     */
    @Transactional
    public MemberResponse updateProfile(UUID orgId, UUID memberId,
                                         UpdateMemberProfileRequest request, UUID actorId) {
        organizationService.findActiveOrThrow(orgId);
        User user = findMemberInOrg(orgId, memberId);

        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("memberId", memberId.toString());

        if (request.name() != null && !request.name().equals(user.getName())) {
            auditDetails.put("oldName", user.getName());
            auditDetails.put("newName", request.name());
            user.setName(request.name());
        }
        if (request.userType() != null && !request.userType().equals(user.getUserType())) {
            // Mirror UserService.update — reject typos so the user doesn't get
            // orphaned from every member-type filter.
            memberTypeService.requireExists(request.userType());
            auditDetails.put("oldUserType", user.getUserType());
            auditDetails.put("newUserType", request.userType());
            user.setUserType(request.userType());
        }

        User saved = userRepository.save(user);
        // Only emit an audit row if something actually changed (auditDetails
        // always contains memberId, so check for >1).
        if (auditDetails.size() > 1) {
            auditService.log(actorId, orgId, OrgAuditActions.MEMBER_PROFILE_UPDATED,
                    OrgAuditActions.ENTITY_ORGANIZATION, orgId, auditDetails);
        }
        return MemberResponse.from(saved);
    }

    @Transactional
    public MemberResponse changeRole(UUID orgId, UUID memberId, ChangeMemberRoleRequest request, UUID actorId) {
        organizationService.findActiveOrThrow(orgId);
        User user = findMemberInOrg(orgId, memberId);

        if (request.role() == UserRole.SUPER_ADMIN) {
            throw new BadRequestException("Cannot assign SUPER_ADMIN role to organization members");
        }

        // ORG_ADMINs cannot change their own role
        User actor = userRepository.findById(actorId).orElse(null);
        if (actor != null && actor.getRole() == UserRole.ORG_ADMIN && actorId.equals(memberId)) {
            throw new BadRequestException("Org admins cannot change their own role");
        }

        UserRole oldRole = user.getRole();
        user.setRole(request.role());
        User saved = userRepository.save(user);
        auditService.log(actorId, orgId, OrgAuditActions.MEMBER_ROLE_CHANGED,
                OrgAuditActions.ENTITY_ORGANIZATION, orgId,
                Map.of("memberId", memberId.toString(),
                        "oldRole", oldRole.name(),
                        "newRole", request.role().name()));
        return MemberResponse.from(saved);
    }

    /**
     * H-S5: Org-admin "Clear Responses" action. Deletes every submission
     * belonging to {@code memberId} within {@code orgId} — answers,
     * pillar evaluations, overall summaries, and survey responses are
     * removed via DB-level ON DELETE CASCADE on submission_id (see V4/V5
     * migrations). Submissions in OTHER orgs the user belongs to are
     * untouched. The audit row records the count for forensics.
     */
    @Transactional
    public void clearResponses(UUID orgId, UUID memberId, UUID actorId) {
        organizationService.findActiveOrThrow(orgId);
        findMemberInOrg(orgId, memberId);
        int deleted = deleteSubmissionsForMemberInOrg(orgId, memberId);
        auditService.log(actorId, orgId, OrgAuditActions.CLEAR_RESPONSES, OrgAuditActions.ENTITY_USER, memberId,
                Map.of("submissionsDeleted", String.valueOf(deleted)));
    }

    /**
     * Remove a member from an organization. The user row is preserved (so
     * existing FKs from audit_logs, pipelines.created_by, etc. remain valid)
     * but their identity is scrubbed: email is rewritten to a domain-reserved
     * placeholder so the original address can be re-invited as a fresh user,
     * status flips to DEACTIVATED, password and SSO bindings are cleared,
     * organization membership is detached, and any active refresh tokens are
     * revoked so an in-flight session can't keep the door open.
     *
     * <p>{@code wipeAssessments} controls what happens to assessment history:
     * <ul>
     *   <li>{@code false} — assignments stay attached to this org so dashboards
     *       and insight reports keep their historical scores, just attributed
     *       to the anonymised user. The everyday case for "user can't log in,
     *       reset and re-invite, keep the team's data".</li>
     *   <li>{@code true} — assignments are bulk-deleted (cascading to
     *       submissions, answers, pillar evaluations, overall summaries,
     *       survey responses, and pillar unlock rows). The complete-wipe path
     *       for GDPR-style erasure or test data cleanup.</li>
     * </ul>
     *
     * <p>Last ORG_ADMIN protection: if removing this user would leave the
     * organization with zero ORG_ADMINs, the action is rejected; the operator
     * must promote someone else first.
     */
    @Transactional
    public RemoveMemberResponse removeMember(UUID orgId, UUID memberId,
                                              boolean wipeAssessments, UUID actorId) {
        organizationService.findActiveOrThrow(orgId);
        User user = requireRemovableMember(orgId, memberId);

        int assignmentsDeleted = 0;
        if (wipeAssessments) {
            // Submissions cascade from assignments (V33) and their children
            // (answers, pillar_evaluations, overall_summaries, survey_responses,
            // pillar unlock rows) cascade from submissions, so a single bulk
            // delete on assignments collapses the whole tree for this (user, org).
            assignmentsDeleted = assignmentRepository.deleteByUserIdAndOrganizationId(memberId, orgId);
        }

        anonymizeUser(user);
        userRepository.save(user);

        // Kill any live session — once status is DEACTIVATED the auth layer
        // would reject new requests, but an outstanding refresh token would
        // still exchange for a fresh access token until expiry. Revoking
        // explicitly closes that window.
        refreshTokenRepository.revokeAllForUser(memberId, Instant.now());

        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("wipeAssessments", String.valueOf(wipeAssessments));
        auditDetails.put("assignmentsDeleted", String.valueOf(assignmentsDeleted));
        auditService.log(actorId, orgId, OrgAuditActions.MEMBER_REMOVED,
                OrgAuditActions.ENTITY_USER, memberId, auditDetails);

        log.info("Removed member {} from org {} (wipeAssessments={}, assignmentsDeleted={})",
                memberId, orgId, wipeAssessments, assignmentsDeleted);

        // Both modes change inputs to org-scoped reports (member list lost a
        // row, possibly assessments too). Same conservative eviction pattern
        // used by clearResponses.
        AfterCommit.run(cacheInvalidationService::invalidateOnNewEvaluation);

        return new RemoveMemberResponse(memberId, wipeAssessments, assignmentsDeleted);
    }

    /**
     * Permanently erase a member: the user row is hard-deleted. Their personal
     * data (assignments, submissions, enrollments, team/cohort memberships,
     * refresh tokens, notifications) is removed by the DB's ON DELETE CASCADE
     * chains, while content they merely authored/administered (assignments they
     * assigned, invitations, join links, surveys, unlock/archive attributions —
     * relaxed to SET NULL in V135, plus V34's pipelines/audit rows) survives
     * without attribution. Same guards as {@link #removeMember}: never a
     * SUPER_ADMIN, never the org's last active ORG_ADMIN.
     */
    @Transactional
    public void deleteMemberPermanently(UUID orgId, UUID memberId, UUID actorId) {
        organizationService.findActiveOrThrow(orgId);
        User user = requireRemovableMember(orgId, memberId);
        String email = user.getEmail();

        userRepository.delete(user);

        // The email lands in the audit details on purpose: after a hard delete it is
        // the only remaining way to answer "who was deleted?" — the row is gone.
        auditService.log(actorId, orgId, OrgAuditActions.MEMBER_DELETED,
                OrgAuditActions.ENTITY_USER, memberId, Map.of("email", email));

        log.info("Permanently deleted member {} from org {}", memberId, orgId);

        // Same conservative eviction as removeMember: the org lost a member and
        // (via cascade) potentially assessments feeding org-scoped reports.
        AfterCommit.run(cacheInvalidationService::invalidateOnNewEvaluation);
    }

    /**
     * Shared removal guards: the target must belong to the org, must not be a
     * SUPER_ADMIN, and must not be the org's last *usable* admin. Count only
     * ACTIVE admins — a SUSPENDED/DEACTIVATED ORG_ADMIN keeps the role but
     * can't log in, so a role-only count would let the sole loginable admin be
     * removed while the org still appears to "have" admins.
     */
    private User requireRemovableMember(UUID orgId, UUID memberId) {
        User user = findMemberInOrg(orgId, memberId);

        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new BadRequestException("Super admins cannot be removed via the org members API");
        }

        if (user.getRole() == UserRole.ORG_ADMIN
                && user.getStatus() == UserStatus.ACTIVE
                && userRepository.countByOrganizationIdAndRoleAndStatus(
                        orgId, UserRole.ORG_ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new BadRequestException(
                    "Cannot remove the only Org Admin. Promote another member to Org Admin first.");
        }
        return user;
    }

    /**
     * Scrub PII and detach from the org. The user row stays so existing FKs
     * (audit_logs, pipelines.created_by, etc.) keep referencing a valid
     * record — just one with no identifying information. The anonymised
     * email uses the original UUID so it's deterministic and unique without
     * extra collision checks.
     */
    private void anonymizeUser(User user) {
        user.setEmail("deleted-" + user.getId() + "@" + ANONYMIZED_EMAIL_DOMAIN);
        user.setName(ANONYMIZED_NAME);
        user.setPasswordHash(null);
        user.setSsoProvider(null);
        user.setAvatarUrl(null);
        user.setStatus(UserStatus.DEACTIVATED);
        user.setOrganization(null);
        user.setRole(UserRole.MEMBER);
    }

    @Transactional
    public List<MemberResponse> bulkChangeStatus(UUID orgId, List<UUID> memberIds, UserStatus status, UUID actorId) {
        organizationService.findActiveOrThrow(orgId);
        return memberIds.stream()
                .map(memberId -> {
                    User user = findMemberInOrg(orgId, memberId);
                    UserStatus oldStatus = user.getStatus();
                    user.setStatus(status);
                    User saved = userRepository.save(user);
                    // See changeStatus: a non-ACTIVE status must immediately
                    // invalidate any live refresh token, or the suspension is
                    // bypassable until the token's natural expiry.
                    if (status != UserStatus.ACTIVE) {
                        refreshTokenRepository.revokeAllForUser(memberId, Instant.now());
                    }
                    auditService.log(actorId, orgId, OrgAuditActions.MEMBER_STATUS_CHANGED,
                            OrgAuditActions.ENTITY_ORGANIZATION, orgId,
                            Map.of("memberId", memberId.toString(),
                                    "oldStatus", oldStatus.name(),
                                    "newStatus", status.name(),
                                    "bulk", "true"));
                    return MemberResponse.from(saved);
                })
                .toList();
    }

    @Transactional
    public void bulkClearResponses(UUID orgId, List<UUID> memberIds, UUID actorId) {
        organizationService.findActiveOrThrow(orgId);
        for (UUID memberId : memberIds) {
            findMemberInOrg(orgId, memberId);
            int deleted = deleteSubmissionsForMemberInOrg(orgId, memberId);
            auditService.log(actorId, orgId, OrgAuditActions.CLEAR_RESPONSES, OrgAuditActions.ENTITY_USER, memberId,
                    Map.of("bulk", "true",
                            "submissionsDeleted", String.valueOf(deleted)));
        }
    }

    /**
     * Bulk-deletes the submissions for a (member, org) pair and registers a
     * post-commit cache eviction. Returns the number deleted (0 is fine —
     * keeps the action idempotent). Cache invalidation is conservative
     * ({@code allEntries=true} on member-results / dashboards) because the
     * cache keys aren't scoped by user; same pattern as
     * {@code EvaluationService} after a write.
     */
    private int deleteSubmissionsForMemberInOrg(UUID orgId, UUID memberId) {
        List<UUID> submissionIds = submissionRepository.findIdsByUserIdAndOrgId(memberId, orgId);
        if (submissionIds.isEmpty()) {
            return 0;
        }
        int deleted = submissionRepository.deleteAllByIdIn(submissionIds);
        log.info("Cleared {} submissions for member {} in org {}", deleted, memberId, orgId);
        AfterCommit.run(cacheInvalidationService::invalidateOnNewEvaluation);
        return deleted;
    }

    private User findMemberInOrg(UUID orgId, UUID memberId) {
        User user = userRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId.toString()));
        if (user.getOrganization() == null || !user.getOrganization().getId().equals(orgId)) {
            throw new BadRequestException("User is not a member of this organization");
        }
        return user;
    }
}
