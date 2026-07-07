package com.bvisionry.assessment;

import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.assessment.dto.AssignmentDetailResponse;
import com.bvisionry.assessment.dto.AssignmentResponse;
import com.bvisionry.assessment.dto.CreateAssignmentRequest;
import com.bvisionry.assessment.dto.ExtendDeadlineRequest;
import com.bvisionry.assessment.dto.OverrideAnswersRequest;
import com.bvisionry.assessment.dto.PillarSummaryResponse;
import com.bvisionry.evaluation.AiUseDetectionService;
import com.bvisionry.evaluation.PillarReeditService;
import com.bvisionry.evaluation.dto.AiDetectionResponse;
import com.bvisionry.evaluation.dto.PillarUnlockSummary;
import com.bvisionry.evaluation.dto.UnlockPillarsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{orgId}/assignments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class AssignmentController {

    private final AssignmentService assignmentService;
    private final PillarReeditService pillarReeditService;
    private final AdminAnswerOverrideService adminAnswerOverrideService;
    private final AiUseDetectionService aiUseDetectionService;

    @PostMapping
    public ResponseEntity<List<AssignmentResponse>> createAssignment(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateAssignmentRequest request) {
        List<AssignmentResponse> response = assignmentService.createAssignment(orgId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AssignmentResponse>> listAssignments(
            @PathVariable UUID orgId,
            @RequestParam(required = false) AssignmentService.AssignmentListScope scope) {
        return ResponseEntity.ok(assignmentService.listAssignments(orgId, scope));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AssignmentDetailResponse> getAssignmentDetail(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(assignmentService.getAssignmentDetail(orgId, id));
    }

    /**
     * Raw per-question answers for a member's submission. Restricted to the
     * platform Super Admin — Org Admins manage assignments but must not be
     * able to read the verbatim content of a member's answers.
     */
    @GetMapping("/{id}/answers")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<AssessmentDetailResponse> getAssignmentAnswers(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(assignmentService.getAssignmentAnswers(orgId, id));
    }

    /**
     * Pillar list (id/name/type) for the unlock-pillars picker — structure
     * only, no answer content, so it stays available to Org Admins under the
     * class-level org-scoped check (unlike {@link #getAssignmentAnswers}).
     */
    @GetMapping("/{id}/pillars")
    public ResponseEntity<List<PillarSummaryResponse>> getAssignmentPillars(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(assignmentService.getAssignmentPillars(orgId, id));
    }

    /**
     * Stored AI-use detection result for the assignment's latest submission.
     * 404 when detection has never been run. SUPER_ADMIN only — the result
     * quotes and reasons about the member's verbatim answers, so it follows
     * the same restriction as {@link #getAssignmentAnswers}.
     */
    @GetMapping("/{id}/ai-detection")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<AiDetectionResponse> getAiDetection(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        UUID submissionId = assignmentService.requireLatestSubmissionId(orgId, id);
        return ResponseEntity.ok(aiUseDetectionService.get(submissionId));
    }

    /**
     * Runs (or re-runs) the AI-use detector over the latest submission's
     * free-text answers and stores the result. Synchronous — a single AI call,
     * unlike the fan-out evaluation pipeline.
     */
    @PostMapping("/{id}/ai-detection")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<AiDetectionResponse> runAiDetection(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        UUID submissionId = assignmentService.requireLatestSubmissionId(orgId, id);
        return ResponseEntity.ok(aiUseDetectionService.detect(submissionId));
    }

    @PostMapping("/{id}/reminder")
    public ResponseEntity<Void> sendReminder(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        assignmentService.sendReminder(orgId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry-evaluation")
    public ResponseEntity<Void> retryEvaluation(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        assignmentService.retryEvaluation(orgId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/pillar-unlocks")
    public ResponseEntity<PillarUnlockSummary> getPillarUnlocks(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(pillarReeditService.getUnlockSummary(orgId, id));
    }

    @PostMapping("/{id}/unlock-pillars")
    public ResponseEntity<PillarUnlockSummary> unlockPillars(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @Valid @RequestBody UnlockPillarsRequest request) {
        return ResponseEntity.ok(
                pillarReeditService.unlockPillars(orgId, id, request.pillarIds(), request.reason()));
    }

    @PostMapping("/{id}/relock-pillars")
    public ResponseEntity<PillarUnlockSummary> relockPillars(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(pillarReeditService.relockPillars(orgId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelAssignment(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        assignmentService.cancelAssignment(orgId, id);
        return ResponseEntity.noContent().build();
    }

    // Extending a deadline is an ordinary lifecycle action an org admin performs
    // for their own members (like reminder/retry/cancel), so it inherits the
    // class-level authorization (SUPER_ADMIN or ORG_ADMIN scoped to #orgId)
    // rather than the SUPER_ADMIN-only override the answer-override/re-evaluate
    // endpoints keep. The service further pins the submission to #orgId.
    @PatchMapping("/{assignmentId}/submissions/{submissionId}/deadline")
    public ResponseEntity<Void> extendDeadline(
            @PathVariable UUID orgId,
            @PathVariable UUID submissionId,
            @Valid @RequestBody ExtendDeadlineRequest request) {
        assignmentService.extendDeadline(orgId, submissionId, request.newDeadline());
        return ResponseEntity.noContent().build();
    }

    /**
     * Super-admin override of a member's answers on a single pillar. Auto-unlocks
     * the pillar if needed (status flips EVALUATED → PENDING_REEDIT). Saving does
     * not trigger AI re-evaluation; the admin batches edits across pillars and
     * then calls {@link #reevaluate} explicitly.
     */
    @PutMapping("/{id}/pillars/{pillarId}/override-answers")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<Void> overrideAnswers(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @PathVariable UUID pillarId,
            @Valid @RequestBody OverrideAnswersRequest request) {
        adminAnswerOverrideService.overrideAnswers(orgId, id, pillarId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Super-admin manual re-evaluation. Flips PENDING_REEDIT → SUBMITTED and
     * dispatches the async pipeline; the partial-re-eval branch in
     * EvaluationService re-scores only the unlocked pillars.
     */
    @PostMapping("/{id}/reevaluate")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<Void> reevaluate(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        adminAnswerOverrideService.triggerReevaluation(orgId, id);
        return ResponseEntity.noContent().build();
    }
}
