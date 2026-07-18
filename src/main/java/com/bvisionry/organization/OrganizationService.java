package com.bvisionry.organization;

import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.insights.InsightReportRepository;
import com.bvisionry.organization.dto.ChangeTierRequest;
import com.bvisionry.organization.dto.CreateOrganizationRequest;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.dto.UpdateOrganizationRequest;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.reporting.service.CacheInvalidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final InsightReportRepository insightReportRepository;
    private final AssignmentRepository assignmentRepository;
    private final InvitationRepository invitationRepository;
    private final JoinLinkRepository joinLinkRepository;
    private final CacheInvalidationService cacheInvalidationService;

    @Transactional
    public OrganizationResponse create(CreateOrganizationRequest request, UUID actorId) {
        Organization org = new Organization();
        org.setName(request.name());
        org.setDescription(request.description());
        org.setSubscriptionTier(SubscriptionTier.FREE);
        org.setActive(true);
        Organization saved = organizationRepository.save(org);
        auditService.log(actorId, saved.getId(), OrgAuditActions.ORGANIZATION_CREATED,
                OrgAuditActions.ENTITY_ORGANIZATION, saved.getId(),
                Map.of("name", saved.getName()));
        return OrganizationResponse.from(saved, 0, null, 0);
    }

    @Transactional(readOnly = true)
    public Page<OrganizationResponse> listAll(Pageable pageable) {
        Page<Organization> page = organizationRepository.findAll(pageable);
        if (page.isEmpty()) {
            return page.map(o -> OrganizationResponse.from(o, 0, null, 0));
        }
        List<UUID> ids = page.getContent().stream().map(Organization::getId).toList();
        Map<UUID, OrgStats> statsById = organizationRepository.findOrgStatsByIds(ids).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> new OrgStats((Long) row[1], (Instant) row[2])));
        // Batch sub-org counts for the page — one group-by query, no per-row lookups.
        Map<UUID, Long> subOrgCounts = organizationRepository.countSubOrgsByParentIds(ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        return page.map(org -> {
            OrgStats stats = statsById.getOrDefault(org.getId(), new OrgStats(0L, null));
            return OrganizationResponse.from(org, stats.count(), stats.lastLogin(),
                    subOrgCounts.getOrDefault(org.getId(), 0L).intValue());
        });
    }

    private record OrgStats(long count, Instant lastLogin) {}

    @Transactional(readOnly = true)
    public OrganizationResponse getById(UUID id) {
        return responseWithStats(findOrThrow(id));
    }

    @Transactional
    public OrganizationResponse update(UUID id, UpdateOrganizationRequest request, UUID actorId) {
        Organization org = findOrThrow(id);
        boolean nameChanged = !java.util.Objects.equals(org.getName(), request.name());
        boolean descChanged = !java.util.Objects.equals(org.getDescription(), request.description());
        org.setName(request.name());
        org.setDescription(request.description());
        Organization saved = organizationRepository.save(org);
        if (nameChanged || descChanged) {
            Map<String, Object> details = new HashMap<>();
            if (nameChanged) details.put("name", request.name());
            if (descChanged) details.put("description", request.description());
            auditService.log(actorId, saved.getId(), OrgAuditActions.ORGANIZATION_UPDATED,
                    OrgAuditActions.ENTITY_ORGANIZATION, saved.getId(), details);
        }
        return responseWithStats(saved);
    }

    @Transactional
    public OrganizationResponse changeTier(UUID id, ChangeTierRequest request, UUID actorId) {
        Organization org = findOrThrow(id);
        if (org.isSubOrganization()) {
            throw new BadRequestException(
                    "Subscription tier is managed by the parent organization");
        }
        if (org.getSubscriptionTier() == request.tier()) {
            throw new BadRequestException("Organization is already on the " + request.tier() + " tier");
        }
        SubscriptionTier oldTier = org.getSubscriptionTier();
        org.setSubscriptionTier(request.tier());
        // A direct admin tier change is permanent — it is NOT a trial — so it must
        // clear any leftover trial marker. Otherwise a stale (already-past) trialEndsAt
        // from a prior trial would make a directly-promoted PREMIUM org match
        // findLapsedTrials(...) and get silently downgraded to FREE by TrialExpiryJob.
        // This also honors Organization.isOnTrial(), which is "defensive against a manual
        // tier-downgrade leaving a future trial_ends_at" — the write path now keeps the
        // trial marker consistent with the tier the admin explicitly chose.
        org.setTrialEndsAt(null);
        Organization saved = organizationRepository.save(org);
        auditService.log(actorId, org.getId(), OrgAuditActions.TIER_CHANGE,
                OrgAuditActions.ENTITY_ORGANIZATION, org.getId(),
                Map.of("oldTier", oldTier.name(), "newTier", request.tier().name()));
        // Defer eviction until afterCommit so a concurrent reader can't repopulate the
        // cache from the still-open transaction's pre-commit (old tier) view.
        AfterCommit.run(cacheInvalidationService::invalidateOnTierChange);
        return responseWithStats(saved);
    }

    /**
     * Suspends/reactivates an organization and its members. Toggling a ROOT
     * org cascades the same state to every sub-organization (and their
     * members) in both directions — a suspended customer must not keep live
     * sub-org access, and reactivating restores the whole tree. Toggling a
     * sub-org directly affects only that sub-org.
     *
     * <p>Kept as one flat method (rather than a per-org helper) on purpose:
     * the ArchUnit ratchet freezes cross-feature edges per ORIGIN METHOD, so
     * the {@code UserRepository}/{@code User} calls must stay inside
     * {@code toggleActive}, each with a single call site, to keep matching
     * their frozen baseline entries.
     */
    @Transactional
    public OrganizationResponse toggleActive(UUID id, boolean active, UUID actorId) {
        Organization org = findOrThrow(id);
        boolean wasActive = org.isActive();

        List<Organization> children = organizationRepository.findByParentOrganizationId(id);
        List<Organization> affectedOrgs = new java.util.ArrayList<>(children.size() + 1);
        affectedOrgs.add(org);
        affectedOrgs.addAll(children);

        long directMemberCount = 0;
        for (Organization affected : affectedOrgs) {
            affected.setActive(active);
            var members = userRepository.findByOrganizationId(affected.getId());
            if (affected == org) {
                directMemberCount = members.size();
            }
            for (var member : members) {
                member.setStatus(active ? UserStatus.ACTIVE : UserStatus.SUSPENDED);
            }
            userRepository.saveAll(members);
        }
        Organization saved = organizationRepository.saveAll(affectedOrgs).get(0);

        if (wasActive != active) {
            String action = active ? OrgAuditActions.ORGANIZATION_REACTIVATED
                                   : OrgAuditActions.ORGANIZATION_SUSPENDED;
            auditService.log(actorId, saved.getId(), action, OrgAuditActions.ENTITY_ORGANIZATION, saved.getId(),
                    Map.of("memberCount", String.valueOf(directMemberCount),
                            "cascadedSubOrganizations", String.valueOf(children.size())));
        }

        return responseWithStats(saved);
    }

    // Package-private so SubOrganizationService reuses the same response shape.
    OrganizationResponse responseWithStats(Organization org) {
        // One level deep: for sub-orgs this count is 0 by invariant, so the
        // extra query stays a single cheap index lookup either way.
        int subOrgCount = (int) organizationRepository.countByParentOrganizationId(org.getId());
        return organizationRepository.findOrgStatsByIds(List.of(org.getId())).stream()
                .findFirst()
                .map(row -> OrganizationResponse.from(org, (Long) row[1], (Instant) row[2], subOrgCount))
                .orElseGet(() -> OrganizationResponse.from(org, 0, null, subOrgCount));
    }

    /**
     * Hard-delete the organization and every row that belongs to it. This is
     * permanent and cannot be undone — callers should surface a typed-name
     * confirmation before invoking this.
     *
     * Cascade order (all in one transaction so partial failure rolls back):
     *   0. Sub-organizations (each gets the same full cascade; one level deep)
     *   1. Org-level insight reports
     *   2. Assignments (submissions + answers + pillar_evaluations +
     *      overall_summaries cascade from the assignment FKs)
     *   3. Invitations + join links
     *   4. All members of the org (pipelines they created keep surviving with
     *      created_by = NULL, audit rows survive with actor_id = NULL — see V34)
     *   5. The organization itself
     */
    @Transactional
    public void hardDelete(UUID id) {
        Organization org = findOrThrow(id);
        String orgName = org.getName();
        long memberCount = userRepository.countByOrganizationId(id);

        // Sub-orgs first: their rows FK-reference the parent, and each child
        // needs the same full cascade. One level deep, so this never recurses
        // further. Self-invocation is fine — we're already inside the
        // REQUIRED transaction, so all deletions commit or roll back together.
        for (Organization child : organizationRepository.findByParentOrganizationId(id)) {
            hardDelete(child.getId());
        }

        insightReportRepository.deleteByOrganizationId(id);
        assignmentRepository.deleteByOrganizationId(id);
        invitationRepository.deleteByOrganizationId(id);
        joinLinkRepository.deleteByOrganizationId(id);
        userRepository.deleteByOrganizationId(id);
        organizationRepository.delete(org);

        // organization_id stays null — the org row is being deleted in this same
        // transaction and the FK would cascade-null the column anyway. Keeping
        // the deletion event addressable by entity_id = id (Organization scope).
        auditService.log(null, null, OrgAuditActions.ORGANIZATION_DELETED,
                OrgAuditActions.ENTITY_ORGANIZATION, id,
                Map.of("name", orgName, "memberCount", String.valueOf(memberCount)));
    }

    Organization findActiveOrThrow(UUID id) {
        Organization org = findOrThrow(id);
        if (!org.isActive()) {
            throw new BadRequestException("Organization is not active");
        }
        return org;
    }

    Organization findOrThrow(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", id.toString()));
    }
}
