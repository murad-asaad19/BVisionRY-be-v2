package com.bvisionry.assessment;

import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The org scope of the dashboard/insights/report reads, pinned in both
 * directions.
 *
 * <p>The default topology is V136's: an org admin lives on the ROOT org while
 * every assignment lives in a sub-org. {@code AssignmentRepository
 * #findDistinctPipelineIdsByOrganizationId} has always matched the org OR its
 * sub-orgs, so the pipeline SELECTOR listed instruments that every stat read
 * then came back empty for. The five queries here were widened to match; this
 * test is what stops that widening from being quietly undone — or from being
 * "fixed" into a sub-org-only match that loses the root's own rows.
 *
 * <p>Three assertions per query, because two of the three failure modes are
 * silent: the sub-org's rows must be visible from the root, the root's own rows
 * must survive the widening, and an unrelated tenant must still see nothing —
 * a widening is one clause away from being a leak.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class SubmissionOrgScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private PillarEvaluationRepository pillarEvaluationRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID rootId;
    private UUID subId;
    private UUID otherId;
    private UUID pipelineId;
    private UUID pillarId;
    private UUID rootSubmissionId;
    private UUID subSubmissionId;

    @BeforeEach
    void seed() {
        rootId = saveOrg("Scope Root", null);
        subId = saveOrg("General", rootId);
        otherId = saveOrg("Scope Other Tenant", null);

        pipelineId = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status) VALUES (?, 'Scope Pipeline', 'PUBLISHED')",
                pipelineId);
        pillarId = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name, display_order) VALUES (?, ?, 'Vision', 1)",
                pillarId, pipelineId);

        // One EVALUATED sitting in the sub-org (the V136 shape) and one on the
        // root itself, so a match on either side alone fails this test.
        subSubmissionId = insertEvaluated(subId, 61.0);
        rootSubmissionId = insertEvaluated(rootId, 72.0);
        // A third tenant's sitting on the SAME pipeline — the leak canary.
        insertEvaluated(otherId, 99.0);
    }

    @Test
    void submissionReadsSeeTheWholeFamilyFromTheRoot_andNoOtherTenant() {
        assertThat(idsOf(submissionRepository.findByOrgAndPipeline(rootId, pipelineId)))
                .as("root query must see its own AND its sub-org's submissions")
                .containsExactlyInAnyOrder(rootSubmissionId, subSubmissionId);
        assertThat(idsOf(submissionRepository.findByOrgAndPipelineForDashboard(rootId, pipelineId)))
                .containsExactlyInAnyOrder(rootSubmissionId, subSubmissionId);

        // Addressing the sub-org directly still works — it has no children, so
        // this is the narrow half of the same clause.
        assertThat(idsOf(submissionRepository.findByOrgAndPipeline(subId, pipelineId)))
                .containsExactly(subSubmissionId);

        assertThat(idsOf(submissionRepository.findByOrgAndPipeline(otherId, pipelineId)))
                .as("the widening must not reach across tenants")
                .hasSize(1)
                .doesNotContain(rootSubmissionId, subSubmissionId);
    }

    @Test
    void statusCountsCoverTheWholeFamily() {
        Map<Object, Long> byStatus = submissionRepository
                .countByStatusForOrgPipeline(rootId, pipelineId).stream()
                .collect(java.util.stream.Collectors.toMap(row -> row[0], row -> (Long) row[1]));

        assertThat(byStatus).containsEntry(SubmissionStatus.EVALUATED, 2L);
    }

    @Test
    void pillarEvaluationReadsCoverTheWholeFamily() {
        assertThat(pillarEvaluationRepository.findByOrgAndPipeline(rootId, pipelineId))
                .as("the export read must see the sub-org's evaluations")
                .hasSize(2);
        assertThat(pillarEvaluationRepository.findScoreViewsByOrgAndPipeline(rootId, pipelineId))
                .as("the dashboard projection must agree with its own entity read")
                .hasSize(2);

        assertThat(pillarEvaluationRepository.findScoreViewsByOrgAndPipeline(otherId, pipelineId))
                .hasSize(1);
    }

    /* ------------------------------------------------------------- fixture */

    private UUID saveOrg(String name, UUID parentId) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        if (parentId != null) {
            org.setParentOrganization(organizationRepository.getReferenceById(parentId));
        }
        return organizationRepository.saveAndFlush(org).getId();
    }

    /** A member of {@code orgId} with one EVALUATED sitting on the fixture pipeline. */
    private UUID insertEvaluated(UUID orgId, double score) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id)
                VALUES (?, ?, 'Founder', 'MEMBER', 'ACTIVE', ?)
                """, userId, "scope." + userId + "@test.invalid", orgId);

        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id)
                VALUES (?, ?, ?, ?)
                """, assignmentId, pipelineId, orgId, userId);

        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at)
                VALUES (?, ?, ?, 'EVALUATED', now())
                """, submissionId, assignmentId, userId);
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage, maturity_label)
                VALUES (?, ?, ?, 'Strong')
                """, submissionId, pillarId, score);
        return submissionId;
    }

    private static List<UUID> idsOf(List<com.bvisionry.assessment.entity.Submission> rows) {
        return rows.stream().map(com.bvisionry.assessment.entity.Submission::getId).toList();
    }
}
