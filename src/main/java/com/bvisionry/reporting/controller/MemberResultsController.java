package com.bvisionry.reporting.controller;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.reporting.dto.MemberHistoryResponse;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import com.bvisionry.reporting.service.MemberDisplayNameResolver;
import com.bvisionry.reporting.service.MemberResultsExcelService;
import com.bvisionry.reporting.service.MemberResultsService;
import com.bvisionry.reporting.service.PdfReportService;
import com.bvisionry.reporting.service.PremiumFeatureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/my")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MemberResultsController {

    private final MemberResultsService memberResultsService;
    private final PdfReportService pdfReportService;
    private final MemberResultsExcelService memberResultsExcelService;
    private final PremiumFeatureGuard premiumFeatureGuard;
    private final SubmissionRepository submissionRepository;
    private final MemberDisplayNameResolver displayNameResolver;

    @GetMapping("/assessments/{submissionId}/results")
    public ResponseEntity<MemberResultsResponse> getResults(@PathVariable UUID submissionId) {
        verifySubmissionOwnership(submissionId);
        return ResponseEntity.ok(memberResultsService.getResults(submissionId));
    }

    @GetMapping("/assessments/{submissionId}/results/pillars/{pillarId}")
    public ResponseEntity<PillarDetailResponse> getPillarDetail(
            @PathVariable UUID submissionId,
            @PathVariable UUID pillarId) {
        verifySubmissionOwnership(submissionId);
        UUID orgId = getOrgIdFromSubmission(submissionId);
        premiumFeatureGuard.checkPremium(orgId, "pillar_detail");
        return ResponseEntity.ok(memberResultsService.getPillarDetail(submissionId, pillarId));
    }

    @GetMapping("/assessments/{submissionId}/results/pdf")
    public ResponseEntity<byte[]> getPdf(
            @PathVariable UUID submissionId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "true") boolean showNames) {
        verifySubmissionOwnership(submissionId);
        UUID orgId = getOrgIdFromSubmission(submissionId);
        premiumFeatureGuard.checkPremium(orgId, "pdf_report");
        String displayName = displayNameResolver.resolve(submissionId, showNames);
        byte[] pdf = pdfReportService.generateReport(submissionId, displayName);
        String filename = "Founder_Mindset_Assessment_"
                + XlsxResponse.sanitizeFilename(displayName) + ".pdf";
        String disposition = "preview".equals(mode)
                ? "inline; filename=\"" + filename + "\""
                : "attachment; filename=\"" + filename + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/assessments/{submissionId}/results/excel")
    public ResponseEntity<byte[]> getExcel(
            @PathVariable UUID submissionId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "true") boolean showNames) {
        verifySubmissionOwnership(submissionId);
        UUID orgId = getOrgIdFromSubmission(submissionId);
        premiumFeatureGuard.checkPremium(orgId, "pdf_report");
        String displayName = displayNameResolver.resolve(submissionId, showNames);
        byte[] xlsx = memberResultsExcelService.generateReport(submissionId, displayName);
        String filename = "Founder_Mindset_Assessment_"
                + XlsxResponse.sanitizeFilename(displayName) + ".xlsx";
        return XlsxResponse.build(xlsx, filename, mode);
    }

    @GetMapping("/history")
    public ResponseEntity<MemberHistoryResponse> getHistory() {
        return ResponseEntity.ok(memberResultsService.getHistory(SecurityUtils.getCurrentUserId()));
    }

    private void verifySubmissionOwnership(UUID submissionId) {
        if (SecurityUtils.isSuperAdmin()) return;
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        Submission submission = submissionRepository.findByIdWithAssignmentAndPipeline(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        // Public (anonymous) submissions have no owning user — no member can
        // ever own one through this endpoint.
        if (submission.getUser() == null || !submission.getUser().getId().equals(currentUserId)) {
            throw new BadRequestException("You do not have access to this submission");
        }
    }

    private UUID getOrgIdFromSubmission(UUID submissionId) {
        Submission submission = submissionRepository.findByIdWithAssignmentAndPipeline(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        // Public (anonymous) submissions have no assignment and therefore no org.
        if (submission.getAssignment() == null) {
            throw new BadRequestException("Submission does not belong to an organization");
        }
        return submission.getAssignment().getOrganization().getId();
    }
}
