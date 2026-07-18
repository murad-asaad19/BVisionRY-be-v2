package com.bvisionry.organization;

import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.organization.dto.MoveMemberResponse;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.organization.event.MemberMovedEvent;
import com.bvisionry.reporting.service.CacheInvalidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Cross-organization member move. Re-parents the user's organization_id and
 * every assignment they own (org-scoped via assignments.organization_id) so
 * the target org's admin views, dashboards, and reports follow the user.
 *
 * <p>The user's submissions are reached transitively through assignments
 * (no separate org_id column on submissions), so moving the assignment row
 * is sufficient. The user's view path at {@code /api/my/assessments} is
 * keyed by user_id alone, which is unchanged — they keep visibility to
 * everything they had before.
 *
 * <p>Out of scope (deliberately not moved): audit_logs (historical record),
 * insight_reports (per-org snapshots), invitations / join_links (org-scoped
 * sign-up artifacts), upgrade_requests (historical signals from the source).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MoveMemberService {

    private final UserRepository userRepository;
    private final OrganizationService organizationService;
    private final AssignmentRepository assignmentRepository;
    private final AuditService auditService;
    private final CacheInvalidationService cacheInvalidationService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public MoveMemberResponse move(UUID memberId, UUID targetOrganizationId, UUID actorId) {
        User user = userRepository.findByIdWithOrganization(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId.toString()));

        // SUPER_ADMIN has no org membership concept — they sit above the
        // multi-tenant boundary. Refuse instead of silently no-op'ing.
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new BadRequestException("Super admins are not members of any organization and cannot be moved");
        }

        // ORG_ADMIN's role is meaningful only inside the org they administer.
        // Carrying it across would silently grant admin rights in the target;
        // refusing forces the operator to demote them first (an explicit,
        // audited step) before the move.
        if (user.getRole() == UserRole.ORG_ADMIN) {
            throw new BadRequestException(
                    "Member is an Org Admin in the source organization. "
                            + "Demote them to MEMBER before moving.");
        }

        Organization source = user.getOrganization();
        if (source == null) {
            throw new BadRequestException("Member does not currently belong to an organization");
        }

        if (source.getId().equals(targetOrganizationId)) {
            throw new BadRequestException("Member is already in the target organization");
        }

        // Target must be active — moving into a suspended org would leave the
        // member in a dead-end state where login is blocked org-wide.
        Organization target = organizationService.findActiveOrThrow(targetOrganizationId);

        // Movable users are member-level accounts; members live in sub-orgs
        // only, so a root org is never a valid destination.
        if (!target.isSubOrganization()) {
            throw new BadRequestException(
                    "Members belong to sub-organizations. Pick a sub-organization as the move target.");
        }

        int movedAssignments = assignmentRepository.reassignToOrganization(
                memberId, source.getId(), target.getId());

        user.setOrganization(target);
        userRepository.save(user);

        Map<String, Object> moveDetails = Map.of(
                "fromOrganizationId", source.getId().toString(),
                "fromOrganizationName", source.getName(),
                "toOrganizationId", target.getId().toString(),
                "toOrganizationName", target.getName(),
                "movedAssignments", String.valueOf(movedAssignments));
        // Surface the move on both orgs' activity feeds. One row each, stamped
        // to the org whose perspective the entry describes.
        auditService.log(actorId, source.getId(), OrgAuditActions.MEMBER_MOVED,
                OrgAuditActions.ENTITY_USER, memberId, moveDetails);
        auditService.log(actorId, target.getId(), OrgAuditActions.MEMBER_MOVED,
                OrgAuditActions.ENTITY_USER, memberId, moveDetails);

        log.info("Moved member {} from org {} to org {} ({} assignments re-parented)",
                memberId, source.getId(), target.getId(), movedAssignments);

        // Apply target-org auto-assign rules to the moved member. Picked up
        // AFTER_COMMIT by AutoAssignmentEventHandler so the user's new
        // organization_id is durably visible — applyAutoAssignRule re-reads
        // the user fresh inside its REQUIRES_NEW transaction and would skip
        // them otherwise. Existing assignments survive the move, and the
        // per-rule (org, pipeline, user) dedup inside applyAutoAssignRule
        // means a moved member who already has the rule's pipeline assigned
        // won't get a duplicate row.
        eventPublisher.publishEvent(new MemberMovedEvent(
                target.getId(), source.getId(), memberId, user.getUserType()));

        // Both source and target dashboards/member-results/insights now have
        // changed inputs (gained or lost a member's submissions). Cache keys
        // aren't org-scoped so we evict broadly, same as on a new evaluation.
        AfterCommit.run(cacheInvalidationService::invalidateOnNewEvaluation);

        return new MoveMemberResponse(memberId, source.getId(), target.getId(), movedAssignments);
    }
}
