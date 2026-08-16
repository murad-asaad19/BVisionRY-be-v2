package com.bvisionry.pipeline.repository;

import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Is this pipeline assigned to this org?" is written twice — once in
 * {@link AssignmentRepository#findDistinctPipelineIdsByOrganizationId} (the published
 * catalog) and once in {@link PipelineRepository#countAssignmentsToOrg} (the band read).
 * The duplication is forced: the ArchUnit ratchet freezes cross-feature violations per
 * call site, so the pipeline feature cannot call the assessment repository from a new
 * method.
 *
 * <p><strong>The drift this exists to catch:</strong> add a rule to one side —
 * grandchild orgs, soft-deleted assignments, an archived-pipeline exclusion — and the
 * other silently keeps the old semantics. Nothing else in the suite compares them, so a
 * founder could be shown bands for a pipeline the catalog says they cannot see, or the
 * reverse.
 *
 * <p>ponytail: known ceiling — this compares the two over the shapes it seeds (root,
 * sub-org, unrelated org). A rule about a shape absent here (grandchildren, soft
 * deletes) needs a case added here as well as to both queries.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class PipelineAssignmentPredicateParityTest extends AbstractPostgresIntegrationTest {

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private AssignmentRepository assignmentRepository;

    private Organization org(String name, Organization parent) {
        Organization org = new Organization();
        org.setName(name);
        org.setParentOrganization(parent);
        return organizationRepository.save(org);
    }

    private void assertBothAgree(UUID pipelineId, Organization queriedAs, boolean expected) {
        boolean viaAssignments = assignmentRepository
                .findDistinctPipelineIdsByOrganizationId(queriedAs.getId())
                .contains(pipelineId);
        boolean viaPipelines = pipelineRepository
                .countAssignmentsToOrg(pipelineId, queriedAs.getId()) > 0;

        assertThat(viaPipelines)
                .as("band read vs published catalog disagree for org %s", queriedAs.getName())
                .isEqualTo(viaAssignments)
                .isEqualTo(expected);
    }

    @Test
    void bothPredicatesAnswerIdenticallyForRootSubAndUnrelatedOrgs() {
        Organization root = org("Root Org", null);
        Organization sub = org("General", root);
        Organization unrelated = org("Other Org", null);

        Pipeline pipeline = new Pipeline();
        pipeline.setName("Founder Readiness");
        pipeline.setStatus(PipelineStatus.PUBLISHED);
        pipeline = pipelineRepository.save(pipeline);

        // The provision lives in the sub-org, where every real assignment lives (V136).
        Assignment provision = new Assignment();
        provision.setOrganization(sub);
        provision.setPipeline(pipeline);
        provision.setMaxCheckIns(1);
        assignmentRepository.save(provision);

        assertBothAgree(pipeline.getId(), root, true);      // parent reaches the sub-org row
        assertBothAgree(pipeline.getId(), sub, true);       // the org itself
        assertBothAgree(pipeline.getId(), unrelated, false); // nobody else
    }
}
