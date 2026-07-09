package com.bvisionry.organization;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.organization.dto.CreateOrganizationRequest;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.dto.UpdateOrganizationRequest;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubOrganizationServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @Mock OrganizationService organizationService;
    @Mock AuditLogger auditLogger;

    @InjectMocks SubOrganizationService subOrganizationService;

    private Organization parent;
    private UUID parentId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        parentId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        parent = new Organization();
        parent.setId(parentId);
        parent.setName("Parent Org");
        parent.setActive(true);
    }

    private Organization newSubOrg(UUID id, Organization parentOrg) {
        Organization subOrg = new Organization();
        subOrg.setId(id);
        subOrg.setName("Sub Org");
        subOrg.setActive(true);
        subOrg.setParentOrganization(parentOrg);
        return subOrg;
    }

    @Test
    void createSubOrganization_underRoot_createsFreeActiveChildAndAuditsParent() {
        when(organizationService.findActiveOrThrow(parentId)).thenReturn(parent);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> {
            Organization saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        OrganizationResponse resp = subOrganizationService.createSubOrganization(
                parentId, new CreateOrganizationRequest("Child", "desc"), actorId);

        assertThat(resp.parentOrganizationId()).isEqualTo(parentId);
        assertThat(resp.parentOrganizationName()).isEqualTo("Parent Org");
        assertThat(resp.subscriptionTier()).isEqualTo(SubscriptionTier.FREE);
        assertThat(resp.active()).isTrue();
        assertThat(resp.subOrganizationCount()).isZero();
        verify(auditLogger).log(eq(actorId), eq(parentId), eq(OrgAuditActions.SUB_ORG_CREATED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), any(UUID.class), any());
    }

    /** The hierarchy is one level deep — a sub-org cannot parent another sub-org. */
    @Test
    void createSubOrganization_underSubOrganization_throws() {
        UUID subOrgId = UUID.randomUUID();
        Organization subOrg = newSubOrg(subOrgId, parent);
        when(organizationService.findActiveOrThrow(subOrgId)).thenReturn(subOrg);

        assertThatThrownBy(() -> subOrganizationService.createSubOrganization(
                subOrgId, new CreateOrganizationRequest("Grandchild", null), actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot have their own sub-organizations");
    }

    @Test
    void updateSubOrganization_renames_andAuditsParent() {
        UUID subOrgId = UUID.randomUUID();
        Organization subOrg = newSubOrg(subOrgId, parent);
        when(organizationService.findOrThrow(subOrgId)).thenReturn(subOrg);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(organizationService.responseWithStats(any(Organization.class)))
                .thenAnswer(inv -> OrganizationResponse.from(inv.getArgument(0), 0, null, 0));

        OrganizationResponse resp = subOrganizationService.updateSubOrganization(
                parentId, subOrgId, new UpdateOrganizationRequest("Renamed", "new desc"), actorId);

        assertThat(resp.name()).isEqualTo("Renamed");
        assertThat(subOrg.getName()).isEqualTo("Renamed");
        assertThat(subOrg.getDescription()).isEqualTo("new desc");
        verify(auditLogger).log(eq(actorId), eq(parentId), eq(OrgAuditActions.SUB_ORG_UPDATED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(subOrgId), any());
    }

    /** Parentage check: an org that is not a child of {@code parentId} must read as 404, not 400/403. */
    @Test
    void updateSubOrganization_wrongParent_throwsNotFound() {
        UUID subOrgId = UUID.randomUUID();
        Organization otherParent = new Organization();
        otherParent.setId(UUID.randomUUID());
        Organization subOrg = newSubOrg(subOrgId, otherParent);
        when(organizationService.findOrThrow(subOrgId)).thenReturn(subOrg);

        assertThatThrownBy(() -> subOrganizationService.updateSubOrganization(
                parentId, subOrgId, new UpdateOrganizationRequest("X", null), actorId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteSubOrganization_wrongParent_throwsNotFound() {
        UUID subOrgId = UUID.randomUUID();
        Organization rootOrg = newSubOrg(subOrgId, null); // a root org is nobody's child
        when(organizationService.findOrThrow(subOrgId)).thenReturn(rootOrg);

        assertThatThrownBy(() -> subOrganizationService.deleteSubOrganization(parentId, subOrgId, actorId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(organizationService, never()).hardDelete(any());
    }

    @Test
    void deleteSubOrganization_delegatesToHardDelete_andAuditsParent() {
        UUID subOrgId = UUID.randomUUID();
        Organization subOrg = newSubOrg(subOrgId, parent);
        when(organizationService.findOrThrow(subOrgId)).thenReturn(subOrg);

        subOrganizationService.deleteSubOrganization(parentId, subOrgId, actorId);

        verify(organizationService).hardDelete(subOrgId);
        verify(auditLogger).log(eq(actorId), eq(parentId), eq(OrgAuditActions.SUB_ORG_DELETED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(subOrgId), any());
    }

    @Test
    void listSubOrganizations_mapsMemberStats() {
        UUID subOrgId = UUID.randomUUID();
        Organization subOrg = newSubOrg(subOrgId, parent);
        when(organizationService.findOrThrow(parentId)).thenReturn(parent);
        when(organizationRepository.findByParentOrganizationIdOrderByNameAsc(parentId))
                .thenReturn(List.of(subOrg));
        when(organizationRepository.findOrgStatsByIds(List.of(subOrgId)))
                .thenReturn(List.<Object[]>of(new Object[]{subOrgId, 3L, null}));

        List<OrganizationResponse> result = subOrganizationService.listSubOrganizations(parentId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).memberCount()).isEqualTo(3);
        assertThat(result.get(0).parentOrganizationId()).isEqualTo(parentId);
    }
}
