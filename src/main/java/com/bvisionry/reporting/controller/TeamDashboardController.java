package com.bvisionry.reporting.controller;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.reporting.dto.CompletionStatsResponse;
import com.bvisionry.reporting.dto.DashboardOverviewResponse;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import com.bvisionry.reporting.dto.ScoreDistributionResponse;
import com.bvisionry.reporting.service.MemberDisplayNameResolver;
import com.bvisionry.reporting.service.MemberResultsExcelService;
import com.bvisionry.reporting.service.MemberResultsService;
import com.bvisionry.reporting.service.PdfReportService;
import com.bvisionry.reporting.service.PremiumFeatureGuard;
import com.bvisionry.reporting.service.TeamDashboardService;
import com.bvisionry.reporting.service.TeamInsightsExcelService;
import com.bvisionry.reporting.service.TeamInsightsPdfService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{orgId}/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class TeamDashboardController {

    private final TeamDashboardService teamDashboardService;
    private final MemberResultsService memberResultsService;
    private final TeamInsightsExcelService teamInsightsExcelService;
    private final TeamInsightsPdfService teamInsightsPdfService;
    private final PremiumFeatureGuard premiumFeatureGuard;
    private final SubmissionRepository submissionRepository;
    private final PdfReportService pdfReportService;
    private final MemberResultsExcelService memberResultsExcelService;
    private final MemberDisplayNameResolver memberDisplayNameResolver;

    /**
     * Team dashboard overview: member scores grid, filterable/sortable.
     */
    @GetMapping("/overview")
    public ResponseEntity<DashboardOverviewResponse> getOverview(
            @PathVariable UUID orgId,
            @RequestParam UUID pipelineId) {
        return ResponseEntity.ok(teamDashboardService.getOverview(orgId, pipelineId));
    }

    /**
     * Full results for a specific member's submission -- admin view.
     */
    @GetMapping("/members/{userId}/results/{submissionId}")
    public ResponseEntity<MemberResultsResponse> getMemberResults(
            @PathVariable UUID orgId,
            @PathVariable UUID userId,
            @PathVariable UUID submissionId) {
        verifySubmissionBelongsToOrg(submissionId, orgId);
        return ResponseEntity.ok(memberResultsService.getResults(submissionId));
    }

    /**
     * Pillar detail for a specific member -- Premium only.
     */
    @GetMapping("/members/{userId}/pillars/{pillarId}")
    public ResponseEntity<PillarDetailResponse> getMemberPillarDetail(
            @PathVariable UUID orgId,
            @PathVariable UUID userId,
            @PathVariable UUID pillarId,
            @RequestParam UUID submissionId) {
        verifySubmissionBelongsToOrg(submissionId, orgId);
        premiumFeatureGuard.checkPremium(orgId, "member_pillar_detail");
        return ResponseEntity.ok(memberResultsService.getPillarDetail(submissionId, pillarId));
    }

    /**
     * Per-member results PDF export (admin view) — reuses the member-report
     * pipeline ({@link PdfReportService}). {@code showNames=false} masks the
     * learner as "Member" via {@link MemberDisplayNameResolver}.
     */
    @GetMapping("/members/{userId}/results/{submissionId}/pdf")
    public ResponseEntity<byte[]> getMemberResultsPdf(
            @PathVariable UUID orgId,
            @PathVariable UUID userId,
            @PathVariable UUID submissionId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "true") boolean showNames) {
        verifySubmissionBelongsToOrg(submissionId, orgId);
        MemberDisplayNameResolver.ReportIdentity identity =
                memberDisplayNameResolver.resolveIdentity(submissionId, showNames);
        byte[] pdf = pdfReportService.generateReport(submissionId, identity.displayName(), identity.redactor());
        String filename = "Member_Report_" + safeFilename(identity.displayName()) + ".pdf";
        String disposition = "preview".equals(mode)
                ? "inline; filename=\"" + filename + "\""
                : "attachment; filename=\"" + filename + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * Per-member results Excel export (admin view) — reuses the member-report
     * pipeline ({@link MemberResultsExcelService}).
     */
    @GetMapping("/members/{userId}/results/{submissionId}/excel")
    public ResponseEntity<byte[]> getMemberResultsExcel(
            @PathVariable UUID orgId,
            @PathVariable UUID userId,
            @PathVariable UUID submissionId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "true") boolean showNames) {
        verifySubmissionBelongsToOrg(submissionId, orgId);
        MemberDisplayNameResolver.ReportIdentity identity =
                memberDisplayNameResolver.resolveIdentity(submissionId, showNames);
        byte[] xlsx = memberResultsExcelService.generateReport(
                submissionId, identity.displayName(), identity.redactor());
        String filename = "Member_Report_" + XlsxResponse.sanitizeFilename(identity.displayName()) + ".xlsx";
        return XlsxResponse.build(xlsx, filename, mode);
    }

    /**
     * Score distribution histograms per pillar.
     */
    @GetMapping("/distribution")
    public ResponseEntity<ScoreDistributionResponse> getDistribution(
            @PathVariable UUID orgId,
            @RequestParam UUID pipelineId) {
        return ResponseEntity.ok(teamDashboardService.getDistribution(orgId, pipelineId));
    }

    /**
     * Completion stats for an org + pipeline.
     */
    @GetMapping("/completion")
    public ResponseEntity<CompletionStatsResponse> getCompletion(
            @PathVariable UUID orgId,
            @RequestParam UUID pipelineId) {
        return ResponseEntity.ok(teamDashboardService.getCompletion(orgId, pipelineId));
    }

    /**
     * Bulk Excel export for Team Insights. Governed by the class-level gate
     * (SUPER_ADMIN, or ORG_ADMIN scoped to their own org) — same access as the
     * rest of this controller.
     *
     * <p>{@code memberIds} (optional, repeated query param) restricts the export
     * to a chosen subset of evaluated members — useful for orgs with 50+ members
     * where the full export is unwieldy. Aggregate stats on the Overview sheet
     * still reflect the whole pipeline roster regardless of the filter.
     */
    @GetMapping("/insights/excel")
    public ResponseEntity<byte[]> getInsightsExcel(
            @PathVariable UUID orgId,
            @RequestParam UUID pipelineId,
            @RequestParam(required = false) String pipelineName,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames,
            @RequestParam(required = false) List<UUID> memberIds) {
        byte[] xlsx = teamInsightsExcelService.generateReport(orgId, pipelineId, memberIds, showNames);
        String safeName = XlsxResponse.sanitizeFilename(
                pipelineName == null || pipelineName.isBlank() ? "pipeline" : pipelineName);
        String filename = "Team_Insights_" + safeName + ".xlsx";
        return XlsxResponse.build(xlsx, filename, mode);
    }

    /**
     * Bulk PDF export for Team Insights. Mirrors the Excel signature — same
     * class-level gate, same {@code memberIds} filter. The PDF contains an
     * aggregated team summary followed by per-member detail sections.
     */
    @GetMapping("/insights/pdf")
    public ResponseEntity<byte[]> getInsightsPdf(
            @PathVariable UUID orgId,
            @RequestParam UUID pipelineId,
            @RequestParam(required = false) String pipelineName,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames,
            @RequestParam(required = false) List<UUID> memberIds) {
        byte[] pdf = teamInsightsPdfService.generateReport(orgId, pipelineId, memberIds, showNames);
        String safeName = (pipelineName == null || pipelineName.isBlank() ? "pipeline" : pipelineName)
                .replaceAll("[^a-zA-Z0-9 _-]", "").replace(" ", "_");
        String filename = "Team_Insights_" + safeName + ".pdf";
        String disposition = "preview".equals(mode)
                ? "inline; filename=\"" + filename + "\""
                : "attachment; filename=\"" + filename + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private static String safeFilename(String raw) {
        return (raw == null || raw.isBlank() ? "member" : raw)
                .replaceAll("[^a-zA-Z0-9 _-]", "").replace(" ", "_");
    }

    private void verifySubmissionBelongsToOrg(UUID submissionId, UUID orgId) {
        Submission submission = submissionRepository.findByIdWithAssignmentAndPipeline(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        if (!submission.getAssignment().getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Submission", submissionId.toString());
        }
    }

}
