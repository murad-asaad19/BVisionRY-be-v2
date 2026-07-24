package com.bvisionry.assessment;

import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the org-admin pipeline catalog scope: admins live on the
 * ROOT org while every assignment lives in a sub-org (V136), so the distinct-
 * pipelines query must match the org itself OR any of its sub-orgs — a
 * root-only match returns nothing for every org admin on the platform.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class AssignmentPipelineCatalogScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private AssignmentRepository assignmentRepository;

    @Test
    void distinctPipelineIds_includeSubOrgAssignments_whenQueriedByRootOrg() {
        Organization root = new Organization();
        root.setName("Root Org");
        root = organizationRepository.save(root);

        Organization sub = new Organization();
        sub.setName("General");
        sub.setParentOrganization(root);
        sub = organizationRepository.save(sub);

        Pipeline pipeline = new Pipeline();
        pipeline.setName("Catalog Scope Test Pipeline");
        pipeline.setStatus(PipelineStatus.PUBLISHED);
        pipeline = pipelineRepository.save(pipeline);

        Assignment provision = new Assignment();
        provision.setOrganization(sub); // assignments live in the sub-org
        provision.setPipeline(pipeline);
        provision.setUser(null);
        provision.setMaxCheckIns(1);
        assignmentRepository.save(provision);

        // Root-org query (the org admin's own org) must see the sub-org's pipeline.
        List<UUID> viaRoot = assignmentRepository.findDistinctPipelineIdsByOrganizationId(root.getId());
        assertThat(viaRoot).containsExactly(pipeline.getId());

        // Direct sub-org query still works.
        List<UUID> viaSub = assignmentRepository.findDistinctPipelineIdsByOrganizationId(sub.getId());
        assertThat(viaSub).containsExactly(pipeline.getId());

        // An unrelated org sees nothing.
        Organization other = new Organization();
        other.setName("Other Org");
        other = organizationRepository.save(other);
        assertThat(assignmentRepository.findDistinctPipelineIdsByOrganizationId(other.getId())).isEmpty();
    }
}
