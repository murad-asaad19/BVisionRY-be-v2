package com.bvisionry.assessment;

import com.bvisionry.assessment.dto.AutoAssignmentResponse;
import com.bvisionry.assessment.entity.PipelineAutoAssignment;
import com.bvisionry.audit.AuditService;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages persistent "auto-assign" rules. The actual side-effect of
 * materialising assignments for new joiners lives in
 * {@link AutoAssignmentEventHandler} so this class can stay free of any
 * dependency on {@link AssignmentService} (which depends back on this one
 * for upserts during bulk creation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineAutoAssignmentService {

    private final PipelineAutoAssignmentRepository repository;
    private final AuditService auditService;

    /**
     * Idempotent: if a rule already exists for {@code (org, pipeline, userType)}
     * its deadline/createdBy are refreshed instead of inserting a duplicate
     * (which would violate the partial unique indexes on the table).
     */
    @Transactional
    public void upsertRule(Organization org, Pipeline pipeline, String userType,
                           Instant deadline, UUID actorId, int maxCheckIns) {
        repository.findRule(org.getId(), pipeline.getId(), userType)
                .ifPresentOrElse(existing -> {
                    existing.setDeadline(deadline);
                    existing.setMaxCheckIns(maxCheckIns);
                    // Preserve original createdBy — the admin who first set up
                    // the rule is the meaningful audit attribution; subsequent
                    // tweaks land in updatedBy.
                    existing.setUpdatedBy(actorId);
                    repository.save(existing);
                    auditService.log(actorId, org.getId(), OrgAuditActions.AUTO_ASSIGN_RULE_UPDATED,
                            OrgAuditActions.ENTITY_ORGANIZATION, org.getId(),
                            ruleAuditPayload(pipeline, userType, deadline));
                }, () -> {
                    PipelineAutoAssignment rule = new PipelineAutoAssignment();
                    rule.setOrganization(org);
                    rule.setPipeline(pipeline);
                    rule.setUserType(userType);
                    rule.setDeadline(deadline);
                    rule.setCreatedBy(actorId);
                    rule.setMaxCheckIns(maxCheckIns);
                    PipelineAutoAssignment saved = repository.save(rule);
                    auditService.log(actorId, org.getId(), OrgAuditActions.AUTO_ASSIGN_RULE_CREATED,
                            OrgAuditActions.ENTITY_ORGANIZATION, org.getId(),
                            ruleAuditPayload(pipeline, userType, deadline, saved.getId()));
                });
    }

    @Transactional(readOnly = true)
    public List<AutoAssignmentResponse> listRules(UUID orgId) {
        return repository.findByOrganizationId(orgId).stream()
                .map(AutoAssignmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PipelineAutoAssignment> findApplicableForMember(UUID orgId, String userType) {
        return repository.findApplicableForMember(orgId, userType);
    }

    /**
     * Reload a rule with its organization + pipeline graph, used by the
     * AFTER_COMMIT auto-assign path which crosses a transaction boundary
     * between rule lookup and assignment materialisation.
     */
    @Transactional(readOnly = true)
    public Optional<PipelineAutoAssignment> findByIdLoaded(UUID ruleId) {
        return repository.findByIdWithOrgAndPipeline(ruleId);
    }

    @Transactional
    public void deleteRule(UUID orgId, UUID ruleId, UUID actorId) {
        PipelineAutoAssignment rule = repository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("AutoAssignmentRule", ruleId.toString()));
        // Org-scope guard — ruleId from one org must not be deletable via another
        // org's path parameter even if the JWT happens to authorise both.
        if (!rule.getOrganization().getId().equals(orgId)) {
            // Surface enumeration attempts in logs so security can spot them;
            // still respond with the same 404 the genuine "not found" path
            // returns so an attacker can't distinguish the two.
            log.warn("Cross-org auto-assign rule probe: actor={} pathOrg={} ruleOrg={} ruleId={}",
                    actorId, orgId, rule.getOrganization().getId(), ruleId);
            throw new ResourceNotFoundException("AutoAssignmentRule", ruleId.toString());
        }
        repository.delete(rule);
        auditService.log(actorId, orgId, OrgAuditActions.AUTO_ASSIGN_RULE_DELETED,
                OrgAuditActions.ENTITY_ORGANIZATION, orgId,
                ruleAuditPayload(rule.getPipeline(), rule.getUserType(), rule.getDeadline(), rule.getId()));
    }

    /** Called by {@code PipelineService} when a pipeline is archived, reverted, or deleted. */
    @Transactional
    public void deleteRulesForPipeline(UUID pipelineId) {
        repository.deleteByPipelineId(pipelineId);
    }

    private static Map<String, Object> ruleAuditPayload(Pipeline pipeline, String userType, Instant deadline) {
        return ruleAuditPayload(pipeline, userType, deadline, null);
    }

    private static Map<String, Object> ruleAuditPayload(Pipeline pipeline, String userType, Instant deadline, UUID ruleId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("pipelineId", pipeline.getId().toString());
        payload.put("pipelineName", pipeline.getName());
        payload.put("userType", userType == null ? "ALL" : userType);
        if (deadline != null) {
            payload.put("deadline", deadline.toString());
        }
        if (ruleId != null) {
            payload.put("ruleId", ruleId.toString());
        }
        return payload;
    }
}
