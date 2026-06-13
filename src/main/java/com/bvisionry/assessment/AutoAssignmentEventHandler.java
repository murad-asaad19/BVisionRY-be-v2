package com.bvisionry.assessment;

import com.bvisionry.assessment.entity.PipelineAutoAssignment;
import com.bvisionry.organization.event.MemberJoinedEvent;
import com.bvisionry.organization.event.MemberMovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * After a member-joined or member-moved transaction commits, materialise
 * per-member assignments for every applicable auto-assign rule in the
 * destination org. Lives in its own component so
 * {@link PipelineAutoAssignmentService} can stay clean of any dependency on
 * {@link AssignmentService} (which depends back the other way for the
 * bulk-create flow).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoAssignmentEventHandler {

    private final PipelineAutoAssignmentService autoAssignmentService;
    private final AssignmentService assignmentService;

    /**
     * AFTER_COMMIT — a rolled-back join must NOT leave behind orphan
     * assignments. Each rule is processed in its own transaction (via the
     * REQUIRES_NEW on {@link AssignmentService#applyAutoAssignRule}) so a
     * single failure does not abort the rest of the batch.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberJoined(MemberJoinedEvent event) {
        applyApplicableRules(event.organizationId(), event.userId(), event.userType());
    }

    /**
     * AFTER_COMMIT for the same reason as the join handler — and additionally
     * because {@link AssignmentService#applyAutoAssignRule} re-reads the user's
     * current organization to defend against races. Until the move commits,
     * the user still appears to belong to the source org and would be skipped.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberMoved(MemberMovedEvent event) {
        applyApplicableRules(event.toOrganizationId(), event.userId(), event.userType());
    }

    private void applyApplicableRules(UUID organizationId, UUID userId, String userType) {
        List<PipelineAutoAssignment> rules =
                autoAssignmentService.findApplicableForMember(organizationId, userType);
        if (rules.isEmpty()) {
            return;
        }
        for (PipelineAutoAssignment rule : rules) {
            // Pass only the rule id — applyAutoAssignRule reloads the entity
            // inside its own REQUIRES_NEW transaction. Anything captured here
            // (status, organization, pipeline) was loaded in a now-closed
            // session and would be detached / lazy-throw at use site.
            UUID ruleId = rule.getId();
            try {
                assignmentService.applyAutoAssignRule(ruleId, userId, organizationId);
            } catch (RuntimeException ex) {
                // Don't let one bad rule (e.g. a member already assigned via a
                // race with the admin clicking 'assign all' moments earlier)
                // prevent the rest of the auto-assigns from landing. Pass the
                // exception itself so the stack survives in production logs.
                log.warn("Auto-assign rule {} failed for user {} in org {}",
                        ruleId, userId, organizationId, ex);
            }
        }
    }
}
