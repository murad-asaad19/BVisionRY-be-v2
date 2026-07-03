package com.bvisionry.publicassessment.controller;

import com.bvisionry.assessment.dto.SubmissionStatusResponse;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.publicassessment.dto.CreatePublicAssessmentLinkRequest;
import com.bvisionry.publicassessment.dto.PublicAssessmentLinkDto;
import com.bvisionry.publicassessment.dto.PublicSubmissionResponsePageDto;
import com.bvisionry.publicassessment.dto.UpdatePublicAssessmentLinkRequest;
import com.bvisionry.publicassessment.service.PublicAssessmentService;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Super-admin management of public assessment links and their responses. */
@RestController
@RequestMapping("/api/admin/public-assessments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class AdminPublicAssessmentController {

    private final PublicAssessmentService publicAssessmentService;

    @GetMapping
    public ResponseEntity<Page<PublicAssessmentLinkDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(publicAssessmentService.listLinks(page, size));
    }

    @PostMapping
    public ResponseEntity<PublicAssessmentLinkDto> create(
            @Valid @RequestBody CreatePublicAssessmentLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(publicAssessmentService.createLink(request, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{linkId}")
    public ResponseEntity<PublicAssessmentLinkDto> getById(@PathVariable UUID linkId) {
        return ResponseEntity.ok(publicAssessmentService.getLink(linkId));
    }

    @PatchMapping("/{linkId}")
    public ResponseEntity<PublicAssessmentLinkDto> update(
            @PathVariable UUID linkId,
            @Valid @RequestBody UpdatePublicAssessmentLinkRequest request) {
        return ResponseEntity.ok(publicAssessmentService.updateLink(linkId, request));
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> delete(@PathVariable UUID linkId) {
        publicAssessmentService.deleteLink(linkId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{linkId}/responses")
    public ResponseEntity<Page<PublicSubmissionResponsePageDto>> listResponses(
            @PathVariable UUID linkId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(publicAssessmentService.listResponses(linkId, page, size));
    }

    @GetMapping("/{linkId}/responses/{submissionId}")
    public ResponseEntity<MemberResultsResponse> getResponseResults(
            @PathVariable UUID linkId,
            @PathVariable UUID submissionId) {
        return ResponseEntity.ok(publicAssessmentService.getResponseResults(linkId, submissionId));
    }

    @GetMapping("/{linkId}/responses/{submissionId}/pillars/{pillarId}")
    public ResponseEntity<PillarDetailResponse> getResponsePillarDetail(
            @PathVariable UUID linkId,
            @PathVariable UUID submissionId,
            @PathVariable UUID pillarId) {
        return ResponseEntity.ok(
                publicAssessmentService.getResponsePillarDetail(linkId, submissionId, pillarId));
    }

    @DeleteMapping("/{linkId}/responses/{submissionId}")
    public ResponseEntity<Void> deleteResponse(
            @PathVariable UUID linkId,
            @PathVariable UUID submissionId) {
        publicAssessmentService.deleteResponse(linkId, submissionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Admin re-evaluation of a FAILED or NEEDS_REVIEW public response — closes the
     * anonymous-submission dead-end where NEEDS_REVIEW had no recovery entry point (the
     * respondent retake is FAILED-only by design). SUPER_ADMIN-only via the class guard.
     */
    @PostMapping("/{linkId}/responses/{submissionId}/retry")
    public ResponseEntity<SubmissionStatusResponse> retryResponseEvaluation(
            @PathVariable UUID linkId,
            @PathVariable UUID submissionId) {
        return ResponseEntity.ok(
                publicAssessmentService.retryResponseEvaluation(linkId, submissionId));
    }
}
