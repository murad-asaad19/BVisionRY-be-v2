package com.bvisionry.publicassessment.controller;

import com.bvisionry.assessment.dto.AnswerResponse;
import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.assessment.dto.BatchSaveAnswersRequest;
import com.bvisionry.assessment.dto.ReviewResponse;
import com.bvisionry.assessment.dto.SubmissionStatusResponse;
import com.bvisionry.assessment.dto.SubmitAssessmentResponse;
import com.bvisionry.publicassessment.dto.GiftRecoveryResponse;
import com.bvisionry.publicassessment.dto.PublicAssessmentLinkInfoResponse;
import com.bvisionry.publicassessment.dto.PublicAssessmentSessionRequest;
import com.bvisionry.publicassessment.dto.PublicAssessmentSessionResponse;
import com.bvisionry.publicassessment.service.PublicAssessmentService;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Anonymous public-assessment taker flow. The {@code by-token} endpoints are
 * keyed by the link's shared QR token; everything under {@code /sessions} is
 * keyed by the per-submission {@code accessToken} secret minted at session
 * start (same trust model as the public survey token). Rate limiting for the
 * POST session-create and submit endpoints is enforced upstream in
 * {@link com.bvisionry.publicassessment.ratelimit.PublicAssessmentRateLimitFilter}.
 */
@RestController
@RequestMapping("/api/public/assessments")
@RequiredArgsConstructor
@PreAuthorize("permitAll()")
public class PublicAssessmentController {

    private final PublicAssessmentService publicAssessmentService;

    @GetMapping("/by-token/{token}")
    public ResponseEntity<PublicAssessmentLinkInfoResponse> getByToken(@PathVariable UUID token) {
        return ResponseEntity.ok(publicAssessmentService.getLinkInfo(token));
    }

    @PostMapping("/by-token/{token}/sessions")
    public ResponseEntity<PublicAssessmentSessionResponse> createSession(
            @PathVariable UUID token,
            @Valid @RequestBody PublicAssessmentSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(publicAssessmentService.createSession(token, request));
    }

    @GetMapping("/sessions/{accessToken}")
    public ResponseEntity<AssessmentDetailResponse> getSession(@PathVariable UUID accessToken) {
        return ResponseEntity.ok(publicAssessmentService.getSessionDetail(accessToken));
    }

    @PostMapping("/sessions/{accessToken}/answers/batch")
    public ResponseEntity<List<AnswerResponse>> batchSaveAnswers(
            @PathVariable UUID accessToken,
            @Valid @RequestBody BatchSaveAnswersRequest request) {
        return ResponseEntity.ok(publicAssessmentService.batchSaveAnswers(accessToken, request));
    }

    @GetMapping("/sessions/{accessToken}/review")
    public ResponseEntity<ReviewResponse> getReview(@PathVariable UUID accessToken) {
        return ResponseEntity.ok(publicAssessmentService.getReview(accessToken));
    }

    @PostMapping("/sessions/{accessToken}/submit")
    public ResponseEntity<SubmitAssessmentResponse> submit(@PathVariable UUID accessToken) {
        return ResponseEntity.ok(publicAssessmentService.submit(accessToken));
    }

    @GetMapping("/sessions/{accessToken}/status")
    public ResponseEntity<SubmissionStatusResponse> getStatus(@PathVariable UUID accessToken) {
        return ResponseEntity.ok(publicAssessmentService.getStatus(accessToken));
    }

    @GetMapping("/sessions/{accessToken}/results")
    public ResponseEntity<MemberResultsResponse> getResults(@PathVariable UUID accessToken) {
        return ResponseEntity.ok(publicAssessmentService.getResults(accessToken));
    }

    @GetMapping("/sessions/{accessToken}/results/pillars/{pillarId}")
    public ResponseEntity<PillarDetailResponse> getResultsPillarDetail(
            @PathVariable UUID accessToken,
            @PathVariable UUID pillarId) {
        return ResponseEntity.ok(publicAssessmentService.getResultsPillarDetail(accessToken, pillarId));
    }

    /**
     * Resolve a personalized survey-gift link (`?g={giftToken}`) to the bound
     * submission so the taker can route a reopened link. 204 when no submission
     * has been started yet — the FE then shows the normal intro/start screen.
     */
    @GetMapping("/by-token/{token}/recover")
    public ResponseEntity<GiftRecoveryResponse> recover(
            @PathVariable UUID token,
            @RequestParam("g") String giftToken) {
        return publicAssessmentService.recoverByGiftToken(token, giftToken)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/sessions/{accessToken}/retake")
    public ResponseEntity<SubmissionStatusResponse> retake(@PathVariable UUID accessToken) {
        return ResponseEntity.ok(publicAssessmentService.retake(accessToken));
    }
}
