package com.bvisionry.organization;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.organization.dto.CreateOrganizationRequest;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.dto.UpdateOrganizationRequest;
import com.bvisionry.organization.entity.Organization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sub-organization lifecycle (one level deep). Split out of
 * {@link OrganizationService} deliberately: the ArchUnit ratchet freezes
 * cross-feature dependency edges per method/constructor SIGNATURE, so adding
 * new collaborators to {@code OrganizationService}'s constructor would break
 * its frozen baseline. This class depends only on same-feature classes and
 * the shared kernel ({@link AuditLogger}), keeping the ratchet clean.
 *
 * <p>Audit entries are logged against the PARENT org id so the parent's
 * activity feed shows its sub-orgs being managed.
 */
@Service
@RequiredArgsConstructor
public class SubOrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationService organizationService;
    private final AuditLogger auditLogger;

    /** Direct sub-organizations of {@code parentId}, ordered by name, with per-org member stats. */
    @Transactional(readOnly = true)
    public List<OrganizationResponse> listSubOrganizations(UUID parentId) {
        organizationService.findOrThrow(parentId);
        List<Organization> children =
                organizationRepository.findByParentOrganizationIdOrderByNameAsc(parentId);
        if (children.isEmpty()) return List.of();
        // One aggregate query for the whole listing — no per-child lookups.
        List<UUID> ids = children.stream().map(Organization::getId).toList();
        Map<UUID, Object[]> statsById = new HashMap<>();
        for (Object[] row : organizationRepository.findOrgStatsByIds(ids)) {
            statsById.put((UUID) row[0], row);
        }
        return children.stream()
                .map(child -> {
                    Object[] stats = statsById.get(child.getId());
                    long memberCount = stats != null ? (Long) stats[1] : 0L;
                    Instant lastLogin = stats != null ? (Instant) stats[2] : null;
                    // Sub-orgs can't have children (one level deep) — count is 0 by invariant.
                    return OrganizationResponse.from(child, memberCount, lastLogin, 0);
                })
                .toList();
    }

    /**
     * Creates a sub-organization under {@code parentId}. The parent must be
     * active and must itself be a root org — the hierarchy is one level deep.
     * The child starts FREE (its effective tier is inherited from the parent)
     * and active.
     */
    @Transactional
    public OrganizationResponse createSubOrganization(UUID parentId, CreateOrganizationRequest request,
                                                      UUID actorId) {
        Organization parent = organizationService.findActiveOrThrow(parentId);
        if (parent.isSubOrganization()) {
            throw new BadRequestException(
                    "Sub-organizations cannot have their own sub-organizations");
        }
        Organization child = new Organization();
        child.setName(request.name());
        child.setDescription(request.description());
        child.setSubscriptionTier(SubscriptionTier.FREE);
        child.setActive(true);
        child.setParentOrganization(parent);
        Organization saved = organizationRepository.save(child);
        auditLogger.log(actorId, parentId, OrgAuditActions.SUB_ORG_CREATED,
                OrgAuditActions.ENTITY_ORGANIZATION, saved.getId(),
                Map.of("name", saved.getName()));
        return OrganizationResponse.from(saved, 0, null, 0);
    }

    /** Renames/re-describes a sub-org. 404 unless {@code subOrgId} is a direct child of {@code parentId}. */
    @Transactional
    public OrganizationResponse updateSubOrganization(UUID parentId, UUID subOrgId,
                                                      UpdateOrganizationRequest request, UUID actorId) {
        Organization subOrg = findSubOrgOfParentOrThrow(parentId, subOrgId);
        boolean nameChanged = !java.util.Objects.equals(subOrg.getName(), request.name());
        boolean descChanged = !java.util.Objects.equals(subOrg.getDescription(), request.description());
        subOrg.setName(request.name());
        subOrg.setDescription(request.description());
        Organization saved = organizationRepository.save(subOrg);
        if (nameChanged || descChanged) {
            Map<String, Object> details = new HashMap<>();
            details.put("subOrganizationId", subOrgId.toString());
            if (nameChanged) details.put("name", request.name());
            if (descChanged) details.put("description", request.description());
            auditLogger.log(actorId, parentId, OrgAuditActions.SUB_ORG_UPDATED,
                    OrgAuditActions.ENTITY_ORGANIZATION, subOrgId, details);
        }
        return organizationService.responseWithStats(saved);
    }

    /** Hard-deletes a sub-org (full cascade). 404 unless {@code subOrgId} is a direct child of {@code parentId}. */
    @Transactional
    public void deleteSubOrganization(UUID parentId, UUID subOrgId, UUID actorId) {
        Organization subOrg = findSubOrgOfParentOrThrow(parentId, subOrgId);
        String name = subOrg.getName();
        organizationService.hardDelete(subOrgId);
        auditLogger.log(actorId, parentId, OrgAuditActions.SUB_ORG_DELETED,
                OrgAuditActions.ENTITY_ORGANIZATION, subOrgId,
                Map.of("name", name));
    }

    /**
     * 404 (not 403/400) when the sub-org doesn't exist under this parent, so a
     * parent admin probing another org's child id learns nothing beyond "not found".
     */
    private Organization findSubOrgOfParentOrThrow(UUID parentId, UUID subOrgId) {
        Organization subOrg = organizationService.findOrThrow(subOrgId);
        if (subOrg.getParentOrganization() == null
                || !subOrg.getParentOrganization().getId().equals(parentId)) {
            throw new ResourceNotFoundException("Sub-organization", subOrgId.toString());
        }
        return subOrg;
    }
}
